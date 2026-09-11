package com.firefly.tv.diag

import java.io.File

/**
 * `/proc` / `/sys` 里那点能用来看「芯片到底累不累」的数字。
 *
 * 为什么要读这些：实机上「4K 送显只有 18 帧」有三种完全不同的解释 ——
 * ① 芯片/总线跑满了；② 有线程被饿死（优先级/调度）；③ 显示通路本身过不去。
 * 应用能拿到的播放器计数器只能说明「慢」，说明不了**为什么**慢，
 * 而 `/proc` 里的线程 CPU 时间、核频、温度、GPU/DDR 频率正好补上这一半。
 *
 * 解析全部做成**纯函数**（[parseThreadStat] 等），文件读取另外放在
 * [SysSample] 里 —— 纯函数能写单元测试，读文件只能上机验。
 */
object ProcStats {

    /** Android 上 `/proc` 的时钟滴答恒为 100 Hz（各架构都是）。 */
    const val CLK_TCK = 100.0

    /** 一个线程的累计时间与调度属性。 */
    class ThreadStat(
        val tid: Int,
        val name: String,
        /** `R`/`S`/`D`… */
        val state: String,
        /** 用户态 + 内核态滴答数。 */
        val ticks: Long,
        /** 内核里的 priority 字段（实时线程是负数，普通线程是 20~39 那种）。 */
        val priority: Int,
        /** nice 值：`Process.setThreadPriority` 改的就是它。 */
        val nice: Int,
    )

    /**
     * 解析 `/proc/<pid>/task/<tid>/stat`。
     *
     * ⚠️ comm 字段是**带括号**的，而且线程名里可能有空格和右括号
     * （ijkplayer 的线程名没有，但系统的有），所以必须从**最后一个** `)` 切开 ——
     * 从头找第一个 `)` 的写法遇到 `(a b)` 这种名字就会整体错位一格，
     * 于是「nice」读出来的是「priority」，排查时看着数字齐全、其实全错。
     */
    fun parseThreadStat(raw: String, fallbackTid: Int = 0): ThreadStat? {
        val close = raw.lastIndexOf(')')
        if (close <= 0) return null
        val open = raw.indexOf('(')
        if (open < 0 || open > close) return null
        val head = raw.substring(0, open).trim()
        val name = raw.substring(open + 1, close)
        val rest = raw.substring(close + 1).trim().split(' ')
        // rest[0]=state(3) …… rest[11]=utime(14) rest[12]=stime(15) rest[15]=priority(18) rest[16]=nice(19)
        if (rest.size < 17) return null
        val tid = head.toIntOrNull() ?: fallbackTid
        val utime = rest[11].toLongOrNull() ?: return null
        val stime = rest[12].toLongOrNull() ?: return null
        return ThreadStat(
            tid = tid,
            name = name,
            state = rest[0],
            ticks = utime + stime,
            priority = rest[15].toIntOrNull() ?: 0,
            nice = rest[16].toIntOrNull() ?: 0,
        )
    }

    /** `/proc/stat` 的第一行：所有核加起来的 jiffies。 */
    class CpuTotal(val total: Long, val idle: Long)

    fun parseCpuTotal(raw: String): CpuTotal? {
        val line = raw.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val n = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (n.size < 5) return null
        // user nice system idle iowait irq softirq steal …
        val idle = n[3] + (n[4]) // idle + iowait 都算「没事干」
        return CpuTotal(total = n.sum(), idle = idle)
    }

    /** 在线核数量（`/sys/devices/system/cpu/online`，形如 `0-3`）。 */
    fun parseOnlineCores(raw: String?): Int {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return 0
        var count = 0
        for (part in s.split(',')) {
            val dash = part.indexOf('-')
            count += if (dash > 0) {
                val a = part.substring(0, dash).trim().toIntOrNull()
                val b = part.substring(dash + 1).trim().toIntOrNull()
                if (a != null && b != null && b >= a) b - a + 1 else 1
            } else {
                1
            }
        }
        return count
    }

