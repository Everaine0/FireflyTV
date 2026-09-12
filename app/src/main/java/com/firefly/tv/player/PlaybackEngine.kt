package com.firefly.tv.player

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.firefly.tv.core.Config
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * 播放层抽象。换回 Media3 只需新增一个实现类，界面层不用动（DESIGN §6）。
 */
interface PlaybackEngine {

    interface Listener {
        /** 已起播。[durationMs] 直播时为 0。 */
        fun onPrepared(durationMs: Long)

        /** 一集正常播完。 */
        fun onCompletion()

        /**
         * 播不了。[fatal] = true 表示这一集彻底没救（应跳下一集）；
         * false 表示是网络类问题（应进故障页重试）。
         */
        fun onError(friendlyMessage: String, fatal: Boolean)

        /** 首帧已上屏，可以撤掉加载提示。 */
        fun onFirstFrame()

        /**
         * 音频真的开始出声了。
         *
         * 单独给一个回调是因为「有画面没声音」是这台电视上最难查的一类问题：
         * 靠人耳听、靠 logcat 翻都不可靠。有了这个信号，测试就能直接判定
         * 「这条流到底有没有音频输出」，不用猜。
         */
        fun onAudioStarted() = Unit

        /**
         * 硬解不可用，已经**自动改用软解重播**（见 [PlaybackEngine.canFallbackToSoftware]）。
         *
         * 界面层必须知道这件事：软解重播等于把起播时间重新归零，
         * 起播看门狗要跟着重新起算，否则会在软解刚建解码器的时候就误报「打不开」。
         *
         * @param reason 给 logcat 看的原因，不给用户看
         */
        fun onDecoderFallback(reason: String) = Unit

        /**
         * 直播断流/连不上，引擎**正在自动重连**（第 [attempt] 次）。
         *
         * ## 为什么单开一个回调
         *
         * 直播的失败**不会**走 [onError]：引擎开了 `liveReconnect`，它会自己
         * 退避重试到天荒地老（见 `PlaybackEngine.handleError`）。这对「看一半断流」
         * 是对的，但以前它**顺手把界面层也蒙在鼓里** —— 一次回调都不发。
         *
         * 用户实测到的现象就是这句话：「从电视[剧]换回直播，又卡住了」。
         * 现场是：频道 403 连不上，屏幕上是**上一个内容冻住的最后一帧**，
         * 按键有反应（浮层、语音都正常），但画面永远不会变，
         * 也没有任何提示 —— 看起来就是死机。
         *
         * 所以引擎必须把「我在重连」这件事说出来，让界面层至少能给用户一句话。
         *
         * @param attempt 连续失败次数，从 1 开始
         * @param url 正在重连的地址，给 logcat 看
         */
        fun onLiveRetry(attempt: Int, url: String?) = Unit
    }

    fun setListener(l: Listener)

    /** Surface 可能比播放请求先到或后到，两种顺序都要能工作。 */
    fun attach(surface: Surface)

    fun detachSurface()

    /** 打开 SMB 上的文件并从 [startMs] 起播。会阻塞进行 SMB 打开操作，请在后台线程调用。 */
    fun playSmb(cfg: Config.Smb, relativePath: String, startMs: Long)

    /** 从任意随机读字节源起播（测试用本地文件，将来也可用于 U 盘）。 */
    fun playSource(source: RandomAccessSource, startMs: Long)

    /**
     * 同 [playSource]，但给的是「怎么再打开一份」而不是已经打开的对象。
     *
     * 硬解失败要用软解重播时，必须能**重新打开**字节源 —— 已经 close 掉的
     * `RandomAccessFile` / SMB 句柄不能复用（`SmbMediaDataSource.close()` 会把它关掉）。
     * 所以能重建的字节源请走这个重载，否则兜底重播会读不出数据。
     */
    fun playSource(open: () -> RandomAccessSource, startMs: Long)

    /** 直接起播 URL（IPTV 直播用）。 */
    fun playUrl(url: String)

    /** 当前播放类型，用于让实现层挑参数（直播与点播的取舍不同）。 */
    fun setMode(kind: PlaybackMode.Kind)

    /** 直播断流自动重连；[urlProvider] 返回当前频道地址。 */
    fun setLiveReconnect(on: Boolean, urlProvider: (() -> String?)?)

    fun isPlaying(): Boolean
    fun positionMs(): Long
    fun durationMs(): Long

    /**
     * 真实输出的帧率（`stat.vfps` = 每秒**真的送进视频层**的帧数）。
     *
     * ## 硬解通路上它也是有值的（这一点以前的注释写错了）
     *
     * 曾经这里写着「MediaCodec 通路上 `vp->bmp` 恒为空、于是 vfps 永远是 0」，
     * 并据此把「播放中存活看门狗」关掉了。**源码核对下来这个判断是错的**：
     * MediaCodec 的帧走 `SDL_VoutAMediaCodec_CreateOverlay`，
     * 它的 overlay 格式是 `SDL_FCC__AMC`（`ijksdl_vout_overlay_android_mediacodec.c`
     * 的 `func_fill_frame`），所以 `ff_ffplay.c:880` 的 `if (vp->bmp)` 是**成立**的；
     * 显示时走 `SDL_VoutOverlayAMediaCodec_releaseFrame_l(overlay, NULL, true)`
     * → `MediaCodec.releaseOutputBuffer(idx, render=true)`（解码结果直接进 Surface，零拷贝）。
     * 实机也印证了：长虹 43Q3T 硬解播 4K 时面板上「送显 18.2 帧/秒」，不是 0。
     *
     * 所以它是「**显示通路每秒真的收下了几帧**」的真值 ——
     * 和解码帧率（[Diag.decodeFps]）一比，就能把「解不出来」和「送不出去」分开：
     * 电视实测 解码 24.6 / 送显 18.2 / 丢帧 0%，说明**瓶颈在显/合成**，
     * 而且中间那 6.4 帧是被 `video_refresh()` 里那条**不计数**的迟到帧跳过吃掉的
     * （`ff_ffplay.c:1373`，只有 `framedrop>0` 时才走）。
     */
    fun outputFps(): Float

    /** 视频解码实际走的通路（[decoderInUse] 的返回值）。 */
    enum class Decoder {
        /** 还没起播，或者播放器已经释放。 */
        UNKNOWN,

        /** FFmpeg 软解（`FFP_PROPV_DECODER_AVCODEC = 1`）。 */
        SOFTWARE,

        /** MediaCodec 硬解（`FFP_PROPV_DECODER_MEDIACODEC = 2`）。 */
        HARDWARE,
    }

    /**
     * 视频解码通路的**真值**。
     *
     * ## 为什么必须问播放器，而不是记住「我设了硬解选项」
     *
     * ijkplayer 的硬解是**静默回落**的：`func_open_video_decoder`
     * （`ffpipeline_android.c:73-77`）试建 MediaCodec 管线，
     * **任何一步失败都只有一行 ALOGE，然后直接换 FFmpeg 软解**，
     * Java 层一个回调都没有。也就是说：
     *
     *  - 「选码回调被调用了」只说明**问过**，不说明建成了；
     *  - 「我设了 `mediacodec-all-videos=1`」更不说明建成了。
     *
     * 唯一可靠的判据是 `IjkMediaPlayer.getVideoDecoder()`
     * （= `ffp->stat.vdec_type`）：
     * `ffppipenode_android_mediacodec_vdec.c:2081` 只在 **MediaCodec 管线真的建成**时
     * 写 `FFP_PROPV_DECODER_MEDIACODEC(2)`，
     * 而 `ffpipenode_ffplay_vdec.c:57` 在软解通路上写 `AVCODEC(1)`。
     *
     * 用户实机反馈「感觉电视的硬解没开」时，这个函数就是答案。
     */
    fun decoderInUse(): Decoder

