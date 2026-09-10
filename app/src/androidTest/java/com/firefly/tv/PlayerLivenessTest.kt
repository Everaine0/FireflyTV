package com.firefly.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.firefly.tv.core.Config
import com.firefly.tv.media.Library
import com.firefly.tv.media.Scanner
import com.firefly.tv.player.IjkPlaybackEngine
import com.firefly.tv.player.PlaybackEngine
import com.firefly.tv.player.PlaybackMode
import com.firefly.tv.smb.SmbClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 「播放器悄悄死掉，界面却不知道」——用**位置是否前进**当存活信号。
 *
 * ## 现场证据（这是这个测试的由来）
 *
 * 用户报「大宅门/猫和老鼠卡住」。趁卡住状态抓了两次线程栈，
 * 两次都显示：ijkplayer 的线程（`ff_read` / `ff_audio_dec` / `ff_video_dec` /
 * `ff_aout_android`）**一个都不存在**，主线程和 SMB 读线程都空闲正常，
 * 进程 CPU 增量为 0。
 *
 * 也就是说：**播放器已经彻底死了，但没有触发 `onError`，也没有触发 `onCompletion`。**
 * 而 app 里唯一会恢复的机制就是这两条回调（`stallWatchdog` 首帧一到就被撤掉了），
 * 所以界面永远冻在最后一帧。
 *
 * ## 这个测试要验证什么
 *
 * 修复需要一个「播放器还活着吗」的信号。最可能可用的是 `currentPosition`：
 * 如果它停止前进，就说明解码/读取链已经断了。
 * 这个测试先确认这个信号**在正常播放时确实会前进**——
 * 否则拿它做判据会误杀正常播放。
 */