    /** `/proc/meminfo` 里的一项（单位 kB）。 */
    fun parseMeminfoKb(raw: String, key: String): Long? =
        raw.lineSequence()
            .firstOrNull { it.startsWith("$key:") }
            ?.let { it.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() }

    /**
     * 温度。内核里普遍是毫摄氏度（`45000`），但也有直接给摄氏度的（`45`）。
     * 判据用「绝对值大于 1000 就按毫度」，比按 zone 名猜靠谱。
     */
    fun parseTempC(raw: String?): Float? {
        val v = raw?.trim()?.toFloatOrNull() ?: return null
        return if (v > 1000f) v / 1000f else v
    }

    /** 频率（kHz → MHz）。MTK 的 `devfreq` 直接给 Hz。 */
    fun khzToMhz(khz: Long?): Int? = khz?.let { (it / 1000).toInt() }

    fun hzToMhz(hz: Long?): Int? = hz?.let { (it / 1_000_000).toInt() }

    /** 线程名 → 建议的优先级（Android 的 `THREAD_PRIORITY_*`，直接当 nice 用）。 */
    val BOOST_TABLE: List<Pair<String, Int>> = listOf(
        "ff_vout" to -8,                        // 送显（video_refresh_thread）：一卡就掉帧
        "ff_read" to -4,                        // 解复用 + SMB 读
        "ff_video_dec" to -4,
        "amediacodec_input_thread" to -4,        // 往解码器喂数据
        "ff_audio_dec" to -16,
        "ff_aout_android" to -16,
        "ff_aout_opensles" to -16,
        "ff_msg_loop" to -2,
    )

    /** MediaCodec 自己的线程（只有 `thread.boost.extra=on` 时才动它们）。 */
    val CODEC_THREAD_HINTS: List<String> = listOf(
        "ACodec", "CodecLooper", "OMXCallbackDisp", "NdkMediaCodec", "MediaCodec", "C2", "vendor.",
    )

    /** 给定线程名，返回该提到多少优先级；null = 不动它。 */
    fun boostFor(name: String, includeCodec: Boolean): Int? {
        BOOST_TABLE.firstOrNull { name == it.first || name.startsWith(it.first) }?.let { return it.second }
        if (includeCodec && CODEC_THREAD_HINTS.any { name.startsWith(it) }) return -8
        return null
    }
}

/**
 * 一次系统采样（1 Hz 就够了）。
 *
 * 所有读取都容错：这台电视是 Android 5.1 + MTK，`/sys` 下的路径和标准 AOSP
 * 不完全一样，读不到就是读不到 —— **缺项要显示成「没有」，绝不能因为一项读不到
 * 就让整块采样失败**，否则排查时看到的是「一片空白」而不是「这项没有」。
 */
class SysSample(private val procRoot: String = "/proc", private val sysRoot: String = "/sys") {

    class Core(val id: Int, val curMhz: Int?, val maxMhz: Int?, val governor: String?)
    class Thermal(val zone: String, val tempC: Float?)
    class DevFreq(val name: String, val curMhz: Int?, val maxMhz: Int?)

    class Snapshot(
        val onlineCores: Int,
        val cores: List<Core>,
        val devfreq: List<DevFreq>,
        val thermal: List<Thermal>,
        val memFreeKb: Long?,
        val memTotalKb: Long?,
        val load1: Float?,
        /** 所有核加起来的忙碌比例（0~100×核数）。 */
        val cpuBusyPct: Float,
        /** 本进程累计 CPU 秒数。 */
        val procCpuSec: Double,
        val threads: List<ProcStats.ThreadStat>,
    )

    private var lastCpu: ProcStats.CpuTotal? = null
    private var lastProcTicks = 0L

    private fun read(path: String): String? = try {
        val f = File(path)
        if (f.canRead()) f.readText() else null
    } catch (_: Throwable) {
        null
    }

    private fun readTrim(path: String): String? = read(path)?.trim()?.takeIf { it.isNotEmpty() }

