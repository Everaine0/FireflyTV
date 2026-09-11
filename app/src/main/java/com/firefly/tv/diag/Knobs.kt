package com.firefly.tv.diag

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat

/**
 * 远程可调的「旋钮」表（见 [DiagServer]）。
 *
 * ## 为什么要有这一层
 *
 * 实机排查「4K 送显只有 18 帧」时，每换一个变量就要**改代码 → 打包 → 装到电视**，
 * 一轮十几分钟，而电视在用户家里、我够不着。用户提出的办法是开一个局域网端口：
 * 我在这边读数字、切变量，用户在电视前看画面即可。
 *
 * 于是把「可能要试的变量」全部做成运行时可写的键值，存在**独立的** prefs 文件里
 * （不叫 `firefly`，免得和配置页那套 NAS/天气配置互相覆盖），
 * 由 [DiagServer] 通过 HTTP 改写、由界面层和播放引擎在**起播那一刻**读取。
 *
 * ## 两条纪律
 *
 * 1. **每个旋钮都必须有非默认档**：默认档 = 这套代码原本的行为，
 *    切到默认档必须能退回改之前的状态（否则排查就把应用改坏了）。
 * 2. **只在起播/建面之前读**：ijkplayer 的选项在 `prepareAsync()` 之后不再生效，
 *    Surface 的格式/层级也只能在建面之前设。所以旋钮改动一律**重播**生效，
 *    由界面层的 `applyKnobs()` 负责。
 */
object Knobs {

    /** 独立的 prefs 文件：配置页 push 那份 `firefly.xml` 不会碰到这里。 */
    private const val FILE = "firefly_diag"

    /** 「跟随程序默认」的取值。 */
    const val DEFAULT = ""

    enum class Kind { CHOICE, INT, TEXT }

    /** 选项分类 —— 和 `IjkMediaPlayer.OPT_CATEGORY_*` 一一对应。 */
    enum class Category { PLAYER, FORMAT, CODEC }

    /** 一条要交给 `IjkMediaPlayer.setOption` 的选项。值可以是 Long 或 String。 */
    class Option(val category: Category, val name: String, val value: Any)

    class Spec(
        val key: String,
        val group: String,
        val def: String,
        val kind: Kind,
        val choices: List<String> = emptyList(),
        val min: Int = 0,
        val max: Int = 0,
        /** 给人和给我看的一句话说明。 */
        val desc: String,
        /** `true` = 改完要重新建面（格式/层级/固定尺寸这类只能在建面前设）。 */
        val needsSurface: Boolean = false,
    )

    private fun choice(key: String, group: String, def: String, choices: List<String>, desc: String, needsSurface: Boolean = false) =
        Spec(key, group, def, Kind.CHOICE, choices = choices, desc = desc, needsSurface = needsSurface)

    private fun int(key: String, group: String, def: Int, min: Int, max: Int, desc: String) =
        Spec(key, group, def.toString(), Kind.INT, min = min, max = max, desc = desc)

    /** 整数旋钮，但默认值可以留空 = **不设这一项**（跟随 ijkplayer 自己的默认值）。 */
    private fun intOpt(key: String, group: String, min: Int, max: Int, desc: String) =
        Spec(key, group, DEFAULT, Kind.INT, min = min, max = max, desc = desc)

    private fun text(key: String, group: String, def: String, desc: String) =
        Spec(key, group, def, Kind.TEXT, desc = desc)

    /** 视频层（SurfaceView）相关的旋钮：都必须重建 Surface 才生效。 */
    const val K_FORMAT = "surface.format"
    const val K_ZORDER = "surface.zorder"
    const val K_FIXED = "surface.fixed"
    const val K_WINDOW_BG = "window.background"

    /** 播放器（ijkplayer）选项旋钮。 */
    const val K_FRAMEDROP = "player.framedrop"
    const val K_MC_SYNC = "player.mediacodec-sync"
    const val K_PICTQ = "player.video-pictq-size"
    const val K_MAXFPS = "player.max-fps"
    const val K_PACKET_BUFFERING = "player.packet-buffering"
    const val K_MAX_BUFFER_MB = "player.max-buffer-mb"
    const val K_OPENSLES = "player.opensles"
    const val K_MC_RES_CHANGE = "player.mediacodec-handle-resolution-change"
    const val K_MC_NAME = "player.mediacodec-default-name"
    const val K_INFBUF = "player.infbuf"
    const val K_START_ON_PREPARED = "player.start-on-prepared"

