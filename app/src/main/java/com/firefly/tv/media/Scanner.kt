package com.firefly.tv.media

import com.firefly.tv.core.Config
import com.firefly.tv.core.NaturalOrder
import com.firefly.tv.smb.SmbClient
import com.firefly.tv.smb.SmbStore

/**
 * 媒体库模型。
 *
 * 结构（DESIGN §4）：
 * ```
 * \\NAS\media\
 * ├── 电视剧\            ← 视频库：库/剧名/剧集文件
 * │   └── 水浒传\        → 水浒传01.mkv ...
 * ├── 电影\              ← 视频库，也可以是**平铺**的：库/电影.mp4（单集）
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

    /**
     * 列根目录下的媒体库。
     * 容错：无视频文件的文件夹、无有效内容的库一律跳过（DESIGN §4）。
     *
     * ⚠️ 但**列目录失败不等于"这个库是空的"**：那种情况会把异常抛给调用方，
     * 由上层判为"整轮扫描失败"（保留缓存 + 报故障重试），而不是丢掉整个媒体库列表。
     * 依据见 [classify] 的说明。
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

    /**
     * 目录内含 .m3u 即判定为直播库；否则含视频文件（或含子文件夹）即为视频库。
     *
     * ⚠️ 这里**不能**把列目录失败吞成 [Kind.Empty]。
     *
     * 老实现是 `catch (_: Throwable) { return Kind.Empty }`，看起来"很宽容"，
     * 实际后果很重：开机那几秒 NAS/WiFi 还没就绪时，**每一个**库的列目录都可能失败，
     * 于是整轮扫下来 `libraries()` 返回一个空表但**不抛异常** ——
     * 上层只能报「NAS 上还没有可以播放的内容」，用户看到的是一个
     * 左右上下键全都不响应的死页面，只能重开应用（实机反馈的"卡住"之一）。
     *
     * 更糟的是那一轮的 `libraries = []` 还会被写进落盘缓存，而 `Snapshot.isEmpty`
     * 只看"库/剧/集是不是全空"，旧剧集列表还在 → 缓存看着仍然"有内容"，
     * 下次开机直接走缓存路径得到空库表（见 `MainActivity.restoreSpot` 的空表防护）。
     *
     * 所以让异常**照原样冒出去**：`libraries()` 那一层会把整轮扫描判为失败，
     * 于是走的是「保留缓存 + 报故障并每 10 秒重试」，而不是"你的 NAS 是空的"。
     */
    private fun classify(cfg: Config.Smb, dir: String): Kind {
        val entries = SmbStore.with(cfg) { it.list(dir) }
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
    private fun hasVideoByContent(cfg: Config.Smb, dir: String, names: List<String>): Boolean =
        probeNamesByContent(cfg, dir, names).isNotEmpty()

    /**
     * 视频库顶层 = 剧列表。[LibraryCache] 命中时由调用方直接用缓存，
     * 走不到这里 —— 保留它是为了「后台刷新」和首次扫描。
     *
     * 两种东西都算「一部剧」：
     *  - 子文件夹（库/剧名/剧集文件，电视剧那种常见结构）
     *  - **散装视频文件**（库/电影.mp4），算单集
     *
     * 老版本只认子文件夹，于是「电影」这种平铺的库被判成**空库**，
     * 界面上就是「这个库打不开，唰地跳过去」（实机反馈）。
     */
    fun shows(cfg: Config.Smb, lib: Library.Video): List<String> {
        val entries = SmbStore.with(cfg) { it.list(lib.name) }
        val named = showNames(entries)
        if (named.isNotEmpty()) return named
        // 一个认识的都没有：后缀可能整批不可信（实测整季 .mp4 实为 MPEG-TS），按内容确认
        return probeNamesByContent(cfg, lib.name, looseNames(entries))
    }

    /**
     * 纯函数部分：目录项 → 剧名列表（子文件夹 + 散装视频文件，自然排序）。
     *
     * 抽出来是为了能单测 —— 这段的 bug 表现是「整个库不见了」，
     * 而它只在有 NAS 的时候才跑得到。
     */
    fun showNames(entries: List<SmbClient.Entry>): List<String> =
        entries.asSequence()
            .filter { it.isDir || MediaExt.isVideo(it.name) }
            .map { it.name }
            .sortedWith(NaturalOrder)
            .take(MAX_ENTRIES)
            .toList()

    /** 顶层的散装文件（.m3u 不算 —— 那是直播库的标记）。 */
    private fun looseNames(entries: List<SmbClient.Entry>): List<String> =
        entries.asSequence()
            .filter { !it.isDir && !MediaExt.isM3u(it.name) }
            .map { it.name }
            .take(MAX_ENTRIES)
            .toList()

    /**
     * 集列表 + **这些集是怎么认出来的**。缓存需要知道这一点：
     * 靠后缀认出来的结果下次可以直接复用，靠内容探出来的不行（见 [LibraryCache.Episodes]）。
     */
    class Episodes(val names: List<String>, val byContent: Boolean)

    /**
     * 读某部剧的集数。三种结构：
     *  - 库/剧名/剧集文件（常见）
     *  - 库/剧名/季/剧集文件（多季，取"第 1 季"或第一层子目录）
     *  - 库/剧名（「剧名」本身就是一个散装视频文件）→ 单集
     */
    fun episodes(cfg: Config.Smb, lib: Library.Video, show: String): List<String> =
        episodesDetailed(cfg, lib, show).names

    fun episodesDetailed(cfg: Config.Smb, lib: Library.Video, show: String): Episodes {
        // 散装文件：这部剧就是它自己（单集）。**不能**去列它 —— 列一个文件路径必然失败
        if (MediaExt.isVideo(show)) return Episodes(listOf(show), byContent = false)

        val base = "${lib.name}/$show"
        val entries = try {
            SmbStore.with(cfg) { it.list(base) }
        } catch (t: Throwable) {
            // 后缀不认识、靠内容才认出来的那种散装文件：列目录失败是**预期**的，不是故障。
            // 先用内容确认一下它真是视频再下结论，免得把网络抖动当成「这是个文件」。
            if (isVideoByContent(cfg, base)) return Episodes(listOf(show), byContent = true)
            throw t
        }
        val direct = entries.filter { !it.isDir && MediaExt.isVideo(it.name) }
            .map { it.name }
            .sortedWith(NaturalOrder)
        if (direct.isNotEmpty()) return Episodes(direct.take(MAX_ENTRIES), byContent = false)

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
            if (vids.isNotEmpty()) return Episodes(vids, byContent = false)
        }

        // 后缀一个都不认识：按内容挑出实际是媒体的那些文件
        // （先确认一下「这部剧」本身会不会就是个文件 —— 见上面对 list 失败的说明；
        //  smbj 对文件路径既可能抛错、也可能回空列表，两种都得兜住）
        if (entries.isEmpty() && isVideoByContent(cfg, base)) return Episodes(listOf(show), byContent = true)
        val found = probeNamesByContent(cfg, base, entries.filter { !it.isDir && !MediaExt.isM3u(it.name) }.map { it.name })
        return Episodes(found, byContent = true)
    }

    /** 剧集文件的完整相对路径（相对库根）。 */
    fun episodePath(lib: Library.Video, show: String, episode: String): String =
        // 散装文件那一档：集名就是剧名，路径不再多一层（[episodesDetailed] 的约定）
        if (isLooseShow(show, episode)) "${lib.name}/$show" else "${lib.name}/$show/$episode"

    /**
     * 这部剧是「散装文件」还是文件夹。
     *
     * 判据是「集名 == 剧名」—— 只有散装那一档会这样（见 [episodesDetailed]）；
     * 文件夹里的集名带后缀，正常不会跟剧名目录撞上。
     */
    fun isLooseShow(show: String, episode: String): Boolean =
        MediaExt.isVideo(show) || episode == show

    // ---- 按内容探测 ----

    /**
     * 逐个读文件头判断是不是媒体。
     *
     * 实测「娘道」76 集后缀是 .mp4、内容却是 MPEG-TS，所以这条路必须留。
     * 但它是最慢的一条路（每个文件一次 SMB 往返），所以：
     *  - 只在后缀全军覆没时才走；
     *  - 并发探测（SmbStore 内部串行，开并发只是让网络往返重叠起来），
     *    实测 76 个文件从「按秒等」降到基本瞬发。
     */
    private fun probeNamesByContent(cfg: Config.Smb, dir: String, names: List<String>): List<String> {
        if (names.isEmpty()) return emptyList()
        val candidates = names.take(PROBE_LIMIT)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PROBE_THREADS) { r ->
            Thread(r, "firefly-probe").apply { isDaemon = true }
        }
        return try {
            val futures = candidates.map { name ->
                pool.submit(java.util.concurrent.Callable { name to isVideoByContent(cfg, "$dir/$name") })
            }
            futures.asSequence()
                .mapNotNull { f -> runCatching { f.get() }.getOrNull() }
                .filter { it.second }
                .map { it.first }
                .sortedWith(NaturalOrder)
                .take(MAX_ENTRIES)
                .toList()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun isVideoByContent(cfg: Config.Smb, relativePath: String): Boolean = try {
        val head = SmbStore.with(cfg) { it.head(relativePath, MediaSniff.HEAD_BYTES) }
        MediaSniff.looksLikeVideoByContent(head)
    } catch (_: Throwable) {
        false
    }

    /** 并发探测的线程数。电视只有 1–2 条 SMB 连接，再多也没用。 */
    private const val PROBE_THREADS = 4

    /**
     * 按内容探测的上限。原来的 32 太小 —— 「娘道」有 76 集、猫和老鼠 157 集，
     * 一旦后缀全不可信，32 个之后整集整集地丢。这里放宽到能覆盖常见整季。
     */
    private const val PROBE_LIMIT = 400
}