    /**
     * 诊断面板要的一屏数字。**全部是播放器报出来的真值**，这里不做推算。
     *
     * 为什么要专门做一份：实机上「帧率不高」有四种完全不同的原因，
     * 只看画面分不出来，而老人家里不会有 adb：
     *
     * | 现象 | 结论 |
     * | :--- | :--- |
     * | 解码 ≈ 片源帧率，丢帧 ≈ 0 | 本来就是 25/24 帧的片源，没问题 |
     * | 解码 ≈ 片源帧率，丢帧 > 0 | 解码跟得上，是渲染/合成来不及（CPU 或 GPU） |
     * | 解码 < 片源帧率 | 解码本身不够快（硬解没生效 → 看 [decoderInUse]） |
     * | 缓存长期接近 0 | 是 SMB 读取供不上，不是解码 |
     */
    class Diag(
        /** 真值：MediaCodec 还是 FFmpeg 软解。 */
        val decoder: Decoder,
        /** 选码时选中的解码器名（硬件时才非空）。 */
        val decoderName: String?,
        /** 播放器自报的视频解码模块，例如 `MediaCodec` / `avcodec`。 */
        val videoModule: String?,
        /** 模块后面的实现名，例如 `OMX.MTK.VIDEO.DECODER.HEVC` / `hevc`。 */
        val videoImpl: String?,
        val audioCodec: String?,
        val videoWidth: Int,
        val videoHeight: Int,
        /**
         * **片源自己的**帧率（`avg_frame_rate`，从 ijkplayer 的 media meta 里取）。
         *
         * 这个数是回答「帧率不高，25 甚至更低」的关键：电视剧 25、动画 23.976，
         * 拿它和 [outputFps] 一比就知道是「片源本来就只有 25 帧」还是「播的时候掉了帧」。
         * 取不到时为 0（调用方要能容忍 —— 有些流不报 avg_frame_rate）。
         */
        val sourceFps: Float,
        /** 每秒**解码**出来的帧数。 */
        val decodeFps: Float,
        /** 每秒**送显**的帧数（真的进了视频层的帧，硬解软解都有值，见 [outputFps]）。 */
        val outputFps: Float,
        /** 每秒丢掉的帧数（`framedrop` 打开时才有意义）。 */
        val dropFps: Float,
        /** 已缓存时长（毫秒）。 */
        val cachedMs: Long,
        val cachedBytes: Long,
        /** 累计读取字节数。 */
        val trafficBytes: Long,
        /** 播放器自报的码率（bit/s）；0 = 不知道。面板用它算「这条流要多少 MB/秒」。 */
        val bitRateBps: Long,
        /**
         * 这次起播**有没有请求**硬解（`playXxx` 时的决定）。
         *
         * 注意它和「现在跑的是不是硬解」是两件事：静默回落软解之后
         * [decoder] 会变成 [Decoder.SOFTWARE]，但这里仍然是 true —— 面板要的正是这个
         * 「本来想硬解、结果没成」的组合（见 [codecOffered]）。
         */
        val requestedHardware: Boolean,
        /**
         * 选码器**有没有给出**解码器名。
         *
         *  - `true`  + [decoder] 还是软解 ⇒ **静默回落**（ijkplayer 建 MediaCodec 失败，
         *    只在 native 层打了一行 ALOGE 就换了 FFmpeg）—— 这是要修的那一种；
         *  - `false` + [decoder] 是软解 ⇒ 这台设备**根本没有**可用的硬解解码器
         *    （或这个编码不交给 MediaCodec），不是故障。
         */
        val codecOffered: Boolean,
        /** 本次运行里硬解静默退回软解的次数。 */
        val silentFallbacks: Int,
        /** 本次运行里因为**没建成**而被禁用的解码器名。 */
        val bannedCodecs: List<String>,
    )

    fun diag(): Diag

    /**
     * 一份用于判断「播放器还活着吗」的快照。
     *
     * 为什么不是单一指标：在 SMB + `IMediaDataSource` 通路上，
     * **`currentPosition` 实测恒为 0**，而输出帧率在软解通路上也没有意义
     * （`PlayerLivenessTest` 钉的就是这件事），拿任何一个单独做判据都会失效。
     * 所以把几个不同来源的计数器一起取回来，让调用方用「全都长时间不变」来判定。
     */
    class Liveness(
        /** 已缓存的视频时长（毫秒）。读取线程还活着时它会增长。 */
        val videoCachedMs: Long,
        /** 累计读取字节数。 */
        val trafficBytes: Long,
        val positionMs: Long,
        val outputFps: Float,
    ) {
        /** 和另一份快照相比，有没有任何一个计数器在动。 */
        fun differsFrom(o: Liveness?): Boolean {
            if (o == null) return true
            return videoCachedMs != o.videoCachedMs ||
                trafficBytes != o.trafficBytes ||
                positionMs != o.positionMs ||
                outputFps != o.outputFps
        }
    }

    fun liveness(): Liveness

    fun seekTo(ms: Long)
    fun pause()
    fun resume()
    fun stop()
    fun release()

    /**
     * 还能不能「退回软解重播」。
     *
     * 仅当**本次起播正在用硬解**、而且还有上一次的起播请求可重放时为 true。
     * 界面层的起播看门狗拿到 true 就该调 [retryInSoftware]，而不是直接出故障页 ——
     * 有些电视的 MediaCodec 建得出来、却一帧都不吐，这时软解仍然能看。
     */
    fun canFallbackToSoftware(): Boolean = false

    /**
     * 用软解把同一份内容重播一遍。
     *
     * @return true = 已经重新起播；false = 没东西可重播（或已经在软解上跑了）
     */
    fun retryInSoftware(): Boolean = false
}

/**
 * ijkplayer 实现。目标设备是 2016 年的 Android 5.1 电视，系统硬解不可靠；
 * ijkplayer 自带完整 FFmpeg 软解兜底，且支持 RMVB/TS/FLV（DESIGN §6）。
 *
 * **解码策略是「硬解优先、软解兜底」**：不打开硬解时 ijkplayer 一律软解，
 * 4K H.265 在这台电视上就是几百毫秒一帧（用户实测「极慢 + 没声音」）。
 * 详见 [VideoDecodePolicy]。
 */
class IjkPlaybackEngine(private val context: Context) : PlaybackEngine {

    private var player: IjkMediaPlayer? = null
    private var dataSource: SmbMediaDataSource? = null
    private var listener: PlaybackEngine.Listener? = null

    private var surface: Surface? = null
    private var pendingSeekMs = 0L
    private var kind: PlaybackMode.Kind = PlaybackMode.Kind.ON_DEMAND

    private var liveReconnect = false
    private var liveUrlProvider: (() -> String?)? = null
    private var retryCount = 0

    /**
     * 覆盖探测窗口（`probesize` / `analyzeduration`），null 表示用 [PlaybackMode.tuning] 的默认值。
     *
     * 给排查用：流参数探测失败（表现为「有画面没声音」）时，
     * 需要单独把窗口放大来区分「窗口不够」和「时间戳有问题」这两种原因。
     */
    private var probeOverride: Pair<Long, Long>? = null

    fun setProbeOverride(probesizeBytes: Long, analyzeDurationUs: Long) {
        probeOverride = probesizeBytes to analyzeDurationUs
    }

