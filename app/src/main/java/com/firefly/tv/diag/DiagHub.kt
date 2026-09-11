package com.firefly.tv.diag

import android.content.Context
import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.firefly.tv.config.ConfigServer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 远程诊断的「大脑」：现场快照 + 1 Hz 采样 + 动作派发。
 *
 * [DiagServer] 只负责 HTTP 那一层，所有数据都从这里取，所有操作都从这里派发。
 * 分开的理由：HTTP 层要能在没有界面（Activity 还没起来、或者已经被回收）时照样
 * 回答「现在什么状态」，而真正碰界面的动作必须回到主线程。
 *
 * ## 为什么做成「常开、无鉴权」
 *
 * 这是用户明确要求的排查通道：电视在老人家里，我没有 adb，也看不到画面。
 * 无鉴权是**权衡后的选择**（用户原话「为便于测试不做令牌/认证啥的，怎么简单怎么来」）：
 * 暴露面是「同一个局域网内的人能看到电视在看什么、能切旋钮」，
 * 而这台电视上本来就不存任何密钥（NAS 密码在配置页那套里，不从这里出去）。
 * 端口固定 [PORT]，方便我从这边直接扫。
 */
object DiagHub {

    const val PORT = 8642

    private const val TAG = "FireflyTV-Diag"

    /** 每秒一个样本，留 3 分钟。 */
    private const val SERIES_CAPACITY = 180

    private const val LOG_CAPACITY = 400

    /**
     * 接口自己的参数名（不是旋钮）。
     *
     * `GET /cmd?a=set&player.framedrop=0&replay=0` 里 `a` / `replay` 是给接口看的，
     * 其余每一项都当旋钮写进去 —— 这样「一次请求设一组旋钮」很方便。
     */
    /** `/props` 默认关心的属性前缀/关键字（不筛的话 getprop 有两三百行噪声）。 */
    private val PROP_HINTS = listOf(
        "sys.display", "ro.sf", "debug.sf", "ro.hardware", "ro.board", "ro.platform", "ro.product",
        "ro.media", "media.", "codec", "gpu", "mali", "gralloc", "dalvik.vm.heap", "ro.build.version",
        "ro.build.characteristics", "adb", "usb", "hdmi", "persist.sys.display", "ro.boot",
    )

    private val RESERVED = setOf(
        "a", "replay", "n", "on", "ms", "d", "path", "title", "dir", "q", "depth",
    )

    // ---- 界面层要实现的接口 ----

    /**
     * 界面层（MainActivity）交出来的能力。
     *
     * [state] 和 [act] **保证在主线程被调用** —— 里面可以直接碰 View。
     */
    interface Control {
        /** 取一份现场快照。 */
        fun state(): Live

        /** 执行一个动作，返回一句给人看的结果。 */
        fun act(a: Act): String
    }

    /** 面板上那一屏的行（label → value），原样带回去，方便我「看到用户看到的」。 */
    class Row(val label: String, val value: String)

    /** 界面层能提供的一切。字段全部是**真值**，不推算。 */
    class Live(
        val title: String = "",
        val kind: String = "",
        /** 当前所在的媒体库名 + **内存里的库列表**（顺序就是按下标取库的顺序）。 */
        val libraryName: String = "",
        val libraryIndex: Int = 0,
        val libraries: List<String> = emptyList(),
        val showName: String = "",
        val showCount: Int = 0,
        val episodeCount: Int = 0,
        /** 故障页此刻显示的原文（没显示就是空串）——「它自己跳走了」这类问题靠它取证。 */
        val fault: String = "",
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val screenW: Int = 0,
        val screenH: Int = 0,
        val dpi: Int = 0,
        val density: Float = 0f,
        val refreshHz: Float = 0f,
        val uiScale: Float = 1f,
        val surfaceW: Int = 0,
        val surfaceH: Int = 0,
        val surfaceFrame: String = "",
        val surfaceFormat: String = "",
        val surfaceZOrder: String = "",
        val surfaceFixed: String = "",
        val videoW: Int = 0,
        val videoH: Int = 0,
        val sourceFps: Float = 0f,
        val decodeFps: Float = 0f,
        val presentFps: Float = 0f,
        val dropRatio: Float = 0f,
        val cachedMs: Long = 0,
        val cachedBytes: Long = 0,
        val trafficBytes: Long = 0,
        val bitRateBps: Long = 0,
        val decoder: String = "",
        val decoderName: String? = null,
        val videoModule: String? = null,
        val videoImpl: String? = null,
        val audioCodec: String? = null,
        val audioStarted: Boolean = false,
        val requestedHardware: Boolean = false,
        val codecOffered: Boolean = false,
        val silentFallbacks: Int = 0,
        val banned: List<String> = emptyList(),
        val prepared: Boolean = false,
        val panelVisible: Boolean = false,
        val verdict: String = "",
        val verdictProblem: Boolean = false,
        val panelRows: List<Row> = emptyList(),
        val extra: List<Row> = emptyList(),
    )

