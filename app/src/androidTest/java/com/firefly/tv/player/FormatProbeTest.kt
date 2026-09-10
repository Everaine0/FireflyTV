package com.firefly.tv.player

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.firefly.tv.core.Config
import com.firefly.tv.media.Library
import com.firefly.tv.media.Scanner
import com.firefly.tv.smb.SmbStore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 定位「娘道 76 集播不了」和「CCTV5 有画面没声音」这两个具体故障。
 *
 * 不做断言，只输出事实 —— 结论要靠数据，不靠猜。
 */
@RunWith(AndroidJUnit4::class)
class FormatProbeTest {

    private val args = InstrumentationRegistry.getArguments()
    private fun arg(k: String) = args.getString(k, "").orEmpty()
    private val cfg get() = Config.Smb(
        arg("ff.smb.host"), arg("ff.smb.share"), arg("ff.smb.root"),
        arg("ff.smb.user"), arg("ff.smb.pass"), arg("ff.smb.domain"),
    )

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun log(m: String) = android.util.Log.i("FireflyProbe", m)

    private class Result(val prepared: Boolean, val frame: Boolean, val audio: Boolean, val duration: Long, val pos: Long, val err: String?)

    private fun playOnMain(label: String, start: (IjkPlaybackEngine) -> Unit): Result {
        val prepared = CountDownLatch(1)
        val frame = CountDownLatch(1)
        val audio = CountDownLatch(1)
        val err = AtomicReference<String?>(null)
        val main = Handler(Looper.getMainLooper())
        val engine = IjkPlaybackEngine(ctx)

        main.post {
            engine.setListener(object : PlaybackEngine.Listener {
                override fun onPrepared(durationMs: Long) {
                    log("  [$label] onPrepared duration=${durationMs}ms")
                    prepared.countDown()
                }

                override fun onCompletion() = Unit
                override fun onError(m: String, fatal: Boolean) {
                    log("  [$label] onError [$m] fatal=$fatal")
                    err.set(m)
                    prepared.countDown()
                }

                override fun onFirstFrame() = frame.countDown()
                override fun onAudioStarted() {
                    log("  [$label] onAudioStarted ✅")
                    audio.countDown()
                }
            })
            runCatching { start(engine) }.onFailure {
                log("  [$label] 起播抛异常 ${it}")
                err.set(it.message)
                prepared.countDown()
            }
        }

        val p = prepared.await(60, TimeUnit.SECONDS)
        val f = if (p) frame.await(20, TimeUnit.SECONDS) else false
        val a = if (p) audio.await(10, TimeUnit.SECONDS) else false
        val dur = readLong(main) { engine.durationMs() }
        val pos = readLong(main) { engine.positionMs() }
        main.post { runCatching { engine.release() } }
        Thread.sleep(500)
        return Result(p, f, a, dur, pos, err.get())
    }

    private fun readLong(main: Handler, block: () -> Long): Long {
        val r = AtomicReference(0L)
        main.post { r.set(runCatching(block).getOrDefault(0L)) }
        Thread.sleep(400)
        return r.get()
    }

    private fun show(r: Result) =
        "起播=${r.prepared} 首帧=${r.frame} 出声=${r.audio} 时长=${r.duration}ms 位置=${r.pos}ms 错误=${r.err}"

