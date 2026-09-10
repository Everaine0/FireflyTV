package com.firefly.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.firefly.tv.media.TsProbe
import com.firefly.tv.player.ByteArrayRandomAccessSource
import com.firefly.tv.player.IjkPlaybackEngine
import com.firefly.tv.player.PlaybackEngine
import com.firefly.tv.player.PlaybackMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频解码能力对照实验：**同一段视频，只换音频编码**。
 *
 * ## 为什么非要做这个实验
 *
 * 「娘道没声音」这个现象牵扯太多变量：SMB 链路、MPEG-TS 容器、15 小时的时间戳偏移、
 * 片源的 PAT/PMT……任何一环出问题都会表现成「没声音」。
 * 光看真实片源，永远分不清到底是**内核不具备 AC-3 解码能力**，
 * 还是**这条流有别的问题**。
 *
 * 所以这里用 ffmpeg 生成一组**除了音频编码全都一样**的片段
 * （脚本 `scripts/make-audio-fixtures.ps1`），逐个播放，看音频起播回调有没有来。
 * 结论是二值的：能出声 / 不能出声，不含推断。
 *
 * ## 判据
 *
 * `FFP_MSG_AUDIO_RENDERING_START` —— ijkplayer 在音频真的开始往 AudioTrack 送数据时触发。
 * 比「用耳朵听」或「翻日志找 SDL_Android_AudioTrack」都可靠。
 */
@RunWith(AndroidJUnit4::class)
class AudioCodecMatrixTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 读**测试 APK** 里的样本。
     *
     * 必须用 `instrumentation.context`（测试 APK）而不是 `targetContext`（被测应用）：
     * 样本放在 `app/src/androidTest/assets/`，只打进测试 APK。
     * 用错 context 会抛 FileNotFoundException，而异常信息就是文件名本身，
     * 看上去特别像「文件不存在」，很容易往构建配置上查 —— 实际是拿错了 context。
     * （`TestVideo.kt` 里有同样的注释，这里是第二次踩。）
     */
    private val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private class Outcome(val name: String, val video: Boolean, val audio: Boolean, val ms: Long) {
        override fun toString() =
            "%-16s 视频=%-5s 音频=%-5s (%dms)".format(name, video, audio, ms)
    }

    /**
     * 把结论同时写进 logcat。
     *
     * 插桩测试的 stdout 在 gradle 报告里经常拿不到（踩过），
     * 而 logcat 是持久的、能事后 `adb logcat -d` 回读的 —— 排查时这点很关键。
     */
    private fun say(msg: String) {
        println(msg)
        android.util.Log.i("FireflyAudioMatrix", msg)
    }

    @Test
    fun allAudioCodecsProduceSound() {
        val names = listOf(
            "audio-aac.ts",
            "audio-ac3.ts",
            "audio-eac3.ts",
            "audio-mp2.ts",
            "audio-aac.mp4",
            "audio-ac3.mp4",
        )

        val results = ArrayList<Outcome>()
        for (n in names) {
            val data = try {
                testAssets.open(n).use { it.readBytes() }
            } catch (t: Throwable) {
                say("!! 读不到样本 $n: ${t.message}")
                continue
            }
            val r = play(n, data)
            val o = Outcome(n, r.video, r.audio, r.ms)
            results += o
            say("=== $o")
        }

        say("========== 音频编码对照表 ==========")
        for (r in results) say(r.toString())
        say("===================================")

        // 先把「哪个能出声」这个事实固定下来：全都不出声说明是测试环境问题
        assertTrue("一个能出声的都没有，说明是环境问题而不是编码问题", results.any { it.audio })

        val silent = results.filter { !it.audio }
        assertTrue(
            "以下样本没有输出音频：${silent.joinToString { it.name }}。" +
                "它们和能出声的样本只有音频编码不同，所以说明内核缺对应的解码器。",
            silent.isEmpty(),
        )
    }

    /**
     * MPEG-TS 片源的音频轨必须能被 TsProbe 认出来。
     *
     * 这条用来区分「探测认不出」（探测的锅）和「认出来了但播不出来」（解码的锅）。
     */
    @Test
    fun tsFixturesAreDetected() {
        for (n in listOf("audio-ac3.ts", "audio-aac.ts", "audio-mp2.ts", "audio-eac3.ts")) {
            val data = testAssets.open(n).use { it.readBytes() }
            val info = TsProbe.probe(data)
            println("$n -> $info")
            assertTrue("$n 应该被认出是 TS", info != null)
            assertTrue("$n 应该认出有音频", info!!.hasAudio)
        }
    }

    private class R(val video: Boolean, val audio: Boolean, val ms: Long)

    private fun play(name: String, data: ByteArray): R {
        val video = CountDownLatch(1)
        val audio = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        val engine = IjkPlaybackEngine(ctx)
        engine.setListener(object : PlaybackEngine.Listener {
            override fun onPrepared(durationMs: Long) = say("   $name onPrepared ${durationMs}ms")
            override fun onCompletion() = say("   $name onCompletion")
            override fun onError(friendlyMessage: String, fatal: Boolean) {
                failed.set(true)
                say("   $name onError: $friendlyMessage fatal=$fatal")
            }

            override fun onFirstFrame() {
                say("   $name 首帧")
                video.countDown()
            }

            override fun onAudioStarted() {
                say("   $name 音频起播")
                audio.countDown()
            }
        })
        engine.setMode(PlaybackMode.Kind.ON_DEMAND)
        engine.attach(OffscreenSurface.make())

        val t0 = System.currentTimeMillis()
        engine.playSource(ByteArrayRandomAccessSource(data), 0L)
        video.await(20, TimeUnit.SECONDS)
        audio.await(15, TimeUnit.SECONDS)
        val ms = System.currentTimeMillis() - t0

        val r = R(video.count == 0L, audio.count == 0L, ms)
        runCatching { engine.release() }
        Thread.sleep(400)
        return r
    }
}
