package com.firefly.tv.media

import com.firefly.tv.core.NaturalOrder

/**
 * NAS 目录结构的落盘缓存。
 *
 * 为什么必须有这一层：视频库有 3 部剧、274 个文件。如果每次开机都重新问一遍 NAS，
 * 老人看到的就是「打开就是一个加载动画」。实测冷启动要几十次 SMB 往返，
 * 所以这里把「库 / 剧 / 集」三级结构缓存下来：冷启动直接出画面，
 * 再在后台静默刷新（DESIGN §7）。
 *
 * 只存**结构**（名字），不存播放进度 —— 进度在 `WatchHistory` 里，两者独立失效。
 *
 * 文本格式（一行一条记录，`|` 分隔字段，`\t` 分隔集名）：
 * ```
 * FF1
 * at=1699999999
 * lib=1|IPTV|央视.m3u
 * sh=电视剧|娘道|大宅门
 * ch=IPTV|CCTV1|http://...
 * ep=娘道|0|01.mp4\t02.mp4
 * ```
 * 解析遇到不认识的记录直接跳过，绝不抛异常 ——
 * 缓存坏掉的正确表现是「当作没有缓存」，而不是打不开电视。
 */
object LibraryCache {

    /** 结构变了就 +1，旧缓存自动作废。 */
    const val VERSION = "FF1"

    private const val PREFIX_LIB = "lib"
    private const val PREFIX_SH = "sh"
    private const val PREFIX_CH = "ch"
    private const val PREFIX_EP = "ep"
    private const val PREFIX_AT = "at"

    private const val FIELD = '|'
    private const val EPISODE_SEP = '\t'
    private const val LINE_SEP = '\n'

    /** 一部剧的集列表。[byContent] = 后缀名不可信、靠内容探出来的，不可跨次复用。 */
    class Episodes(val names: List<String>, val byContent: Boolean)

    class Snapshot(
        val savedAt: Long,
        val libraries: List<Library>,
        /** 库名 → 该库的剧列表（视频库）。 */
        val shows: Map<String, List<String>>,
        /** 剧名 → 集列表。剧名在同一 NAS 下唯一，所以不按库分组。 */
        val episodes: Map<String, Episodes>,
        /** 库名 → 频道列表（直播库）。 */
        val channels: Map<String, List<Library.Channel>>,
    ) {
        val isEmpty: Boolean get() = libraries.isEmpty() && shows.isEmpty() && episodes.isEmpty()

        /** 这个库的剧列表。空表示「不知道」，调用方应去 NAS 问一次。 */
        fun showsOf(lib: String): List<String> = shows[lib].orEmpty()

        /**
         * 这个库的频道。注意「知道且为空」和「不知道」是两回事：
         * 前者说明这个直播库确实是空的，不该再问一次。
         */
        fun hasChannels(lib: String): Boolean = channels.containsKey(lib)

        /**
         * 某部剧已知的集。只有「靠后缀认出来的」才敢直接复用 ——
         * 靠内容探出来的那次可能只探了一部分，复用会把整集整集地丢掉。
         */
        fun episodesOf(show: String): List<String>? =
            episodes[show]?.takeIf { !it.byContent }?.names?.takeIf { it.isNotEmpty() }
    }

    private val EMPTY = Snapshot(0L, emptyList(), emptyMap(), emptyMap(), emptyMap())

    // ---- 读 ----

    /**
     * 解析。任何异常都当作「没有缓存」，绝不向上抛。
     *
     * 字段是用**前缀裁掉**而不是整行 split 出来的：片名里出现 `|` 或 `,` 时，
     * 整行 split 会把一行撑成好几段，静默丢内容（第一版就踩了这个坑）。
     */
    fun parse(text: String): Snapshot {
        // 版本对不上就当没有缓存：格式换了以后硬读只会读出乱七八糟的名字
        if (text.lineSequence().firstOrNull()?.trim() != VERSION) return EMPTY

        val libs = ArrayList<Library>()
        val shows = LinkedHashMap<String, List<String>>()
        val episodes = LinkedHashMap<String, Episodes>()
        val channels = LinkedHashMap<String, ArrayList<Library.Channel>>()
        var savedAt = 0L

        for (line in text.lineSequence()) {
            if (line.isBlank() || line == VERSION) continue
            when {
                line.startsWith("$PREFIX_AT$FIELD") ->
                    savedAt = line.substring(PREFIX_AT.length + 1).toLongOrNull() ?: 0L

                line.startsWith("$PREFIX_LIB$FIELD") -> {
                    val rest = line.substring(PREFIX_LIB.length + 1)
                    val kind = rest.substringBefore(FIELD).toIntOrNull() ?: continue
                    val tail = rest.substringAfter(FIELD, "")
                    val name = decode(tail.substringBefore(FIELD)).takeIf { it.isNotBlank() } ?: continue
                    val m3u = decode(tail.substringAfter(FIELD, ""))
                    libs += if (kind == KIND_LIVE) Library.Live(name, m3u) else Library.Video(name)
                }

                line.startsWith("$PREFIX_SH$FIELD") -> {
                    val rest = line.substring(PREFIX_SH.length + 1)
                    val lib = decode(rest.substringBefore(FIELD)).takeIf { it.isNotBlank() } ?: continue
                    shows[lib] = splitNames(rest.substringAfter(FIELD, ""))
                }

                line.startsWith("$PREFIX_EP$FIELD") -> {
                    val rest = line.substring(PREFIX_EP.length + 1)
                    val show = decode(rest.substringBefore(FIELD)).takeIf { it.isNotBlank() } ?: continue
                    val afterShow = rest.substringAfter(FIELD, "")
                    val byContent = afterShow.substringBefore(FIELD) == "1"
                    episodes[show] = Episodes(
                        names = splitNames(afterShow.substringAfter(FIELD, "")),
                        byContent = byContent,
                    )
                }

                line.startsWith("$PREFIX_CH$FIELD") -> {
                    val rest = line.substring(PREFIX_CH.length + 1)
                    val lib = decode(rest.substringBefore(FIELD)).takeIf { it.isNotBlank() } ?: continue
                    // 频道名和地址都可能带 `,`（HLS 地址里很常见），所以只按 `|` 切
                    val tail = rest.substringAfter(FIELD, "")
                    val name = decode(tail.substringBefore(FIELD)).takeIf { it.isNotBlank() } ?: continue
                    val url = decode(tail.substringAfter(FIELD, "")).takeIf { it.isNotBlank() } ?: continue
                    val list: ArrayList<Library.Channel> = channels.getOrPut(lib) { ArrayList() }
                    list.add(Library.Channel(name, url))
                }
            }
        }
        return Snapshot(savedAt, libs, shows, episodes, channels)
    }

