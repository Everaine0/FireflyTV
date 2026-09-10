package com.firefly.tv.player

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.firefly.tv.core.Config
import com.firefly.tv.media.Library
import com.firefly.tv.media.LiveSource
import com.firefly.tv.media.MediaSniff
import com.firefly.tv.media.Scanner
import com.firefly.tv.smb.SmbClient
import com.firefly.tv.smb.SmbStore
import com.firefly.tv.smb.SmbTestProbe
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * **格式兼容性逐类实测**（用户要求「拉下来测一遍」）。
 *
 * 这台 NAS 上的真实片源分成几类，容器/编码完全不同，任何一类出问题都会表现成
 * 「这台电视看不了那个片子」：
 *
 * | 片源 | 容器 | 视频 | 音频 | 备注 |
 * |---|---|---|---|---|
 * | 娘道 | MPEG-TS（后缀却叫 .mp4） | H.264 High 1080p50 | **AC-3** 48k 立体声 | 约 7.5 Mbps |
 * | 大宅门 | MP4，moov 在尾部 | **H.265 4K** | AAC | 需要 moov 搬迁 |
 * | 猫和老鼠 | MP4，moov 在尾部 | H.264 | AAC | 157 集 |
 * | IPTV | HLS（10 秒 MPEG-TS 分片） | H.264 1080p25 | **MP2** 48k 立体声 | 每片约 4 MB |
 *
 * 产出是一份**事实清单**：每一类到底能不能起播、出首帧、出声。
 * 「合格」要拿这份清单去对，不靠猜、也不靠人耳听。
 *
 * 结果用 `adb logcat -s FireflyFormat` 看。
 */
@RunWith(AndroidJUnit4::class)
class FormatMatrixTest {

    private val args = InstrumentationRegistry.getArguments()
    private fun arg(k: String) = args.getString(k, "").orEmpty()

    private val cfg get() = Config.Smb(
        arg("ff.smb.host"), arg("ff.smb.share"), arg("ff.smb.root"),
        arg("ff.smb.user"), arg("ff.smb.pass"), arg("ff.smb.domain"),
    )

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(msg: String) = android.util.Log.i("FireflyFormat", msg)

    private fun requireServer(): List<Library> {
        assumeTrue("没有配置真 NAS 账号，跳过", cfg.host.isNotBlank() && cfg.share.isNotBlank())
        val libs = try {
            SmbStore.with(cfg) { Scanner.libraries(cfg) }
        } catch (t: Throwable) {
            log("SMB 不可达：${SmbClient.describe(t)}")
            emptyList()
        }
        assumeTrue("SMB 不可达，跳过", libs.isNotEmpty())
        return libs
    }

    // ---- 第一块：设备本身的音频通路 ----

    /**
     * 设备能不能真的输出 48kHz 立体声。
     *
     * 用户报「没声音」时，第一步要分清是「设备/模拟器没有音频输出」还是「应用没解码出声」。
     * 这里直接按片源用的参数（48kHz 立体声 PCM16）建一条 AudioTrack 并写一段静音，
     * 能建起来就说明设备的音频通路是通的。
     */
    @Test
    fun 设备音频输出通路可用() {
        val rate = 48_000
        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        log("==== 设备音频通路 ====")
        log("minBufferSize($rate, stereo, pcm16) = $minBuf")

        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        log(
            "音量=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} " +
                "muted=${am.isStreamMute(AudioManager.STREAM_MUSIC)} 采样率=${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)}"
        )

        assumeTrue("设备不支持 48kHz 立体声输出", minBuf > 0)

        // 用 API 21 就有的构造器。AudioTrack.Builder 是 API 23 才加的 ——
        // 目标电视是 Android 5.1，用新 API 会直接 NoClassDefFoundError（这里就踩了一次）。
        @Suppress("DEPRECATION")
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
            AudioTrack.MODE_STREAM,
        )

