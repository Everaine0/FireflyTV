package com.firefly.tv.media

import com.firefly.tv.core.Config
import com.firefly.tv.core.NaturalOrder
import com.firefly.tv.smb.SmbStore

/**
 * 媒体库模型。
 *
 * 结构（DESIGN §4）：
 * ```
 * \\NAS\media\
 * ├── 电视剧\            ← 视频库：库/剧名/剧集文件
 * ├── 电影\              ← 视频库
 * └── 直播\              ← 含 .m3u 即自动识别为 IPTV 库
 * ```
 */
sealed class Library {

    abstract val name: String

    /** 视频库：库根下每个子文件夹 = 一部剧。 */
    class Video(override val name: String) : Library()

    /** 直播库：平铺频道列表。 */
    class Live(override val name: String, val m3u: String) : Library()

    class Show(val name: String, val episodes: List<String>)

    class Channel(val name: String, val url: String)
}

object MediaExt {
    val VIDEO = setOf(
        "mp4", "mkv", "avi", "ts", "m2ts", "mov", "wmv", "flv", "rmvb", "rm",
        "mpg", "mpeg", "m4v", "vob", "3gp", "webm", "divx", "asf", "f4v", "iso",
    )

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isVideo(name: String) = ext(name) in VIDEO

    fun isM3u(name: String): Boolean {
        val e = ext(name)
        return e == "m3u" || e == "m3u8"
    }
}

/**
 * 惰性扫描：绝不预递归整个 NAS。只列当前库顶层，切剧才读集数（DESIGN §7）。
 * 单次列目录限 [MAX_ENTRIES] 条。
 */
object Scanner {

    const val MAX_ENTRIES = 2000

    /** 按内容探测时最多看几个文件，避免整柜非视频文件把列目录拖慢。 */
    private const val CONTENT_PROBE_LIMIT = 32

    /**
     * 列根目录下的媒体库。
     * 容错：无视频文件的文件夹、无有效内容的库一律跳过（DESIGN §4）。
     */
    fun libraries(cfg: Config.Smb): List<Library> {
        val entries = SmbStore.with(cfg) { it.list("") }
            .asSequence()
            .filter { it.isDir }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .take(MAX_ENTRIES)
            .toList()

        val result = ArrayList<Library>(entries.size)
        for (dir in entries) {
            when (val kind = classify(cfg, dir.name)) {
                is Kind.Video -> result += Library.Video(dir.name)
                is Kind.Live -> result += Library.Live(dir.name, kind.m3u)
                Kind.Empty -> Unit // 跳过
            }
        }
        return result
    }

    private sealed class Kind {
        object Empty : Kind()
        object Video : Kind()
        class Live(val m3u: String) : Kind()
    }

    /** 目录内含 .m3u 即判定为直播库；否则含视频文件（或含子文件夹）即为视频库。 */
    private fun classify(cfg: Config.Smb, dir: String): Kind {
        val entries = try {
            SmbStore.with(cfg) { it.list(dir) }
        } catch (_: Throwable) {
            return Kind.Empty
        }
        entries.firstOrNull { !it.isDir && MediaExt.isM3u(it.name) }?.let { return Kind.Live(it.name) }
        if (entries.any { !it.isDir && MediaExt.isVideo(it.name) }) return Kind.Video
        // 电视剧目录：本级只有子文件夹，真正的视频在剧名目录里
        if (entries.any { it.isDir }) return Kind.Video
        // 后缀全都不认识：可能整批片源后缀是错的（实测有整季 .mp4 实际是 MPEG-TS），
        // 这时才按内容确认一下，避免整部剧被当成空目录跳过
        if (hasVideoByContent(cfg, dir, entries.map { it.name })) return Kind.Video
        return Kind.Empty
    }

    /** 按内容逐个确认（只在后缀判断全军覆没时才调用，代价有界）。 */
    private fun hasVideoByContent(cfg: Config.Smb, dir: String, names: List<String>): Boolean {
        for (name in names.asSequence().take(CONTENT_PROBE_LIMIT)) {
            val head = try {
                SmbStore.with(cfg) { it.head("$dir/$name", MediaSniff.HEAD_BYTES) }
            } catch (_: Throwable) {
                continue
            }
            if (MediaSniff.looksLikeVideoByContent(head)) return true
        }
        return false
    }

    /** 视频库顶层 = 剧列表（每个子文件夹一部剧）。 */
    fun shows(cfg: Config.Smb, lib: Library.Video): List<String> =
        SmbStore.with(cfg) { it.list(lib.name) }
            .asSequence()
            .filter { it.isDir }
            .map { it.name }
            .sortedWith(NaturalOrder)
            .take(MAX_ENTRIES)
            .toList()

    /**
     * 读某部剧的集数。两级结构：
     *  - 库/剧名/剧集文件（常见）
     *  - 库/剧名/季/剧集文件（多季，取"第 1 季"或第一层子目录）
     */
    fun episodes(cfg: Config.Smb, lib: Library.Video, show: String): List<String> {
        val base = "${lib.name}/$show"
        val entries = SmbStore.with(cfg) { it.list(base) }
        val direct = entries.filter { !it.isDir && MediaExt.isVideo(it.name) }
            .map { it.name }
            .sortedWith(NaturalOrder)
        if (direct.isNotEmpty()) return direct.take(MAX_ENTRIES)

        // 没有直接视频 → 找第一层子目录当季
        val season = entries.filter { it.isDir }
            .map { it.name }
            .sortedWith(NaturalOrder)
            .firstOrNull { it.isNotBlank() }
        if (season != null) {
            val inSeason = SmbStore.with(cfg) { it.list("$base/$season") }
            val vids = inSeason.asSequence()
                .filter { !it.isDir && MediaExt.isVideo(it.name) }
                .map { it.name }
                .sortedWith(NaturalOrder)
                .take(MAX_ENTRIES)
                .toList()
            if (vids.isNotEmpty()) return vids
        }

        // 后缀一个都不认识：按内容挑出实际是媒体的那些文件
        return entries.asSequence()
            .filter { !it.isDir && !MediaExt.isM3u(it.name) }
            .map { it.name }
            .filter { name -> isVideoByContent(cfg, "$base/$name") }
            .sortedWith(NaturalOrder)
            .take(MAX_ENTRIES)
            .toList()
    }

    private fun isVideoByContent(cfg: Config.Smb, relativePath: String): Boolean = try {
        val head = SmbStore.with(cfg) { it.head(relativePath, MediaSniff.HEAD_BYTES) }
        MediaSniff.looksLikeVideoByContent(head)
    } catch (_: Throwable) {
        false
    }

    /** 剧集文件的完整相对路径（相对库根）。 */
    fun episodePath(lib: Library.Video, show: String, episode: String): String = "${lib.name}/$show/$episode"
}