    /** 进程内调度旋钮。 */
    const val K_THREAD_BOOST = "thread.boost"
    const val K_BOOST_EXTRA = "thread.boost.extra"

    val SPECS: List<Spec> = listOf(
        // ---- 视频层 ----
        choice(
            K_FORMAT, "surface", "opaque",
            listOf("opaque", "rgba8888", "rgbx8888", "translucent", "unknown"),
            "SurfaceHolder.setFormat 的取值。旧的 RGBA_8888 提示可能把视频层挤出硬件叠加通路",
            needsSurface = true,
        ),
        choice(
            K_ZORDER, "surface", "default", listOf("default", "media", "top"),
            "视频层和窗口的前后关系：top=浮在窗口之上（窗口不再需要合成到视频上）",
            needsSurface = true,
        ),
        choice(
            K_FIXED, "surface", "none", listOf("none", "hd1080", "screen"),
            "给 Surface 固定尺寸。**已由 AOSP 5.1 源码核实为无效**（ACodec 自己设的输出尺寸会覆盖 app 设的）：" +
                "保留只为了留一份现场对照，不值得在扫档里花时间",
            needsSurface = true,
        ),
        choice(
            K_WINDOW_BG, "window", "black", listOf("black", "transparent"),
            "窗口底色：透明 = 让 SurfaceFlinger 不必把整屏窗口合成到视频层上",
            needsSurface = true,
        ),

        // ---- 播放器选项 ----
        int(K_FRAMEDROP, "player", 1, 0, 120, "迟到帧策略：1=允许丢（默认，丢的时候数字上看不出来），0=一帧不丢"),
        choice(K_MC_SYNC, "player", "off", listOf("off", "on"), "mediacodec-sync：收/发改成单线程交错，换一种缓冲回收的节奏"),
        int(K_PICTQ, "player", 3, 2, 24, "video-pictq-size：解码→送显之间的帧队列深度。加深可以吸收送显抖动，代价是画面延迟变大"),
        intOpt(K_MAXFPS, "player", 0, 120, "max-fps：片源帧率超过它就被当成「高帧率流」——软解会跳非参考帧 + 跳环路滤波（硬解不受影响）。ijkplayer 默认 31；留空=不设"),
        choice(K_PACKET_BUFFERING, "player", "", listOf("", "off", "on"), "packet-buffering：留空=跟随点播/直播各自的默认值"),
        int(K_MAX_BUFFER_MB, "player", 0, 0, 15, "max-buffer-size（MB）：0=跟随默认。⚠️ ijkplayer 上限就是 15，写大了会让后面所有选项失效"),
        choice(K_OPENSLES, "player", "off", listOf("off", "on"), "opensles：换一种音频输出实现，排除音频线程抢占"),
        choice(K_MC_RES_CHANGE, "player", "on", listOf("on", "off"), "mediacodec-handle-resolution-change：分辨率变化时是否重建解码器（H.264 才用）"),
        text(K_MC_NAME, "player", "", "强制指定硬解解码器名（空=按 ijkplayer 的排名自动选）"),
        choice(K_INFBUF, "player", "off", listOf("off", "on"), "infbuf：不限制输入缓冲（直播才用得上）"),
        choice(K_START_ON_PREPARED, "player", "on", listOf("on", "off"), "start-on-prepared：准备完自动起播"),

        // ---- 调度 ----
        choice(K_THREAD_BOOST, "thread", "off", listOf("off", "on"), "给 ijkplayer / 解码器的线程提优先级（排除「线程被饿死」）"),
        choice(K_BOOST_EXTRA, "thread", "off", listOf("off", "on"), "连 MediaCodec 自己的线程（ACodec/CodecLooper/OMXCallbackDisp）一起提权"),
    )

    private val byKey: Map<String, Spec> = SPECS.associateBy { it.key }

    fun specOf(key: String): Spec? = byKey[key]

