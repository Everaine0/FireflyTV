package com.firefly.tv.media

/**
 * 观看记录：**每部剧各记各的**（看到第几集、这一集看到第几分钟）。
 *
 * ## 为什么要单独一层
 *
 * 原来只有一份全局记忆（`last_lib` / `last_show` / `last_ep` / `last_pos`），
 * 于是：
 *  - 按 ↑↓ 换到别的剧，`Navigator.vertical` 永远给 `(第 1 集, 0ms)` ——
 *    换回来只能从头看；
 *  - 换一部剧就会把上一部的位置**覆盖掉**。
 *
 * 现在按「库 + 剧」各存一条，换剧、换库、关机再开都能接着看。
 *
 * ## 关于「老人直接关电视」
 *
 * 记录是**周期性写盘**的（见 `MainActivity.positionTick`，5 秒一次），
 * 不是只在退出时写。所以拔电源最多丢 5 秒，符合「10 秒左右偏差」的要求。
 * 另外直播**不记时长**（`currentPosition` 对直播是从开播算起的假进度，
 * 拿它去 seek 会让音画长时间不同步）—— 直播只记频道号。
 *
 * ## 两条「宁可少写，也不写坏」的规矩
 *
 * 整份记录是**一个整体**落盘的（`serialize` 出来的一整段文本），所以每一个写点
 * 都有能力把全部记录写坏。两条规矩就是为了堵住这件事：
 *
 *  1. **问不到进度就不写**（[shouldStore] / [usablePosition]）。换剧/换库/关电视
 *     这些 `force = true` 的写点正落在「播放器刚重建」的窗口里，那时进度是 0 ——
 *     0 是「不知道」，不是「片头」，写下去就等于把好记录抹掉。
 *  2. **只认「真的在播的那一集」**。界面上的「选中的剧」和「正在播的那一集」不是
 *     一回事：换剧时新剧的集列表要异步去 NAS 列，那几秒里选中项已经变了、播放器
 *     还在放上一部剧。身份由界面层显式给出（`MainActivity.Spot`），这里不猜。
 *
 * ## 文本格式
 *
 * ```
 * WH1
 * lib=电视剧
 * r=电视剧|大宅门|0|263067|1789131895535
 * ```
 *
 * 字段：库名 / 剧名 / 集序号 / 毫秒 / 写入时间（用来挑「这个库最近看的」）。
 * 名字里可能有 `|`，所以写入时按 [esc] 转义。解析遇到坏行直接跳过，
 * 绝不抛异常 —— 记录坏掉的正确表现是「当作没记录」，而不是打不开电视。
 */
object WatchHistory {

    /** 结构变了就 +1，旧记录自动作废。 */
    const val VERSION = "WH1"

    private const val PREFIX_LIB = "lib"
    private const val PREFIX_RECORD = "r"

    private const val FIELD = '|'
    private const val LINE_SEP = '\n'

    /** 分隔库名和剧名用的内部字符（不会出现在用户可见的名字里）。 */
    private const val KEY_SEP = '\u0001'

    /**
     * 记到哪儿就从哪儿接着看；**离片头太近就别续了**。
     *
     * 老人可能只是点开看了一眼就换台，从第 8 秒开始播没有意义，
     * 反而会让人以为「怎么一打开就跳到中间」。
     */
    const val MIN_RESUME_MS = 15_000L

    /** 记录条数上限：只留最近这么多部，防止这个 blob 无限长大。 */
    const val MAX_RECORDS = 200

    /** 周期写盘的最小间隔（见 [shouldStore]）。 */
    const val SAVE_DEBOUNCE_MS = 5_000L

    /** 周期写盘的最小前进量：没走到 1 秒就不必再写一遍。 */
    const val MIN_STEP_MS = 1_000L

    /** 一部剧看到哪儿了。[at] 是最后一次写入的墙上时间。 */
    class Record(
        val lib: String,
        val show: String,
        val episode: Int,
        val posMs: Long,
        val at: Long,
    ) {
        /** 该从哪一毫秒起播。 */
        val resumeMs: Long get() = if (posMs >= MIN_RESUME_MS) posMs else 0L
    }

    /** 全部记录 + 上次用的是哪个库（**含直播库**，因为开机要回到上次那个库）。 */
    class Book(val lastLib: String, val records: Map<String, Record>) {

        fun find(lib: String, show: String): Record? = records[key(lib, show)]

        /** 这个库里最近看的那部剧。开机恢复和切库都用它。 */
        fun mostRecentIn(lib: String): Record? =
            records.values.filter { it.lib == lib }.maxByOrNull { it.at }

        val isEmpty: Boolean get() = records.isEmpty()

        companion object {
            val EMPTY = Book("", emptyMap())
        }
    }

    fun key(lib: String, show: String): String = "$lib$KEY_SEP$show"

    // ---- 读 ----

    /** 任何异常都当作「没有记录」。 */
    fun parse(text: String?): Book {
        if (text.isNullOrBlank()) return Book.EMPTY
        return try {
            val lines = text.split(LINE_SEP)
            if (lines.firstOrNull()?.trim() != VERSION) return Book.EMPTY
            var lastLib = ""
            val map = LinkedHashMap<String, Record>()
            for (line in lines.drop(1)) {
                val l = line.trim()
                if (l.isEmpty()) continue
                when {
                    l.startsWith("$PREFIX_LIB=") -> lastLib = unesc(l.removePrefix("$PREFIX_LIB="))
                    l.startsWith("$PREFIX_RECORD=") -> {
                        val rec = parseRecord(l.removePrefix("$PREFIX_RECORD=")) ?: continue
                        map[key(rec.lib, rec.show)] = rec
                    }
                }
            }
            Book(lastLib, map)
        } catch (t: Throwable) {
            Book.EMPTY
        }
    }