    /** 一个动作。`a` 是动作名，`p` 是参数表（HTTP 的 query 原样传进来）。 */
    class Act(val name: String, val p: Map<String, String>) {
        fun str(k: String, def: String = ""): String = p[k]?.takeIf { it.isNotBlank() } ?: def
        fun int(k: String, def: Int = 0): Int = p[k]?.trim()?.toIntOrNull() ?: def
        fun long(k: String, def: Long = 0L): Long = p[k]?.trim()?.toLongOrNull() ?: def
        fun bool(k: String, def: Boolean = false): Boolean = when (p[k]?.lowercase()) {
            "1", "true", "on", "yes" -> true
            "0", "false", "off", "no" -> false
            else -> def
        }
    }

    // ---- 状态 ----

    @Volatile
    private var control: Control? = null

    @Volatile
    private var appContext: Context? = null

    private val main = Handler(Looper.getMainLooper())

    private var server: DiagServer? = null

    @Volatile
    var lastLive: Live = Live()
        private set

    /** 端口可能因为被占用而顺延，以实际绑定为准。 */
    val port: Int get() = server?.port ?: 0

    val url: String?
        get() {
            val ip = ConfigServer.localIpv4() ?: return null
            val p = port
            return if (p > 0) "http://$ip:$p" else null
        }

    private val series = ArrayDeque<Sample>()
    private val logs = ArrayDeque<String>()
    private val sys = SysSample()
    private var threadCpu = HashMap<Int, Double>()
    private var lastThreadTicks = HashMap<Int, Long>()
    private var lastTraffic = -1L
    private var startedAt = 0L
    private var lastTickAt = 0L
    private var sampler: Thread? = null

    @Volatile
    private var running = false

    /** 一次 1 Hz 采样。 */
    class Sample(
        val at: Long,
        val title: String,
        val decodeFps: Float,
        val presentFps: Float,
        val dropRatio: Float,
        val mpxPresent: Float,
        val mpxSource: Float,
        val cachedMs: Long,
        val readBps: Long,
        val positionMs: Long,
        val cpuBusyPct: Float,
        val procCpuPct: Float,
        val tempC: Float?,
        val coreMhz: String,
        val ddrMhz: String,
        val verdict: String,
        val threads: List<ThreadRow>,
    )

    class ThreadRow(val tid: Int, val name: String, val nice: Int, val cpuPct: Double, val boosted: Boolean)

    // ---- 生命周期 ----

    /** 幂等启动。界面层在 `onCreate` 里调；重复调用只是重新挂上 [Control]。 */
    fun start(ctx: Context, c: Control? = null) {
        appContext = ctx.applicationContext
        if (c != null) control = c
        if (running) return
        synchronized(this) {
            if (running) return
            startedAt = System.currentTimeMillis()
            val s = DiagServer(PORT) { path, query -> route(path, query) }
            if (!s.start()) {
                log("诊断服务起不来（端口 $PORT 被占？）")
                return
            }
            server = s
            running = true
            log("远程诊断已开：${url ?: "（没网？）"}（端口 ${s.port}）")
            sampler = Thread({ sampleLoop() }, "firefly-diag").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY + 1
                start()
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.stop() }
        server = null
        control = null
    }

    /**
     * 界面层销毁时解绑。
     *
     * **只解绑、不关服**：电视上的 Activity 重建（切显示模式、系统回收）之后
     * 服务还得在，否则「我刚连上就被断开」会让人以为是网络问题。
     * 传进来的对象不是当前那个就不动 —— 免得旧 Activity 的 `onDestroy` 把新 Activity 踢掉。
     */
    fun release(c: Control) {
        if (control === c) control = null
    }

    // ---- 日志 ----