    /** 集名用 `\t` 分隔 —— 片名里出现 `\t` 的可能性远低于 `|`。 */
    private fun splitNames(s: String): List<String> =
        s.split(EPISODE_SEP).map { decode(it) }.filter { it.isNotBlank() }

    private fun joinNames(list: List<String>): String =
        list.map { encode(it).replace(EPISODE_SEP, ' ') }.joinToString(EPISODE_SEP.toString())

    // 转义
    //
    // 第一版没做转义，结果片名里带 `|` 时结构会被撑破：`lib|0|剧|集,合|` 读回来变成「剧」。
    // 用 `%` 前缀转义后，名字可以原样存、原样读回。
    private fun encode(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '%' -> append("%25")
                FIELD -> append("%7C")
                LINE_SEP -> append("%0A")
                EPISODE_SEP -> append("%09")
                else -> append(c)
            }
        }
    }

    private fun decode(s: String): String {
        if (!s.contains('%')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    // ---- 写 ----

    /** 序列化。名字统一走 [encode]，所以原样存也能原样读回。 */
    fun serialize(snap: Snapshot): String {
        val sb = StringBuilder()
        sb.append(VERSION).append(LINE_SEP)
        sb.append(PREFIX_AT).append(FIELD).append(snap.savedAt).append(LINE_SEP)

        for (lib in snap.libraries) {
            val kind = if (lib is Library.Live) KIND_LIVE else KIND_VIDEO
            val m3u = (lib as? Library.Live)?.m3u.orEmpty()
            sb.append(listOf(PREFIX_LIB, kind.toString(), encode(lib.name), encode(m3u))
                .joinToString(FIELD.toString())).append(LINE_SEP)
        }
        for ((lib, list) in snap.shows) {
            sb.append(listOf(PREFIX_SH, encode(lib), joinNames(list)).joinToString(FIELD.toString()))
                .append(LINE_SEP)
        }
        for ((show, eps) in snap.episodes) {
            sb.append(
                listOf(PREFIX_EP, encode(show), if (eps.byContent) "1" else "0", joinNames(eps.names))
                    .joinToString(FIELD.toString())
            ).append(LINE_SEP)
        }
        for ((lib, list) in snap.channels) {
            for (c in list) {
                sb.append(
                    listOf(PREFIX_CH, encode(lib), encode(c.name), encode(c.url)).joinToString(FIELD.toString())
                ).append(LINE_SEP)
            }
        }
        return sb.toString()
    }

    // ---- 增量更新 ----
    //
    // 每扫描完一级就立刻落盘，中途断电只丢最后一级，不用整盘重扫。

    private fun base(old: Snapshot?): Snapshot = old ?: EMPTY

    fun withLibraries(old: Snapshot?, libs: List<Library>): Snapshot {
        val b = base(old)
        return Snapshot(now(), libs, b.shows, b.episodes, b.channels)
    }

    fun withShows(old: Snapshot?, lib: String, list: List<String>): Snapshot {
        val b = base(old)
        val shows = LinkedHashMap(b.shows)
        shows[lib] = list
        // 剧列表换了以后，老剧名对应的集缓存可能已经失效，但剧名对得上的仍然有效。
        // 这里只留还存在的剧名，避免缓存无限膨胀。
        val episodes = LinkedHashMap<String, Episodes>()
        for ((show, eps) in b.episodes) if (list.contains(show)) episodes[show] = eps
        return Snapshot(now(), b.libraries, shows, episodes, b.channels)
    }

    fun withEpisodes(old: Snapshot?, show: String, eps: List<String>, byContent: Boolean): Snapshot {
        val b = base(old)
        val episodes = LinkedHashMap(b.episodes)
        episodes[show] = Episodes(eps, byContent)
        return Snapshot(now(), b.libraries, b.shows, episodes, b.channels)
    }

    fun withChannels(old: Snapshot?, lib: String, list: List<Library.Channel>): Snapshot {
        val b = base(old)
        val channels = LinkedHashMap(b.channels)
        channels[lib] = list
        return Snapshot(now(), b.libraries, b.shows, b.episodes, channels)
    }

    private fun now(): Long = System.currentTimeMillis()

    private const val KIND_VIDEO = 0
    private const val KIND_LIVE = 1

    /** 排序统一用自然序，保证缓存和 NAS 直读的顺序完全一致（否则「上一集/下一集」会错位）。 */
    fun sorted(list: List<String>): List<String> = list.sortedWith(NaturalOrder)
}
