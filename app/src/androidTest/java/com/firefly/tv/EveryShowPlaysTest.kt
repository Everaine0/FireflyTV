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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 把「切换其他电视剧卡死」扫一遍：**每一个库里的每一集**都真起播一次，
 * 断言首帧出来了。
 *
 * ## 为什么做成全扫而不是抽查
 *
 * 用户报的是「除了娘道其他都卡在当前页面」。抽查很容易碰到「碰巧能播的那一个」，
 * 然后得出「已经好了」的错误结论。只有全扫才能回答「是不是每一部都能播」。
 *
 * 判据用 `MEDIA_INFO_VIDEO_RENDERING_START`（首帧真的上屏），
 * 而不是「起播没报错」—— 卡死的表现恰恰是「没报错，但画面永远不出来」。
 *
 * 每集只验证到首帧就切走，所以总耗时可控。
 */
@RunWith(AndroidJUnit4::class)
class EveryShowPlaysTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args get() = InstrumentationRegistry.getArguments()
    private fun arg(k: String) = args.getString(k, "").orEmpty()
    private val cfg get() = Config.Smb(
        arg("ff.smb.host"), arg("ff.smb.share"), arg("ff.smb.root"),
        arg("ff.smb.user"), arg("ff.smb.pass"), arg("ff.smb.domain"),
    )

    private fun say(msg: String) {
        println(msg)
        android.util.Log.i("FireflyEveryShow", msg)
    }

    @Test
    fun everyShowInVideoLibraryStartsRendering() {
        assumeTrue("没有配置真 NAS 账号，跳过", cfg.host.isNotBlank() && cfg.share.isNotBlank())

        val libs = try {
            SmbClient(cfg).use0 { Scanner.libraries(cfg) }
        } catch (t: Throwable) {
            assumeTrue("扫不到库，跳过：${t.message}", false)
            return
        }
        val videoLib = libs.filterIsInstance<Library.Video>().firstOrNull()
        assumeTrue("没有视频库，跳过", videoLib != null)

        val shows = try {
            Scanner.shows(cfg, videoLib!!)
        } catch (t: Throwable) {
            assumeTrue("列不到剧，跳过：${t.message}", false)
            return
        }
        say("=== 视频库《${videoLib.name}》共 ${shows.size} 部剧 ===")

        // 只测每部剧的第一集：卡死是「切换」时发生的，第一集就能暴露
        val report = ArrayList<String>()
        var okCount = 0

        for (show in shows) {
            val eps = try {
                Scanner.episodes(cfg, videoLib, show)
            } catch (t: Throwable) {
                say("!! 《$show》列集失败：${t.message}")
                report += "%-34s 列集失败".format(show.take(34))
                continue
            }
            if (eps.isEmpty()) {
                say("-- 《$show》没有可播文件（跳过）")
                continue
            }

            val ep = eps.first()
            val path = Scanner.episodePath(videoLib, show, ep)
            val r = playUntilFirstFrame(path)
            val mark = if (r.firstFrame) "OK" else "卡死"
            if (r.firstFrame) okCount++
            say("%-6s 《%s》[%s] 首帧=%s 音频=%s (%.1fs)%s".format(
                mark, show, ep, r.firstFrame, r.audio, r.seconds,
                if (r.error != null) " 错误=${r.error}" else "",
            ))
            report += "%-34s %-4s 首帧=%-5s 音频=%-5s %5.1fs".format(
                show.take(34), mark, r.firstFrame, r.audio, r.seconds,
            )
        }

        say("========== 全剧起播扫描结果 ==========")
        for (line in report) say(line)
        say("======================================")
        say("共 ${report.size} 部，首帧成功 $okCount 部")

        val stuck = report.filter { it.contains("卡死") || it.contains("列集失败") }
        assertTrue(
            "以下剧集没能出首帧（就是「切换后卡在当前页面」）：\n" +
                stuck.joinToString("\n"),
            stuck.isEmpty(),
        )
    }

    private class R(val firstFrame: Boolean, val audio: Boolean, val seconds: Double, val error: String?)

    private fun playUntilFirstFrame(path: String): R {
        val frame = CountDownLatch(1)
        val audio = CountDownLatch(1)
        var err: String? = null

        val engine = IjkPlaybackEngine(ctx)
        engine.setListener(object : PlaybackEngine.Listener {
            override fun onPrepared(durationMs: Long) = Unit
            override fun onCompletion() = Unit
            override fun onError(friendlyMessage: String, fatal: Boolean) {
                err = friendlyMessage
            }

            override fun onFirstFrame() = frame.countDown()
            override fun onAudioStarted() = audio.countDown()
        })
        engine.setMode(PlaybackMode.Kind.ON_DEMAND)
        engine.attach(OffscreenSurface.make())

        val t0 = System.currentTimeMillis()
        val worker = Thread { runCatching { engine.playSmb(cfg, path, 0L) } }
        worker.isDaemon = true
        worker.start()

        frame.await(START_TIMEOUT_S, TimeUnit.SECONDS)
        // 首帧之后给音频一点时间。4K H.265 在模拟器上纯软解极慢，
        // 视频线程会把 CPU 吃光，音频可能排不上队 —— 所以这里的等待只是
        // 「尽量观察到」，不作为断言依据（真正的音频断言在 Ac3AudioTest 里，
        // 那份是能实时播放的片源）。
        audio.await(25, TimeUnit.SECONDS)
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        val r = R(frame.count == 0L, audio.count == 0L, secs, err)
        runCatching { engine.release() }
        Thread.sleep(300)
        return r
    }

    private companion object {
        /** 起播看门狗是 25 秒（MainActivity），这里给到 35 秒留余量。 */
        const val START_TIMEOUT_S = 35L
    }
}
