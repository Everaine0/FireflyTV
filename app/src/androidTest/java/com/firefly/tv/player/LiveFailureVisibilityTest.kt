package com.firefly.tv.player

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 直播连不上时，**界面层必须收到通知**。
 *
 * 这是用户报的第二个「卡住」：「我尝试从电视[剧]换回直播，又卡住了」。
 * 现场是频道连不上（403 / 断流），而引擎的直播重连逻辑**顺手把界面层也蒙在鼓里**：
 *
 * ```kotlin
 * if (liveReconnect && liveUrlProvider?.invoke() != null) {
 *     scheduleLiveRetry()
 *     return            // ← 一次回调都不发
 * }
 * ```
 *
 * 结果就是：屏幕上是**上一集/上一个频道冻住的最后一帧**，按键有反应
 * （浮层、语音都正常），但画面永远不变，也没有任何提示 —— 看起来就是死机。
 *
 * 所以这里用一个**必然连不上**的地址（本机 1 端口）走一遍真实的失败路径，钉住两件事：
 *  1. [PlaybackEngine.Listener.onLiveRetry] 会被调用 —— 界面层有话说；
 *  2. 不走 [PlaybackEngine.Listener.onError] —— 直播的重连是引擎自己的活，
 *     界面再挂一套重试就会互相打架（见 `MainActivity.onLiveRetry`）。
 *
 * 结果看 `adb logcat -s FireflyLive`。
 */
@RunWith(AndroidJUnit4::class)
class LiveFailureVisibilityTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(msg: String) = android.util.Log.i("FireflyLive", msg)

    /** 127.0.0.1:1 上没有服务，connect 会立刻被拒，不用等 15 秒的 I/O 超时。 */
    private val deadUrl = "http://127.0.0.1:1/none.m3u8"

    @Test
    fun 直播连不上时界面层会收到重连通知而不是一直静默() {
        val notified = CountDownLatch(1)
        val attempt = AtomicInteger(0)
        val url = AtomicReference<String?>(null)
        val errored = AtomicReference<String?>(null)
        val main = Handler(Looper.getMainLooper())
        val engine = IjkPlaybackEngine(context)

        main.post {
            engine.setListener(object : PlaybackEngine.Listener {
                override fun onPrepared(durationMs: Long) = Unit
                override fun onCompletion() = Unit
                override fun onFirstFrame() = Unit

                override fun onError(friendlyMessage: String, fatal: Boolean) {
                    errored.set("$friendlyMessage/fatal=$fatal")
                    notified.countDown()
                }

                override fun onLiveRetry(a: Int, u: String?) {
                    attempt.set(a)
                    url.set(u)
                    notified.countDown()
                }
            })
            engine.setMode(PlaybackMode.Kind.LIVE)
            engine.setLiveReconnect(true) { deadUrl }
            engine.playUrl(deadUrl)
        }

        val ok = notified.await(30, TimeUnit.SECONDS)
        engine.stop()

        assertTrue(
            "直播连不上时引擎必须回调 onLiveRetry —— 不回调就是用户看到的「画面冻住、一声不吭」",
            ok,
        )
        assertEquals("第一次失败就该报出来，界面层才知道要说句话", 1, attempt.get())
        assertEquals("要带着正在重连的地址，日志里才追得下去", deadUrl, url.get())
        assertFalse(
            "直播的重连由引擎负责（2s 起退避、永不放弃），不该走 onError 让界面再挂一套重试",
            errored.get() != null,
        )
        log("onLiveRetry 第 ${attempt.get()} 次，url=${url.get()}；onError=${errored.get()}")
    }

    /**
     * 反向钉住：**点播**失败该走 [PlaybackEngine.Listener.onError]（故障页），
     * 而不是被当成直播断流去重连。
     *
     * 这条守的是一个真实的坑：`liveReconnect` 一旦被打开就**再也没有关过**
     * （界面层换台时才置它）。如果只按 `liveReconnect` 判断，
     * 用户「先看直播、再回电视剧」以后，某一集 SMB 读取失败就会被误判成直播断流 ——
     * 去重连一个根本没在播的频道，该出的故障页永远不出来，又是一个「卡住」。
     * 所以判据里必须有「当前确实在播直播」这一半（`kind`）。
     */
    @Test
    fun 点播失败仍然走故障页而不是被当成直播断流() {
        val notified = CountDownLatch(1)
        val errored = AtomicReference<String?>(null)
        val retries = AtomicInteger(0)
        val main = Handler(Looper.getMainLooper())
        val engine = IjkPlaybackEngine(context)
        val missing = File(context.cacheDir, "不存在的片子.mp4").absolutePath

        main.post {
            engine.setListener(object : PlaybackEngine.Listener {
                override fun onPrepared(durationMs: Long) = Unit
                override fun onCompletion() = Unit
                override fun onFirstFrame() = Unit

                override fun onError(friendlyMessage: String, fatal: Boolean) {
                    errored.set(friendlyMessage)
                    notified.countDown()
                }

                override fun onLiveRetry(attempt: Int, url: String?) {
                    retries.incrementAndGet()
                    notified.countDown()
                }
            })
            engine.setMode(PlaybackMode.Kind.ON_DEMAND)
            // 故意把直播重连开着：它不该影响点播
            engine.setLiveReconnect(true) { deadUrl }
            engine.playSource({ FileRandomAccessSource(missing) }, 0L)
        }

        val ok = notified.await(30, TimeUnit.SECONDS)
        engine.stop()

        assertTrue("点播失败必须报出来，不能一声不吭", ok)
        assertFalse(
            "点播失败被当成直播断流了：会去重连一个没在播的频道，故障页永远不出现",
            retries.get() > 0,
        )
        assertTrue("点播要出故障页，消息不能是空的", !errored.get().isNullOrBlank())
        log("点播失败：onError=${errored.get()}，onLiveRetry 次数=${retries.get()}")
    }
}