    /**
     * 把一个真实文件复制到应用私有目录，再用**不同的文件名**播。
     *
     * 这是在分辨「是文件内容的问题」还是「文件扩展名把解复用器带偏了」：
     * 同样的字节，`.mp4` 和 `.ts` 两个名字各播一次，结果不一样就说明是名字的问题。
     */
    @Test
    fun 同一份字节用不同扩展名播() {
        val libs = try {
            SmbStore.with(cfg) { Scanner.libraries(cfg) }
        } catch (t: Throwable) {
            log("SMB 不可达，跳过"); return
        }
        val videoLib = libs.filterIsInstance<Library.Video>().firstOrNull() ?: return
        val show = SmbStore.with(cfg) { Scanner.shows(cfg, videoLib) }
            .firstOrNull { it.contains("娘道") } ?: SmbStore.with(cfg) { Scanner.shows(cfg, videoLib) }.first()
        val ep = SmbStore.with(cfg) { Scanner.episodes(cfg, videoLib, show) }.first()
        val path = Scanner.episodePath(videoLib, show, ep)
        log("==== 源文件: $path")

        // 抓 24MB 存两个名字：一个保持 .mp4，一个改成 .ts
        val bytes = SmbStore.with(cfg) { client ->
            client.open(path).use { h ->
                val want = minOf(24L * 1024 * 1024, h.size).toInt()
                val buf = ByteArray(want)
                var got = 0
                while (got < want) {
                    val n = h.read(got.toLong(), buf, got, want - got)
                    if (n <= 0) break
                    got += n
                }
                buf.copyOf(got)
            }
        }
        log("抓到 ${bytes.size} 字节，头 16 字节 = ${bytes.take(16).joinToString("") { "%02x".format(it) }}")

        for (name in listOf("probe_lying.mp4", "probe_honest.ts")) {
            val f = File(ctx.cacheDir, name)
            f.writeBytes(bytes)
            log("---- 用文件名 [$name] 播 ----")
            val r = playOnMain(name) { engine ->
                engine.setMode(PlaybackMode.Kind.ON_DEMAND)
                engine.playSource(FileRandomAccessSource(f.absolutePath), 0L)
            }
            log("  $name -> ${show(r)}")
            // 播放器 release 时会关掉它持有的 SMB 会话，单例里可能留着已经死掉的共享，
            // 所以下一轮之前显式丢掉，重新建连接。
            SmbStore.drop()
        }
    }

    /** 合成的 AC-3 与 AAC 各播一次：分辨「缺少解码器」还是「数据源有问题」。 */
    @Test
    fun 合成音频编解码能力探测() {
        for (name in listOf("ac3-in-ts.ts", "aac-control.mp4")) {
            val f = File(ctx.cacheDir, name)
            val asset = try {
                InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
            } catch (t: Throwable) {
                log("资产 $name 读不到：${t.message}")
                continue
            }
            f.writeBytes(asset)
            log("---- $name (${asset.size} 字节) 头=${asset.take(12).joinToString("") { "%02x".format(it) }} ----")
            val r = playOnMain(name) { engine ->
                engine.setMode(PlaybackMode.Kind.ON_DEMAND)
                engine.playSource(FileRandomAccessSource(f.absolutePath), 0L)
            }
            log("  $name -> ${show(r)}")
        }
    }

    /**
     * 摸 MPEG-TS 的时长估算：`analyzeduration` / `probesize` 到底要多大才准。
     *
     * 起因：AC-3 的 TS 报出来的时长是 1700ms（实际 2560 秒），AAC 的 mp4 却是准的。
     * 时长不准会连带影响进度条与「播完了没」的判断，得先弄清是不是参数太小。
     */
    @Test
    fun TS时长估算与探测参数的关系() {
        val asset = try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("ac3-in-ts.ts")
                .use { it.readBytes() }
        } catch (t: Throwable) {
            log("资产读不到：${t.message}"); return
        }
        val f = File(ctx.cacheDir, "ts_duration.ts")
        f.writeBytes(asset)
        // 这个片源是 6 秒
        log("==== 合成 AC-3 TS 实际时长应为 6000ms，文件 ${asset.size} 字节 ====")

        for ((probe, analyze) in listOf(
            512L * 1024 to 1_000_000L,
            2L * 1024 * 1024 to 3_000_000L,
            8L * 1024 * 1024 to 10_000_000L,
            32L * 1024 * 1024 to 30_000_000L,
        )) {
            val dur = probeDuration(f, probe, analyze)
            log("  probesize=${probe / 1024}KB analyzeduration=${analyze / 1000}ms -> 时长 ${dur}ms")
        }
    }

    /** 直接建一个播放器、只改探测参数，读它报出来的时长。 */
    private fun probeDuration(f: File, probesize: Long, analyzeUs: Long): Long {
        val main = Handler(Looper.getMainLooper())
        val ready = CountDownLatch(1)
        val dur = AtomicReference(-1L)
        val p = tv.danmaku.ijk.media.player.IjkMediaPlayer()

        main.post {
            p.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", probesize)
            p.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", analyzeUs)
            p.setOnPreparedListener {
                dur.set(it.duration)
                ready.countDown()
            }
            p.setOnErrorListener { _, _, _ ->
                ready.countDown()
                true
            }
            runCatching { p.setDataSource(f.absolutePath) }
            runCatching { p.prepareAsync() }
        }
        ready.await(30, TimeUnit.SECONDS)
        main.post { runCatching { p.release() } }
        Thread.sleep(300)
        return dur.get()
    }
}