    /**
     * 起播参数（缓冲、追帧、硬解）都在这里一次性落给 ijkplayer。
     *
     * 这些值是实测定下来的（长虹 43Q3T / MT5891 / 1080p 面板，2026-09）：
     * 4K **H.264** 能满帧（3840×2160@50 送显中位 50.0），4K **HEVC** 顶多 ~18 帧/秒
     * （这颗芯片 HEVC 解码块约 150 Mpx/秒封顶，见 `PlaybackVerdict.HEVC_DECODE_MPX`）。
     * 当时为了量这件事做过一整套运行时可调旋钮 + 局域网实验台（12 档预设 ×
     * 格式/层级/队列/丢帧/线程优先级），结论是**这些开关对送显帧率全都没有影响**，
     * 所以最终版把它们全部移除、只保留实测确认过的这套默认值
     * （完整实验台保留在 git 分支 `diag-experiment-snapshot`）。
     *
     * ## 为什么这些选项必须在这里读
     *
     * ijkplayer 的 `setOption` **只在 `prepareAsync()` 之前**被读取
     * （`ff_ffplay.c` 的 `ijkmp_set_option` 写进字典，prepare 时一次性 `av_opt_set_dict`），
     * 所以任何参数改动都要靠**重播**生效，不能指望运行中改一下就有效果。
     */

    private val main = Handler(Looper.getMainLooper())

    override fun setListener(l: PlaybackEngine.Listener) {
        listener = l
    }

    /**
     * 首帧只上报一次。
     *
     * ijkplayer 会在每次分辨率变化时重发 `VIDEO_RENDERING_START`
     * （HLS 换分片很常见），不去重的话上层会反复收到「首帧」，
     * 每次都去撤看门狗、刷界面。
     */
    private var firstFrameFired = false

    /** 是否已经知道视频尺寸（= 解码器已就绪，但**不代表**出过帧）。 */
    private var sizeKnown = false

    private fun fireFirstFrame() {
        if (firstFrameFired) return
        firstFrameFired = true
        cancelHardwareWatchdog()
        // 首帧出来才开始盯「播放中卡死」：起播阶段由界面层的 25 秒看门狗负责
        armStallWatchdog()
        onMain { listener?.onFirstFrame() }
    }

    override fun attach(surface: Surface) {
        this.surface = surface
        player?.setSurface(surface)
    }

    override fun detachSurface() {
        player?.setSurface(null)
        surface = null
    }

    // ---- 起播请求 ----
    //
    // 记下「这一次要播什么」，硬解失败时才能用软解把同一份内容重播一遍。
    // 字节源不能直接复用（SmbMediaDataSource.close() 会把 SMB 句柄关掉），
    // 所以存的是**重开方式**而不是已经打开的对象。

    private sealed class LastRequest {
        abstract val startMs: Long

        class Smb(val cfg: Config.Smb, val path: String, override val startMs: Long) : LastRequest()

        class Source(val factory: () -> RandomAccessSource, override val startMs: Long) : LastRequest()

        class Url(val url: String) : LastRequest() {
            override val startMs: Long get() = 0L
        }
    }

    private var lastRequest: LastRequest? = null

    /** 本次起播是否跑在硬解上。 */
    private var usingHardware = false

    /**
     * 这次起播**请求过**硬解（不受后续回落影响）。
     * 面板要区分「想硬解但没建成」和「这台设备本来就没有硬解」，判据就是它 +
     * [DecoderPath]（见 [PlaybackEngine.Diag.codecOffered]）。
     */
    private var hardwareRequested = false

    /**
     * 本次运行里已经证实「选中了，但实际没接上」的解码器名。
     *
     * 为什么需要：ijkplayer 硬解失败是**静默回落软解**的（见 [decoderInUse]），
     * 每次都白试同一个坏解码器，永远退不到「次优但能用」的那个。
     * 拉黑之后 [MediaCodecChoice.pick] 会自动选下一个候选。
     * 只在进程内有效（重启应用重新评估）—— 解码器不会因为重启就变好或变坏，
     * 但**设备状态**会（换片源、系统更新），所以不做持久化。
     */
    private val bannedCodecs = mutableSetOf<String>()

    /** 硬解静默退回软解的次数（诊断面板显示用）。 */
    @Volatile
    private var silentFallbacks = 0

    /**
     * 硬失败（解码器报错 / 界面层判定卡死）的次数。
     *
     * 这类失败说明**这台设备的硬解这条路真的不通**，达到
     * [VideoDecodePolicy.MAX_FAILURES] 就本次运行不再试，免得每集都白等一个超时。
     */
    private var hardFailures = 0

    /**
     * 超时失败（建了解码器却不出首帧）的次数。
     *
     * 和 [hardFailures] 分开计数，是因为它**可能是冤枉的**：
     * 片源在 SMB 上打开得慢、moov 要搬到头部、探流窗口 16MB，
     * 这些都可能让首帧晚到，而解码器本身没问题。
     * 所以它只对**当前这一份内容**生效，换内容（下一集/换剧/换台）就重新给硬解机会。
     * 用户报的「电视上硬解好像没开」，最怕的就是一次误判把硬解永久关掉。
     */
    private var softFailures = 0

    /** 当前内容的标识，用来判断「是不是换内容了」。 */
    private var contentKey: String? = null

    /**
     * 换了内容就重新给硬解机会。
     *
     * 只有**超时类**失败会因此归零；真报错的硬失败要累计到 MAX_FAILURES 才关
     * （那种情况每集都重试就是「每集黑屏十几秒」）。
     */
    private fun noteContent(key: String) {
        if (key == contentKey) return
        contentKey = key
        if (softFailures > 0) {
            Log.i(TAG, "换了内容，超时计数 $softFailures → 0，重新给硬解一次机会")
            softFailures = 0
        }
    }

    /**
     * 视频解码实际走的通路 —— 「电视上还是很慢」这类问题需要它给出确定答案，
     * 靠猜（`DISABLE_HW` 有没有生效、这台电视有没有硬解）都不可靠。
     *
     * 判据是 ijkplayer 的 MediaCodec 选码回调**有没有被调用**：
     * 只有真的要建 MediaCodec 解码器时它才会被问。
     */
    enum class DecoderPath {
        /** 没被问过 —— 硬解选项没生效，或者还没起播。 */
        NOT_ASKED,

        /** 选了 MediaCodec 解码器，名字见 [IjkPlaybackEngine.decoderName]。 */
        MEDIACODEC,

        /** 问过了，但这台设备没有可用的硬解解码器（已退回软解）。 */
        NO_CODEC,
    }

    @Volatile
    private var decoderPath: DecoderPath = DecoderPath.NOT_ASKED

    @Volatile
    private var decoderName: String? = null

    /** 给插桩测试与真机排查用（logcat 之外的第二条证据）。 */
    fun decoderPath(): DecoderPath = decoderPath

    /** 走硬解时用的是哪个解码器；否则 null。 */
    fun decoderName(): String? = decoderName

    private fun wantHardware(): Boolean =
        VideoDecodePolicy.canTryHardware(hardFailures) && VideoDecodePolicy.canTryHardware(softFailures)

    override fun playSmb(cfg: Config.Smb, relativePath: String, startMs: Long) {
        kind = PlaybackMode.Kind.ON_DEMAND
        lastRequest = LastRequest.Smb(cfg, relativePath, startMs)
        noteContent("smb:$relativePath")
        startPlayback(wantHardware())
    }

    override fun setMode(kind: PlaybackMode.Kind) {
        this.kind = kind
    }

