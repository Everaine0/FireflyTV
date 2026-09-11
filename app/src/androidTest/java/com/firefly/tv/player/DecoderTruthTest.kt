package com.firefly.tv.player

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 「诊断面板上的数字是不是真的」—— 面板要贴到用户眼前，读数错了比没有更糟。
 *
 * 三个真值来源，各钉一条：
 *
 * 1. **解码通路**（`decoderInUse`）：ijkplayer 在建 MediaCodec 失败时会**静默回落软解**
 *    （`ffpipeline_android.c:73-77`），只有 `ffp_video_thread` / AMC 两条路各自写下的
 *    `stat.vdec_type`（1=avcodec、2=MediaCodec）能说清到底用的哪条。
 *    「电视的硬解好像没开」只能靠它回答。
 * 2. **片源帧率**（`sourceFps`）：用户那句「帧率不高，25 甚至更低」必须有个对照物 ——
 *    它来自 ijkplayer 的 media meta（`ijkmeta.c:245` 写的 `avg_frame_rate`）。
 *    这条链路（C → JSON meta → Java Bundle → 面板）任何一环断了，
 *    面板都会显示 0，而 0 会被当成「不知道」，于是**永远说不出结论**。
 * 3. **视频尺寸**：确认解码器真的通了（`getVideoWidth/Height` 有值）。
 *
 * 样本是 `testsrc=rate=15`，所以片源帧率必须是 15。
 */
@RunWith(AndroidJUnit4::class)
class DecoderTruthTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(msg: String) = android.util.Log.i("FireflyDecode", msg)

    @Test
    fun 解码通路_片源帧率_尺寸都有真值() {
        val video = TestVideo.ensure(context)
        val firstFrame = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val main = Handler(Looper.getMainLooper())
        val engine = IjkPlaybackEngine(context)

        main.post {
            engine.setListener(object : PlaybackEngine.Listener {
                override fun onPrepared(durationMs: Long) = Unit
                override fun onCompletion() = Unit
                override fun onFirstFrame() = firstFrame.countDown()
                override fun onError(friendlyMessage: String, fatal: Boolean) {
                    failure.set(friendlyMessage)
                    firstFrame.countDown()
                }
            })
            engine.playSource({ FileRandomAccessSource(video.absolutePath) }, 0L)
        }

        assertTrue("等不到首帧：${failure.get()}", firstFrame.await(60, TimeUnit.SECONDS))

        var diag: PlaybackEngine.Diag? = null
        main.post { diag = engine.diag() }
        Thread.sleep(600)
        val d = requireNotNull(diag) { "主线程上取不到 diag()" }

        log(
            "diag: 解码通路=${d.decoder}(${d.videoModule}/${d.videoImpl}) " +
                "尺寸=${d.videoWidth}×${d.videoHeight} 片源=${d.sourceFps}fps " +
                "解码=${d.decodeFps}fps 送显=${d.outputFps}fps 缓存=${d.cachedMs}ms " +
                "音频=${d.audioCodec} 请求硬解=${d.requestedHardware}",
        )

        // 1) 解码通路：起播之后必须问得出来（UNKNOWN 只允许出现在没起播时）
        assertNotEquals(
            "起播了却问不出解码通路 —— 面板上「解码」那一行会显示「还没起播」",
            PlaybackEngine.Decoder.UNKNOWN,
            d.decoder,
        )
        assertEquals("diag 的通路和 decoderInUse() 必须一致", engine.decoderInUse(), d.decoder)

        // 2) 片源帧率：样本是 15 帧，读不到就说明 media meta 那条链路断了
        assertEquals("片源帧率要从 media meta 读出来", 15f, d.sourceFps, 0.5f)

        // 3) 尺寸：解码器真的出图了
        assertTrue("视频尺寸应该有值", d.videoWidth > 0 && d.videoHeight > 0)

        // 4) 音频编解码器也要报出来（「有画面没声音」靠它定性）
        assertTrue("音频解码器名应该非空", !d.audioCodec.isNullOrBlank())

        main.post { runCatching { engine.release() } }
        Thread.sleep(400)
    }
}
