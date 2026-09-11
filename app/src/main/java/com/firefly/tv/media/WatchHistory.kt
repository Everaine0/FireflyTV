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
 * 记录是**周期性写盘**的（见 `MainActivity.saveRecordTick`，5 秒一次），
 * 不是只在退出时写。所以拔电源最多丢 5 秒，符合「10 秒左右偏差」的要求。
 * 另外直播**不记时长**（`currentPosition` 对直播是从开播算起的假进度，
 * 拿它去 seek 会让音画长时间不同步）—— 直播只记频道号。
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
