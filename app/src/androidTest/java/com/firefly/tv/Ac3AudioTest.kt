package com.firefly.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.firefly.tv.core.Config
import com.firefly.tv.player.ByteArrayRandomAccessSource
import com.firefly.tv.player.IjkPlaybackEngine
import com.firefly.tv.player.PlaybackEngine
import com.firefly.tv.player.PlaybackMode
import com.firefly.tv.smb.SmbClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 「内核有 AC-3 能力」和「娘道能出声」之间的落差。
 *
 * ## 这个测试要回答的问题
 *
 * 已经用 [AudioCodecMatrixTest] 证明：**同一份内核配 AC-3 是能出声的**
 * （自生成的 `audio-ac3.ts` / `audio-ac3.mp4` 都拿到了音频起播回调）。
 * 所以「内核缺 AC-3 解码器」这个结论已经被排除。
 *
 * 那娘道为什么还是没声音？剩下两种可能：
 *  1. 内核没问题，是**这条流**有别的特征（时间戳偏移、码率、SPS/PPS 布局…）让音频打不开；
 *  2. SMB 读取链路在这一集的读取模式下出了问题。
 *
 * 这个测试用**同一台设备、同一份内核、同一条 SMB 链路**分别播
 * 「内核自测样本」和「娘道真实片源」，把两者直接对照。
 *
 * ## 判据
 *
 * `FFP_MSG_AUDIO_RENDERING_START` —— 音频真的开始往 AudioTrack 送数据。
 * 二值结论，不依赖耳朵听，也不依赖我读日志的细心程度。
 *
 * ## 两个 context 的区别（踩过两次）
 *
 * `instrumentation.context` = 测试 APK，`targetContext` = 被测应用。
 * 样本放在 `app/src/androidTest/assets/`，只打进测试 APK，**必须**用前者；
 * 拿错 context 抛的 FileNotFoundException 消息就是文件名本身，看着像「文件不存在」，
 * 很容易误查到构建配置上去。
 */
@RunWith(AndroidJUnit4::class)
class Ac3AudioTest {

    private val appCtx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets

    /**
     * NAS 账号从 instrumentation 参数取，**不读应用里的配置**。
     *
     * 原因：跑插桩测试会把被测应用重装，应用里保存的配置就没了 ——
     * 那时候 `Config.smb(ctx)` 读到的是空值，测试会「静默跳过」而不是失败，
     * 看起来像通过，实际什么都没验证（这个坑已经踩过）。
     * 账号由 `local.properties` → gradle → 这里注入，和 `FormatMatrixTest` 一致。
     */
    private val args = InstrumentationRegistry.getArguments()
    private fun arg(k: String) = args.getString(k, "").orEmpty()
    private val cfg get() = Config.Smb(
        arg("ff.smb.host"), arg("ff.smb.share"), arg("ff.smb.root"),
        arg("ff.smb.user"), arg("ff.smb.pass"), arg("ff.smb.domain"),
    )

    /** 对照 A：内核自测样本（已知能出声，用来证明「设备+内核没问题」）。 */
    @Test
    fun kernelCanPlayAc3() {
        val data = testAssets.open("audio-ac3.ts").use { it.readBytes() }
        val r = play { e -> e.playSource(ByteArrayRandomAccessSource(data), 0L) }
        println("=== 内核自测 audio-ac3.ts: $r ===")
        assertTrue("对照样本必须能出声，否则说明是环境问题：$r", r.audioStarted)
    }

    /** 对照 B：同一条 SMB 链路上的 AAC 片源（已知能出声）。 */
    @Test
    fun aacShowViaSmbProducesAudio() {
        val cfg = nasConfig()
        val r = play { e -> e.playSmb(cfg, AAC_SHOW, 0L) }
        println("=== 猫和老鼠(AAC) SMB: $r ===")
        assumeTrue("读不到对照片源，跳过", r.prepared || r.videoStarted)
        assertTrue("AAC 片源必须能出声，否则问题不在音频编码：$r", r.audioStarted)
    }

    /** 被测对象：娘道真实片源，走真实 SMB 路径。 */
    @Test
    fun niangdaoViaSmbProducesAudio() {
        val cfg = nasConfig()
        val r = play { e -> e.playSmb(cfg, NIANGDAO, 0L) }
        println("=== 娘道 01.mp4 (SMB): $r ===")
        assertTrue("画面应该出得来（否则是另一个问题）", r.videoStarted)
        assertTrue(
            "播放器没有输出音频。已确认内核能播 AC-3（见 kernelCanPlayAc3），" +
                "所以问题在「这条流的音频打不开」，不在解码器能力。",
            r.audioStarted,
        )
    }

    private fun nasConfig(): Config.Smb {
        assumeTrue(
            "没有配置真 NAS 账号，跳过（检查 local.properties 的 ff.smb.*）",
            cfg.host.isNotBlank() && cfg.share.isNotBlank(),
        )
        return cfg
    }

    private class Result(
        val prepared: Boolean,
        val videoStarted: Boolean,
        val audioStarted: Boolean,
        val durationMs: Long,
        val error: String?,
    ) {
        override fun toString() =
            "prepared=$prepared video=$videoStarted audio=$audioStarted " +
                "duration=${durationMs}ms error=$error"
    }

    private inline fun play(crossinline start: (IjkPlaybackEngine) -> Unit): Result {
        val prepared = CountDownLatch(1)
        val video = CountDownLatch(1)
        val audio = CountDownLatch(1)
        val duration = AtomicLong(0)
        val failed = AtomicBoolean(false)
        var errMsg: String? = null

        val engine = IjkPlaybackEngine(appCtx)
        engine.setListener(object : PlaybackEngine.Listener {
            override fun onPrepared(durationMs: Long) {
                duration.set(durationMs)
                prepared.countDown()
            }

            override fun onCompletion() = Unit
            override fun onError(friendlyMessage: String, fatal: Boolean) {
                errMsg = "$friendlyMessage(fatal=$fatal)"
                failed.set(true)
                prepared.countDown()
            }

            override fun onFirstFrame() = video.countDown()
            override fun onAudioStarted() = audio.countDown()
        })
        engine.setMode(PlaybackMode.Kind.ON_DEMAND)
        engine.attach(OffscreenSurface.make())

        // playSmb 会阻塞做 SMB 打开，不能占着测试线程
        val worker = Thread { runCatching { start(engine) } }
        worker.isDaemon = true
        worker.start()

        prepared.await(30, TimeUnit.SECONDS)
        video.await(30, TimeUnit.SECONDS)
        audio.await(20, TimeUnit.SECONDS)

        val r = Result(
            prepared = prepared.count == 0L && !failed.get(),
            videoStarted = video.count == 0L,
            audioStarted = audio.count == 0L,
            durationMs = duration.get(),
            error = errMsg,
        )
        runCatching { engine.release() }
        Thread.sleep(500)
        return r
    }

    private companion object {
        /** 娘道：MPEG-TS + H.264 + AC-3（后缀是 .mp4，内容不是） */
        const val NIANGDAO = "电视剧/[娘道][2018][全集][国产剧]/01.mp4"

        /** 对照：mp4 + H.265 + AAC */
        const val AAC_SHOW = "电视剧/猫和老鼠 50周年珍藏版 157集/猫和老鼠（001）.mp4"
    }
}

/** 离屏 Surface：测试里不需要真的上屏，只要一个合法的 Surface 对象。 */
internal object OffscreenSurface {
    fun make(): android.view.Surface =
        android.view.Surface(android.graphics.SurfaceTexture(16))
}