    /** 记一行（进环形缓冲，`/log` 能取到）。 */
    fun log(msg: String) {
        val line = "${stamp()} $msg"
        Log.i(TAG, msg)
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > LOG_CAPACITY) logs.removeFirst()
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    // ---- 采样 ----

    private fun sampleLoop() {
        while (running) {
            try {
                tick()
            } catch (t: Throwable) {
                log("采样出错：${t.javaClass.simpleName}: ${t.message}")
            }
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun tick() {
        val ctx = appContext ?: return
        val live = onMain(2000) { control?.state() } ?: lastLive
        lastLive = live

        val snap = sys.sample()

        // 线程 CPU：和上一次采样的累计滴答相减，**除以真实间隔**。
        // 采样本身要花几十到几百毫秒（要读 /proc 下几十个线程 + /sys），
        // 固定按「1 秒」当分母会把占用率整体放大 —— 实机上曾把自家的 HTTP
        // 请求线程算成 35%，差点让我以为诊断通道自己在抢 CPU。
        val tickAt = System.currentTimeMillis()
        val dtSec = if (lastTickAt > 0) ((tickAt - lastTickAt).coerceAtLeast(200L)) / 1000.0 else 1.0
        lastTickAt = tickAt
        val nowTicks = HashMap<Int, Long>(snap.threads.size * 2)
        val cpu = HashMap<Int, Double>(snap.threads.size * 2)
        val boosted = Knobs.boostThreads(ctx)
        for (t in snap.threads) {
            nowTicks[t.tid] = t.ticks
            val prev = lastThreadTicks[t.tid]
            // prev == null = 这一秒里新起的线程：它从 0 开始累计，按一秒算会虚高，
            // 所以这一轮只记数、不报占用率（下一轮就有真实增量了）
            if (prev != null && t.ticks >= prev) {
                cpu[t.tid] = (t.ticks - prev) / ProcStats.CLK_TCK / dtSec * 100.0
            }
        }
        lastThreadTicks = nowTicks
        threadCpu = cpu

        // 提权：每秒补一次，新起的线程（每次重播都会重建）也能跟上
        if (boosted) applyBoostLocked(ctx, snap, includeCodec = Knobs.boostCodecThreads(ctx))

        val readBps = if (lastTraffic >= 0 && live.trafficBytes >= lastTraffic) {
            live.trafficBytes - lastTraffic
        } else {
            0L
        }
        lastTraffic = live.trafficBytes

        val px = live.videoW.toFloat() * live.videoH
        val rows = snap.threads
            .filter { cpu[it.tid] != null && (cpu[it.tid]!! > 0.4 || it.name.startsWith("ff_") || it.name.startsWith("amedia")) }
            .sortedByDescending { cpu[it.tid] }
            .take(12)
            .map {
                ThreadRow(
                    tid = it.tid,
                    name = it.name,
                    nice = it.nice,
                    cpuPct = cpu[it.tid] ?: 0.0,
                    boosted = ProcStats.boostFor(it.name, Knobs.boostCodecThreads(ctx)) != null,
                )
            }

        val sample = Sample(
            at = System.currentTimeMillis(),
            title = live.title,
            decodeFps = live.decodeFps,
            presentFps = live.presentFps,
            dropRatio = live.dropRatio,
            mpxPresent = if (px > 0) live.presentFps * px / 1e6f else 0f,
            mpxSource = if (px > 0) live.sourceFps * px / 1e6f else 0f,
            cachedMs = live.cachedMs,
            readBps = readBps,
            positionMs = live.positionMs,
            cpuBusyPct = snap.cpuBusyPct,
            procCpuPct = (snap.procCpuSec / dtSec * 100).toFloat(),
            tempC = snap.thermal.mapNotNull { it.tempC }.maxOrNull(),
            coreMhz = snap.cores.joinToString(",") { "${it.curMhz ?: -1}" },
            ddrMhz = snap.devfreq.filter { d -> d.name.contains("emi", true) || d.name.contains("ddr", true) }
                .joinToString(",") { "${it.curMhz ?: -1}" },
            verdict = live.verdict,
            threads = rows,
        )
        synchronized(series) {
            series.addLast(sample)
            while (series.size > SERIES_CAPACITY) series.removeFirst()
        }
    }

    private fun applyBoostLocked(ctx: Context, snap: SysSample.Snapshot, includeCodec: Boolean) {
        for (t in snap.threads) {
            val want = ProcStats.boostFor(t.name, includeCodec) ?: continue
            if (t.nice <= want) continue // 已经够了
            setThreadNice(t.tid, want)
        }
    }

    /**
     * 给某个线程设优先级。
     *
     * 走反射：`android.os.Process.setThreadPriority(int tid, int priority)` 这个双参重载
     * 在公开文档里一直存在，但不同 SDK 存根里时有时无 —— 反射能编过，
     * 而且真失败了也只是「没提到权」，不会把应用搞崩。
     */
    private fun setThreadNice(tid: Int, nice: Int): Boolean = try {
        val m = android.os.Process::class.java.getMethod(
            "setThreadPriority",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        m.invoke(null, tid, nice)
        true
    } catch (t: Throwable) {
        log("线程提权失败（tid=$tid nice=$nice）：${t.javaClass.simpleName}")
        false
    }

    /** 立刻给一次提权（重播之后马上调，不用等下一秒）。 */
    fun boostNow() {
        val ctx = appContext ?: return
        if (!Knobs.boostThreads(ctx)) return
        runCatching { applyBoostLocked(ctx, sys.sample(), Knobs.boostCodecThreads(ctx)) }
    }

    // ---- 主线程桥 ----

    private fun <T> onMain(timeoutMs: Long, block: () -> T?): T? {
        if (Looper.myLooper() === Looper.getMainLooper()) return block()
        var result: T? = null
        val latch = CountDownLatch(1)
        val posted = main.post {
            try {
                result = block()
            } catch (t: Throwable) {
                log("主线程执行出错：${t.javaClass.simpleName}: ${t.message}")
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return null
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return result
    }

    // ---- 路由 ----

    private fun route(path: String, query: Map<String, String>): DiagServer.Answer = when (path) {
        "/", "/index.html" -> DiagServer.Answer.html(page())
        "/state" -> DiagServer.Answer.json(stateJson())
        "/series" -> DiagServer.Answer.json(seriesJson(query["n"]?.toIntOrNull() ?: 60))
        "/threads" -> DiagServer.Answer.json(threadsJson())
        "/knobs" -> DiagServer.Answer.json(knobsJson())
        "/presets" -> DiagServer.Answer.json(presetsJson())
        "/props" -> DiagServer.Answer.json(propsJson(query["q"]))
        "/codecs" -> DiagServer.Answer.json(codecsJson())
        "/log" -> DiagServer.Answer.text(logText(query["n"]?.toIntOrNull() ?: 120))
        "/set" -> DiagServer.Answer.json(action(Act("set", query)))
        "/preset" -> DiagServer.Answer.json(action(Act("preset", query)))
        "/cmd" -> DiagServer.Answer.json(action(Act(query["a"] ?: "state", query)))
        "/ping" -> DiagServer.Answer.text("ok ${stamp()}\n")
        else -> DiagServer.Answer.notFound()
    }

    private fun action(a: Act): String {
        val ctx = appContext ?: return """{"ok":false,"msg":"应用还没起来"}"""
        val params = HashMap(a.p)
        // 先落盘旋钮（界面层随后重建/重播时读到的就是新值）。
        //
        // `set` 会把 query 里**除保留字以外**的每一项都当旋钮 —— 保留字是接口自己的参数
        // （`replay=0` 这类），不排掉的话它们会被当成「没有这个旋钮」，
        // 于是「只想设个旋钮」的请求整条失败（远程连调时这种错最容易浪费时间）。
        if (a.name == "set") {
            val errs = ArrayList<String>(4)
            var needsReplay = false
            for ((k, v) in a.p) {
                if (k in RESERVED) continue
                val spec = Knobs.specOf(k)
                if (spec == null) {
                    errs += "没有这个旋钮：$k"
                    continue
                }
                val e = Knobs.put(ctx, k, v)
                if (e != null) {
                    errs += e
                    continue
                }
                // 建面前的一锤子买卖、以及起播前才读的播放器选项，都必须重播
                if (spec.needsSurface || spec.group == "player") needsReplay = true
            }
            if (errs.isNotEmpty()) return """{"ok":false,"msg":${json(errs.joinToString("；"))}}"""
            if (!params.containsKey("replay")) params["replay"] = if (needsReplay) "1" else "0"
            log("远程改旋钮：${a.p.filterKeys { it !in RESERVED }}（重播=$needsReplay）")
        }
        if (a.name == "reset") {
            Knobs.reset(ctx)
            log("远程复位全部旋钮")
            if (!params.containsKey("replay")) params["replay"] = "1"
        }
        // NAS 目录浏览**必须在 HTTP 线程上做**：它要走 SMB（网络 I/O），
        // 而 [act] 是在主线程执行的 —— 主线程碰 socket 会直接抛 NetworkOnMainThreadException。
        // 这条通道是给我找对照片源用的（例如「有没有 1080p 的《大宅门》」）。
        if (a.name == "resetio") {
            com.firefly.tv.player.ReadStats.reset()
            log("远程复位读路径计数")
            return """{"ok":true,"msg":"读路径计数已清零"}"""
        }
        if (a.name == "ls") return lsJson(ctx, a.str("dir"))
        if (a.name == "find") return findJson(ctx, a.str("q"), a.int("depth", 3))
        val act = Act(a.name, params)
        val msg = onMain(6000) { control?.act(act) } ?: "界面层没有响应（可能正在忙）"
        log("远程动作 ${a.name} ${params.filterKeys { it !in RESERVED && it != "a" }} -> $msg")
        return """{"ok":true,"msg":${json(msg)}}"""
    }

    /** 列一层目录（`GET /cmd?a=ls&dir=电视剧/大宅门`）。 */
    private fun lsJson(ctx: Context, dir: String?): String {
        val cfg = com.firefly.tv.core.Config.smb(ctx)
        return try {
            val entries = com.firefly.tv.smb.SmbStore.with(cfg) { it.list(dir.orEmpty()) }
            val body = entries.take(500).joinToString(",", "[", "]") {
                """{"name":${json(it.name)},"dir":${it.isDir},"size":${it.size}}"""
            }
            log("远程 ls ${dir.orEmpty()} -> ${entries.size} 项")
            """{"ok":true,"dir":${json(dir.orEmpty())},"count":${entries.size},"entries":$body}"""
        } catch (t: Throwable) {
            """{"ok":false,"msg":${json(com.firefly.tv.smb.SmbClient.describe(t))}}"""
        }
    }

    /** 在共享里按名字找文件（`GET /cmd?a=find&q=大宅门&depth=3`），广度优先、有上限。 */
    private fun findJson(ctx: Context, q: String?, depth: Int): String {
        if (q.isNullOrBlank()) return """{"ok":false,"msg":"要给 q（名字里的一段）"}"""
        val cfg = com.firefly.tv.core.Config.smb(ctx)
        val hits = ArrayList<String>(32)
        val started = System.currentTimeMillis()
        try {
            com.firefly.tv.smb.SmbStore.with(cfg) {
                var frontier = listOf("")
                for (d in 0 until depth.coerceIn(1, 5)) {
                    val next = ArrayList<String>(16)
                    for (dir in frontier) {
                        if (hits.size >= 200 || System.currentTimeMillis() - started > 25_000) break
                        val entries = runCatching { it.list(dir) }.getOrDefault(emptyList())
                        for (e in entries) {
                            val path = if (dir.isEmpty()) e.name else "$dir/${e.name}"
                            if (e.name.contains(q, ignoreCase = true)) {
                                hits += (if (e.isDir) "[目录] " else "[${e.size / 1024 / 1024}MB] ") + path
                            }
                            if (e.isDir) next += path
                        }
                    }
                    frontier = next
                }
            }
            log("远程 find $q -> ${hits.size} 项")
            return """{"ok":true,"q":${json(q)},"count":${hits.size},"hits":[${hits.joinToString(",") { json(it) }}]}"""
        } catch (t: Throwable) {
            return """{"ok":false,"msg":${json(com.firefly.tv.smb.SmbClient.describe(t))}}"""
        }
    }

    // ---- JSON ----

    fun json(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun f(v: Float): String = if (v.isFinite()) String.format(Locale.US, "%.2f", v) else "0"

    private fun stateJson(): String {
        val l = lastLive
        val ctx = appContext
        val snap = sys
        val px = l.videoW.toFloat() * l.videoH
        val sb = StringBuilder(2048)
        sb.append('{')
        sb.append(""""app":{"uptime_s":${(System.currentTimeMillis() - startedAt) / 1000},"port":$port,"url":${json(url ?: "")}}""")
        sb.append(""","screen":{"w":${l.screenW},"h":${l.screenH},"dpi":${l.dpi},"density":${f(l.density)},"refresh_hz":${f(l.refreshHz)},"ui_scale":${f(l.uiScale)}}""")
        sb.append(""","surface":{"w":${l.surfaceW},"h":${l.surfaceH},"frame":${json(l.surfaceFrame)},"format":${json(l.surfaceFormat)},"zorder":${json(l.surfaceZOrder)},"fixed":${json(l.surfaceFixed)}}""")
        sb.append(""","content":{"title":${json(l.title)},"kind":${json(l.kind)},"pos_ms":${l.positionMs},"dur_ms":${l.durationMs},"prepared":${l.prepared}}""")
        sb.append(""","library":{"name":${json(l.libraryName)},"index":${l.libraryIndex},"all":[${l.libraries.joinToString(",") { json(it) }}],"show":${json(l.showName)},"shows":${l.showCount},"episodes":${l.episodeCount}}""")
        sb.append(""","fault":{"text":${json(l.fault)},"visible":${l.fault.isNotBlank()}}""")
        sb.append(""","video":{"w":${l.videoW},"h":${l.videoH},"source_fps":${f(l.sourceFps)},"decode_fps":${f(l.decodeFps)},"present_fps":${f(l.presentFps)},"drop_ratio":${f(l.dropRatio)}""")
        if (px > 0) {
            sb.append(""","pixel_present_mpx":${f(l.presentFps * px / 1e6f)},"pixel_source_mpx":${f(l.sourceFps * px / 1e6f)}""")
        }
        sb.append(""","cached_ms":${l.cachedMs},"cached_bytes":${l.cachedBytes},"traffic_bytes":${l.trafficBytes},"bitrate_bps":${l.bitRateBps}}""")
        sb.append(""","decoder":{"in_use":${json(l.decoder)},"name":${json(l.decoderName ?: "")},"module":${json(l.videoModule ?: "")},"impl":${json(l.videoImpl ?: "")},"requested_hw":${l.requestedHardware},"codec_offered":${l.codecOffered},"silent_fallbacks":${l.silentFallbacks},"banned":${l.banned.joinToString(",", "[", "]") { json(it) }}}""")
        sb.append(""","audio":{"codec":${json(l.audioCodec ?: "")},"started":${l.audioStarted}}""")
        val io = com.firefly.tv.player.ReadStats
        sb.append(""","io":{"relocated_opens":${io.relocatedOpens},"read_calls":${io.readCalls},"read_bytes":${io.readBytes},"avg_read_bytes":${io.avgBytes()},"segment_crossings":${io.segmentCrossings}}""")
        sb.append(""","verdict":{"text":${json(l.verdict)},"problem":${l.verdictProblem},"panel_visible":${l.panelVisible}}""")
        sb.append(""","knobs":${knobsJsonMap(ctx)}""")
        sb.append(""","preset":{"index":${ctx?.let { Knobs.presetIndex(it) } ?: -1},"count":${Knobs.PRESETS.size}}""")
        val last = synchronized(series) { series.peekLast() }
        if (last != null) {
            sb.append(""","live":{"at":${last.at},"cpu_busy_pct":${f(last.cpuBusyPct)},"proc_cpu_pct":${f(last.procCpuPct)},"temp_c":${last.tempC?.let { f(it) } ?: "null"},"core_mhz":[${last.coreMhz.split(',').filter { it != "-1" }.joinToString(",")}],"ddr_mhz":[${last.ddrMhz.split(',').filter { it != "-1" }.joinToString(",")}],"read_bps":${last.readBps}}""")
        }
        sb.append(""","panel":[${l.panelRows.joinToString(",") { """{"label":${json(it.label)},"value":${json(it.value)}}""" }}]""")
        sb.append(""","extra":[${l.extra.joinToString(",") { """{"label":${json(it.label)},"value":${json(it.value)}}""" }}]""")
        sb.append('}')
        return sb.toString()
    }

    private fun knobsJsonMap(ctx: Context?): String {
        if (ctx == null) return "{}"
        return Knobs.all(ctx).entries.joinToString(",", "{", "}") { (k, v) -> "${json(k)}:${json(v)}" }
    }

    private fun knobsJson(): String {
        val ctx = appContext ?: return "{}"
        return Knobs.SPECS.joinToString(",", "[", "]") { s ->
            val cur = Knobs.get(ctx, s.key)
            """{"key":${json(s.key)},"group":${json(s.group)},"value":${json(cur)},"default":${json(s.def)},"choices":${s.choices.joinToString(",", "[", "]") { json(it) }},"min":${s.min},"max":${s.max},"needs_surface":${s.needsSurface},"desc":${json(s.desc)}}"""
        }
    }

    private fun presetsJson(): String {
        val ctx = appContext
        val cur = ctx?.let { Knobs.presetIndex(it) } ?: -1
        return """{"current":$cur,"presets":[${Knobs.PRESETS.joinToString(",") { """{"label":${json(it.label)},"values":${it.values.entries.joinToString(",", "{", "}") { (k, v) -> "${json(k)}:${json(v)}" }}}""" }}]}"""
    }

    /**
     * 系统属性（`getprop`）—— **adb 的廉价替代**。
     *
     * 三条最要紧的：
     *  - `debug.sf.hw=0`：SurfaceFlinger 全程走 GLES，视频层必然 GPU 合成（厂商/调试开关）；
     *  - `sys.display-size`：电视**真正的**输出分辨率（可能是 3840×2160 而 Android 合成在 1080p）；
     *  - `service.adb.tcp.port` / `persist.adb.tcp.port`：网络 adb 开没开 ——
     *    开了的话我这边 `adb connect <电视IP>:5555` 就能直接拿 `dumpsys SurfaceFlinger`，
     *    那一条能一锤定音地看出视频层是 HWC 还是 GLES 合成。
     */
    private fun propsJson(q: String?): String {
        val want = q?.trim()?.takeIf { it.isNotEmpty() }
        val hits = LinkedHashMap<String, String>()
        try {
            val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val k = line.substringAfter('[', "").substringBefore(']', "")
                    if (k.isEmpty()) continue
                    val v = line.substringAfter("]: [", "").let { if (it.endsWith("]")) it.dropLast(1) else it }
                    if (want != null) {
                        if (k.contains(want, ignoreCase = true)) hits[k] = v
                    } else if (PROP_HINTS.any { k.startsWith(it) || k.contains(it, ignoreCase = true) }) {
                        hits[k] = v
                    }
                    if (hits.size >= 200) break
                }
            }
            p.waitFor()
        } catch (t: Throwable) {
            return """{"ok":false,"msg":${json("读 getprop 失败：" + t.javaClass.simpleName)}}"""
        }
        log("远程读系统属性 ${want ?: "（默认关注项）"} -> ${hits.size} 项")
        return hits.entries.joinToString(",", "{", "}") { (k, v) -> "${json(k)}:${json(v)}" }
    }

    /**
     * 本机的视频解码器清单（含 ijkplayer 的排名、能力、**声明的最大尺寸**）。
     *
     * 为什么要它：电视上「4K 只能送显 18 帧」时，一个必须排除的可能就是
     * **解码器选错了**（这台机器可能同时有 4K 和 1080p 两个 HEVC 解码器，
     * 也可能某个实例只声明支持 1080p —— MTK 电视平台就有 `media_codecs.xml`
     * 软链到 2K/4K 两套能力声明的做法）。有了这份清单 + `player.mediacodec-default-name`
     * 旋钮，我就能在电视上当场换解码器再量一次，而不用猜。
     */
    private fun codecsJson(): String {
        val mimes = listOf("video/hevc", "video/avc", "video/mp4v-es", "video/mpeg2")
        val sb = StringBuilder(512)
        sb.append('[')
        var first = true
        for (mime in mimes) {
            val list = runCatching { com.firefly.tv.player.MediaCodecChoice.enumerate(mime) }
                .getOrDefault(emptyList())
            for (c in list) {
                if (!first) sb.append(',')
                first = false
                val caps = runCatching {
                    MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                        .firstOrNull { it.name == c.name }
                        ?.getCapabilitiesForType(mime)
                }.getOrNull()
                val vc = caps?.videoCapabilities
                sb.append(
                    """{"mime":${json(mime)},"name":${json(c.name)},"rank":${c.rank},"surface":${c.surface},"hevc_profiles":${c.hevcProfiles.joinToString(",", "[", "]")}""" +
                        ""","max_w":${vc?.supportedWidths?.upper ?: 0},"max_h":${vc?.supportedHeights?.upper ?: 0}""" +
                        ""","supports_4k":${runCatching { vc?.isSizeSupported(3840, 2160) }.getOrDefault(false)}""" +
                        ""","fps":${runCatching { vc?.supportedFrameRates?.upper?.toInt() ?: 0 }.getOrDefault(0)}""" +
                        // 隧道播放（HWC_SIDEBAND）是唯一可能完全绕开合成通路的 app 级路径，
                        // 但只有组件自己声明支持才谈得上（见 DESIGN 风险 24）
                        ""","tunneled":${runCatching { caps?.isFeatureSupported("tunneled-playback") }.getOrDefault(false)}}""",
                )
            }
        }
        sb.append(']')
        return sb.toString()
    }

    private fun seriesJson(n: Int): String {
        val list = synchronized(series) { series.toList().takeLast(n.coerceIn(1, SERIES_CAPACITY)) }
        return list.joinToString(",", "[", "]") { s ->
            """{"at":${s.at},"title":${json(s.title)},"dec":${f(s.decodeFps)},"pres":${f(s.presentFps)},"drop":${f(s.dropRatio)},"mpx":${f(s.mpxPresent)},"src_mpx":${f(s.mpxSource)},"cached_ms":${s.cachedMs},"read_bps":${s.readBps},"pos_ms":${s.positionMs},"cpu":${f(s.cpuBusyPct)},"proc_cpu":${f(s.procCpuPct)},"temp_c":${s.tempC?.let { f(it) } ?: "null"},"core_mhz":${json(s.coreMhz)},"ddr_mhz":${json(s.ddrMhz)},"verdict":${json(s.verdict)}}"""
        }
    }

    private fun threadsJson(): String {
        val last = synchronized(series) { series.peekLast() } ?: return "[]"
        return last.threads.joinToString(",", "[", "]") {
            """{"tid":${it.tid},"name":${json(it.name)},"nice":${it.nice},"cpu_pct":${f(it.cpuPct.toFloat())},"boost_target":${it.boosted}}"""
        }
    }

    private fun logText(n: Int): String {
        val list = synchronized(logs) { logs.toList().takeLast(n.coerceIn(1, LOG_CAPACITY)) }
        return list.joinToString("")
    }

    // ---- 给界面用的一页 HTML（老人/用户也能直接看） ----

    private fun page(): String {
        val l = lastLive
        val rows = l.panelRows.joinToString("") { "<tr><th>${esc(it.label)}</th><td>${esc(it.value)}</td></tr>" }
        val extra = l.extra.joinToString("") { "<tr><th>${esc(it.label)}</th><td>${esc(it.value)}</td></tr>" }
        val knobRows = appContext?.let { ctx ->
            Knobs.nonDefault(ctx).joinToString("") { (k, v) -> "<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>" }
        }.orEmpty()
        return """
<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>萤火照夜 · 远程诊断</title>
<style>
 body{background:#141210;color:#f5efe6;font:15px/1.5 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;margin:0;padding:16px}
 h1{font-size:19px;margin:0 0 4px} p.sub{color:#a99e90;font-size:13px;margin:0 0 14px}
 table{width:100%;border-collapse:collapse;background:#211d1a;border-radius:10px;overflow:hidden;margin-bottom:14px}
 th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #2f2925;font-size:14px;vertical-align:top}
 th{color:#ffcc66;white-space:nowrap;width:34%;font-weight:600}
 tr:last-child th,tr:last-child td{border-bottom:0}
 a{color:#ffcc66} code{background:#0e0c0b;padding:1px 4px;border-radius:4px}
</style></head><body>
<h1>萤火照夜 · 远程诊断</h1>
<p class="sub">每 1 秒自动刷新。数据源和电视上的诊断页完全一致。</p>
<table>$rows</table>
<table>$extra</table>
${if (knobRows.isEmpty()) "" else "<h1>非默认旋钮</h1><table>$knobRows</table>"}
<p class="sub"><a href="/state">/state</a> · <a href="/series">/series</a> · <a href="/threads">/threads</a> ·
<a href="/knobs">/knobs</a> · <a href="/presets">/presets</a> · <a href="/log">/log</a></p>
<script>setTimeout(()=>location.reload(),1000)</script>
</body></html>
""".trimIndent()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