    override fun playSource(source: RandomAccessSource, startMs: Long) =
        playSource({ source }, startMs)

    override fun playSource(open: () -> RandomAccessSource, startMs: Long) {
        lastRequest = LastRequest.Source(open, startMs)
        noteContent("src:$startMs")
        startPlayback(wantHardware())
    }

    override fun playUrl(url: String) {
        kind = PlaybackMode.Kind.LIVE
        lastRequest = LastRequest.Url(url)
        noteContent("url:$url")
        startPlayback(wantHardware())
    }

    /**
     * 按最后一次请求起播。
     *
     * @param hardware 是否让 MediaCodec 接管视频解码；false = 纯 FFmpeg 软解
     */
    private fun startPlayback(hardware: Boolean) {
        val req = lastRequest ?: return
        releaseInternal()
        usingHardware = hardware
        hardwareRequested = hardware
        // URL 请求要把地址交给 newPlayer：直播的超时选项跟传输协议绑在一起
        //（RTSP 的 `timeout` 是秒、还兼作保活间隔，见 newPlayer 里的说明）。
        val p = newPlayer(hardware, (req as? LastRequest.Url)?.url)
        // Surface 必须在 prepareAsync 之前挂上：MediaCodec 解码器是在准备阶段建的，
        // 那一刻没有 Surface 的话 ijkplayer 会建一个**假的**解码器（收数据、不出画面），
        // 之后再 setSurface 只能靠重新配置解码器补救。正常路径上 Surface 早就有了
        // （playEpisode 会先查 surfaceUsable），这里只是把顺序钉死。
        surface?.let { p.setSurface(it) }

        when (req) {
            is LastRequest.Url -> {
                pendingSeekMs = 0
                p.setDataSource(req.url)
            }

            is LastRequest.Smb -> {
                // 非 faststart 的 mp4 由 SmbMediaDataSource 内部负责把 moov 搬到头部（DESIGN 风险 8）
                val src = openOrReport("打不开这一集") {
                    SmbMediaDataSource(SmbRandomAccessSource(req.cfg, req.path))
                } ?: return
                dataSource = src
                // 直播的进度没有意义，绝不能拿着一个假的毫秒数去 seek（见 PlaybackMode 注释）
                pendingSeekMs = PlaybackMode.startPositionMs(kind, req.startMs)
                p.setDataSource(src)
            }

            is LastRequest.Source -> {
                val src = openOrReport("打不开这个文件") {
                    SmbMediaDataSource(req.factory())
                } ?: return
                dataSource = src
                pendingSeekMs = PlaybackMode.startPositionMs(kind, req.startMs)
                p.setDataSource(src)
            }
        }
        p.prepareAsync()
    }

