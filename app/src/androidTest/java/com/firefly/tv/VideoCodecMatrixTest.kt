package com.firefly.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.firefly.tv.player.ByteArrayRandomAccessSource
import com.firefly.tv.player.IjkPlaybackEngine
import com.firefly.tv.player.PlaybackEngine
import com.firefly.tv.player.PlaybackMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 同一段视频、同一个容器、同一条播放路径，**只换视频编码**：H.264 vs H.265。
 *
 * ## 为什么必须做这个对照
 *
 * 真实 NAS 上三部剧的表现是：
 *  - 《娘道》  H.264 1080p  → 正常出画面
 *  - 《大宅门》H.265 4K     → 卡死（首帧永远不出来）
 *  - 《猫和老鼠》H.265 2960x2160 → 卡死
 *
 * 「失败的两个都是 H.265」这个规律太整齐，不能当成巧合放过。
 * 但真实片源同时还有别的变量（SMB、moov 在尾部、4K 分辨率），
 * 所以这里用两份**自生成的小样本**把变量清干净：
 * 640x360、10 秒、faststart（moov 在头部，**不触发重排**）、走内存字节源（**不经过 SMB**）。
 *
 * 如果 H.264 能出画面而 H.265 不能，那问题就在 HEVC 解码能力上，
 * 和 moov 重排、和 SMB、和分辨率都无关。
 */
@RunWith(AndroidJUnit4::class)
class VideoCodecMatrixTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 样本在**测试 APK** 的 assets 里，必须用 instrumentation.context 读（踩过）。 */
    private val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun say(msg: String) {
        println(msg)
        android.util.Log.i("FireflyVideoMatrix", msg)
    }

    @Test
    fun h264AndH265Compared() {
        val h264 = probe("video-h264.mp4")
        val h265 = probe("video-hevc.mp4")

        say("---------- 视频编码对照 ----------")
        say("H.264 : $h264")
        say("H.265 : $h265")
        say("---------------------------------")

        assertTrue(
            "H.264 必须能出画面，否则这个对照实验本身不成立：$h264",
            h264.firstFrame,
        )
        assertTrue(
            "两份样本只有视频编码不同（同内容 / 同容器 / 同分辨率 / 都 faststart / 都走内存源），" +
                "H.265 却出不了画面 —— 问题在 HEVC 解码：$h265",
            h265.firstFrame,
        )
    }

    private class R(val firstFrame: Boolean, val audio: Boolean, val secs: Double, val error: String?) {
        override fun toString() =
            "首帧=$firstFrame 音频=$audio 耗时=%.1fs 错误=%s".format(secs, error)
    }

    private fun probe(asset: String): R {
        val data = testAssets.open(asset).use { it.readBytes() }
        say("=== $asset (${data.size} 字节) ===")

        val frame = CountDownLatch(1)
        val audio = CountDownLatch(1)
        var err: String? = null
        val duration = AtomicLong(0)

        val engine = IjkPlaybackEngine(ctx)
        engine.setListener(object : PlaybackEngine.Listener {
            override fun onPrepared(durationMs: Long) {
                duration.set(durationMs)
                say("   onPrepared duration=$durationMs")
            }

            override fun onCompletion() = say("   onCompletion")
            override fun onError(friendlyMessage: String, fatal: Boolean) {
                err = friendlyMessage
                say("   onError: $friendlyMessage fatal=$fatal")
            }

            override fun onFirstFrame() {
                say("   首帧")
                frame.countDown()
            }

            override fun onAudioStarted() {
                say("   音频起播")
                audio.countDown()
            }
        })
        engine.setMode(PlaybackMode.Kind.ON_DEMAND)
        engine.attach(OffscreenSurface.make())

        val t0 = System.currentTimeMillis()
        engine.playSource(ByteArrayRandomAccessSource(data), 0L)
        frame.await(25, TimeUnit.SECONDS)
        audio.await(10, TimeUnit.SECONDS)
        val secs = (System.currentTimeMillis() - t0) / 1000.0

        // 顺便把存活快照也打出来：真实片源上这几个计数器可能是判断依据
        val l = engine.liveness()
        say("   存活快照: cached=${l.videoCachedMs}ms traffic=${l.trafficBytes}B " +
            "pos=${l.positionMs} fps=${l.outputFps}")

        val r = R(frame.count == 0L, audio.count == 0L, secs, err)
        runCatching { engine.release() }
        Thread.sleep(400)
        return r
    }
}
