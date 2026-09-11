package com.firefly.tv.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.firefly.tv.core.Config
import com.firefly.tv.media.Library
import com.firefly.tv.media.Scanner
import com.firefly.tv.smb.SmbStore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SMB 读取到底有多快 —— **4K 片源够不够，只能实测**。
 *
 * 起因：用户实机反馈「还算流畅、声音也跟得上，但帧率不高」。
 * 这类症状有三种根因（解码不够快 / 渲染来不及 / 读数据供不上），
 * 前两种在 [PlaybackVerdict] 里能看出来，第三种必须先知道**这台 NAS + 这条链路**的上限：
 *
 *  - 4K H.265 一集 45 分钟约 1GB ⇒ **约 3.7 MB/s** 才能连续播；
 *  - smbj 的每次 `File.read` 是一次 SMB2 READ 往返，
 *    而 ijkplayer 经 `AVIOContext` 读进来的是 **32KB 一次**（FFmpeg 默认缓冲）；
 *  - 所以「32KB 顺序读」这一组的吞吐就是**播放时真实能用到的吞吐**，
 *    另外两组（64KB/256KB）用来看瓶颈是「往返次数」还是「链路带宽」。
 *
 * 不做断言，只输出事实：结论要看数字（`adb logcat -s FireflyProbe`）。
 */
@RunWith(AndroidJUnit4::class)
class SmbThroughputTest {

    private val args = InstrumentationRegistry.getArguments()
    private fun arg(k: String) = args.getString(k, "").orEmpty()
    private val cfg get() = Config.Smb(
        arg("ff.smb.host"), arg("ff.smb.share"), arg("ff.smb.root"),
        arg("ff.smb.user"), arg("ff.smb.pass"), arg("ff.smb.domain"),
    )

    private fun log(m: String) = android.util.Log.i("FireflyProbe", m)

    @Test
    fun 顺序读吞吐() {
        val libs = try {
            SmbStore.with(cfg) { Scanner.libraries(cfg) }
        } catch (t: Throwable) {
            log("SMB 不可达，跳过：${t.message}"); return
        }
        val lib = libs.filterIsInstance<Library.Video>().firstOrNull() ?: return
        val shows = SmbStore.with(cfg) { Scanner.shows(cfg, lib) }
        val show = shows.firstOrNull { it.contains("大宅门") } ?: shows.firstOrNull() ?: return
        val ep = SmbStore.with(cfg) { Scanner.episodes(cfg, lib, show) }.firstOrNull() ?: return
        val path = Scanner.episodePath(lib, show, ep)
        val size = SmbStore.with(cfg) { client -> client.open(path).use { it.size } }
        log("==== 片源 $path（${size / 1024 / 1024} MB）")

        for (chunk in listOf(32 * 1024, 64 * 1024, 256 * 1024)) {
            val mbps = timeRead(path, size, chunk)
            log("  原始 read(${chunk / 1024}KB) → ${"%.2f".format(mbps)} MB/s")
        }

        // 经块缓存（播放器真正走的那条路）：256KB 块 + 32KB 的 readAt
        val cached = timeDataSource(path, size, 32 * 1024)
        log("  经 SmbMediaDataSource（块缓存 256KB，readAt 32KB）→ ${"%.2f".format(cached)} MB/s")
    }

    /** 顺序读 [bytes] 字节，每次 [chunk] 字节，返回 MB/s。 */
    private fun timeRead(path: String, size: Long, chunk: Int, bytes: Long = 256L * 1024 * 1024): Double {
        val want = minOf(bytes, size)
        val buf = ByteArray(chunk)
        val t0 = System.currentTimeMillis()
        var got = 0L
        SmbStore.with(cfg) { client ->
            client.open(path).use { h ->
                while (got < want) {
                    val n = h.read(got, buf, 0, minOf(chunk.toLong(), want - got).toInt())
                    if (n <= 0) break
                    got += n
                }
            }
        }
        return rate(got, System.currentTimeMillis() - t0)
    }

    /** 走 `SmbMediaDataSource.readAt`（块缓存），模拟播放器的读法。 */
    private fun timeDataSource(path: String, size: Long, chunk: Int, bytes: Long = 256L * 1024 * 1024): Double {
        val want = minOf(bytes, size)
        val buf = ByteArray(chunk)
        val t0 = System.currentTimeMillis()
        var got = 0L
        val src = SmbMediaDataSource(cfg, path)
        try {
            while (got < want) {
                val n = src.readAt(got, buf, 0, minOf(chunk.toLong(), want - got).toInt())
                if (n <= 0) break
                got += n
            }
        } finally {
            runCatching { src.close() }
        }
        return rate(got, System.currentTimeMillis() - t0)
    }

    private fun rate(got: Long, ms: Long): Double =
        if (ms <= 0) 0.0 else got / 1024.0 / 1024.0 / (ms / 1000.0)
}