    fun sample(): Snapshot {
        val cpuRaw = read("$procRoot/stat").orEmpty()
        val total = ProcStats.parseCpuTotal(cpuRaw)
        val busy = if (total != null && lastCpu != null) {
            val dt = total.total - lastCpu!!.total
            val di = total.idle - lastCpu!!.idle
            if (dt > 0) (100.0 * (dt - di) / dt).toFloat() else 0f
        } else {
            0f
        }
        lastCpu = total

        val online = ProcStats.parseOnlineCores(readTrim("$sysRoot/devices/system/cpu/online"))
        val n = if (online > 0) online else 1
        val cores = (0 until n).map { i ->
            val base = "$sysRoot/devices/system/cpu/cpu$i/cpufreq"
            val policy = "$sysRoot/devices/system/cpu/cpufreq/policy$i"
            Core(
                id = i,
                curMhz = ProcStats.khzToMhz(
                    readTrim("$base/scaling_cur_freq")?.toLongOrNull()
                        ?: readTrim("$policy/scaling_cur_freq")?.toLongOrNull(),
                ),
                maxMhz = ProcStats.khzToMhz(
                    readTrim("$base/cpuinfo_max_freq")?.toLongOrNull()
                        ?: readTrim("$policy/cpuinfo_max_freq")?.toLongOrNull(),
                ),
                governor = readTrim("$base/scaling_governor") ?: readTrim("$policy/scaling_governor"),
            )
        }

        // MTK 把 GPU / DDR（EMI）频率挂在 devfreq 下 —— 4K 卡顿时这两个比 CPU 更有说服力
        val devfreq = ArrayList<DevFreq>(8)
        try {
            File("$sysRoot/class/devfreq").listFiles()?.take(24)?.forEach { d ->
                val cur = readTrim("${d.path}/cur_freq")?.toLongOrNull()
                    ?: readTrim("${d.path}/cur_freq_hz")?.toLongOrNull()
                val max = readTrim("${d.path}/max_freq")?.toLongOrNull()
                    ?: readTrim("${d.path}/max_freq_hz")?.toLongOrNull()
                if (cur != null || max != null) {
                    devfreq += DevFreq(d.name, ProcStats.hzToMhz(cur), ProcStats.hzToMhz(max))
                }
            }
        } catch (_: Throwable) {
            // 没有就没有
        }

        val thermal = ArrayList<Thermal>(8)
        try {
            File("$sysRoot/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }
                ?.sortedBy { it.name }?.take(16)?.forEach { z ->
                    thermal += Thermal(
                        zone = readTrim("${z.path}/type") ?: z.name,
                        tempC = ProcStats.parseTempC(readTrim("${z.path}/temp")),
                    )
                }
        } catch (_: Throwable) {
        }

        val meminfo = read("$procRoot/meminfo").orEmpty()
        val load = readTrim("$procRoot/loadavg")?.split(' ')?.firstOrNull()?.toFloatOrNull()

        val threads = ArrayList<ProcStats.ThreadStat>(48)
        var procTicks = 0L
        try {
            File("$procRoot/self/task").listFiles()?.forEach { t ->
                val tid = t.name.toIntOrNull() ?: return@forEach
                val st = ProcStats.parseThreadStat(readTrim("${t.path}/stat").orEmpty(), tid) ?: return@forEach
                procTicks += st.ticks
                threads += st
            }
        } catch (_: Throwable) {
        }
        val procCpuSec = if (lastProcTicks > 0 && procTicks >= lastProcTicks) {
            (procTicks - lastProcTicks) / ProcStats.CLK_TCK
        } else {
            0.0
        }
        lastProcTicks = procTicks

        return Snapshot(
            onlineCores = n,
            cores = cores,
            devfreq = devfreq,
            thermal = thermal,
            memFreeKb = ProcStats.parseMeminfoKb(meminfo, "MemFree"),
            memTotalKb = ProcStats.parseMeminfoKb(meminfo, "MemTotal"),
            load1 = load,
            cpuBusyPct = busy,
            procCpuSec = procCpuSec,
            threads = threads.sortedByDescending { it.ticks },
        )
    }
}