    /**
     * 建字节源时抛异常 → 走故障页，**不要让它冒到调用方**。
     *
     * `FileRandomAccessSource` / `SmbRandomAccessSource` 都是在构造函数里就把句柄打开的，
     * 所以「文件被删了」「NAS 掉线了」这类事是在这一行**同步**炸出来的。
     * 以前这个异常会一路冒到 MainActivity 的 Handler（或主线程）上：
     *  - 后台线程：UncaughtExceptionHandler 直接**杀进程**；
     *  - 主线程：同样崩。
     * 用户看到的不是「打不开这一集」，而是应用整个消失 —— 对一个给老人用的电视应用
     * 来说这是最糟的失败方式。
     *
     * 现在统一在这里兜住：出一句人话，交给故障页去重试。
     */
    private fun <T> openOrReport(what: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "$what：${t.javaClass.simpleName}: ${t.message}")
        releaseInternal()
        onMain { listener?.onError(what, fatal = false) }
        null
    }

    override fun setLiveReconnect(on: Boolean, urlProvider: (() -> String?)?) {
        liveReconnect = on
        liveUrlProvider = urlProvider
        if (on) retryCount = 0
    }

    private fun newPlayer(hardware: Boolean, url: String?): IjkMediaPlayer {
        // 每次起播都要重置：这几个标志描述的都是「当前这一次播放」。
        // 不清掉的话第二次起播会永远收不到首帧（被 firstFrameFired 挡住），
        // 解码通路的诊断结论也会停在上一集上。
        firstFrameFired = false
        sizeKnown = false
        decoderPath = DecoderPath.NOT_ASKED
        decoderName = null
        watchdogGrace = 0
        cachedMediaInfo = null
        cancelHardwareTruthCheck()
        // 直播源的传输协议决定超时选项怎么给（单位/语义都不同，见下面 setOption 处）
        val transport = url?.let { PlaybackMode.Transport.of(it) } ?: PlaybackMode.Transport.HTTP
        val p = IjkMediaPlayer()
        p.setOnPreparedListener(object : tv.danmaku.ijk.media.player.IMediaPlayer.OnPreparedListener {
            override fun onPrepared(mp: tv.danmaku.ijk.media.player.IMediaPlayer) {
                retryCount = 0
                surface?.let { mp.setSurface(it) }
                if (pendingSeekMs > 0) {
                    mp.seekTo(pendingSeekMs)
                    pendingSeekMs = 0
                }
                mp.start()
                val duration = mp.duration
                onMain { listener?.onPrepared(duration) }
            }
        })
        p.setOnCompletionListener {
            if (liveReconnect) {
                // 直播"完成"通常是断流
                scheduleLiveRetry()
            } else {
                onMain { listener?.onCompletion() }
            }
        }
        p.setOnErrorListener { _, what, extra ->
            handleError(what, extra)
            true
        }
        p.setOnInfoListener { _, what, _ ->
            when (what) {
                tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START ->
                    fireFirstFrame()

                tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START -> {
                    Log.i(TAG, "音频已开始输出（AUDIO_RENDERING_START）")
                    onMain { listener?.onAudioStarted() }
                }

                // 下面几条只为排查留痕：真机上「没声音」「没画面」时，
                // 这几行能直接说明是「解码器没打开」还是「打开了但没送数据」。
                tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_AUDIO_DECODED_START ->
                    Log.i(TAG, "音频首帧已解出（AUDIO_DECODED_START）")

                tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_VIDEO_DECODED_START ->
                    Log.i(TAG, "视频首帧已解出（VIDEO_DECODED_START）")

                tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_BUFFERING_START ->
                    Log.i(TAG, "开始缓冲")

                tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_BUFFERING_END ->
                    Log.i(TAG, "缓冲结束")
            }
            false
        }
        // ⚠️ 这里**不能**当成首帧。
        //
        // 原来这一行也调 onFirstFrame()，是个真实存在过的 bug：
        // `MEDIA_INFO_VIDEO_SIZE_CHANGED` 在**准备阶段**就会触发 —— 解码器刚拿到
        // 分辨率就报，此时一帧都还没解出来。而 onFirstFrame 的第一件事是撤掉
        // 起播看门狗，于是看门狗在画面真正出来之前就被撤了。
        //
        // 后果：如果解码器随后卡住（例如模拟器软解不动 4K H.265），
        // 就再也没有任何东西会报故障，界面永远冻在最后一帧 ——
        // 正是用户报的「切换其他电视剧卡死」。
        //
        // 现在只记下尺寸，首帧必须由 VIDEO_RENDERING_START 触发。
        p.setOnVideoSizeChangedListener { _, _, _, _, _ ->
            sizeKnown = true
        }
        // 硬解到底有没有被用上、用的是哪个解码器，只有这一行日志能回答。
        // 真机上排查「还是很慢」时先看它：没有这行 = 根本没走 MediaCodec 通路。
        //
        // ⚠️ 这里返回一个非空的名字**不等于硬解建成了** —— ijkplayer 在后面
        // reconfigure/configure 失败时会静默改用 FFmpeg 软解（见 [decoderInUse]）。
        // 真值要等起播以后问 [decoderInUse]（[hardwareTruthCheck]）。
        p.setOnMediaCodecSelectListener { _, mime, profile, level ->
            val chosen = MediaCodecChoice.choose(mime, profile, level, bannedCodecs)
            decoderName = chosen
            decoderPath = if (chosen.isNullOrBlank()) DecoderPath.NO_CODEC else DecoderPath.MEDIACODEC
            Log.i(
                TAG,
                "硬解选码器 mime=$mime profile=$profile level=$level -> " +
                    (chosen ?: "没有可用的硬解解码器，改用软解"),
            )
            if (chosen.isNullOrBlank()) {
                // 这台设备没有可用硬解（或这个编码/档次不交给 MediaCodec），实际跑的是软解：
                // 既不该算「硬解失败」，更不该给它挂「硬解卡住」的超时 ——
                // 软解本来就慢（模拟器上 4K 首帧要二三十秒），拿硬解的超时去催它
                // 只会把一次正常播放变成「重播 + 故障页」。
                usingHardware = false
                cancelHardwareWatchdog()
            } else {
                // 真的开始建硬解解码器了，这时才值得给它一个首帧超时
                armHardwareWatchdog()
                armHardwareTruthCheck()
            }
            chosen
        }

        // 老人用：宁可轻微丢帧也不要黑屏卡住（默认值；下面那批远程旋钮可以覆盖它）
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
        // 直播/点播各自一套参数；直播还要按传输协议再分一档（RTSP/组播不是 HLS）
        val t = PlaybackMode.tuning(kind, transport)
        val probe = probeOverride
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", if (t.packetBuffering) 1L else 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", t.maxBufferBytes)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", probe?.first ?: t.probesizeBytes)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", probe?.second ?: t.analyzeDurationUs)
        // 解码策略：硬解优先、软解兜底（4K H.265 必须走硬解，见 VideoDecodePolicy）
        for (o in VideoDecodePolicy.options(hardware)) {
            p.setOption(categoryOf(o.category), o.name, o.value)
        }
        // 网络层：关掉 ijkplayer 那份「只按主机名做键」的 DNS 缓存。
        // 不关的话：央视直播地址（:82 重定向到 :81 带 token 的地址）在换台
        // 第二次开始必定 403，且进程内永不恢复（见 VideoDecodePolicy.NETWORK_OPTIONS）
        for (o in VideoDecodePolicy.NETWORK_OPTIONS) {
            p.setOption(categoryOf(o.category), o.name, o.value)
        }
        // 内嵌中文字幕轨优先，无中文则整个不显示（DESIGN §5）
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1L)

        // ---- 让「流断了」变成一次明确的报错，而不是静默僵死 ----
        //
        // 起因：用户报「卡住」。抓线程栈发现 ijkplayer 的线程全部消失、
        // 进程 CPU 增量为 0，但**既没有 onError 也没有 onCompletion** ——
        // 因为底层的 socket 读默认是无限等待的，对方不再发数据时就永远挂着。
        // 界面层唯一能自救的两个信号都没来，于是画面冻在最后一帧再也不动。
        //
        // 给底层 I/O 设上限，超时就会走 onError → 界面重连。
        // 15 秒是权衡：太短会把正常的缓冲抖动误判成断流。
        //
        // ⚠️ 但 `timeout` 的单位**随协议而变**，这里必须分开写 —— 见下面那一大段。
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rw_timeout", IO_TIMEOUT_US)
        if (kind == PlaybackMode.Kind.LIVE) {
            when (transport) {
                PlaybackMode.Transport.HTTP -> {
                    // HLS：HTTP 的 `timeout` 就是微秒，和 rw_timeout 同一个量纲
                    p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", IO_TIMEOUT_US)
                    p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http_persistent", 0L)
                    p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "reconnect", 1L)
                }

                PlaybackMode.Transport.RTSP -> {
                    // ## 这两行是「IPTV 直播播不了 / 播一段卡住」的修复点
                    //
                    // 1) `rtsp_transport = tcp`
                    //    ijkplayer 默认走 UDP，而运营商这套 IPTV 拒绝 UDP 的 SETUP
                    //    （回 `405 Method Not Allowed`）。ffmpeg 3.4 本来有一条
                    //    「UDP 超时 → 改用 TCP」的回退，实测在这条通路上触发不了，
                    //    表现是每个频道都在 `could not find codec parameters` 上死掉。
                    //    直接指定 TCP 就绕开了整条回退路径，换台也不用先白等一次 UDP 超时。
                    //    （RTSP 服务端基本都支持 TCP 交织传输，代价只是延迟略高一点点。）
                    p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp")
                    // 2) 超时用 `stimeout`（**微秒**，给 socket 读写）
                    //    RTSP 的 `timeout` 单位是**秒**，而且是保活间隔的来源
                    //    （`rtspdec.c`：`>= rt->timeout / 2` 才发 GET_PARAMETER）。
                    //    原来这里塞的是 `IO_TIMEOUT_US`，被当成 15000000 秒 ≈ 173 天，
                    //    等于把保活关掉了 —— 服务端会认为客户端已经死了，中途掐流。
                    //    所以对 RTSP 绝不能再设 `timeout`，只设 `stimeout`。
                    p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "stimeout", IO_TIMEOUT_US)
                }

                PlaybackMode.Transport.UDP -> {
                    // 组播是**无连接**的：`rw_timeout` 对 UDP 套接字不生效，
                    // 也没有 RTSP 那种保活。流停了不会自己报错，只能靠引擎的
                    // 播放中存活看门狗（见 [stallWatchdog]）来发现。
                    // `timeout` 对 UDP 是「等待入向连接」的秒数，给个有限值即可。
                    p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", UDP_TIMEOUT_SECONDS)
                }
            }
        } else {
            // 点播走 SMB，`timeout` 用不上；保留它只为不让老行为变化
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", IO_TIMEOUT_US)
        }

        player = p
        // 硬解看门狗**不在这里挂**：此刻还不知道会不会真的走 MediaCodec。
        // 要等选码回调告诉我们结果（见上面的 setOnMediaCodecSelectListener）：
        // 选了硬解才挂，没选到就当软解慢慢跑，不催它。
        return p
    }

    private fun categoryOf(c: VideoDecodePolicy.Category): Int = when (c) {
        VideoDecodePolicy.Category.PLAYER -> IjkMediaPlayer.OPT_CATEGORY_PLAYER
        VideoDecodePolicy.Category.FORMAT -> IjkMediaPlayer.OPT_CATEGORY_FORMAT
        VideoDecodePolicy.Category.CODEC -> IjkMediaPlayer.OPT_CATEGORY_CODEC
    }

    // ---- 硬解兜底 ----
    //
    // 为什么需要它：有些电视（尤其是 Android 5.x 的老机型）的 MediaCodec
    // **建得出来、却一帧都不吐**，而且不报错 —— 画面永远黑的，也没有 onError。
    // 对老人来说这就是「电视坏了」。软解虽然慢，但至少能看，
    // 所以硬解卡住要能自己退回去，而不是把故障页甩给用户。

    /** 一个超时周期内解码器有没有真的拿到数据。 */
    private fun decoderHasData(): Boolean {
        val p = player ?: return false
        return runCatching {
            p.videoCachedBytes > 0 || p.videoCachedDuration > 0
        }.getOrDefault(false)
    }

    /** 首帧看门狗宽限了几次（见 [hardwareWatchdog]）。 */
    private var watchdogGrace = 0

    private val hardwareWatchdog = Runnable {
        if (firstFrameFired || !usingHardware) return@Runnable
        // 解码器还没拿到数据就先别怪它：SMB 打开 + 探流（probesize 16MB）本来就要几秒，
        // 这段时间「没出首帧」是正常的。宽限两次，再没有才判定硬解不行。
        // 不加这一层的话，一次网络抖动就会把硬解判死（而这台电视上硬解是 4K 唯一的活路）。
        if (watchdogGrace < WATCHDOG_GRACE && !decoderHasData()) {
            watchdogGrace++
            Log.i(TAG, "硬解还没拿到数据，首帧超时先宽限一次（第 $watchdogGrace 次）")
            armHardwareWatchdog()
            return@Runnable
        }
        fallbackToSoftware("硬解 ${VideoDecodePolicy.FIRST_FRAME_TIMEOUT_MS / 1000} 秒没出首帧")
    }

    /**
     * 「选中了硬解、实际却在软解」的检测 —— 这是用户报「电视的硬解没开」的**唯一**可靠证据。
     *
     * ijkplayer 在建 MediaCodec 失败时**静默换 FFmpeg 软解**，一个回调都不发
     * （见 [decoderInUse] 的说明）。不查这一下，界面上永远显示「走的是硬解」，
     * 而用户看到的是 4K 幻灯片 —— 排查方向会完全跑偏。
     *
     * 查到之后：把那个解码器拉黑（下次自动换一个候选），
     * 但**不重播**（已经跑在软解上了，重播没有意义）。
     */
    private val hardwareTruthCheck = Runnable {
        if (!usingHardware) return@Runnable
        when (decoderInUse()) {
            PlaybackEngine.Decoder.HARDWARE -> Log.i(
                TAG,
                "解码通路确认：硬解 ${decoderName}（vdec_type=2）",
            )

            PlaybackEngine.Decoder.SOFTWARE -> {
                usingHardware = false
                silentFallbacks++
                val name = decoderName
                if (!name.isNullOrBlank()) bannedCodecs.add(name)
                Log.w(
                    TAG,
                    "硬解没建成：选中的 ${name ?: "?"} 实际没接上，ijkplayer 已经静默改用软解" +
                        "（vdec_type=1）。本次运行不再选它，下次自动换下一个候选",
                )
            }

            PlaybackEngine.Decoder.UNKNOWN -> Log.w(TAG, "解码通路还问不出来（播放器可能已经释放）")
        }
    }

    /**
     * 兜底重播的专用工作线程。
     *
     * 为什么必须离开主线程：重播要**重新打开字节源**（SMB 打开是阻塞的网络操作）。
     * 而触发兜底的两条路都在主线程上 —— 硬解看门狗是主线程的 Handler，
     * 界面层的 `retryInSoftware()` 也在主线程。在主线程上做 SMB I/O 会直接抛
     * `NetworkOnMainThreadException`，就算不抛也会把整屏卡住。
     *
     * 线程按需创建、不主动 quit：`release()` 之后引擎还会被复用
     * （例如界面层「自动重连」就是先 release 再重播），quit 掉会让后续
     * `post` 静默失败。一个引擎实例最多留一个空转的 looper 线程。
     */
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    private fun onWorker(block: () -> Unit) {
        val h = synchronized(this) {
            val alive = workerThread?.isAlive == true
            if (!alive) {
                val t = HandlerThread("firefly-decode")
                t.start()
                workerThread = t
                worker = Handler(t.looper)
            }
            worker
        }
        if (h?.post(block) != true) Log.w(TAG, "兜底重播没能排上工作线程")
    }

    private fun armHardwareWatchdog() {
        cancelHardwareWatchdog()
        watchdogGrace = 0
        main.postDelayed(hardwareWatchdog, VideoDecodePolicy.FIRST_FRAME_TIMEOUT_MS)
    }

    private fun cancelHardwareWatchdog() {
        main.removeCallbacks(hardwareWatchdog)
    }

    private fun armHardwareTruthCheck() {
        cancelHardwareTruthCheck()
        main.postDelayed(hardwareTruthCheck, TRUTH_CHECK_DELAY_MS)
    }

    private fun cancelHardwareTruthCheck() {
        main.removeCallbacks(hardwareTruthCheck)
    }

    /**
     * 硬解不行了：记一笔、拉黑这个解码器，然后用软解把同一份内容重播。
     *
     * @param hard true = 解码器真的报错/卡死（按 [VideoDecodePolicy.MAX_FAILURES] 累计，
     *   累计够数就本次运行不再试硬解）；false = 首帧超时（只对当前内容生效，
     *   换内容会重新给机会，见 [softFailures]）
     * @return true = 已经重新起播
     */
    private fun fallbackToSoftware(reason: String, hard: Boolean = false): Boolean {
        if (!usingHardware) return false
        usingHardware = false
        cancelHardwareWatchdog()
        cancelHardwareTruthCheck()
        if (hard) hardFailures++ else softFailures++
        // 这次没接上的解码器拉黑：下次自动换下一个候选，而不是再撞一次同一面墙
        val banned = decoderName
        if (!banned.isNullOrBlank()) bannedCodecs.add(banned)
        val remaining = VideoDecodePolicy.MAX_FAILURES - hardFailures
        Log.w(
            TAG,
            "硬解失败（$reason）：${banned ?: "?"} 已拉黑。" +
                "硬失败 $hardFailures 次、超时 $softFailures 次，" +
                if (remaining > 0) "改用软解重播" else "本次运行不再尝试硬解，改用软解重播",
        )
        if (lastRequest == null) return false
        // 先告诉界面层（它要把起播看门狗重新起算），再去重新打开字节源
        onMain { listener?.onDecoderFallback(reason) }
        onWorker { startPlayback(hardware = false) }
        return true
    }

    override fun canFallbackToSoftware(): Boolean = usingHardware && lastRequest != null

    override fun retryInSoftware(): Boolean = fallbackToSoftware("界面层判定起播卡死", hard = true)

    /**
     * 所有对外回调都必须回到主线程。
     *
     * ijkplayer 的 onPrepared / onError / onCompletion / onInfo 是在它自己的
     * `IjkMediaPlayer$EventHandler` 线程上触发的。直接透传给界面层的话，
     * 界面会在非 UI 线程上碰 View —— 抛 `CalledFromWrongThreadException` 当场崩，
     * 而且 Activity 重建后立刻再报同样的错，表现就是「闪退之后再也打不开」。
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post(block)
    }

    private fun handleError(what: Int, extra: Int) {
        // 解码器报错（不是 I/O）而且正在用硬解 → 先软解重播，别打扰用户。
        // 判据里的 I/O 例外很重要：SMB 断线也会走到这里，
        // 那种情况下重播一次只是白等，应该直接交给故障页去重连。
        if (usingHardware && extra != tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_ERROR_IO) {
            if (fallbackToSoftware("解码器报错 what=$what extra=$extra", hard = true)) return
        }
        // 「正在播直播」是走这条兜底的前提。
        //
        // 只看 liveReconnect 是不够的：界面层换到直播时会把它置 true，
        // 但**从来没有置回 false**（见 MainActivity.playChannel）。少了 kind 这一半，
        // 用户「先看直播、再回电视剧」以后，某一集 SMB 读取失败也会被当成直播断流 ——
        // 于是去重连一个根本没在播的频道地址，该出的故障页永远不出来。
        val liveUrl = if (kind == PlaybackMode.Kind.LIVE) liveUrlProvider?.invoke() else null
        if (liveReconnect && liveUrl != null) {
            // 直播断流由引擎自己一直重连，但**必须让界面层知道**：
            // 以前这里直接 return，界面上什么都不显示，用户看到的就是
            // 「换台以后画面冻住、按键有反应、永远不好」（见 Listener.onLiveRetry）
            onMain { listener?.onLiveRetry(retryCount + 1, liveUrl) }
            scheduleLiveRetry()
            return
        }
        val fatal = what == tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_ERROR_UNSUPPORTED ||
            what == tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_ERROR_MALFORMED ||
            extra == tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_ERROR_IO
        onMain { listener?.onError("这个视频无法播放", fatal) }
    }

    /** 断流自动重连：2 秒起退避，最多退到 10 秒，永不放弃（DESIGN §8）。 */
    private fun scheduleLiveRetry() {
        if (!liveReconnect) return
        releaseInternal()
        retryCount++
        val delay = minOf(2000L * retryCount, 10_000L)
        main.postDelayed(retryRunnable, delay)
    }

    // ---- 播放中存活看门狗（「播一段就卡住」的正解）----
    //
    // ## 为什么超时选项救不了这一类故障
    //
    // 上面给 socket 设了 `rw_timeout` / `stimeout`，但它们只能覆盖
    // 「**socket 报错**」和「**对端关闭连接**」这两种情况。
    // 直播真正难处理的是第三种：**TCP 连接好好挂着，对端就是不再发数据了**。
    // 这时 socket 读永远等不到错误，`MEDIA_INFO_BUFFERING_START` 也不会来
    // （ijkplayer 只在缓存被读空时才报缓冲，而它此时正卡在等包上）。
    // 于是：既没有 onError 也没有 onCompletion，画面冻在最后一帧，**永远不会自己好**。
    // 界面层那个 25 秒看门狗只管起播（首帧出来就撤了），也帮不上忙。
    //
    // 组播（`udp://`）更彻底：UDP 无连接，上面那些超时选项对它根本没有意义。
    //
    // ## 判据（这里刻意**不用** [Liveness.differsFrom]）
    //
    // 乍看 `differsFrom` 正合适，其实不行：它把 `outputFps` 也拿 `!=` 比，
    // 而 `outputFps` 是个**速率**、不是累计计数器 —— 流卡死时它只是在 0 附近抖动，
    // 相邻两次采样几乎必然不相等。拿它当判据的话，「有动静」永远成立，
    // 看门狗一辈子都不会触发。
    //
    // 所以只认一个单调量：**送显帧率**。真在播就有几十帧/秒，
    // 真的卡死才会长时间贴近 0；抖动传不过 [STALL_MIN_FPS] 这个门槛。
    private var stallStrikes = 0

    // 注意：这里**不能**在 lambda 里直接重挂 `stallWatchdog` 自己 ——
    // 属性初始化期间引用自身，Kotlin 会报「Variable 'stallWatchdog' must be initialized」。
    // 和 [hardwareWatchdog] 一样，把重挂放进一个私有方法里。
    private val stallWatchdog = Runnable {
        // 只管直播；点播有界面层的卡死判据，别在这里抢
        if (kind != PlaybackMode.Kind.LIVE || !liveReconnect) return@Runnable
        val p = player ?: return@Runnable
        val live = runCatching { liveness() }.getOrDefault(PlaybackEngine.Liveness(0, 0, 0, 0f))

        // 「没在播」不算卡死：暂停、或者正在缓冲起播，都不该被判定为断流。
        // 注意这里**不能**碰 stallStrikes —— 缓冲抖动会让 isPlaying 短暂变 false，
        // 一旦清零就永远攒不满，看门狗等于没有。
        if (!runCatching { p.isPlaying }.getOrDefault(false)) {
            armStallWatchdog()
            return@Runnable
        }

        if (live.outputFps > STALL_MIN_FPS) {
            stallStrikes = 0
        } else {
            stallStrikes++
            Log.w(
                TAG,
                "直播卡死判据：送显 ${"%.2f".format(live.outputFps)} 帧/秒（低于 " +
                    "$STALL_MIN_FPS），已持续约 ${(stallStrikes * STALL_RECHECK_MS) / 1000} 秒" +
                    "（缓存 ${live.videoCachedMs}ms，流量 ${live.trafficBytes}B，第 $stallStrikes 次）",
            )
            if (stallStrikes >= STALL_MAX_STRIKES) {
                Log.w(TAG, "直播画面已经不动了，走重连（第 ${retryCount + 1} 次）")
                stallStrikes = 0
                onMain { listener?.onLiveRetry(retryCount + 1, liveUrlProvider?.invoke()) }
                scheduleLiveRetry()
                return@Runnable
            }
        }
        armStallWatchdog()
    }

    private fun armStallWatchdog() {
        main.removeCallbacks(stallWatchdog)
        // 刚出首帧先别急着量：这一刻缓存本来就在剧烈变化，等一个周期再开始比
        main.postDelayed(stallWatchdog, STALL_RECHECK_MS)
    }

    private fun cancelStallWatchdog() {
        main.removeCallbacks(stallWatchdog)
        stallStrikes = 0
    }

    private val retryRunnable = Runnable {
        val url = liveUrlProvider?.invoke() ?: return@Runnable
        playUrl(url)
    }

    override fun isPlaying(): Boolean = player?.isPlaying == true

    override fun positionMs(): Long = player?.currentPosition ?: 0L

    override fun durationMs(): Long = player?.duration ?: 0L

    override fun outputFps(): Float = runCatching {
        player?.videoOutputFramesPerSecond ?: 0f
    }.getOrDefault(0f)

    // ijkplayer 的 ff_ffmsg.h：0=UNKNOWN / 1=AVCODEC(软解) / 2=MEDIACODEC(硬解)
    override fun decoderInUse(): PlaybackEngine.Decoder = when (runCatching {
        player?.videoDecoder ?: 0
    }.getOrDefault(0)) {
        FFP_PROPV_DECODER_MEDIACODEC -> PlaybackEngine.Decoder.HARDWARE
        FFP_PROPV_DECODER_AVCODEC -> PlaybackEngine.Decoder.SOFTWARE
        else -> PlaybackEngine.Decoder.UNKNOWN
    }

    /**
     * `getMediaInfo()` 会顺带解析一遍 media meta（几百个字段），
     * 一秒一次没必要 —— 起播后解码器名不会变，缓存一份就够。
     *
     * ⚠️ 但**片源帧率拿不到时不能钉死**：起播后头几秒 meta 还没填全，
     * 那时缓存下来的 `avg_frame_rate` 是 0，面板上就会一直显示
     * 「片源 0.0 帧/秒」，而这一行正是判断「是片源本来就只有 25 帧、
     * 还是播的时候掉了帧」的唯一对照物 —— 实机排查时它长时间是 0，
     * 害得结论只能走「不知道片源帧率」的兜底分支。所以：
     * 拿到 0 就过 [MEDIA_INFO_RETRY_MS] 再问一次，直到问出真值。
     */
    private var cachedMediaInfo: tv.danmaku.ijk.media.player.MediaInfo? = null
    private var mediaInfoAt = 0L

    /**
     * 片源自己的帧率。
     *
     * ijkplayer 把 `st->avg_frame_rate` 写进了 media meta（`ijkmeta.c:245`），
     * Java 侧从 `IjkStreamMeta.mFpsNum/mFpsDen` 读出来即可。
     * `fps_num` 为 0 时退回 `tbr_num`（有些流只报 tbr）；还是不行就返回 0，
     * 调用方**不能**把它当「0 帧」，要当「不知道」。
     */
    private fun sourceFps(info: tv.danmaku.ijk.media.player.MediaInfo?): Float {
        val s = runCatching { info?.mMeta?.mVideoStream }.getOrNull() ?: return 0f
        val fps = ratio(s.mFpsNum, s.mFpsDen)
        if (fps > 0f) return fps
        return ratio(s.mTbrNum, s.mTbrDen)
    }

    private fun ratio(num: Int, den: Int): Float =
        if (num > 0 && den > 0) num.toFloat() / den else 0f

    override fun diag(): PlaybackEngine.Diag {
        val p = player
        val now = System.currentTimeMillis()
        val stale = cachedMediaInfo == null ||
            (sourceFps(cachedMediaInfo) <= 0f && now - mediaInfoAt > MEDIA_INFO_RETRY_MS)
        val info = if (stale) {
            runCatching { p?.mediaInfo }.getOrNull()?.also {
                cachedMediaInfo = it
                mediaInfoAt = now
            } ?: cachedMediaInfo
        } else {
            cachedMediaInfo
        }
        return PlaybackEngine.Diag(
            decoder = decoderInUse(),
            decoderName = decoderName,
            videoModule = info?.mVideoDecoder,
            // ijkplayer 拼的是 "模块, 实现名"，逗号后面那个空格没去掉，这里自己 trim
            videoImpl = info?.mVideoDecoderImpl?.trim()?.takeIf { it.isNotEmpty() },
            audioCodec = listOfNotNull(info?.mAudioDecoder?.trim(), info?.mAudioDecoderImpl?.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" "),
            videoWidth = runCatching { p?.videoWidth ?: 0 }.getOrDefault(0),
            videoHeight = runCatching { p?.videoHeight ?: 0 }.getOrDefault(0),
            sourceFps = sourceFps(info),
            decodeFps = finite(runCatching { p?.videoDecodeFramesPerSecond ?: 0f }.getOrDefault(0f)),
            outputFps = finite(outputFps()),
            dropFps = finite(runCatching { p?.dropFrameRate ?: 0f }.getOrDefault(0f)),
            cachedMs = runCatching { p?.videoCachedDuration ?: 0L }.getOrDefault(0L),
            cachedBytes = runCatching { p?.videoCachedBytes ?: 0L }.getOrDefault(0L),
            trafficBytes = runCatching { p?.trafficStatisticByteCount ?: 0L }.getOrDefault(0L),
            bitRateBps = runCatching { p?.bitRate ?: 0L }.getOrDefault(0L),
            requestedHardware = hardwareRequested,
            codecOffered = decoderPath == DecoderPath.MEDIACODEC,
            silentFallbacks = silentFallbacks,
            bannedCodecs = bannedCodecs.toList(),
        )
    }

    /**
     * 帧率计数器在**第一个采样点**上会给出 `Infinity`（`SDL_SpeedSamplerAdd` 除以 0 间隔），
     * 面板上就会显示「解码 Infinity 帧/秒」。这里统一当 0 处理（= 还不知道），
     * 一秒之后自然就有真值了。
     */
    private fun finite(v: Float): Float = if (v.isFinite() && v >= 0f) v else 0f

    override fun liveness(): PlaybackEngine.Liveness {
        val p = player ?: return PlaybackEngine.Liveness(0, 0, 0, 0f)
        return runCatching {
            PlaybackEngine.Liveness(
                videoCachedMs = p.videoCachedDuration,
                trafficBytes = p.trafficStatisticByteCount,
                positionMs = p.currentPosition,
                outputFps = p.videoOutputFramesPerSecond,
            )
        }.getOrDefault(PlaybackEngine.Liveness(0, 0, 0, 0f))
    }

    override fun seekTo(ms: Long) {
        // 直播没有可信的时间轴，seek 进去就是长时间音画不同步（见 PlaybackMode）
        if (kind == PlaybackMode.Kind.LIVE) return
        runCatching { player?.seekTo(ms) }
    }

    override fun pause() {
        // 暂停期间不该算「硬解卡住」：音频焦点被别的应用抢走也会走到这里
        cancelHardwareWatchdog()
        // 暂停时计数器本来就不动，不撤掉存活看门狗会把「用户按了暂停」误判成断流
        cancelStallWatchdog()
        runCatching { player?.pause() }
    }

    override fun resume() {
        // 还没出首帧、而且确实在跑硬解，就接着等（重新起算一个完整超时，宁可多等也不要误判）
        if (!firstFrameFired && usingHardware) armHardwareWatchdog()
        if (firstFrameFired) armStallWatchdog()
        runCatching { player?.start() }
    }

    override fun stop() {
        runCatching { player?.stop() }
    }

    override fun release() {
        main.removeCallbacks(retryRunnable)
        releaseInternal()
    }

    private fun releaseInternal() {
        cancelHardwareWatchdog()
        cancelHardwareTruthCheck()
        cancelStallWatchdog()
        val p = player ?: return
        player = null
        runCatching {
            p.setSurface(null)
            p.stop()
            p.release()
        }
        dataSource?.close()
        dataSource = null
    }

    private companion object {
        const val TAG = "FireflyTV"

        /** 底层 I/O 超时（微秒）。见 [newPlayer] 里的说明。 */
        const val IO_TIMEOUT_US = 15_000_000L

        /**
         * 起播后多久去问「到底建成硬解没有」。
         *
         * 取 5 秒：configure + start + 第一帧解码在正常的电视硬解上是毫秒级，
         * 5 秒足够让 `vdec_type` 定下来；又不至于拖太久才在日志里暴露问题。
         */
        const val TRUTH_CHECK_DELAY_MS = 5_000L

        /** 片源帧率还没解析出来时的重问间隔（见 [cachedMediaInfo]）。 */
        const val MEDIA_INFO_RETRY_MS = 5_000L

        /** 首帧超时最多宽限几次（每次一个完整超时）。见 [hardwareWatchdog]。 */
        const val WATCHDOG_GRACE = 2

        /** 播放中存活看门狗的检查周期。见 [stallWatchdog]。 */
        const val STALL_RECHECK_MS = 5_000L

        /**
         * 连续几次「送显帧率贴近 0」才判定画面不动了。
         *
         * 取 3（= 15 秒）：既要盖过正常的网络抖动、缓冲与换台前后的空档，
         * 又不能让老人对着冻住的画面干等。25 秒（界面层的起播超时）那种量级太久了。
         */
        const val STALL_MAX_STRIKES = 3

        /**
         * 判定「画面真的在动」的送显帧率门槛（帧/秒）。
         *
         * 直播片源最低也有 23.976 帧/秒；真要卡死时 `stat.vfps` 会掉到 0 附近。
         * 取 0.5 是为了让测量噪声过不来，同时又能立刻识别出「几乎不出帧」。
         */
        const val STALL_MIN_FPS = 0.5f

        /**
         * UDP 组播的 `timeout`（**秒**，不是微秒）。
         *
         * 它对无连接的组播流其实没有实质作用，给个有限值只是为了不让它停在
         * 那个语义为「等入向连接」的默认值上。真正兜底的是 [stallWatchdog]。
         */
        const val UDP_TIMEOUT_SECONDS = 10L

        // ijkplayer `ff_ffmsg.h` 的解码通路常量
        const val FFP_PROPV_DECODER_AVCODEC = 1
        const val FFP_PROPV_DECODER_MEDIACODEC = 2
    }
}