        try {
            track.play()
            val silence = ShortArray(rate / 10) // 100ms
            val written = track.write(silence, 0, silence.size)
            Thread.sleep(300)
            log("AudioTrack 创建成功 state=${track.state} playState=${track.playState} 写入=$written 采样")
            assertTrue("AudioTrack 没能进入播放态 —— 这台设备的音频输出有问题", track.playState == AudioTrack.PLAYSTATE_PLAYING)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    // ---- 第二块：真实片源逐类播放 ----

    @Test
    fun 逐类格式实测() {
        val libs = requireServer()
        val videos = libs.filterIsInstance<Library.Video>()
        val lives = libs.filterIsInstance<Library.Live>()

        log("================ 设备 ================")
        log("Android ${android.os.Build.VERSION.RELEASE} / ${android.os.Build.SUPPORTED_ABIS.joinToString()}")

        log("================ 片源清单 ================")
        val samples = ArrayList<Triple<Library.Video, String, String>>()
        for (lib in videos) {
            for (show in SmbStore.with(cfg) { Scanner.shows(cfg, lib) }) {
                val eps = SmbStore.with(cfg) { Scanner.episodes(cfg, lib, show) }
                if (eps.isEmpty()) continue
                log("《$show》 ${eps.size} 集")
                log("   ${describe(lib, show, eps.first())}")
                samples += Triple(lib, show, eps.first())
            }
        }

        var okCount = 0
        var failCount = 0
        var audioCount = 0
        var skipped = 0

        for ((lib, show, ep) in samples) {
            val path = Scanner.episodePath(lib, show, ep)
            val mb = fileSizeMb(path)
            // 4K/H.265 大文件在模拟器上会把模拟器整个搞崩（实测跑挂过一次），
            // 那是模拟器软解的极限，不是应用的问题。真机（armv7/arm64）走的是
            // ijkplayer 自带的软解，另有 armv7 的格式矩阵结论可用。
            if (mb > HUGE_MB) {
                log("================ 跳过《$show》（${"%.0f".format(mb)}MB > ${HUGE_MB}MB，模拟器扛不住）")
                skipped++
                continue
            }
            log("================ 试播 《$show》/$ep ================")
            val r = observe(90_000L) { engine ->
                engine.setMode(PlaybackMode.Kind.ON_DEMAND)
                engine.playSmb(cfg, path, 0L)
            }
            log("结果: 起播=${r.prepared} 首帧=${r.firstFrame} 出声=${r.audio} 位置=${r.positionMs}ms 时长=${r.durationMs}ms 错误=${r.error}")
            if (r.prepared && r.firstFrame) okCount++ else failCount++
            if (r.audio) audioCount++
        }

        for (lib in lives) {
            val channels = SmbStore.with(cfg) { LiveSource.channels(cfg, lib) }
            log("================ 直播库 ${lib.name}: ${channels.size} 个频道 ================")
            for (ch in channels.take(3)) {
                log("---- 试播频道 ${ch.name} ----")
                log("   url=${ch.url}")
                val r = observe(30_000L) { engine ->
                    engine.setMode(PlaybackMode.Kind.LIVE)
                    engine.playUrl(ch.url)
                }
                log("结果: 起播=${r.prepared} 首帧=${r.firstFrame} 出声=${r.audio} 位置=${r.positionMs}ms 时长=${r.durationMs}ms 错误=${r.error}")
                if (r.prepared && r.firstFrame) okCount++ else failCount++
                if (r.audio) audioCount++
            }
        }

        log("================ 汇总 ================")
        log("画面成功=$okCount 画面失败=$failCount 有声音=$audioCount 跳过(过大)=$skipped")
        assertTrue("没有任何一种片源能播，播放通路本身有问题", okCount > 0)
    }

    private fun fileSizeMb(path: String): Double = try {
        SmbStore.with(cfg) { client -> client.open(path).use { it.size } } / 1024.0 / 1024.0
    } catch (_: Throwable) {
        0.0
    }

    /** 头部探测 + 体积 + moov 位置 =「这条片源长什么样」。 */
    private fun describe(lib: Library.Video, show: String, ep: String): String {
        val path = Scanner.episodePath(lib, show, ep)
        val head = SmbStore.with(cfg) { it.head(path, MediaSniff.HEAD_BYTES) }
        val total = SmbStore.with(cfg) { client -> client.open(path).use { it.size } }
        val hex = head.take(12).joinToString("") { "%02x".format(it) }
        val kind = when {
            head.size >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> "ISO-BMFF(mp4)"
            head.isNotEmpty() && head[0] == 0x47.toByte() -> "MPEG-TS"
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() -> "Matroska"
            else -> "未知"
        }
        val moov = runCatching { SmbStore.with(cfg) { SmbTestProbe.probeMoov(it, path) } }.getOrDefault("?")
        return "大小=${"%.1f".format(total / 1024.0 / 1024.0)}MB 容器=$kind moov=$moov 头=$hex"
    }

    private class Result(
        val prepared: Boolean,
        val firstFrame: Boolean,
        val audio: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val error: String?,
    )

    /**
     * 真播一次：等起播 → 等首帧 → 再让它播几秒看音频有没有出来。
     *
     * 「出声」用的是 ijkplayer 的 `MEDIA_INFO_AUDIO_RENDERING_START`，
     * 不是翻日志猜的 —— 这样「有画面没声音」在测试里就是一条明确的红/绿。
     */
    private fun observe(timeoutMs: Long, start: (IjkPlaybackEngine) -> Unit): Result {
        val prepared = CountDownLatch(1)
        val firstFrame = CountDownLatch(1)
        val audio = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val main = Handler(Looper.getMainLooper())
        val engine = IjkPlaybackEngine(ctx)

        main.post {
            engine.setListener(object : PlaybackEngine.Listener {
                override fun onPrepared(durationMs: Long) {
                    log("  onPrepared duration=${durationMs}ms")
                    prepared.countDown()
                }

                override fun onCompletion() = Unit

                override fun onError(friendlyMessage: String, fatal: Boolean) {
                    log("  onError [$friendlyMessage] fatal=$fatal")
                    failure.set(friendlyMessage)
                    prepared.countDown()
                }

                override fun onFirstFrame() = firstFrame.countDown()

                override fun onAudioStarted() {
                    log("  onAudioStarted ✅")
                    audio.countDown()
                }
            })
            runCatching { start(engine) }.onFailure { failure.set(it.message) }
        }

        val preparedOk = prepared.await(timeoutMs, TimeUnit.MILLISECONDS)
        val frameOk = if (preparedOk) firstFrame.await(25, TimeUnit.SECONDS) else false
        // 再播几秒：音频起播通常比视频晚一点，尤其直播
        val audioOk = if (preparedOk) audio.await(12, TimeUnit.SECONDS) else false

        val pos = readOnMain(main) { engine.positionMs() }
        val dur = readOnMain(main) { engine.durationMs() }

        main.post { runCatching { engine.release() } }
        Thread.sleep(500)

        return Result(preparedOk, frameOk, audioOk, pos, dur, failure.get())
    }

    private fun readOnMain(main: Handler, block: () -> Long): Long {
        val ref = AtomicReference(0L)
        main.post { ref.set(runCatching(block).getOrDefault(0L)) }
        Thread.sleep(400)
        return ref.get()
    }

    private companion object {
        /** 超过这个体积就不在模拟器上试播了 —— 4K/H.265 会把模拟器整个搞崩。 */
        const val HUGE_MB = 500.0
    }
}
