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

    /** 直播断流自动重连；[urlProvider] 返回当前频道地址。 */
    fun setLiveReconnect(on: Boolean, urlProvider: (() -> String?)?)

    fun isPlaying(): Boolean
    fun positionMs(): Long
    fun durationMs(): Long
    fun seekTo(ms: Long)
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

    private var liveReconnect = false
    private var liveUrlProvider: (() -> String?)? = null
    private var retryCount = 0

    private val main = Handler(Looper.getMainLooper())

    override fun setListener(l: PlaybackEngine.Listener) {
        listener = l
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
        playSource(SmbRandomAccessSource(cfg, relativePath), startMs)
    }

    override fun playSource(source: RandomAccessSource, startMs: Long) {
        // 非 faststart 的 mp4 由 SmbMediaDataSource 内部负责把 moov 搬到头部（DESIGN 风险 8）
        val src = SmbMediaDataSource(source)
        releaseInternal()
        dataSource = src
        pendingSeekMs = startMs
        val p = newPlayer()
        p.setDataSource(src)
        p.prepareAsync()
    }

    override fun playUrl(url: String) {
        releaseInternal()
        pendingSeekMs = 0
        val p = newPlayer()
        // 直播不要 packet-buffering，否则延迟会越积越大
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0)
        p.setDataSource(url)
        p.prepareAsync()
    }

    override fun setLiveReconnect(on: Boolean, urlProvider: (() -> String?)?) {
        liveReconnect = on
        liveUrlProvider = urlProvider
        if (on) retryCount = 0
    }

    private fun newPlayer(): IjkMediaPlayer {
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
            if (what == tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                onMain { listener?.onFirstFrame() }
            }
            false
        }
        p.setOnVideoSizeChangedListener { _, _, _, _, _ -> onMain { listener?.onFirstFrame() } }

        // 老人用：宁可轻微丢帧也不要黑屏卡住
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 15L * 1024 * 1024)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 2L * 1024 * 1024)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 3_000_000L)
        // 内嵌中文字幕轨优先，无中文则整个不显示（DESIGN §5）
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1L)

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

    override fun seekTo(ms: Long) {
        runCatching { player?.seekTo(ms) }
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
}
