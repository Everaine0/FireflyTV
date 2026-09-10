package com.firefly.tv.smb

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.firefly.tv.core.Config
import com.firefly.tv.media.Library
import com.firefly.tv.media.LiveSource
import com.firefly.tv.media.Scanner
import com.firefly.tv.player.IjkPlaybackEngine
import com.firefly.tv.player.PlaybackEngine
import com.firefly.tv.player.SmbMediaDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 真 SMB 往返联调（DESIGN 里「SMB 端到端」那一项）。
 *
 * 需要一个在跑着的 SMB 服务：用 `scripts/dev-smb-server.py` 在宿主机上起一个，
 * 模拟器通过 10.0.2.2 访问宿主机的 loopback。
 *
 * 没起服务时整类跳过（[assumeTrue]），所以它不会拖累常规的 connectedAndroidTest。
 */
@RunWith(AndroidJUnit4::class)
class SmbEndToEndTest {

    private val cfg = Config.Smb(
        host = HOST,
        share = SHARE,
        root = "",
        user = USER,
        pass = PASS,
        domain = "",
    )

    private fun requireServer() {
        val reachable = try {
            SmbStore.with(cfg) { it.list("").isNotEmpty() }
        } catch (t: Throwable) {
            println("SMB 服务不可达（$HOST:$SHARE）：${SmbClient.describe(t)}")
            false
        }
        assumeTrue("没有可用的 SMB 测试服务，跳过真 SMB 联调", reachable)
    }

    @Test
    fun 能列出媒体库并识别直播库() {
        requireServer()

        val libraries = SmbStore.with(cfg) { Scanner.libraries(cfg) }
        val names = libraries.map { it.name }
        println("发现媒体库：$names")

        // 「空文件夹」里没有视频，必须被跳过（DESIGN §4）
        assertTrue("不该把空文件夹当媒体库：$names", names.none { it == "空文件夹" })
        assertTrue("没识别出视频库：$names", libraries.any { it is Library.Video && it.name == "电视剧" })
        assertTrue("没把含 m3u 的目录识别成直播库：$names", libraries.any { it is Library.Live && it.name == "直播" })
    }

    @Test
    fun 剧集按自然顺序排列() {
        requireServer()

        val lib = Library.Video("电视剧")
        val shows = SmbStore.with(cfg) { Scanner.shows(cfg, lib) }
        println("剧列表：$shows")
        assertEquals(listOf("水浒传", "西游记"), shows)

        val episodes = SmbStore.with(cfg) { Scanner.episodes(cfg, lib, "水浒传") }
        println("水浒传集数：$episodes")
        // 自然排序：第 02 必须排在第 10 之前
        assertEquals(
            listOf("水浒传01.mp4", "水浒传02.mp4", "水浒传10.mp4", "水浒传11.mp4"),
            episodes,
        )
    }

    @Test
    fun 直播库能解析出频道() {
        requireServer()

        val live = SmbStore.with(cfg) { Scanner.libraries(cfg) }.filterIsInstance<Library.Live>().first()
        val channels = SmbStore.with(cfg) { LiveSource.channels(cfg, live) }
        println("频道：${channels.map { it.name + " -> " + it.url }}")
        assertEquals(2, channels.size)
        assertEquals("测试频道一", channels[0].name)
        assertTrue("地址应是 http 链接", channels[0].url.startsWith("http://"))
    }

    @Test
    fun 能通过SMB桥接播放并出首帧() {
        requireServer()

        // 先取一集的真实路径
        val lib = Library.Video("电视剧")
        val episodes = SmbStore.with(cfg) { Scanner.episodes(cfg, lib, "水浒传") }
        val path = Scanner.episodePath(lib, "水浒传", episodes.first())
        println("准备播放：$path")

        // 1) 纯 SMB 随机读：验证 readAt 语义在真实网络上成立
        val dataSource = SmbMediaDataSource(cfg, path)
        val size = dataSource.getSize()
        println("SMB 文件大小 = $size")
        assertTrue("拿到的大小不合理：$size", size > 10_000)

        val first = ByteArray(16)
        assertEquals("头部应能读满 16 字节", 16, dataSource.readAt(0, first, 0, 16))
        // 跳读文件尾：非顺序访问必须也能拿到数据
        val tail = ByteArray(16)
        assertTrue("末尾跳读失败", dataSource.readAt(size - 16, tail, 0, 16) > 0)
        // 越界必须是 -1
        assertEquals(-1, dataSource.readAt(size, tail, 0, 16))
        dataSource.close()

        // 2) 走完整播放通路
        val prepared = CountDownLatch(1)
        val firstFrame = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val engine = IjkPlaybackEngine(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        )
        main.post {
            engine.setListener(object : PlaybackEngine.Listener {
                override fun onPrepared(durationMs: Long) {
                    println("已起播，时长 ${durationMs}ms")
                    prepared.countDown()
                }

                override fun onCompletion() = Unit

                override fun onError(friendlyMessage: String, fatal: Boolean) {
                    failure.set(friendlyMessage)
                    prepared.countDown()
                }

                override fun onFirstFrame() {
                    firstFrame.countDown()
                }
            })
            engine.playSmb(cfg, path, 0L)
        }

        assertTrue("起播失败/超时：${failure.get()}", prepared.await(40, TimeUnit.SECONDS))
        assertTrue("播放报错：${failure.get()}", failure.get() == null)
        assertTrue("等不到首帧", firstFrame.await(40, TimeUnit.SECONDS))
        println("SMB 播放出首帧 ✅")

        main.post { engine.release() }
    }

    @Test
    fun 凭据错误会给出中文原因() {
        // 这一项不需要服务在跑也能给出结论之一，但为了确定是「凭据错」而不是「连不上」，
        // 还是要服务在
        requireServer()

        val bad = cfg.copy(pass = "definitely-wrong-password")
        SmbStore.drop()
        val message = try {
            SmbStore.with(bad) { it.list("") }
            null
        } catch (t: Throwable) {
            SmbClient.describe(t)
        }
        println("错误凭据的结果：$message")
        assertTrue("应给出中文原因，实际：$message", message != null && message.any { it.code > 0x4E00 })
        SmbStore.drop()
    }

    companion object {
        /** 模拟器里 10.0.2.2 就是宿主机的 loopback。 */
        private const val HOST = "10.0.2.2"
        private const val SHARE = "media"
        private const val USER = "firefly"
        private const val PASS = "firefly"
    }
}