    private fun parseRecord(body: String): Record? {
        val f = body.split(FIELD)
        if (f.size < 5) return null
        val lib = unesc(f[0])
        val show = unesc(f[1])
        if (lib.isBlank() || show.isBlank()) return null
        val ep = f[2].toIntOrNull() ?: return null
        val pos = f[3].toLongOrNull() ?: return null
        val at = f[4].toLongOrNull() ?: return null
        return Record(lib, show, ep.coerceAtLeast(0), pos.coerceAtLeast(0L), at)
    }

    // ---- 写 ----

    fun serialize(book: Book): String {
        val sb = StringBuilder(VERSION).append(LINE_SEP)
        if (book.lastLib.isNotBlank()) {
            sb.append(PREFIX_LIB).append('=').append(esc(book.lastLib)).append(LINE_SEP)
        }
        // 只留最近的 MAX_RECORDS 条
        val keep = book.records.values.sortedByDescending { it.at }.take(MAX_RECORDS)
        for (r in keep) {
            sb.append(PREFIX_RECORD).append('=').append(esc(r.lib)).append(FIELD)
                .append(esc(r.show)).append(FIELD)
                .append(r.episode).append(FIELD)
                .append(r.posMs).append(FIELD)
                .append(r.at).append(LINE_SEP)
        }
        return sb.toString()
    }

    /**
     * 这次量到的进度**能不能信**。
     *
     * ⚠️ `0` 不是「看到了片头」，而是「**问不出来**」：
     * 播放器刚被重建（`prepareAsync` 还没走完）、硬解退回软解重播的窗口里、
     * 或者已经 release 之后，`PlaybackEngine.positionMs()` 回的都是 0。
     *
     * 这条判据是「多次切换以后播放记录丢了」的正解：换剧/换库/退到后台都是
     * **立刻**写一条（`force = true`），而那几秒正好落在上面那些窗口里 ——
     * 以前的实现把 0 照收，等于把上一次存下的好进度**抹成 0**，
     * 用户看到的就是「换回来只能从头看」。
     */
    fun usablePosition(posMs: Long): Boolean = posMs > 0

    /**
     * 自动连播到第 [epIndex] 集时该从哪儿起播（拿不到就用 0）。
     *
     * ⚠️ 传 0 的后果不是"从头看"那么轻：`MainActivity.playEpisode(startMs)` 一进去
     * 就按这个值**写一条记录**，所以自动连播时传 0 会把下一集已经存下的续播点
     * **直接覆盖掉** —— 用户第二天打开就从那一集开头重看。
     *
     * 两条规矩：
     *  - 记录里的集号必须就是这一集（否则那是别的集的进度，拿它 seek 会跳到莫名其妙的位置）；
     *  - 进度必须「可信」（见 [usablePosition]；小于 [MIN_RESUME_MS] 的会被
     *    [Record.resumeMs] 归 0，那本来也是"刚点开看了一眼"）。
     *
     * 抽成纯函数是为了能用单测钉住 —— 这条 bug 只在"上一集播完"那一刻才显形，
     * 靠手点很难回归。
     */
    fun autoAdvanceResumeMs(rec: Record?, epIndex: Int): Long {
        if (rec == null || rec.episode != epIndex) return 0L
        val pos = rec.resumeMs
        return if (usablePosition(pos)) pos else 0L
    }

    /**
     * 要不要把这次量到的进度写进记录（纯函数，单测钉住）。
     *
     * - 问不到进度（≤ 0）→ **一律不写**，宁可留着上一次那条旧的（见 [usablePosition]）；
     * - [force]（换剧/换库/关电视）→ 不等防抖，只要进度可用就写；
     * - 周期写盘 → 至少隔 [SAVE_DEBOUNCE_MS] 且至少前进 [MIN_STEP_MS] 才写，
     *   免得一个十几 KB 的 blob 每 5 秒原样重写一遍。
     *
     * @param lastSavedAt 上一次写盘的时刻（本次会话内，也就是 `Record.at` 的口径）
     * @param lastSavedPos 上一次写盘时的进度
     */
    fun shouldStore(
        posMs: Long,
        force: Boolean,
        now: Long,
        lastSavedAt: Long,
        lastSavedPos: Long,
    ): Boolean {
        if (!usablePosition(posMs)) return false
        if (force) return true
        if (now - lastSavedAt < SAVE_DEBOUNCE_MS) return false
        return kotlin.math.abs(posMs - lastSavedPos) >= MIN_STEP_MS
    }

    /** 记一条（同一部剧覆盖）。 */
    fun with(book: Book, rec: Record): Book {
        val map = LinkedHashMap(book.records)
        map[key(rec.lib, rec.show)] = rec
        return Book(book.lastLib, map)
    }

    fun withLastLib(book: Book, lib: String): Book =
        if (book.lastLib == lib) book else Book(lib, book.records)

    /**
     * 从旧版那份「只有一条全局记忆」迁移过来。
     *
     * 用户电视上已经存着上次看到哪儿了，升级后不该从头开始。
     */
    fun fromLegacy(lib: String, show: String, episode: Int, posMs: Long, at: Long): Book {
        if (lib.isBlank() || show.isBlank()) return Book(lib, emptyMap())
        return Book(lib, mapOf(key(lib, show) to Record(lib, show, episode, posMs, at)))
    }

    // ---- 转义 ----

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace(FIELD.toString(), "\\p")
        .replace(LINE_SEP.toString(), "\\n")

    private fun unesc(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'p' -> { sb.append(FIELD); i += 2 }
                    'n' -> { sb.append(LINE_SEP); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
