package com.firefly.tv.player

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 「硬解到底有没有被打开」—— 用户报的「装到电视上极慢、还没声音」就是在这里定性。
 *
 * 为什么必须专门测：ijkplayer 的 `mediacodec*` 选项**默认全是 0**，
 * 不写就等于整条链路走 FFmpeg 软解，而且**不会有任何报错**。
 * 4K H.265 在软解下就是几百毫秒一帧（`DESIGN` 风险 8 记的「模拟器首帧 27~36 秒」正是它）。
 * 改完选项如果没人验证，很容易出现「改了等于没改」却看不出来。
 *
 * 判据是 ijkplayer 的 MediaCodec 选码回调**有没有被调用**（[IjkPlaybackEngine.decoderPath]）：
 * 只有真的要建 MediaCodec 解码器时它才会被问。
 *  - 真机（有硬解）：`MEDIACODEC` + 具体解码器名；
 *  - 模拟器（只有 `OMX.google.*` 这种软件实现）：ijkplayer 按排名把它们拒掉 → `NO_CODEC`，
 *    也就是「硬解通路已经接上，但这台设备没有硬解可用」——同样算通过，行为与改之前一致。
 *
 * 结果看 `adb logcat -s FireflyDecode`。
 */
@RunWith(AndroidJUnit4::class)
class HardwareDecodeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(msg: String) = android.util.Log.i("FireflyDecode", msg)

    @Test
    fun 起播时真的会去问MediaCodec而不是直接软解() {
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
        // 选码回调发生在建解码器的时候，首帧都出来了，必然已经问过
        val path = engine.decoderPath()
        log("本地 H.264 样本 → 解码通路=$path 解码器=${engine.decoderName()}")
        assertNotEquals(
            "没去问 MediaCodec = 硬解选项没生效，这台设备仍然是纯软解（4K 片源必然极慢）",
            IjkPlaybackEngine.DecoderPath.NOT_ASKED,
            path,
        )

        main.post { engine.release() }
    }

    /**
     * 硬解失败（或压根没有硬解）时要能退回软解，而且**软解也要真的能出画面**。
     *
     * 这条以前没法测：字节源一旦 close 就报废（SMB 句柄 / RandomAccessFile），
     * 兜底重播会读不出数据。所以引擎现在收的是「怎么再打开一份」的工厂。
     *
     * ⚠️ 「兜底」这件事**只有真机上才谈得上**：模拟器的 MediaCodec 里没有可用硬解，
     * 选码回调会当场报 `NO_CODEC`（`usingHardware` 那一刻就被清掉了），
     * 既不会有兜底、也不该有。所以这条测试分成两支：
     *  - `NO_CODEC`（模拟器）：只验证「软解能出首帧」；
     *  - 有硬解（真机）：完整验证「硬解 → 兜底 → 软解重播出第二帧」。
     *
     * 早先的版本不管哪种设备都去等第二帧，在模拟器上必然超时 —— 那不是产品的问题，
     * 是测试自己没分清这两种设备。
     */
    @Test
    fun 硬解不行时能用软解把同一份内容重播() {
        val video = TestVideo.ensure(context)
        val frames = AtomicInteger(0)
        val firstFrame = CountDownLatch(1)
        val secondFrame = CountDownLatch(1)
        val fallbacks = AtomicInteger(0)
        val failure = AtomicReference<String?>(null)
        val main = Handler(Looper.getMainLooper())
        val engine = IjkPlaybackEngine(context)

        main.post {
            engine.setListener(object : PlaybackEngine.Listener {
                override fun onPrepared(durationMs: Long) = Unit
                override fun onCompletion() = Unit

                override fun onError(friendlyMessage: String, fatal: Boolean) {
                    failure.set(friendlyMessage)
                    firstFrame.countDown()
                    secondFrame.countDown()
                }

                override fun onFirstFrame() {
                    when (frames.incrementAndGet()) {
                        1 -> firstFrame.countDown()
                        else -> secondFrame.countDown()
                    }
                }

                override fun onDecoderFallback(reason: String) {
                    log("兜底回调：$reason")
                    fallbacks.incrementAndGet()
                }
            })
            engine.playSource({ FileRandomAccessSource(video.absolutePath) }, 0L)
        }

        assertTrue("第一次起播等不到首帧：${failure.get()}", firstFrame.await(60, TimeUnit.SECONDS))
        assertTrue("第一次起播就报错了：${failure.get()}", failure.get() == null)

        val path = engine.decoderPath()
        log("解码通路=$path 解码器=${engine.decoderName()} 兜底回调次数=${fallbacks.get()}")

        if (path == IjkPlaybackEngine.DecoderPath.NO_CODEC) {
            // 模拟器走这支：这台设备没有可用硬解，起播时就已经是软解。
            // 首帧出来了就等于「软解这条路是通的」，兜底无从谈起。
            assertFalse("没有硬解可用时不该有兜底（它在选码回调里就被判掉了）",
                engine.canFallbackToSoftware())
            log("本机没有可用硬解：跳过兜底断言，已确认软解能出首帧")
            main.post { engine.release() }
            return
        }

        // 有硬解：完整走一遍兜底
        val manual = if (engine.canFallbackToSoftware()) engine.retryInSoftware() else false
        log("手动兜底=$manual")

        assertTrue("软解重播后等不到第二帧：${failure.get()}", secondFrame.await(90, TimeUnit.SECONDS))
        assertTrue("应该发生过一次兜底（自动或手动）", fallbacks.get() >= 1)
        assertEquals("兜底之后必须是真的软解（不会再问 MediaCodec）",
            IjkPlaybackEngine.DecoderPath.NOT_ASKED, engine.decoderPath())

        main.post { engine.release() }
    }
}
