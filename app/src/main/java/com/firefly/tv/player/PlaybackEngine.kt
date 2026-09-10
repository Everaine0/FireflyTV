package com.firefly.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    }

    fun setListener(l: Listener)

    /** Surface 可能比播放请求先到或后到，两种顺序都要能工作。 */
    fun attach(surface: Surface)

    fun detachSurface()

    /** 打开 SMB 上的文件并从 [startMs] 起播。会阻塞进行 SMB 打开操作，请在后台线程调用。 */
    fun playSmb(cfg: Config.Smb, relativePath: String, startMs: Long)

    /** 从任意随机读字节源起播（测试用本地文件，将来也可用于 U 盘）。 */
    fun playSource(source: RandomAccessSource, startMs: Long)

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
     * 画面实际输出的帧率。
     *
     * 这是「播放器还活着吗」的**唯一可靠判据**，`isPlaying()` 和 `positionMs()` 都不行：
     *
     *  - `isPlaying()` 在原生侧只查 `mp_state` 这个状态变量
     *    （`ff_ffplay.c` 的 `ijkmp_is_playing`），**播放器内部线程全死光了它照样返回 true**。
     *  - `positionMs()` 实测在 SMB + IMediaDataSource 通路上恒为 0，没法当判据。
     *
     * 而输出帧率来自视频时钟，解码链一断就掉到 0。
     * 现场佐证：用户报「卡住」时抓线程栈，ijkplayer 的线程
     * （`ff_read`/`ff_audio_dec`/`ff_video_dec`/`ff_aout_android`）一个都不存在，
     * 进程 CPU 增量为 0 —— 这时输出帧率必然是 0。
     */
    fun outputFps(): Float

    /**
     * 一份用于判断「播放器还活着吗」的快照。
     *
     * 为什么不是单一指标：实测在 SMB + `IMediaDataSource` 通路上，
     * **输出帧率和 `currentPosition` 都恒为 0**（见 `PlayerLivenessTest`），
     * 拿任何一个单独做判据都会失效。
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
}

/**
 * ijkplayer 实现。目标设备是 2016 年的 Android 5.1 电视，系统硬解不可靠；
 * ijkplayer 自带完整 FFmpeg 软解兜底，且支持 RMVB/TS/FLV（DESIGN §6）。
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

    override fun playSmb(cfg: Config.Smb, relativePath: String, startMs: Long) {
        kind = PlaybackMode.Kind.ON_DEMAND
        playSource(SmbRandomAccessSource(cfg, relativePath), startMs)
    }

    override fun setMode(kind: PlaybackMode.Kind) {
        this.kind = kind
    }

    override fun playSource(source: RandomAccessSource, startMs: Long) {
        // 非 faststart 的 mp4 由 SmbMediaDataSource 内部负责把 moov 搬到头部（DESIGN 风险 8）
        val src = SmbMediaDataSource(source)
        releaseInternal()
        dataSource = src
        // 直播的进度没有意义，绝不能拿着一个假的毫秒数去 seek（见 PlaybackMode 注释）
        pendingSeekMs = PlaybackMode.startPositionMs(kind, startMs)
        val p = newPlayer()
        p.setDataSource(src)
        p.prepareAsync()
    }

    override fun playUrl(url: String) {
        kind = PlaybackMode.Kind.LIVE
        releaseInternal()
        pendingSeekMs = 0
        val p = newPlayer()
        p.setDataSource(url)
        p.prepareAsync()
    }

    override fun setLiveReconnect(on: Boolean, urlProvider: (() -> String?)?) {
        liveReconnect = on
        liveUrlProvider = urlProvider
        if (on) retryCount = 0
    }

    private fun newPlayer(): IjkMediaPlayer {
        // 每次起播都要重置：这两个标志描述的是「当前这一次播放」，
        // 不清掉的话第二次起播会永远收不到首帧（被 firstFrameFired 挡住）。
        firstFrameFired = false
        sizeKnown = false
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

                tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START ->
                    onMain { listener?.onAudioStarted() }
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

        // 老人用：宁可轻微丢帧也不要黑屏卡住
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
        // 直播/点播各自一套参数，取舍不同（见 PlaybackMode.tuning）
        val t = PlaybackMode.tuning(kind)
        val probe = probeOverride
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", if (t.packetBuffering) 1L else 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", t.maxBufferBytes)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", probe?.first ?: t.probesizeBytes)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", probe?.second ?: t.analyzeDurationUs)
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
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rw_timeout", IO_TIMEOUT_US)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", IO_TIMEOUT_US)
        if (kind == PlaybackMode.Kind.LIVE) {
            // 直播用 HTTP/HTTPS：再给一层连接与读取超时
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http_persistent", 0L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "reconnect", 1L)
        }

        player = p
        return p
    }

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
        if (liveReconnect && liveUrlProvider?.invoke() != null) {
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
        runCatching { player?.pause() }
    }

    override fun resume() {
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
        /** 底层 I/O 超时（微秒）。见 [newPlayer] 里的说明。 */
        const val IO_TIMEOUT_US = 15_000_000L
    }
}
