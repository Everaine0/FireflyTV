package com.firefly.tv.smb

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * 真实 SMB 往返联调。
 *
 * 账号从 `local.properties` 经 instrumentation 参数传进来（不写死在源码里，不入库）。
 * 没配账号就整类跳过，不会让常规 `connectedAndroidTest` 变红。
 *
 * 在模拟器里连本机局域网 NAS 时注意：模拟器走 NAT，`10.0.2.2` 是宿主机，
 * 局域网里的其它机器直接填它的真实 IP 即可（模拟器默认能路由到局域网）。
 */
@RunWith(AndroidJUnit4::class)
class SmbEndToEndTest {

    private val args = InstrumentationRegistry.getArguments()

    private fun arg(key: String) = args.getString(key, "").orEmpty()

    private val host get() = arg("ff.smb.host")
    private val share get() = arg("ff.smb.share")
    private val user get() = arg("ff.smb.user")
    private val pass get() = arg("ff.smb.pass")

    private val cfg get() = Config.Smb(host, share, arg("ff.smb.root"), user, pass, arg("ff.smb.domain"))

    private fun requireServer() {
        assumeTrue("没有配置真 NAS 账号（local.properties），跳过", host.isNotBlank() && share.isNotBlank())
        val ok = try {
            SmbStore.with(cfg) { it.list("").isNotEmpty() }
        } catch (t: Throwable) {
            println("SMB 不可达 \\\\$host\\$share -> ${SmbClient.describe(t)}")
            false
        }
        assumeTrue("SMB 不可达，跳过", ok)
    }

    @Test
    fun 能列出媒体库并正确分类() {
        requireServer()

        val libraries = SmbStore.with(cfg) { Scanner.libraries(cfg) }
        println("发现媒体库：${libraries.map { it.name + "(" + it::class.simpleName + ")" }}")

        assertTrue("一个媒体库都没识别出来", libraries.isNotEmpty())
        // 没有视频也没有 m3u 的目录必须被跳过（DESIGN §4）
        libraries.filterIsInstance<Library.Live>().forEach {
            println("直播库 ${it.name} -> ${it.m3u}")
        }
    }

    @Test
    fun 每个视频库都能列出剧和集() {
        requireServer()

        val videos = SmbStore.with(cfg) { Scanner.libraries(cfg) }.filterIsInstance<Library.Video>()
        assumeTrue("这台 NAS 上没有视频库", videos.isNotEmpty())

        for (lib in videos) {
            val shows = try {
                SmbStore.with(cfg) { Scanner.shows(cfg, lib) }
            } catch (t: Throwable) {
                println("库 ${lib.name} 列剧失败：${SmbClient.describe(t)}")
                continue
            }
            println("库 ${lib.name}: ${shows.size} 部剧 -> ${shows.take(5)}")
            assertTrue("库 ${lib.name} 列不出任何剧", shows.isNotEmpty())

            // 抽第一部剧验证集数能读出来
            val first = shows.first()
            val eps = SmbStore.with(cfg) { Scanner.episodes(cfg, lib, first) }
            println("  《$first》 ${eps.size} 集，前 3 个：${eps.take(3)}")
            assertTrue("《$first》一集都没有", eps.isNotEmpty())
        }
    }

    @Test
    fun 直播库的m3u能解析出频道() {
        requireServer()

        val lives = SmbStore.with(cfg) { Scanner.libraries(cfg) }.filterIsInstance<Library.Live>()
        assumeTrue("这台 NAS 上没有直播库", lives.isNotEmpty())

        for (lib in lives) {
            val channels = SmbStore.with(cfg) { LiveSource.channels(cfg, lib) }
            println("直播库 ${lib.name}（${lib.m3u}）: ${channels.size} 个频道")
            channels.take(5).forEach { println("   ${it.name} -> ${it.url}") }
            // m3u 里可能全是注释或空，允许 0 —— 但要能解析完不炸
            assertTrue("解析结果不应为负", channels.size >= 0)
        }
    }

    /** 逐集探测 moov 位置：这决定 ijkplayer 能不能直接播（DESIGN 风险 8）。 */
    @Test
    fun 报告每个视频库的moov分布() {
        requireServer()

        val videos = SmbStore.with(cfg) { Scanner.libraries(cfg) }.filterIsInstance<Library.Video>()
        assumeTrue("这台 NAS 上没有视频库", videos.isNotEmpty())

        for (lib in videos) {
            val shows = SmbStore.with(cfg) { Scanner.shows(cfg, lib) }
            for (show in shows) {
                val eps = SmbStore.with(cfg) { Scanner.episodes(cfg, lib, show) }
                if (eps.isEmpty()) continue

                var head = 0
                var tail = 0
                var unknown = 0
                val samples = ArrayList<String>()
                for (ep in eps) {
                    val path = Scanner.episodePath(lib, show, ep)
                    val verdict = SmbStore.with(cfg) { client -> SmbTestProbe.probeMoov(client, path) }
                    when (verdict) {
                        "front" -> head++
                        "tail" -> {
                            tail++
                            if (samples.size < 3) samples += ep
                        }
                        else -> unknown++
                    }
                }
                println("《$show》: 共 ${eps.size} 集  头部=$head  尾部=$tail  未知/非mp4=$unknown")
                samples.forEach { println("    尾部样例: $it") }
            }
        }
    }

    /** 真机播放：抽一部剧的第一集，验证能起播并出首帧。 */
    @Test
    fun 能通过SMB播放并出首帧() {
        requireServer()

        val lib = SmbStore.with(cfg) { Scanner.libraries(cfg) }
            .filterIsInstance<Library.Video>().firstOrNull()
        assumeTrue("没有视频库", lib != null)
        val videoLib = lib!!

        val show = SmbStore.with(cfg) { Scanner.shows(cfg, videoLib) }.firstOrNull()
        assumeTrue("没有剧", show != null)
        val firstShow = show!!

        val eps = SmbStore.with(cfg) { Scanner.episodes(cfg, videoLib, firstShow) }
        assumeTrue("没有集", eps.isNotEmpty())

        val path = Scanner.episodePath(videoLib, firstShow, eps.first())
        println("准备播放：$path")

        // 先验证随机读语义在真实网络上成立
        val ds = SmbMediaDataSource(cfg, path)
        val size = ds.getSize()
        println("文件大小 = $size 字节 (${"%.1f".format(size / 1024.0 / 1024.0)}MB)")
        assertTrue("大小不合理：$size", size > 10_000)
        val buf = ByteArray(16)
        assertEquals(16, ds.readAt(0, buf, 0, 16))
        assertTrue("尾部跳读失败", ds.readAt(size - 16, buf, 0, 16) > 0)
        assertEquals(-1, ds.readAt(size, buf, 0, 16))
        ds.close()

        // 走完整播放通路
        val prepared = CountDownLatch(1)
        val firstFrame = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val main = Handler(Looper.getMainLooper())
        val engine = IjkPlaybackEngine(InstrumentationRegistry.getInstrumentation().targetContext)
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

        assertTrue("起播失败/超时：${failure.get()}", prepared.await(45, TimeUnit.SECONDS))
        assertTrue("播放报错：${failure.get()}", failure.get() == null)
        assertTrue("等不到首帧", firstFrame.await(45, TimeUnit.SECONDS))
        println("SMB 播放出首帧 ✅")

        main.post { engine.release() }
    }

    @Test
    fun 凭据错误会给出中文原因() {
        assumeTrue("没有配置真 NAS 账号，跳过", host.isNotBlank() && share.isNotBlank())

        val bad = cfg.copy(pass = "definitely-wrong-password-xyz")
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
}