    // ---- prefs 读写 ----

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): String {
        val spec = byKey[key] ?: return DEFAULT
        return sp(ctx).getString(key, spec.def) ?: spec.def
    }

    /** 写一个值。返回 null = 成功，否则是给 HTTP 调用方看的中文原因。 */
    fun put(ctx: Context, key: String, value: String): String? {
        val spec = byKey[key] ?: return "没有这个旋钮：$key"
        val v = normalize(spec, value) ?: return "取值不合法：$key=$value（${choicesText(spec)}）"
        sp(ctx).edit().putString(key, v).commit()
        return null
    }

    fun all(ctx: Context): Map<String, String> = SPECS.associate { it.key to get(ctx, it.key) }

    fun reset(ctx: Context) {
        sp(ctx).edit().clear().commit()
    }

    /** 只列出**和默认值不同**的旋钮（界面上只显示这些，免得一屏噪声）。 */
    fun nonDefault(ctx: Context): List<Pair<String, String>> =
        SPECS.mapNotNull { s -> get(ctx, s.key).takeIf { it != s.def }?.let { s.key to it } }

    /**
     * 校验并归一化。
     *
     * `true/false/yes/no/1/0` 这类写法一律收敛到 `on/off`，
     * 免得我从 HTTP 上写 `1` 却被当成非法值。
     */
    fun normalize(spec: Spec, raw: String): String? {
        val v = raw.trim()
        return when (spec.kind) {
            Kind.INT -> {
                val n = v.toIntOrNull() ?: if (v == DEFAULT) null else return null
                if (n == null) spec.def
                else if (n < spec.min || n > spec.max) null else n.toString()
            }

            Kind.CHOICE -> {
                if (v == DEFAULT && spec.def == DEFAULT) return DEFAULT
                val canon = canonical(v)
                if (spec.choices.contains(canon)) canon else null
            }

            Kind.TEXT -> v
        }
    }

    private fun canonical(v: String): String = when (v.lowercase()) {
        "1", "true", "yes", "y", "开", "on" -> "on"
        "0", "false", "no", "n", "关", "off" -> "off"
        else -> v.lowercase()
    }

    /** 取值的可读范围（[describe] 用）。 */
    private fun choicesText(s: Spec): String = when (s.kind) {
        Kind.INT -> "${s.min}~${s.max}"
        Kind.CHOICE -> s.choices.filter { it != DEFAULT }.joinToString("|")
        Kind.TEXT -> "任意文本"
    }

    // ---- 给应用读的类型化取值 ----

    /**
     * SurfaceHolder.setFormat 的取值。
     *
     * `null` = 不调用 `setFormat`（用 SurfaceView 默认的 OPAQUE）——
     * 实测「不设」和「设 OPAQUE」在电视上是两条不同的路：`setFormat(OPAQUE)`
     * 会把 BufferQueue 的格式钉成不透明，而完全不设则交给系统决定。
     */
    fun surfaceFormat(ctx: Context): Int? = when (get(ctx, K_FORMAT)) {
        "opaque" -> PixelFormat.OPAQUE
        "rgba8888" -> PixelFormat.RGBA_8888
        "rgbx8888" -> PixelFormat.RGBX_8888
        "translucent" -> PixelFormat.TRANSLUCENT
        "unknown" -> null
        else -> PixelFormat.OPAQUE
    }

    fun needsSetFormat(ctx: Context): Boolean = get(ctx, K_FORMAT) != "unknown"

    fun zOrder(ctx: Context): String = get(ctx, K_ZORDER)

    fun fixedSize(ctx: Context): String = get(ctx, K_FIXED)

    fun transparentWindow(ctx: Context): Boolean = get(ctx, K_WINDOW_BG) == "transparent"

    fun boostThreads(ctx: Context): Boolean = get(ctx, K_THREAD_BOOST) == "on"

    fun boostCodecThreads(ctx: Context): Boolean = get(ctx, K_BOOST_EXTRA) == "on"

    /**
     * 需要交给 ijkplayer 的旋钮（**在 `prepareAsync()` 之前**逐条 setOption）。
     *
     * 留空的旋钮这里不出现，让程序原有的那套参数（点播/直播两套 tuning）继续生效。
     */
    fun options(ctx: Context): List<Option> {
        val out = ArrayList<Option>(16)
        get(ctx, K_FRAMEDROP).toIntOrNull()?.let { out += Option(Category.PLAYER, "framedrop", it.toLong()) }
        if (get(ctx, K_MC_SYNC) == "on") out += Option(Category.PLAYER, "mediacodec-sync", 1L)
        if (get(ctx, K_PICTQ).isNotEmpty()) {
            get(ctx, K_PICTQ).toIntOrNull()?.let { out += Option(Category.PLAYER, "video-pictq-size", it.toLong()) }
        }
        get(ctx, K_MAXFPS).toIntOrNull()?.takeIf { it > 0 }?.let { out += Option(Category.PLAYER, "max-fps", it.toLong()) }
        when (get(ctx, K_PACKET_BUFFERING)) {
            "on" -> out += Option(Category.PLAYER, "packet-buffering", 1L)
            "off" -> out += Option(Category.PLAYER, "packet-buffering", 0L)
        }
        get(ctx, K_MAX_BUFFER_MB).toIntOrNull()?.takeIf { it > 0 }
            ?.let { out += Option(Category.PLAYER, "max-buffer-size", it.toLong() * 1024 * 1024) }
        if (get(ctx, K_OPENSLES) == "on") out += Option(Category.PLAYER, "opensles", 1L)
        when (get(ctx, K_MC_RES_CHANGE)) {
            "on" -> out += Option(Category.PLAYER, "mediacodec-handle-resolution-change", 1L)
            "off" -> out += Option(Category.PLAYER, "mediacodec-handle-resolution-change", 0L)
        }
        get(ctx, K_MC_NAME).takeIf { it.isNotBlank() }
            ?.let { out += Option(Category.PLAYER, "mediacodec-default-name", it) }
        if (get(ctx, K_INFBUF) == "on") out += Option(Category.PLAYER, "infbuf", 1L)
        when (get(ctx, K_START_ON_PREPARED)) {
            "on" -> out += Option(Category.PLAYER, "start-on-prepared", 1L)
            "off" -> out += Option(Category.PLAYER, "start-on-prepared", 0L)
        }
        return out
    }

    // ---- 预设方案（面板上单击「设置键」循环切换） ----

    class Preset(val label: String, val values: Map<String, String>)

    /**
     * 预设 = 一组旋钮取值。
     *
     * 面板上单击设置键循环切换；HTTP 上 `GET /preset?n=3` 是同一件事。
     * 第 1 档**必须**是全部默认值 —— 那是「这套代码原本的行为」，随时可以退回来。
     */
    val PRESETS: List<Preset> = listOf(
        Preset("默认（不动任何旋钮）", emptyMap()),
        Preset("旧行为：格式提示 RGBA_8888", mapOf(K_FORMAT to "rgba8888")),
        Preset("硬解同步 mediacodec-sync=1", mapOf(K_MC_SYNC to "on")),
        Preset("不丢帧 framedrop=0", mapOf(K_FRAMEDROP to "0")),
        Preset("视频层置顶 setZOrderOnTop", mapOf(K_ZORDER to "top")),
        Preset("视频层浮层 setZOrderMediaOverlay", mapOf(K_ZORDER to "media")),
        Preset("窗口底色透明", mapOf(K_WINDOW_BG to "transparent")),
        Preset("线程提权", mapOf(K_THREAD_BOOST to "on")),
        Preset("深帧队列 pictq=8 + 不丢帧", mapOf(K_PICTQ to "8", K_FRAMEDROP to "0")),
        Preset("浅帧队列 pictq=2", mapOf(K_PICTQ to "2")),
        Preset("最大输入缓冲 15MB", mapOf(K_MAX_BUFFER_MB to "15")),
        Preset("完全不设格式提示", mapOf(K_FORMAT to "unknown")),
    )

    /** 应用第 [index] 个预设：**先全部复位**，再写入这一档的取值。 */
    fun applyPreset(ctx: Context, index: Int): Int {
        val i = index.coerceIn(0, PRESETS.lastIndex)
        reset(ctx)
        val e = sp(ctx).edit()
        for ((k, v) in PRESETS[i].values) {
            val spec = byKey[k] ?: continue
            normalize(spec, v)?.let { e.putString(k, it) }
        }
        e.commit()
        return i
    }

    /** 当前更接近第几档预设（面板显示用）：完全匹配才算。 */
    fun presetIndex(ctx: Context): Int {
        val now = all(ctx)
        return PRESETS.indexOfFirst { p -> SPECS.all { s -> (p.values[s.key] ?: s.def) == now[s.key] } }
            .takeIf { it >= 0 } ?: -1
    }

    fun describe(spec: Spec): String = "${spec.key}（${spec.group}）：${spec.desc}；默认 ${spec.def.ifEmpty { "跟随程序" }}，取值 ${choicesText(spec)}"
}