@RunWith(AndroidJUnit4::class)
class PlayerLivenessTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args get() = InstrumentationRegistry.getArguments()
    private fun arg(k: String) = args.getString(k, "").orEmpty()
    private val cfg get() = Config.Smb(
        arg("ff.smb.host"), arg("ff.smb.share"), arg("ff.smb.root"),
        arg("ff.smb.user"), arg("ff.smb.pass"), arg("ff.smb.domain"),
    )

    private fun say(msg: String) {
        println(msg)
        android.util.Log.i("FireflyLiveness", msg)
    }

    /**
     * 「播放器还活着吗」的判据筛选。
     *
     * 同时采样 `positionMs()` 和 `outputFps()`，看**哪一个真的能反映存活**。
     * 之前想当然地拿位置当判据，实测在 SMB 通路上恒为 0，白写一版。
     * 所以这次先把两个信号都量出来再决定用哪个。
     *
     * 用《猫和老鼠》（H.264 1080p + AAC）而不是 4K 的《大宅门》——
     * 后者在模拟器上即使一切正常也会因为软解不动而掉帧，
     * 那样就分不清「播放器死了」和「模拟器算力不够」。
     */
    @Test
    fun whichSignalReflectsLiveness() {
        assumeTrue("没有配置真 NAS 账号，跳过", cfg.host.isNotBlank() && cfg.share.isNotBlank())
        val libs = SmbClient(cfg).use0 { Scanner.libraries(cfg) }
        val lib = libs.filterIsInstance<Library.Video>().first()
        val shows = Scanner.shows(cfg, lib)
        val show = shows.first { it.contains("猫和老鼠") }
        val ep = Scanner.episodes(cfg, lib, show).first()
        val path = Scanner.episodePath(lib, show, ep)

        val tracing = Trace()
        val engine = IjkPlaybackEngine(ctx)
        engine.setListener(tracing)
        engine.setMode(PlaybackMode.Kind.ON_DEMAND)
        engine.attach(OffscreenSurface.make())

        val worker = Thread { runCatching { engine.playSmb(cfg, path, 0L) } }
        worker.isDaemon = true
        worker.start()

        // 等首帧（最多 30 秒）
        var waited = 0
        while (!tracing.firstFrame.get() && waited < 60) {
            Thread.sleep(500)
            waited++
        }
        say("首帧=${tracing.firstFrame.get()} 音频=${tracing.audio.get()} " +
            "等了 ${waited * 500}ms 时长=${tracing.duration.get()}ms 错误=${tracing.error.get()}")

        // 先把「到底有没有播起来」钉死，否则后面的采样全是无意义的 0
        org.junit.Assert.assertTrue(
            "这个实验的前提是画面真的在播。首帧=${tracing.firstFrame.get()} " +
                "错误=${tracing.error.get()} 等=${waited * 500}ms",
            tracing.firstFrame.get(),
        )

        val fpsSamples = ArrayList<Float>()
        val posSamples = ArrayList<Long>()
        for (i in 0 until 10) {
            fpsSamples += engine.outputFps()
            posSamples += engine.positionMs()
            Thread.sleep(1000)
        }
        runCatching { engine.release() }

        say("outputFps 采样: $fpsSamples")
        say("positionMs 采样: $posSamples")

        val fpsAlive = fpsSamples.count { it > 1f }
        val posMoved = (posSamples.maxOrNull() ?: 0L) - (posSamples.minOrNull() ?: 0L)
        say("帧率 >1 的采样数 = $fpsAlive / ${fpsSamples.size}；位置变化量 = ${posMoved}ms")

        org.junit.Assert.assertTrue(
            "画面确实在播（上面已断言），那 outputFps 必须有读数 —— " +
                "如果这里是 0，说明 ijk 的统计在 IMediaDataSource 通路上不更新，" +
                "存活判据要另想办法。采样=$fpsSamples",
            fpsSamples.any { it > 1f },
        )
    }

    /**
     * 直播通路：线程死掉导致「卡住」的现场就发生在直播上。
     *
     * 直播走 URL（HLS），**不经过 SMB 的 `IMediaDataSource` 桥**，
     * 所以 ijk 的统计有可能和点播表现完全不同。
     * 如果直播上这些计数器是活的，就能拿它们做存活判据 —— 正好覆盖出问题的那条路。
     */
    @Test
    fun liveChannelCounters() {
        assumeTrue("没有配置真 NAS 账号，跳过", cfg.host.isNotBlank() && cfg.share.isNotBlank())
        val libs = SmbClient(cfg).use0 { Scanner.libraries(cfg) }
        val live = libs.filterIsInstance<Library.Live>().firstOrNull()
        assumeTrue("没有直播库，跳过", live != null)

        val chans = try {
            com.firefly.tv.media.LiveSource.channels(cfg, live!!)
        } catch (t: Throwable) {
            assumeTrue("读不到频道列表，跳过：${t.message}", false)
            return
        }
        assumeTrue("频道列表为空，跳过", chans.isNotEmpty())

        // 挑前几个里能连上的，别赌某一个源一定活着
        val candidates = chans.take(6)
        var done = false
        for (c in candidates) {
            val tracing = Trace()
            val engine = IjkPlaybackEngine(ctx)
            engine.setListener(tracing)
            engine.setMode(PlaybackMode.Kind.LIVE)
            engine.attach(OffscreenSurface.make())
            say("试播《${c.name}》${c.url.take(70)}")
            runCatching { engine.playUrl(c.url) }

            var waited = 0
            while (!tracing.firstFrame.get() && !tracing.error.get() && waited < 40) {
                Thread.sleep(500)
                waited++
            }
            say("  首帧=${tracing.firstFrame.get()} 错误=${tracing.error.get()} 等=${waited * 500}ms")

            if (tracing.firstFrame.get()) {
                for (i in 0 until 6) {
                    val l = engine.liveness()
                    say("  t=${i}s cached=${l.videoCachedMs}ms traffic=${l.trafficBytes}B " +
                        "pos=${l.positionMs} fps=${l.outputFps}")
                    Thread.sleep(1000)
                }
                done = true
            }
            runCatching { engine.release() }
            Thread.sleep(500)
            if (done) break
        }
        say("直播采样完成：${if (done) "拿到数据" else "没有频道能起播"}")
    }

    /**
     * SMB 通路上几个计数器各自的表现。
     *
     * 用《猫和老鼠》（最小的一部，234 MB）而不是 4K 的《大宅门》，
     * 排除「模拟器软解不动 4K」这个干扰项。
     */
    @Test
    fun smbCountersSnapshot() {
        assumeTrue("没有配置真 NAS 账号，跳过", cfg.host.isNotBlank() && cfg.share.isNotBlank())
        val libs = SmbClient(cfg).use0 { Scanner.libraries(cfg) }
        val lib = libs.filterIsInstance<Library.Video>().first()
        val shows = Scanner.shows(cfg, lib)
        val show = shows.first { it.contains("猫和老鼠") }
        val ep = Scanner.episodes(cfg, lib, show).first()
        val path = Scanner.episodePath(lib, show, ep)

        val tracing = Trace()
        val engine = IjkPlaybackEngine(ctx)
        engine.setListener(tracing)
        engine.setMode(PlaybackMode.Kind.ON_DEMAND)
        engine.attach(OffscreenSurface.make())

        val worker = Thread { runCatching { engine.playSmb(cfg, path, 0L) } }
        worker.isDaemon = true
        worker.start()

        var waited = 0
        while (!tracing.firstFrame.get() && waited < 60) {
            Thread.sleep(500)
            waited++
        }
        say("SMB：首帧=${tracing.firstFrame.get()} 时长=${tracing.duration.get()}ms " +
            "等=${waited * 500}ms")

        for (i in 0 until 8) {
            val l = engine.liveness()
            say("  t=${i}s cached=${l.videoCachedMs}ms traffic=${l.trafficBytes}B " +
                "pos=${l.positionMs} fps=${l.outputFps}")
            Thread.sleep(1000)
        }
        runCatching { engine.release() }
    }

    /**
     * 本地文件对照：同一个引擎、同一份内核，只把源换成 `test.mp4`。
     *
     * 用来把「ijk 的统计在这个 build 上根本不更新」和
     * 「只有 SMB/大文件通路不更新」分开。既有测试已经证明
     * `test.mp4` 上 `currentPosition` 能读到 >1000ms，所以这一条应当通过 ——
     * 如果它也不动，说明是环境问题而不是通路问题。
     */
    @Test
    fun localFileSignalsWork() {
        val video = com.firefly.tv.player.TestVideo.ensure(ctx)
        val tracing = Trace()
        val engine = IjkPlaybackEngine(ctx)
        engine.setListener(tracing)
        engine.setMode(PlaybackMode.Kind.ON_DEMAND)
        engine.attach(OffscreenSurface.make())

        engine.playSource(com.firefly.tv.player.FileRandomAccessSource(video.absolutePath), 0L)

        var waited = 0
        while (!tracing.firstFrame.get() && waited < 60) {
            Thread.sleep(500)
            waited++
        }
        say("本地文件：首帧=${tracing.firstFrame.get()} 时长=${tracing.duration.get()}ms")

        val fps = ArrayList<Float>()
        val pos = ArrayList<Long>()
        for (i in 0 until 6) {
            fps += engine.outputFps()
            pos += engine.positionMs()
            Thread.sleep(1000)
        }
        runCatching { engine.release() }

        say("本地文件 outputFps: $fps")
        say("本地文件 positionMs: $pos")
        org.junit.Assert.assertTrue("本地文件也必须能出首帧", tracing.firstFrame.get())
        org.junit.Assert.assertTrue(
            "本地文件上 currentPosition 必须前进（既有测试依赖这一点）。实测：$pos",
            (pos.maxOrNull() ?: 0L) - (pos.minOrNull() ?: 0L) > 500,
        )
    }

    /**
     * 点播：位置必须随时间前进。
     *
     * ⚠️ 已知结论：这一条在 SMB 通路上**会失败** —— `currentPosition` 恒为 0。
     * 保留它是为了把这个事实钉在测试里，免得以后有人又拿位置去做存活判据
     * （我自己就先这么干过一次）。存活判据改用 [PlaybackEngine.outputFps]。
     */
    @Test
    fun onDemandPositionAdvances() {
        assumeTrue("没有配置真 NAS 账号，跳过", cfg.host.isNotBlank() && cfg.share.isNotBlank())
        val libs = SmbClient(cfg).use0 { Scanner.libraries(cfg) }
        val lib = libs.filterIsInstance<Library.Video>().first()
        val shows = Scanner.shows(cfg, lib)
        val show = shows.first { it.contains("猫和老鼠") }
        val ep = Scanner.episodes(cfg, lib, show).first()
        val path = Scanner.episodePath(lib, show, ep)

        val tracing = Trace()
        val engine = IjkPlaybackEngine(ctx)
        engine.setListener(tracing)
        engine.setMode(PlaybackMode.Kind.ON_DEMAND)
        engine.attach(OffscreenSurface.make())

        val worker = Thread { runCatching { engine.playSmb(cfg, path, 0L) } }
        worker.isDaemon = true
        worker.start()

        val samples = sample(engine, tracing, seconds = 8)
        say("点播位置采样：$samples")
        runCatching { engine.release() }

        val advanced = samples.last() - samples.first()
        say("8 秒内位置前进 ${advanced}ms")
        org.junit.Assert.assertTrue(
            "正常播放时 currentPosition 必须前进。实测：$samples —— " +
                "如果这里是 0，说明 SMB 通路上位置不可用，存活判据必须改用 outputFps。",
            advanced > 2_000,
        )
    }

    /** 拿一个能实时播放的点播样本，采样位置。 */
    private fun sample(
        engine: IjkPlaybackEngine,
        tracing: Trace,
        seconds: Int,
    ): List<Long> {
        val out = ArrayList<Long>(seconds)
        // 先等首帧
        for (i in 0 until 60) {
            if (tracing.firstFrame.get()) break
            Thread.sleep(500)
        }
        for (i in 0 until seconds) {
            out += engine.positionMs()
            Thread.sleep(1000)
        }
        return out
    }

    private class Trace : PlaybackEngine.Listener {
        val firstFrame = AtomicBoolean(false)
        val audio = AtomicBoolean(false)
        val error = AtomicBoolean(false)
        val completion = AtomicBoolean(false)
        val duration = AtomicLong(0)

        override fun onPrepared(durationMs: Long) {
            duration.set(durationMs)
        }

        override fun onCompletion() {
            completion.set(true)
        }

        override fun onError(friendlyMessage: String, fatal: Boolean) {
            error.set(true)
        }

        override fun onFirstFrame() {
            firstFrame.set(true)
        }

        override fun onAudioStarted() {
            audio.set(true)
        }
    }
}
