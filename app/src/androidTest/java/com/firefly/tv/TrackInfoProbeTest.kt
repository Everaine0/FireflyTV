package com.firefly.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.firefly.tv.core.Config
import com.firefly.tv.player.SmbMediaDataSource
import com.firefly.tv.player.SmbRandomAccessSource
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * 直接问播放器：「你在这一集里看到了几条轨？」
 *
 * ## 为什么要问它
 *
 * 已经把外围全部排除了：
 *  - 内核有 AC-3（自生成样本出声正常，`AudioCodecMatrixTest`）
 *  - 片源的 PMT 正确（`stream_type=0x81`，PID 0x109C 真实存在，`scripts/ts-psi.py` 可复现）
 *  - 同一条 SMB 链路上 AAC 片源有声（`Ac3AudioTest`）
 *  - 电脑上的 FFmpeg 能把这条流解出来（`-xerror` 退出码 0）
 *
 * 所以问题就落在「ijkplayer 里那份 FFmpeg n3.4 把这一集解析成了什么」。
 * `getTrackInfo()` 返回的就是**解复用器实际识别出来的轨道列表**，
 * 是这个问题最直接的判据。
 *
 * ## 为什么单独建播放器而不是用 IjkPlaybackEngine
 *
 * 这个测试要摸的是 ijk 的元数据 API，属于内核行为，不该为了测试去改生产代码的封装。
 * 数据源用的仍是生产实现（`SmbRandomAccessSource` + `SmbMediaDataSource`），
 * 所以读到的字节和线上完全一致。
 */
@RunWith(AndroidJUnit4::class)
class TrackInfoProbeTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args get() = InstrumentationRegistry.getArguments()
    private fun arg(k: String) = args.getString(k, "").orEmpty()
    private val cfg get() = Config.Smb(
        arg("ff.smb.host"), arg("ff.smb.share"), arg("ff.smb.root"),
        arg("ff.smb.user"), arg("ff.smb.pass"), arg("ff.smb.domain"),
    )

    private fun say(msg: String) {
        println(msg)
        android.util.Log.i("FireflyTracks", msg)
    }

    @Test
    fun compareTracksOfWorkingAndFailingShow() {
        if (cfg.host.isBlank()) {
            say("SKIP: 没有 NAS 账号")
            return
        }
        probe("娘道 (AC-3)", "电视剧/[娘道][2018][全集][国产剧]/01.mp4")
        probe("猫和老鼠 (AAC)", "电视剧/猫和老鼠 50周年珍藏版 157集/猫和老鼠（001）.mp4")
        probe("大宅门 (AAC)", "电视剧/大宅门/[大宅门].The.Grand.Mansion.Gate.2001.S01E01.2160p.WEB-DL.H265.AAC-HotWEB.mp4")
    }

    /**
     * 娘道的音频参数必须能被探测出来。
     *
     * ## 判据的选取过程（走过弯路，记下来免得再绕）
     *
     * 最初想拿「时长」当判据，因为探测失败时 FFmpeg 也算不出时长。
     * **实测证明这个判据是错的**：把 probesize 从 2 MB 提到 16 MB 之后
     * 音频已经正常出声（`Ac3AudioTest.niangdaoViaSmbProducesAudio` 通过），
     * 但 `player.duration` 仍然是 1700ms —— 说明「时长错」和「音频打不开」
     * 虽然同源，却不是同一个可互换的指标。
     *
     * 所以判据回到最直接的那一个：**轨道信息里的音频参数有没有值**。
     * 这正是 FFmpeg 那条警告的内容
     * （`Audio: ac3 ..., 0 channels, fltp: unspecified sample rate`）。
     */
    @Test
    fun niangdaoAudioParamsAreResolved() {
        if (cfg.host.isBlank()) {
            say("SKIP: 没有 NAS 账号")
            return
        }
        val fmt = audioFormatOf("电视剧/[娘道][2018][全集][国产剧]/01.mp4")
        say("娘道 音频轨 format = $fmt")
        org.junit.Assert.assertNotNull("应该有音频轨", fmt)
        val s = fmt.toString()
        org.junit.Assert.assertTrue(
            "音频采样率/声道必须被解析出来，否则 AVCodecContext 打不开，" +
                "音频组件会静默失败（表现为「有画面没声音」）。实测=$s",
            !s.contains("sample-rate=0") && !s.contains("channel-count=0"),
        )
    }

    /** 取音频轨的格式信息（FFmpeg 解析出来的采样率/声道都在这）。 */
    private fun audioFormatOf(path: String): Any? {
        val prepared = CountDownLatch(1)
        val player = IjkMediaPlayer()
        val src = SmbMediaDataSource(SmbRandomAccessSource(cfg, path))
        try {
            player.setDataSource(src)
            player.setOnPreparedListener { prepared.countDown() }
            player.setOnErrorListener { _, _, _ ->
                prepared.countDown()
                true
            }
            val t = com.firefly.tv.player.PlaybackMode.tuning(
                com.firefly.tv.player.PlaybackMode.Kind.ON_DEMAND,
            )
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", t.probesizeBytes)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", t.analyzeDurationUs)
            player.prepareAsync()
            if (!prepared.await(60, TimeUnit.SECONDS)) return null

            return player.trackInfo
                ?.firstOrNull {
                    it.trackType == tv.danmaku.ijk.media.player.misc.ITrackInfo.MEDIA_TRACK_TYPE_AUDIO
                }
                ?.getFormat()
        } finally {
            runCatching { player.release() }
            runCatching { src.close() }
        }
    }

    private fun probe(label: String, path: String) {
        say("---------- $label ----------")
        say("  路径: $path")

        val prepared = CountDownLatch(1)
        val player = IjkMediaPlayer()
        val src = SmbMediaDataSource(SmbRandomAccessSource(cfg, path))
        var duration = 0L

        try {
            player.setDataSource(src)
            player.setOnPreparedListener {
                duration = player.duration
                prepared.countDown()
            }
            player.setOnErrorListener { _, what, extra ->
                say("  onError what=$what extra=$extra")
                prepared.countDown()
                true
            }
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 2L * 1024 * 1024)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 3_000_000L)
            player.prepareAsync()

            if (!prepared.await(40, TimeUnit.SECONDS)) {
                say("  准备超时")
                return
            }

            say("  时长 = ${duration}ms")

            val tracks = try {
                player.trackInfo
            } catch (t: Throwable) {
                say("  getTrackInfo 失败: ${t.message}")
                null
            }
            if (tracks == null || tracks.isEmpty()) {
                say("  ** 轨道列表为空（解复用器一条轨都没识别出来）**")
            } else {
                say("  轨道 ${tracks.size} 条:")
                tracks.forEachIndexed { i, t ->
                    val fmt = try {
                        t.getFormat()
                    } catch (_: Throwable) {
                        null
                    }
                    say("    [$i] type=${t.trackType} format=$fmt")
                }
                val audioCount = tracks.count {
                    it.trackType == tv.danmaku.ijk.media.player.misc.ITrackInfo.MEDIA_TRACK_TYPE_AUDIO
                }
                say("  >>> 音频轨数量 = $audioCount")
            }
        } catch (t: Throwable) {
            say("  异常: ${t::class.java.simpleName} ${t.message}")
        } finally {
            runCatching { player.release() }
            runCatching { src.close() }
        }
    }
}
