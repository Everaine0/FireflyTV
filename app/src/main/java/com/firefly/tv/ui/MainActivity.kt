package com.firefly.tv.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.firefly.tv.BuildConfig
import com.firefly.tv.config.ConfigServer
import com.firefly.tv.core.Config
import com.firefly.tv.core.Lunar
import com.firefly.tv.diag.DiagHub
import com.firefly.tv.diag.Knobs
import com.firefly.tv.media.AudioSupport
import com.firefly.tv.media.Library
import com.firefly.tv.media.LibraryCache
import com.firefly.tv.media.WatchHistory
import com.firefly.tv.media.LiveSource
import com.firefly.tv.media.Scanner
import com.firefly.tv.net.WeatherClient
import com.firefly.tv.player.IjkPlaybackEngine
import com.firefly.tv.player.PlaybackEngine
import com.firefly.tv.player.PlaybackMode
import com.firefly.tv.player.PlaybackVerdict
import com.firefly.tv.smb.SmbClient
import com.firefly.tv.smb.SmbStore
import com.firefly.tv.tts.TtsSpeaker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 唯一的 Activity。
 *
 * 按键只有三类（DESIGN §3）：← → 换库、↑ ↓ 换剧/换频道、OK 看天气。
 * 其余键（返回、菜单）不响应任何应用逻辑；音量键不拦截，交给系统。
 *
 * 重入配置页（已与用户确认）：设置键 + OK，或 返回键 + OK —— 单键不会误触，
 * 组合键在电视遥控器上又很容易按出来。
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback, PlaybackEngine.Listener {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var configScreen: ConfigScreen
    private lateinit var faultScreen: FaultScreen
    private lateinit var overlay: OverlayScreen
    private lateinit var hudView: HudView
    private lateinit var diagScreen: DiagScreen

    private lateinit var engine: PlaybackEngine
    private lateinit var tts: TtsSpeaker

    private val main = Handler(Looper.getMainLooper())
    private lateinit var io: HandlerThread
    private lateinit var ioHandler: Handler

    private var configServer: ConfigServer? = null
    private var surfaceReady = false
    private var audioManager: android.media.AudioManager? = null
    private val focusListener by lazy {
        object : android.media.AudioManager.OnAudioFocusChangeListener {
            override fun onAudioFocusChange(focusChange: Int) {
                // 决策交给 AudioFocusPolicy（有单元测试钉住），这里只负责执行。
                // 关键点：直播永远不 seek，所以重播时进度必须是 0。
                val pos = engine.positionMs()
                when (val a = AudioFocusPolicy.decide(contentKind(), focusChange, pos)) {
                    is AudioFocusPolicy.Action.Continue -> Unit
                    is AudioFocusPolicy.Action.PauseTransient -> engine.pause()
                    is AudioFocusPolicy.Action.StopAndAbandon -> engine.stop()
                    // 点播接着刚才的位置；直播 a.resumeMs 恒为 0，从当前时刻重新起播
                    is AudioFocusPolicy.Action.Replay ->
                        replayCurrent(if (a.content == AudioFocusPolicy.Content.OnDemand) pos else 0L)
                }
            }
        }
    }

    /**
     * 用远程接口（`/cmd?a=open`）**临时点名**打开的文件（相对共享根目录）。
     *
     * 为什么要有它：切实验档位会 `replayCurrent()`，而那个函数只会重播
     * 「当前媒体库里的内容」—— 于是远程打开一个测试片、紧接着切档，
     * 画面就又跳回原来的剧集了（第一轮扫档整轮都量错了对象）。
     * 所以临时打开的文件要记在这儿，重播时优先认它；一旦走正常导航
     * （[playEpisode] / [playChannel]）就清掉，不干扰老人正常看电视。
     */
    private var adHocPath: String? = null

    /** 当前在播什么。按**库类型**判断，不看名字 —— 否则直播和剧集的状态会串味。 */
    private fun contentKind(): AudioFocusPolicy.Content = when (libraries.getOrNull(libIndex)) {
        is Library.Video -> if (episodes.isEmpty()) AudioFocusPolicy.Content.None else AudioFocusPolicy.Content.OnDemand
        is Library.Live -> if (channels.isEmpty()) AudioFocusPolicy.Content.None else AudioFocusPolicy.Content.Live
        null -> AudioFocusPolicy.Content.None
    }

    // ---- 当前播放位置 ----
    private var libraries: List<Library> = emptyList()
    private var libIndex = 0

    /**
     * 当前库的索引缓存：**切库时必须整体清掉**。
     * 否则在视频库里按 ↑↓ 会用到 IPTV 遗留的频道列表（实测就是「按上键跳回 CCTV5」）。
     */
    private var shows: List<String> = emptyList()
    private var episodes: List<String> = emptyList()
    private var channels: List<Library.Channel> = emptyList()
    private var showName: String = ""
    private var episodeIndex = 0
    private var channelIndex = 0

    /** 缓存归属哪个库，用来判断还能不能用。 */
    private var cachedShowsLib: String = ""
    private var cachedShow: String = ""
    private var channelLib: String = ""

    /**
     * 连续碰到几部「里面没有能播的视频」的剧。
     *
     * 这个数只为一件事存在：**给自动换剧封顶**。老行为是空剧就换下一部、每 3 秒一次，
     * 一圈转回来又从头开始，永远停不下来（用户反馈的「快速跳过」就是这个现象）。
     */
    private var emptyShowStreak = 0

    /**
     * 每次切库 +1。异步结果回来时对不上就丢弃，
     * 免得连按左右键时旧任务把界面覆盖回上一个库。
     */
    private var librarySeq = 0

    /** NAS 目录结构的落盘缓存；冷启动直接出画面，不用等 SMB 扫完。 */
    private var cache: LibraryCache.Snapshot? = null

    /** 按键反馈条。没有它，切库那几秒屏幕上什么都没有，看起来就是卡死。 */
    private val hud = SwitchHud()

    private var currentTitle: String = ""

    /** 这条流的音频这台电视解不了时，给用户的一句话（见 AudioSupport）。 */
    private var audioWarning: String? = null

    // ---- 定时任务 ----
    private val overlayHide = Runnable { hideOverlay() }
    private val retryTick = Runnable { startPlayback() }
    private val hudTick = object : Runnable {
        override fun run() {
            if (renderHud()) main.postDelayed(this, HUD_TICK_MS)
        }
    }

    /**
     * 诊断页的刷新（1 Hz）。
     *
     * 只在页面显示时跑，并且顺手把「解码/读取跟不跟得上」的结论写进日志 ——
     * 电视上没有 adb，日志要靠 [startPlayback] 那套落盘机制事后取，
     * 但屏幕上的数字才是当场能用的证据。
     */
    private val diagTick = object : Runnable {
        override fun run() {
            if (diagScreen.visibility != View.VISIBLE) return
            renderDiag()
            main.postDelayed(this, DIAG_INTERVAL_MS)
        }
    }
    /**
     * 每 5 秒把「看到哪儿了」写盘一次。
     *
     * **不是只在退出时写**：用户明确说过老人不看了直接关电视，应用不一定拿得到
     * onPause/onDestroy。5 秒一次 → 最坏丢 5 秒，满足「10 秒左右偏差」的要求。
     * 写的量很小（十几 KB 的 SharedPreferences），对闪存和流畅度都没有影响。
     */
    private val positionTick = object : Runnable {
        override fun run() {
            saveRecord(force = false)
            main.postDelayed(this, POSITION_INTERVAL_MS)
        }
    }

    private var lastSavedPos = 0L
    private var lastSavedAt = 0L

    /**
     * 观看记录（每部剧各一条）。
     *
     * 只在主线程读写：它同时被「周期性写盘」「换剧/换库」「开机恢复」三条路径用到，
     * 分散到 IO 线程反而容易写出竞态。读盘本身在 onCreate 里丢给 IO 线程做（见 loadHistory）。
     */
    private var history: WatchHistory.Book = WatchHistory.Book.EMPTY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))

        io = HandlerThread("firefly-io").apply { start() }
        ioHandler = Handler(io.looper)

        buildViews()
        requestAudioFocus()

        engine = IjkPlaybackEngine(this).apply { setListener(this@MainActivity) }
        tts = TtsSpeaker(this).apply { init() }
        // 上次的天气先顶上，过期了在浮层里再联网刷（省心知那边的调用次数）
        restoreWeatherCache()

        // 远程诊断通道：电视上只有画面、没有 adb，这是这一轮排查唯一的「仪表盘」。
        // 只在 debug 构建开（见 diagControl）；面板上的「远程」那一行会显示它的地址。
        if (EXPERIMENTS) DiagHub.start(this, diagControl)

        // 缓存读盘放在 IO 线程，别在主线程碰 SharedPreferences
        ioHandler.post {
            cache = readCache()
            val book = loadHistory()
            main.post { history = book }
        }

        if (!Config.configured(this)) {
            openConfigServer()
        } else {
            startPlayback()
            main.postDelayed(positionTick, POSITION_INTERVAL_MS)
            main.postDelayed(hudTick, HUD_TICK_MS)
        }
    }

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        surfaceView = SurfaceView(this)
        // 格式 / 层级 / 固定尺寸只有**建面之前**设才有意义，所以在这里就按旋钮落一次。
        // 老代码在这里写死 `setFormat(PixelFormat.RGBA_8888)`（抄来的），而带 alpha 的
        // 格式提示有可能把视频层挤出硬件叠加通路 —— 默认改成不设格式提示（见 [applySurfaceKnobs]）。
        applySurfaceKnobs()
        surfaceView.holder.addCallback(this@MainActivity)
        root.addView(surfaceView, matchParent())

        configScreen = ConfigScreen(this)
        faultScreen = FaultScreen(this)
        overlay = OverlayScreen(this)
        hudView = HudView(this)
        diagScreen = DiagScreen(this)

        configScreen.visibility = View.GONE
        faultScreen.visibility = View.GONE
        overlay.visibility = View.GONE
        hudView.visibility = View.GONE
        diagScreen.visibility = View.GONE
        root.addView(configScreen, matchParent())
        root.addView(faultScreen, matchParent())
        root.addView(overlay, matchParent())
        root.addView(hudView, matchParent())
        root.addView(diagScreen, matchParent())

        setContentView(root)

        // 4K 电视上「界面缩在中间」这类问题，第一手证据就是这一行（见 UiScale）
        Log.i(TAG, "屏幕：${ui().describe()} 刷新率=${refreshRateHz()}Hz")
    }

    private fun ui() = Ui(this)

    /** 当前显示刷新率（Hz）。API 21 上 `Display.getRefreshRate()` 就有。 */
    private fun refreshRateHz(): Float = runCatching {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.refreshRate
    }.getOrDefault(0f)

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    // ---- Surface ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        engine.attach(holder.surface)
        launchPendingEpisode()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 尺寸变化由 SurfaceView 自己处理，播放器只认 surface 对象。
        // 但要**记一笔**：解码器把面重配成 4K 时会再回调一次，这条历史是
        // 「视频层到底在搬 1080p 还是 4K」的直接证据（见 surfaceChanges）。
        surfaceChanges += "${System.currentTimeMillis() % 100000}ms:${width}×${height}@$format"
        if (surfaceChanges.size > 6) surfaceChanges.removeAt(0)
        Log.i(TAG, "Surface 变化：${width}×${height} format=$format")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        engine.detachSurface()
    }

    /**
     * 按旋钮设置**视频层**：格式提示 / 前后层级 / 固定尺寸 / 窗口底色（见 [Knobs]）。
     *
     * 这四个变量都必须**在建面之前**设，所以只在两处调用：`buildViews()`（首次建面之前）
     * 和 [applyKnobs]（先销毁再重建）。
     *
     * 为什么要做成旋钮：电视上 4K **HEVC** 只有 18 帧，而当时以为是像素率卡在
     * 150~200 Mpx/秒 —— GPU 合成 / 视频层被窗口拖住 / 显示通路搬不动 4K，
     * 这三种解释各对应一个开关，只有**一项一项换、一项一项量**才能分清，
     * 而这些开关全都是「建面前的一锤子买卖」，改一个就要重播一次。
     *
     * （2026-09-12 实机结论：这些开关全部无效，但原因不是「4K 显示通路吃不下」——
     *  4K **H.264** 在同一台机器上送显中位 50.0 帧/秒。真正封顶的是 HEVC 解码块，
     *  见 [com.firefly.tv.player.PlaybackVerdict.HEVC_DECODE_MPX]。）
     */
    private fun applySurfaceKnobs() {
        val holder = surfaceView.holder
        // 1) 格式提示。`unknown` = 连 setFormat 都不调，交给 SurfaceView 的默认值
        runCatching {
            if (Knobs.needsSetFormat(this)) {
                holder.setFormat(Knobs.surfaceFormat(this) ?: PixelFormat.OPAQUE)
            }
        }
        // 2) 层级：top = 视频层浮在窗口之上（窗口就不用再合成到视频上）；media = 浮在窗口但低于顶层
        runCatching {
            when (Knobs.zOrder(this)) {
                "top" -> {
                    surfaceView.setZOrderMediaOverlay(false)
                    surfaceView.setZOrderOnTop(true)
                }

                "media" -> {
                    surfaceView.setZOrderOnTop(false)
                    surfaceView.setZOrderMediaOverlay(true)
                }

                else -> {
                    surfaceView.setZOrderOnTop(false)
                    surfaceView.setZOrderMediaOverlay(false)
                }
            }
        }
        // 3) 固定尺寸：这是「让解码器别吐 4K、直接吐 1080p」的赌注。
        //    MediaCodec 的输出缓冲尺寸通常是按**片源**定的（ACodec 自己 set_buffers_dimensions），
        //    所以这一条大概率无效 —— 但代价只有一行，量一次就能确认。
        runCatching {
            when (Knobs.fixedSize(this)) {
                "hd1080" -> holder.setFixedSize(1920, 1080)
                "screen" -> {
                    @Suppress("DEPRECATION")
                    val d = windowManager.defaultDisplay
                    holder.setFixedSize(d.width, d.height)
                }

                else -> holder.setSizeFromLayout()
            }
        }
        // 4) 窗口底色：透明的话 SurfaceFlinger 理论上可以少合成一层
        runCatching {
            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(
                    if (Knobs.transparentWindow(this)) Color.TRANSPARENT else Color.BLACK,
                ),
            )
        }
        Log.i(
            TAG,
            "视频层：格式=${Knobs.get(this, Knobs.K_FORMAT)} 层级=${Knobs.zOrder(this)} " +
                "固定=${Knobs.fixedSize(this)} 窗口底=${Knobs.get(this, Knobs.K_WINDOW_BG)}",
        )
    }

    /** Surface 现在到底能不能用（按对象问，不靠标志位猜）。 */
    private fun surfaceUsable(): Boolean =
        surfaceView.holder.surface?.isValid == true

    /**
     * 把排队等 Surface 的那一集放出去。
     *
     * 这里原来只有一个隐患很大的前提：「播放请求排上队以后，surfaceCreated 一定会再来一次」。
     * 实测不成立 —— 从后台回到前台时 `dispatchKeyEvent` 是可以触发的，但 Surface 早就建好了，
     * `surfaceCreated` 不会再回调，于是 [pendingEpisode] 永远躺在队列里：
     * **屏幕停在上一集或上一个库的画面，按键却都有反应**，用户看到的就是「卡在当前页面」。
     * （日志里那一行 `startMs=1180132 surfaceReady=false` 就是它。）
     *
     * 现在改成：只要有排队的东西，就当场问一次 Surface 能不能用，能用就立刻起播；
     * 排队超时就明确报故障，不再无声无息地烂在队列里。
     */
    private fun launchPendingEpisode() {
        val p = pendingEpisode ?: return
        if (!surfaceUsable()) {
            trace("launchPendingEpisode 仍在等 Surface：《${p.show}》")
            return
        }
        pendingEpisode = null
        main.removeCallbacks(pendingTimeout)
        surfaceReady = true
        trace("launchPendingEpisode 起播《${p.show}》startMs=${p.startMs}")
        playEpisodeNow(p.path, p.startMs)
    }

    private val pendingTimeout = Runnable {
        val p = pendingEpisode ?: return@Runnable
        pendingEpisode = null
        Log.w(TAG, "等 Surface 超时，放弃起播《${p.show}》")
        showFault("画面还没准备好，请按一下遥控器上的返回键再试", retry = false)
    }

    private fun playEpisodeNow(path: String, startMs: Long) {
        val cfg = Config.smb(this)
        prepared = false
        audioStarted = false
        armStallWatchdog()
        ioHandler.post {
            try {
                engine.playSmb(cfg, path, startMs)
            } catch (t: Throwable) {
                Log.w(TAG, "播放失败 $path", t)
                main.post { onError("这个视频无法播放", fatal = true) }
            }
        }
    }

    private var startedAt = 0L
    private var gotFirstFrame = false

    /**
     * 播放器已经起播（`onPrepared` 回来之后）。
     *
     * 诊断页用它区分「还没起播」和「起播了但很慢」—— 这两种情况给出的结论完全不同。
     */
    private var prepared = false

    /**
     * 音频真的出声了（`AUDIO_RENDERING_START`）。
     *
     * 「有画面没声音」是这台电视上最难查的一类问题，所以单独记一个真值，
     * 诊断页上直接显示，不用靠耳朵听、也不用翻日志。
     */
    private var audioStarted = false

    /**
     * 起播看门狗起算。
     *
     * 每次起播、以及**硬解退回软解重播**时都要重新起算：
     * 软解重播等于从头再建一次解码器，拿上一次的起播时刻算超时必然误判。
     */
    private fun armStallWatchdog() {
        gotFirstFrame = false
        startedAt = System.currentTimeMillis()
        main.removeCallbacks(stallWatchdog)
        main.postDelayed(stallWatchdog, STALL_TIMEOUT_MS)
    }

    /**
     * 起播卡死看门狗。
     *
     * 「卡在当前页面」是用户实测到的最烦人的一种故障：按键都有反应、浮层也出得来，
     * 就是画面不动，而且**永远不会自己好**。以前的代码在这种情况下一声不吭。
     *
     * 这里给每次起播都挂一个超时：到点还没出首帧，先看能不能**退回软解重播**
     * （有些电视的 MediaCodec 一帧都不吐，见 [PlaybackEngine.canFallbackToSoftware]）；
     * 软解也不行才当播放失败处理（跳故障页 → 自动重试）。
     * 宁可让它自己重试几次，也不要留一个死的画面。
     */
    private val stallWatchdog = Runnable {
        if (gotFirstFrame) return@Runnable
        val waited = System.currentTimeMillis() - startedAt
        if (engine.canFallbackToSoftware() && engine.retryInSoftware()) {
            trace("stallWatchdog 卡死 ${waited}ms，先退回软解重播")
            armStallWatchdog()
            return@Runnable
        }
        Log.w(TAG, "起播 ${waited}ms 还没出首帧，判定卡死，走故障页重试")
        trace("stallWatchdog 卡死 ${waited}ms")
        onError("这个视频打不开，正在换下一个", fatal = false)
    }

    // ---- 播放中存活看门狗 ----

    /**
     * 「播放器悄悄死掉」看门狗。
     *
     * ## 为什么还需要第二个看门狗
     *
     * [stallWatchdog] 只保护**首帧之前**，`onFirstFrame` 一到就被撤掉。
     * 而用户实测到的「卡住」是发生在**播放中**：画面冻在最后一帧再也不动。
     *
     * 抓线程栈确认了现场：ijkplayer 的线程
     * （`ff_read` / `ff_audio_dec` / `ff_video_dec` / `ff_aout_android`）**全部消失**，
     * 进程 CPU 增量为 0，但**既没有 `onError` 也没有 `onCompletion`**。
     * 界面层能自救的两个信号都没来，于是永远冻着。
     *
     * ## ⚠️ 目前**没有启用** —— 因为还没找到可信的存活判据
     *
     * 一开始打算用「输出帧率」：`isPlaying()` 在原生侧只查 `mp_state`
     * 状态变量（线程全死照样返回 true），`positionMs()` 被实测排除，
     * 于是 `outputFps()` 看着最合适。
     *
     * 但实测把它也否掉了：**在 SMB + `IMediaDataSource` 通路上 `outputFps()`
     * 恒为 0**，而同一条通路上的画面是正常在放的
     * （`PlayerLivenessTest.whichSignalReflectsLiveness` 拿真实片源量过；
     * 本地文件 `test.mp4` 上它正常，能读到 14.8fps）。
     *
     * 拿一个恒为 0 的信号做判据，会在 15 秒后把**正常播放**误判成死机并不断
     * 重启画面 —— 那比原来的问题更糟。所以这里加了 [LIVENESS_ENABLED] 开关，
     * 默认关闭，等找到可信判据再打开。**不要只是把开关改成 true。**
     *
     * 可行的方向（都还没验证）：
     *  - 让 `IMediaDataSource` 通路上的统计生效（可能要设某个 ijk 选项）
     *  - 用「读取字节数的增量」等别的计数器（`PlaybackEngine.Liveness` 已经把
     *    cached/traffic/position/fps 四个一起取回来了，但需要先在真实片源上
     *    量出哪个在动）
     *  - 在 `MainActivity` 里记录最近一次 `onInfo`/`onPrepared` 等回调的时间，
     *    用「多久没收到任何播放器事件」当判据
     */
    private val livenessWatchdog = object : Runnable {
        override fun run() {
            if (!LIVENESS_ENABLED) return
            if (!gotFirstFrame || !engine.isPlaying()) {
                strikes = 0
                main.postDelayed(this, LIVENESS_INTERVAL_MS)
                return
            }
            val fps = engine.outputFps()
            if (fps > 0.5f) {
                strikes = 0
                lastLiveFps = fps
            } else {
                strikes++
                trace("存活检查：帧率=$fps 连续第 $strikes 次为 0")
                if (strikes >= LIVENESS_STRIKES) {
                    strikes = 0
                    Log.w(TAG, "画面连续 ${LIVENESS_STRIKES * LIVENESS_INTERVAL_MS / 1000} 秒没有输出，判定播放器已死，自动重连")
                    trace("livenessWatchdog 判定播放器已死，自动重连")
                    recoverFromDeadPlayer()
                    return
                }
            }
            main.postDelayed(this, LIVENESS_INTERVAL_MS)
        }
    }

    private var strikes = 0
    private var lastLiveFps = 0f

    /**
     * 自动重连不能无上限地试。
     *
     * 如果片源本身就有问题（比如 4K H.265 在这台设备上根本解不动），
     * 看门狗会一直判定「死了 → 重连 → 又死」，画面每隔十几秒重启一次，
     * 用户看到的是「一直在闪」，比冻住更糟。
     * 所以连续重启超过 [MAX_AUTO_RECOVER] 次就停下来，老实出故障页。
     */
    private var recoverCount = 0
    private var recoverWindowStart = 0L

    /** 播放器已经死了（不会自己报错）—— 只能重建一个。 */
    private fun recoverFromDeadPlayer() {
        val now = System.currentTimeMillis()
        if (now - recoverWindowStart > RECOVER_WINDOW_MS) {
            recoverWindowStart = now
            recoverCount = 0
        }
        recoverCount++
        if (recoverCount > MAX_AUTO_RECOVER) {
            trace("自动重连 $recoverCount 次仍失败，出故障页")
            disarmLivenessWatchdog()
            showFault("画面卡住了，请按一下遥控器上的返回键再试", retry = false)
            return
        }

        val lib = libraries.getOrNull(libIndex)
        disarmLivenessWatchdog()
        // 先把死的那个彻底丢掉，否则新起的播放器会和它抢 Surface
        runCatching { engine.release() }
        trace("自动重连第 $recoverCount 次 lib=${lib?.name}")
        if (lib is Library.Live) {
            currentTitle = channels.getOrNull(channelIndex)?.name ?: currentTitle
            playChannel()
        } else {
            // 位置在这条通路上取不到（恒为 0，见 PlayerLivenessTest），
            // 所以只能从头续播同一集。对老人来说「这一集重头放」远比
            // 「画面永远冻着」好收拾。
            playEpisode(0L)
        }
    }

    private fun armLivenessWatchdog() {
        if (!LIVENESS_ENABLED) return
        main.removeCallbacks(livenessWatchdog)
        strikes = 0
        main.postDelayed(livenessWatchdog, LIVENESS_INTERVAL_MS)
    }

    private fun disarmLivenessWatchdog() {
        main.removeCallbacks(livenessWatchdog)
        strikes = 0
    }

    // ---- 按键（只有三类 + 一个隐藏的诊断入口） ----

    /** 上一次按「设置 / 信息」键的时刻，用来识别双击（见 [DIAG_DOUBLE_CLICK_MS]）。 */
    private var lastDiagKeyAt = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 诊断页：**双击**遥控器上的「设置 / 信息」键打开；打开以后同一颗键**单击**就切换实验。
        //
        // 为什么是双击不是单击：用户实测这台电视的「信息」键其实就是**设置键**，
        // 单击就弹一屏字太重（也容易和别的功能撞车）。双击误触概率极低，
        // 而且不用记组合键；打开之后 **OK 键关闭**。
        //
        // 又为什么用同一颗键切换实验：用户的遥控器**没有数字键**（老人机遥控器的常态），
        // 所以「打开用双击、打开后单击切换」是这台遥控器上唯一可行的两档操作。
        when (event.keyCode) {
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU, KEYCODE_SETTINGS -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (diagScreen.visibility == View.VISIBLE && EXPERIMENTS) {
                        // 面板开着：单击 = 切到下一个实验方案（会重播）
                        lastDiagKeyAt = 0L
                        applyPreset((experimentIndex + 1) % Knobs.PRESETS.size)
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastDiagKeyAt <= DIAG_DOUBLE_CLICK_MS) {
                            lastDiagKeyAt = 0L
                            toggleDiag()
                        } else {
                            lastDiagKeyAt = now
                            Log.i(TAG, "收到设置/信息键（keyCode=${event.keyCode}），再按一次开诊断页")
                        }
                    }
                }
                return true
            }
        }

        // OK：诊断页开着就关掉，否则看天气
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_UP) onOkPressed()
            return true
        }

        // 长按也当一次处理，避免老人按住不放导致疯狂切台
        if (event.repeatCount > 0) return true

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> consume(event) { switchLibrary(-1) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> consume(event) { switchLibrary(+1) }
            KeyEvent.KEYCODE_DPAD_UP -> consume(event) { switchShowOrChannel(-1) }
            KeyEvent.KEYCODE_DPAD_DOWN -> consume(event) { switchShowOrChannel(+1) }
            else -> true // 返回、主页一律不响应
        }
    }

    /** OK：诊断页开着就关掉，否则看天气。 */
    private fun onOkPressed() {
        if (diagScreen.visibility == View.VISIBLE) {
            hideDiag()
            return
        }
        toggleOverlay()
    }

    // ---- 实验旋钮（远程可调；见 com.firefly.tv.diag.Knobs / DiagHub）----
    //
    // 现场问题（长虹 43Q3T：3840×2160@25 的《大宅门》只有送显 18.2 帧）需要
    // 「换一个变量、量一次数字」，而电视在用户家里，我没有 adb。
    // 所以做了一条局域网 HTTP 通道（DiagHub），变量做成了运行时可写的旋钮：
    // 我在这边改、电视那边当场重播，用户在电视前看画面就行。
    // 面板上单击「设置键」= 按顺序换预设方案，和 HTTP 上 `GET /preset?n=N` 等价。

    /** 当前方案（预设）序号；-1 = 当前旋钮组合不对应任何一档预设（被单独改过）。 */
    private var experimentIndex = 0

    /**
     * 把旋钮落到实际的地方，然后（可选）重播。
     *
     * 分两类：
     *  - **建面前的一锤子买卖**（[applySurfaceKnobs]）：格式/层级/固定尺寸/窗口底色；
     *  - **起播前才读的选项**：ijkplayer 的 `setOption` 只在下一次 `newPlayer()` 时读，
     *    所以只要重播就自然生效，这里不用管。
     *
     * Surface 的重建用「先 INVISIBLE 再 VISIBLE」来触发（`surfaceDestroyed` → 重新
     * `surfaceCreated`）。不这么做的话 `setFormat` 对已经建好的面毫无作用，
     * 量出来的数字其实还是上一档的 —— 那种「改了没反应」最容易把人带偏。
     */
    private fun applyKnobs(replay: Boolean, resumeMs: Long? = null) {
        val pos = resumeMs ?: engine.positionMs()
        DiagHub.log("应用旋钮：${Knobs.nonDefault(this).joinToString(" ") { "${it.first}=${it.second}" }.ifEmpty { "（全默认）" }}")
        rebuildSurfaceView()
        if (!replay) return
        replayCurrent(pos)
    }

    /**
     * 把 SurfaceView 整个换一只新的。
     *
     * 为什么要「整个换」而不是改属性 + INVISIBLE/VISIBLE：[applySurfaceKnobs] 里那几项
     * （`setFormat` / `setZOrderOnTop` / `setZOrderMediaOverlay` / `setFixedSize`）
     * **按文档都只能在建面之前设**。面已经存在时再调，轻则被忽略、重则抛异常，
     * 而表现是一样的：**改了没反应**。第一轮实机扫档时「视频层层级」两档量出来
     * 和默认一模一样，我现在不能确定那是「层级真的不影响」还是「压根没生效」——
     * 所以宁可换一只新 View，把「建面之前」这件事做实。
     *
     * 顺序很关键：先摘下来（触发 surfaceDestroyed → 引擎解绑），
     * 新建一只、**先设旋钮**、再挂回调、最后按原位置放回去（触发 surfaceCreated → 重新起播）。
     * 起播请求在面还没建好时会走 [pendingEpisode] 排队，这正是已有机制。
     */
    private fun rebuildSurfaceView() {
        val parent = surfaceView.parent as? ViewGroup
        if (parent == null) {
            // 还没挂上去（buildViews 阶段）：直接设就行
            runCatching { applySurfaceKnobs() }
            return
        }
        val index = parent.indexOfChild(surfaceView)
        val lp = surfaceView.layoutParams
        parent.removeView(surfaceView)
        surfaceView = SurfaceView(this)
        runCatching { applySurfaceKnobs() }
        surfaceView.holder.addCallback(this)
        parent.addView(surfaceView, index, lp)
    }

    /** 切到第 [index] 个预设方案并重播。返回一句给人看的结果。 */
    private fun applyPreset(index: Int, replay: Boolean = true): String {
        val i = Knobs.applyPreset(this, index)
        experimentIndex = i
        val label = Knobs.PRESETS[i].label
        Log.i(TAG, "实验方案 ${i + 1}/${Knobs.PRESETS.size}：$label")
        applyKnobs(replay = replay)
        DiagHub.boostNow()
        return "${i + 1}/${Knobs.PRESETS.size} $label"
    }

    private inline fun consume(event: KeyEvent, action: () -> Unit): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) action()
        return true
    }

    // ---- 配置 ----

    private fun openConfigServer() {
        engine.stop()
        engine.detachSurface()
        faultScreen.visibility = View.GONE
        configScreen.visibility = View.VISIBLE

        val ip = ConfigServer.localIpv4()
        val server = ConfigServer(this) {
            main.post { onConfigured() }
        }
        configServer = server
        val started = server.start()
        val url = if (ip != null && started) "http://$ip:${server.port}/?t=${server.token}" else null
        configScreen.show(url)
        if (url == null) {
            Log.w(TAG, "无法确定电视 IP 或 HTTP 服务启动失败，无法显示二维码")
        }
    }

    private fun onConfigured() {
        // 保存成功到出画面要连 NAS、列目录、起播，可能好几秒。
        // 这段先显示「正在打开」，别让用户以为没保存上。
        configScreen.showStarting()
        main.postDelayed(configStartingTick, CONFIG_STARTING_MS)
        startPlayback()
        main.removeCallbacks(positionTick)
        main.postDelayed(positionTick, POSITION_INTERVAL_MS)
    }

    private fun closeConfigServer() {
        configServer?.stop()
        configServer = null
    }

    /** 过渡态最多显示这么久；到点就交回播放（画面出来时也会提前撤掉）。 */
    private val configStartingTick = Runnable {
        if (configScreen.visibility == View.VISIBLE) {
            configScreen.recycle()
            configScreen.visibility = View.GONE
        }
    }

    // ---- 播放 ----

    private fun startPlayback() {
        main.removeCallbacks(retryTick)
        val cfg = Config.smb(this)
        if (!cfg.ready) {
            openConfigServer()
            return
        }

        showFault(null) // 清掉故障页
        ioHandler.post {
            // 先把落盘缓存读出来：冷启动靠它直接出画面，不必等 NAS 扫完（DESIGN §7）
            if (cache == null) cache = readCache()
            val cached = cache?.takeIf { !it.isEmpty }

            if (cached != null) {
                main.post {
                    libraries = cached.libraries
                    restoreSpot(cached.libraries)
                }
            }

            try {
                val libs = Scanner.libraries(cfg)
                val snap = LibraryCache.withLibraries(cache, libs)
                commitCache(snap)
                main.post {
                    if (libs.isEmpty()) {
                        showFault("NAS 上还没有可以播放的内容")
                    } else {
                        // 新列表可能比缓存里多/少几个库（用户刚在 NAS 上加了文件夹就是这样），
                        // 下标会整体错位 —— 必须按**名字**把当前库重新锚一次，见
                        // [Navigator.libraryIndexAfterRefresh]。不锚的实机表现：
                        // 「按上键没反应、按右键跳到别的库去了」。
                        val wasOn = libraries.getOrNull(libIndex)?.name
                        libraries = libs
                        libIndex = Navigator.libraryIndexAfterRefresh(libs, wasOn)
                        // 有缓存时界面已经在放了，不能再切一次 —— 否则画面会被打断重来
                        if (cached == null) restoreSpot(libs)
                    }
                }
            } catch (t: Throwable) {
                val msg = SmbClient.describe(t)
                SmbStore.drop()
                main.post {
                    // 缓存里有内容就先照常看，NAS 的毛病等它自己好；没有才报故障
                    if (cache?.isEmpty != false) {
                        showFault(msg, retry = true)
                    } else {
                        Log.w(TAG, "后台刷新失败（先用缓存继续播）：$msg")
                    }
                }
            }
        }
    }

    /**
     * 开机恢复：**回到上次用的那个库，并接着看那个库里最近看的一部**。
     *
     * 两件事都要，用户那条反馈说的就是这个：
     * 「启动时自动切换到上一次使用的影视库的最近观看（直播则只到频道）」。
     *
     *  - 上次用的是**直播库** → 直接去那个库，频道号由 `Config.channel` 记着；
     *  - 上次用的是**视频库** → 取这个库里 `at` 最新的那条记录（剧 + 集 + 进度）；
     *  - 什么都没记过 → 老行为：第 0 个库、第 1 部剧、第 1 集、开头。
     */
    private fun restoreSpot(libs: List<Library>) {
        val book = history
        val wanted = book.lastLib.ifBlank { libraries.firstOrNull()?.name.orEmpty() }
        var li = libs.indexOfFirst { it.name == wanted }
        if (li < 0) li = 0
        val lib = libs[li]
        val rec = (lib as? Library.Video)?.let { book.mostRecentIn(it.name) }
        if (rec == null) {
            // 直播库走的是 loadChannels，频道号由 Config.channel 记着，这里不用给剧名
            val what = if (lib is Library.Live) "第 1 个频道" else "第一部剧第 1 集"
            trace("restoreSpot：${lib.name} 没有观看记录，从$what 开始")
            selectLibrary(li, resumeShow = "", resumeIndex = 0, resumePos = 0L)
            return
        }
        trace("restoreSpot：${libs[li].name} 接着看《${rec.show}》第 ${rec.episode + 1} 集 @${rec.resumeMs}ms")
        selectLibrary(li, resumeShow = rec.show, resumeIndex = rec.episode, resumePos = rec.resumeMs)
    }

    // ---- 观看记录 ----

    /**
     * 读盘。旧版只有一份「全局记忆」，这里顺手迁移一次 ——
     * 用户电视上正存着上次看到哪儿，升级后不该从头开始。
     */
    private fun loadHistory(): WatchHistory.Book {
        val saved = WatchHistory.parse(Config.watchHistoryText(this))
        val legacy = Config.takeLegacySpot(this)
        if (!saved.isEmpty || legacy == null) return saved
        if (legacy.lib.isBlank() || legacy.show.isBlank()) return saved
        Log.i(TAG, "迁移旧版续播记忆：《${legacy.show}》第 ${legacy.index + 1} 集 @${legacy.posMs}ms")
        val book = WatchHistory.fromLegacy(
            legacy.lib, legacy.show, legacy.index, legacy.posMs, System.currentTimeMillis(),
        )
        Config.saveWatchHistoryText(this, WatchHistory.serialize(book))
        return book
    }

    /**
     * 把「现在看到哪儿了」写下来。
     *
     * @param force true = 不等防抖立刻写（换剧/换库/退到后台时用）
     */
    private fun saveRecord(force: Boolean) {
        val lib = libraries.getOrNull(libIndex) as? Library.Video ?: return
        if (showName.isBlank()) return
        val pos = engine.positionMs()
        // 直播不记时长（见 PlaybackMode）；这里本来就已经限定视频库了，
        // 这一行防的是「视频库但位置还没出来」的瞬间
        if (pos <= 0 && !force) return

        val now = System.currentTimeMillis()
        if (!force) {
            if (now - lastSavedAt < SAVE_DEBOUNCE_MS) return
            if (kotlin.math.abs(pos - lastSavedPos) < 1000) return
        }
        lastSavedPos = pos
        lastSavedAt = now
        history = WatchHistory.with(
            history,
            WatchHistory.Record(lib.name, showName, episodeIndex, pos.coerceAtLeast(0L), now),
        )
        // 只更新记录、不动 last_lib：切库那一刻已经单独记过了
        Config.saveWatchHistoryText(this, WatchHistory.serialize(history))
    }

    /** 上次用过的库（含直播）。切库时写，开机时读。 */
    private fun rememberLibrary(name: String) {
        history = WatchHistory.withLastLib(history, name)
        Config.saveWatchHistoryText(this, WatchHistory.serialize(history))
    }

    /** 「这部剧看到哪儿了」——交给 [Navigator] 决定换台/换剧之后从哪儿起播。 */
    private fun resumeOf(lib: String, show: String): Navigator.Resume {
        val rec = history.find(lib, show) ?: return Navigator.Resume.FIRST
        return Navigator.Resume(rec.episode, rec.resumeMs)
    }

    // ---- NAS 结构缓存 ----
    //
    // 用户实测的原话是「你不会每次都现查吧」。之前的答案很难看：确实每次都现查，
    // 开机要几十次 SMB 往返。现在冷启动直接用上次的结构出画面，同时后台静默刷新。

    private fun readCache(): LibraryCache.Snapshot? {
        val text = Config.loadCache(this) ?: return null
        val snap = runCatching { LibraryCache.parse(text) }.getOrNull() ?: return null
        return snap.takeIf { !it.isEmpty }
    }

    /** 缓存只在 IO 线程上碰，所以这里不做同步。 */
    private fun commitCache(snap: LibraryCache.Snapshot) {
        cache = snap
        Config.saveCache(this, LibraryCache.serialize(snap))
    }

    /**
     * 这份目录缓存还够不够新，能不能省掉一次后台刷新。
     *
     * 为什么要有这个判断：刷新剧集列表要走**同一条 SMB 连接**（[SmbStore] 串行化），
     * 而切换的瞬间播放器正在读首帧要用的数据 —— 两边抢同一把锁，
     * 实测切换首帧会因此多等 100~300ms。省掉这次刷新，切换就干脆了。
     *
     * 过期时间取 10 分钟：够覆盖「同一集里反复换台/换剧」的整段操作，
     * 又不至于让 NAS 上新加的集长时间看不到（冷启动和过期后照样刷新）。
     */
    private fun cacheIsFresh(): Boolean {
        val at = cache?.savedAt ?: 0L
        return at > 0 && System.currentTimeMillis() - at < CACHE_FRESH_MS
    }

    private fun cacheAgeSec(): Long =
        ((System.currentTimeMillis() - (cache?.savedAt ?: 0L)) / 1000).coerceAtLeast(0)

    /** 一次按键的即时反馈。返回是否还需要继续跑定时器。 */
    private fun renderHud(): Boolean {
        val state = hud.peek(System.currentTimeMillis()) ?: run {
            hudView.visibility = View.GONE
            return false
        }
        hudView.render(state)
        hudView.visibility = View.VISIBLE
        return true
    }

    /** 内容真的开始播了：把「正在打开…」换成名字，1.6 秒后自动收起。 */
    private fun onContentPicked(title: String) {
        if (!Config.configured(this)) return
        hud.onPlaying(title, System.currentTimeMillis())
        if (renderHud()) main.postDelayed(hudTick, HUD_TICK_MS)
    }

    /** 换剧/换台：先出一条名字，别让屏幕一动不动。 */
    private fun announce(title: String) {
        hud.onContentSwitch(title, System.currentTimeMillis())
        if (renderHud()) main.postDelayed(hudTick, HUD_TICK_MS)
    }

    private fun announceLibrary(name: String) {
        // 换库就把上一条「没声音」的提示清掉：那是上一个片源的毛病，
        // 留着会让人以为新片源也没声音
        audioWarning = null
        // 库名同时写进 OK 浮层：按左右键时用户本来就习惯按 OK 确认，现在按下就看到库名变了
        currentTitle = SwitchHud.libraryTitle(name)
        hud.onLibrarySwitch(name)
        if (renderHud()) main.postDelayed(hudTick, HUD_TICK_MS)
    }

    /**
     * 切到第 [index] 个库并播放。
     *
     * 三处关键点：
     *  1) **先清掉上一个库的缓存**（剧/集/频道）。否则在视频库里按↑↓，会拿 IPTV 遗留的
     *     频道列表去换台 —— 表现就是「在《娘道》上按上键，跳回 CCTV5」。
     *  2) `librarySeq` 递增，异步结果回来时对不上就丢弃。连按左右时不会有旧任务
     *     把界面覆盖回上一个库。
     *  3) 剧/集按库缓存，切剧只做一次列目录，不再每次按键都重新问 NAS。
     */
    private fun selectLibrary(
        index: Int,
        resumeShow: String,
        resumeIndex: Int,
        resumePos: Long,
        dir: Int = 1,
        /** 已经因为「这个库是空的」跳过了几个库 —— 给跳过封顶，见 [refreshShows]。 */
        skipDepth: Int = 0,
    ) {
        if (libraries.isEmpty()) return
        if (Navigator.skipExhausted(skipDepth, libraries.size)) {
            // 转了一整圈，一个能播的都没找到。老版本这里会**无限跳**：每跳一次一轮 SMB
            // 列目录，屏幕上就是唰唰唰跳个不停、永远不停下来，也不说为什么（实机反馈）。
            // 现在停下来把话说清楚。
            trace("selectLibrary 跳过 ${skipDepth} 个库仍无内容 -> 报故障")
            showFault("${libraries.size} 个媒体库里都没有能播放的视频", retry = false)
            return
        }
        libIndex = ((index % libraries.size) + libraries.size) % libraries.size
        trace("selectLibrary idx=$index -> ${libraries[libIndex].name} resumeShow=[$resumeShow] resumeEp=$resumeIndex dir=$dir")

        // 换库即作废上一个库的所有缓存
        channels = emptyList()
        channelIndex = 0
        shows = emptyList()
        episodes = emptyList()
        showName = ""
        episodeIndex = 0
        cachedShow = ""
        cachedShowsLib = ""

        val seq = ++librarySeq
        val lib = libraries[libIndex]
        val cfg = Config.smb(this)
        // 开机要回到「上次用的库」，直播库也算 —— 所以在这里记，而不是只在点播起播时记
        rememberLibrary(lib.name)

        // 立刻告诉用户「键收到了，正在打开哪个库」。切库要等 SMB 列目录，
        // 原来这段时间屏幕上什么都没变，看起来就是卡住（用户实测反馈）。
        announceLibrary(lib.name)

        when (lib) {
            is Library.Video -> {
                // 缓存里有这个库的剧列表就先用着，画面马上出来，不必等 NAS
                val cachedShows = cache?.showsOf(lib.name).orEmpty()
                if (cachedShows.isNotEmpty()) {
                    cachedShowsLib = lib.name
                    shows = cachedShows
                    val found = cachedShows.indexOf(resumeShow)
                    playShow(
                        lib,
                        if (found >= 0) found else 0,
                        if (found >= 0) resumeIndex else 0,
                        if (found >= 0) resumePos else 0L,
                        seq,
                    )
                    // 注意：这里**不能** return —— 还要去后台刷新一次。
                    // 刷新回来发现首轮已经有内容在播，就不会再切一次（见 refreshShows）。
                }
                refreshShows(lib, cfg, seq, resumeShow, resumeIndex, resumePos, dir, skipDepth)
            }

            is Library.Live -> loadChannels(lib, Config.channel(this), seq)
        }
    }

    /** 后台刷新剧列表：拿到新结果就更新缓存与界面，拿不到就继续用缓存。 */
    private fun refreshShows(
        lib: Library.Video,
        cfg: Config.Smb,
        seq: Int,
        resumeShow: String,
        resumeIndex: Int,
        resumePos: Long,
        dir: Int,
        skipDepth: Int = 0,
    ) {
        ioHandler.post {
            val list = try {
                Scanner.shows(cfg, lib)
            } catch (t: Throwable) {
                // 有缓存就没必要打扰用户；没有才报故障
                if (shows.isEmpty()) postIf(seq) { showFault(SmbClient.describe(t), retry = true) }
                return@post
            }
            val snap = LibraryCache.withShows(cache ?: LibraryCache.withLibraries(null, libraries), lib.name, list)
            commitCache(snap)
            postIf(seq) {
                if (list.isEmpty()) {
                    // 这个库一部剧都没有：换下一个库（DESIGN §4），但跳过要有上限 ——
                    // 一圈都跳完还没内容就说明是 NAS 那边的事，报出来而不是继续转。
                    trace("refreshShows 《${lib.name}》一部剧都没有，跳过（第 ${skipDepth + 1} 个）")
                    selectLibrary(libIndex + dir, "", 0, 0L, dir, skipDepth + 1)
                    return@postIf
                }
                val firstRun = shows.isEmpty()
                cachedShowsLib = lib.name
                shows = list
                if (firstRun) {
                    val found = list.indexOf(resumeShow)
                    playShow(lib, if (found >= 0) found else 0, if (found >= 0) resumeIndex else 0, if (found >= 0) resumePos else 0L, seq)
                }
            }
        }
    }

    /**
     * 换库：循环，并接着看**目标库里最近看的那部**。
     *
     * 「拿 A 库的剧名去 B 库找」这类串库问题在这里从结构上消失了：
     * 记录是按「库 + 剧」存的（[WatchHistory]），查出来的必然属于这个库。
     */
    private fun switchLibrary(delta: Int) {
        if (libraries.isEmpty()) return
        trace("switchLibrary delta=$delta from=${libraries.getOrNull(libIndex)?.name}")
        // 离开这个库之前先把它看到哪儿了写下来
        saveRecord(force = true)
        val target = Navigator.horizontal(libIndex, libraries.size, delta)
        val lib = libraries[target]
        val rec = (lib as? Library.Video)?.let { history.mostRecentIn(it.name) }
        selectLibrary(
            target,
            resumeShow = rec?.show.orEmpty(),
            resumeIndex = rec?.episode ?: 0,
            resumePos = rec?.resumeMs ?: 0L,
            dir = if (delta == 0) 1 else delta,
        )
    }

    /** ↑ / ↓：决策交给 [Navigator]（有单元测试钉住），这里只负责执行。 */
    private fun switchShowOrChannel(delta: Int) {
        if (libraries.isEmpty()) return
        trace("switchShowOrChannel delta=$delta lib=${libraries.getOrNull(libIndex)?.name} shows=${shows.size} chans=${channels.size}")
        // 先把这个库当前看到哪儿了写下来，再换 —— 否则刚看的这几秒会丢
        saveRecord(force = true)
        val libNow = libraries.getOrNull(libIndex)
        val action = Navigator.vertical(
            lib = libNow,
            shows = shows,
            channels = channels,
            current = currentContent(),
            delta = delta,
            // 换到哪部剧，就接着那部剧上次的地方看（用户实测的「换回来只能从头看」）
            resume = { show -> resumeOf((libNow as? Library.Video)?.name.orEmpty(), show) },
        )
        when (action) {
            is Navigator.Action.PlayShow -> {
                val lib = libraries.getOrNull(libIndex) as? Library.Video ?: return
                // 先把剧名打出来：列剧集目录还要等一下，不能让屏幕一动不动
                shows.getOrNull(action.showIndex)?.let { announce(it) }
                playShow(lib, action.showIndex, action.episodeIndex, action.startMs, librarySeq)
            }

            is Navigator.Action.TuneChannel -> {
                channelIndex = action.channelIndex
                Config.saveChannel(this, channelIndex)
                playChannel()
            }

            // 缓存没就绪：什么都不做，不猜也不跳（DESIGN：宁可慢一拍，不要跳错）
            Navigator.Action.Wait -> Unit
        }
    }

    /** 当前正在播什么，按**库类型**给出，避免把剧名和频道名混在一起。 */
    private fun currentContent(): Navigator.Current = when (libraries.getOrNull(libIndex)) {
        is Library.Video -> if (showName.isEmpty()) Navigator.Current.None else Navigator.Current.Show(showName)
        is Library.Live -> channels.getOrNull(channelIndex)
            ?.let { Navigator.Current.Channel(it.name) } ?: Navigator.Current.None
        null -> Navigator.Current.None
    }

    /** 当前库的剧列表。内存 → 落盘缓存 → 最后才问 NAS。 */
    private fun loadShows(lib: Library.Video): List<String> {
        if (cachedShowsLib == lib.name && shows.isNotEmpty()) return shows
        val cached = cache?.showsOf(lib.name).orEmpty()
        if (cached.isNotEmpty()) {
            cachedShowsLib = lib.name
            shows = cached
            return cached
        }
        val list = try {
            Scanner.shows(Config.smb(this), lib)
        } catch (t: Throwable) {
            emptyList()
        }
        if (list.isNotEmpty()) {
            cachedShowsLib = lib.name
            shows = list
        }
        return list
    }

    private fun playShow(
        lib: Library.Video,
        showIdx: Int,
        epIdx: Int,
        startMs: Long,
        seq: Int,
    ) {
        if (shows.isEmpty()) return
        val wanted = shows[showIdx.coerceIn(0, shows.size - 1)]
        showName = wanted
        val cfg = Config.smb(this)

        // 这部剧的集列表如果已经在缓存里（且是靠后缀认出来的），直接开播，不再问 NAS。
        // 「切换很慢」的主因就是这个：以前每按一次上下键都要重新列一遍剧集目录。
        val cached = cache?.episodesOf(wanted)
        if (!cached.isNullOrEmpty()) {
            episodes = cached
            cachedShow = wanted
            episodeIndex = epIdx.coerceIn(0, cached.size - 1)
            trace("playShow 《$wanted》用缓存集列表 ${cached.size} 集 -> 直接起播")
            playEpisode(startMs)
            // 缓存还很新就别再去问 NAS。这次刷新挂着**同一把 SMB 锁**，
            // 而播放器此刻正在读首帧要用的数据 —— 实测切换首帧会因此多等 100~300ms。
            // 冷启动、以及缓存过期后仍然会刷新，NAS 上新加的集不会一直看不到。
            if (cacheIsFresh()) {
                trace("playShow 《$wanted》缓存还新（${cacheAgeSec()}s），不起后台刷新")
                return
            }
        }

        ioHandler.post {
            val eps = try {
                Scanner.episodesDetailed(cfg, lib, wanted)
            } catch (t: Throwable) {
                trace("playShow 《$wanted》列集失败：${t.message}")
                if (episodes.isEmpty()) postIf(seq) { showFault(SmbClient.describe(t), retry = true) }
                return@post
            }
            trace("playShow 《$wanted》列到 ${eps.names.size} 集 byContent=${eps.byContent}")
            val snap = LibraryCache.withEpisodes(cache, wanted, eps.names, eps.byContent)
            commitCache(snap)
            postIf(seq) {
                when {
                    eps.names.isEmpty() -> {
                        // 无视频文件的文件夹一律跳过（DESIGN §4），但同样要**封顶**：
                        // 老行为是每 3 秒换下一部剧，一圈下来又回到第一部，永远转下去，
                        // 而且每转一次就是一轮 SMB 列目录。转完一圈就停下来把话说清楚。
                        emptyShowStreak++
                        if (Navigator.skipExhausted(emptyShowStreak, shows.size)) {
                            trace("playShow 连续 $emptyShowStreak 部剧都是空的 -> 停止自动换剧")
                            emptyShowStreak = 0
                            showFault("《${lib.name}》里没有能播放的视频", retry = false)
                        } else {
                            showFault("《$wanted》里没有能播放的视频", retry = false)
                            main.postDelayed({ switchShowOrChannel(1) }, 3000)
                        }
                    }
                    // 首轮已经在播了就别切，免得画面刚出来又被重启
                    cachedShow == wanted -> Unit
                    else -> {
                        episodes = eps.names
                        cachedShow = wanted
                        episodeIndex = epIdx.coerceIn(0, eps.names.size - 1)
                        playEpisode(startMs)
                    }
                }
            }
        }
    }

    /** 回到主线程执行，但只在「还是同一轮切库」时才执行。 */
    private inline fun postIf(seq: Int, crossinline block: () -> Unit) {
        main.post { if (seq == librarySeq) block() }
    }

    /**
     * 排查用的调用链日志。
     *
     * 按键/切库这类问题的现象是「它自己跳到别的库去了」，光看代码推不出来是哪条路进去的，
     * 必须有调用链。默认关闭，只在排查时把 [DEBUG_TRACE] 改成 true 重新打包。
     */
    private fun trace(msg: String) {
        if (DEBUG_TRACE) Log.i(TAG_TRACE, msg)
    }

    private fun playEpisode(startMs: Long) {
        val lib = libraries.getOrNull(libIndex) as? Library.Video ?: return
        adHocPath = null // 走正常导航了，临时点名的文件作废
        val ep = episodes.getOrNull(episodeIndex) ?: return
        // 真有东西要播了：把「连续空剧」的计数清掉（它只用来给自动换剧封顶）
        emptyShowStreak = 0
        val path = Scanner.episodePath(lib, showName, ep)
        currentTitle = showName
        // 先出名字，再去做后面那些可能慢的事
        announce(showName)
        // 立刻落一条记录：换集/换剧之后马上拔电源，起来的也是这一集
        lastSavedPos = startMs
        lastSavedAt = System.currentTimeMillis()
        history = WatchHistory.with(
            history,
            WatchHistory.Record(lib.name, showName, episodeIndex, startMs.coerceAtLeast(0L), lastSavedAt),
        )
        Config.saveWatchHistoryText(this, WatchHistory.serialize(history))
        trace("playEpisode 《$showName》[$ep] startMs=$startMs surfaceUsable=${surfaceUsable()}")

        if (!surfaceUsable()) {
            // Surface 还没建好：排队等着，并在 surfaceCreated 或超时时处理
            pendingEpisode = PendingEpisode(path, startMs, showName)
            main.removeCallbacks(pendingTimeout)
            main.postDelayed(pendingTimeout, SURFACE_WAIT_MS)
            main.postDelayed(surfacePoll, SURFACE_POLL_MS)
            return
        }
        playEpisodeNow(path, startMs)
        // 音频探测**故意不在这里做**：它要读 1MB，走的是同一条 SMB 连接，
        // 会和播放器读首帧抢锁（实测切换首帧为此多等 100~300ms）。
        // 挪到首帧之后做，结论一样，代价为零 —— 见 onFirstFrame()。
        pendingAudioCheck = path
    }

    /**
     * 排队等 Surface 时的轮询兜底。
     *
     * 不想只依赖 `surfaceCreated`：它是回调，不是状态 —— 回调错过一次就永远不会再来。
     * 每 500ms 自己问一次「Surface 现在能用了吗」，问到了就起播，问不到就一直等
     * （直到 [pendingTimeout] 兜底报故障）。
     */
    private val surfacePoll = object : Runnable {
        override fun run() {
            if (pendingEpisode == null) return
            if (surfaceUsable()) {
                launchPendingEpisode()
            } else {
                main.postDelayed(this, SURFACE_POLL_MS)
            }
        }
    }

    /**
     * 起播前认一下音频编码。
     *
     * 实测：ijkplayer 的内核没有 AC-3 解码器，而《娘道》76 集全是 AC-3 ——
     * 表现是「画面好好的，就是没声音」，用户只能反复重启电视。
     * 认出来就直说，并且明确「画面能看」，别让人以为是电视坏了。
     *
     * 只读开头 1MB，纯字节解析，不依赖任何解码器，所以结论是确定的。
     */
    private fun checkAudio(path: String) {
        audioWarning = null
        val cfg = Config.smb(this)
        ioHandler.post {
            val head = runCatching {
                SmbStore.with(cfg) { it.head(path, PROBE_BYTES) }
            }.getOrNull() ?: return@post
            val verdict = AudioSupport.probeFor(PlaybackMode.Kind.ON_DEMAND, head)
            trace("checkAudio head=${head.size}B 判定=${verdict.codec} canProbe=${verdict.canProbe} 提示=${verdict.warning}")
            val warn = verdict.warning ?: return@post
            main.post {
                audioWarning = warn
                Log.i(TAG, "音频提示：$warn")
            }
        }
    }

    private class PendingEpisode(val path: String, val startMs: Long, val show: String)
    private var pendingEpisode: PendingEpisode? = null

    /**
     * 等首帧之后再跑的音频探测（路径）。
     *
     * 见 [playEpisode]：探测本身很便宜，但读的那 1MB 会走 SMB 抢锁，
     * 放在起播路径上就是白白拖慢切换。
     */
    private var pendingAudioCheck: String? = null

    /** 一集播完自动下一集 → 全剧播完下一部剧 → 最后一部回到该库第一部（DESIGN §5）。 */
    private fun onEpisodeFinished() {
        trace("onEpisodeFinished lib=${libraries.getOrNull(libIndex)?.name} show=[$showName] ep=$episodeIndex/${episodes.size}")
        if (episodeIndex + 1 < episodes.size) {
            episodeIndex++
            playEpisode(0L)
            return
        }
        // 全剧播完 → 下一部剧（用缓存的剧列表，不再问 NAS）
        val lib = libraries.getOrNull(libIndex) as? Library.Video ?: run {
            playEpisode(0L)
            return
        }
        val list = loadShows(lib)
        if (list.isEmpty()) {
            playEpisode(0L)
        } else {
            val cur = list.indexOf(showName)
            val next = if (cur < 0) 0 else (cur + 1) % list.size
            playShow(lib, next, 0, 0L, librarySeq)
        }
    }

    private fun loadChannels(lib: Library.Live, startIndex: Int, seq: Int) {
        // 频道列表按库缓存：换台时不该每次重拉一遍 m3u
        if (channelLib == lib.name && channels.isNotEmpty()) {
            channelIndex = startIndex.coerceIn(0, channels.size - 1)
            playChannel()
            return
        }
        // 落盘缓存里也有：冷启动换台不必等 NAS
        val cached = cache?.channels?.get(lib.name)
        if (!cached.isNullOrEmpty()) {
            channels = cached
            channelLib = lib.name
            channelIndex = startIndex.coerceIn(0, cached.size - 1)
            playChannel()
        }
        val cfg = Config.smb(this)
        ioHandler.post {
            val list = try {
                LiveSource.channels(cfg, lib)
            } catch (t: Throwable) {
                if (channels.isEmpty()) postIf(seq) { showFault("直播源暂时无法加载", retry = true) }
                return@post
            }
            commitCache(LibraryCache.withChannels(cache, lib.name, list))
            postIf(seq) {
                if (list.isEmpty()) {
                    showFault("直播源暂时无法加载", retry = true)
                } else {
                    val firstRun = channels.isEmpty()
                    channels = list
                    channelLib = lib.name
                    if (firstRun) {
                        channelIndex = startIndex.coerceIn(0, list.size - 1)
                        playChannel()
                    }
                }
            }
        }
    }

    private fun playChannel() {
        val ch = channels.getOrNull(channelIndex) ?: return
        currentTitle = ch.name
        // 换台就是走正常导航了：诊断页那行「临时文件」得跟着作废，
        // 否则会挂着上一次 `open` 打开的测试片路径（实机看到过 live+file 这种四不像状态）
        adHocPath = null
        announce(ch.name) // 立刻出频道名，换台不能看起来没反应
        // 上一个频道留下的「信号中断」提示要马上撤掉，否则换到好频道也还挂着那句话
        showFault(null)
        prepared = false
        audioStarted = false
        engine.setMode(PlaybackMode.Kind.LIVE)
        engine.setLiveReconnect(true) { channels.getOrNull(channelIndex)?.url }
        engine.playUrl(ch.url)
    }

    // ---- 浮层 ----

    private fun toggleOverlay() {
        if (overlay.visibility == View.VISIBLE) {
            hideOverlay()
            return
        }
        Log.i(TAG, "开浮层：${ui().describe()}")
        renderOverlay()
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(220).start()
        main.removeCallbacks(overlayHide)
        main.postDelayed(overlayHide, OVERLAY_MS)
        speakOverlay()
    }

    private fun hideOverlay() {
        main.removeCallbacks(overlayHide)
        if (overlay.visibility != View.VISIBLE) return
        overlay.animate().alpha(0f).setDuration(320).withEndAction {
            overlay.visibility = View.GONE
        }.start()
    }

    // ---- 诊断页（长按 OK / 遥控器「信息」键）----

    private fun toggleDiag() {
        if (diagScreen.visibility == View.VISIBLE) {
            hideDiag()
            return
        }
        hideOverlay()
        hud.dismiss()
        renderHud()
        // 进诊断页时把起点归零，否则第一次读到的「读取速率」是上一段时间的平均值
        lastDiagTraffic = -1L
        // 当前方案（旋钮组合）同步成真值：进程重启后引擎侧会回到默认值，
        // 而面板要显示的是「现在跑的是哪一档」，不是「上次按到过哪一档」
        if (EXPERIMENTS) experimentIndex = Knobs.presetIndex(this).coerceAtLeast(0)
        renderDiag()
        diagScreen.visibility = View.VISIBLE
        main.removeCallbacks(diagTick)
        main.postDelayed(diagTick, DIAG_INTERVAL_MS)
        main.removeCallbacks(diagHide)
        main.postDelayed(diagHide, DIAG_HIDE_MS)
        Log.i(TAG, "诊断页已打开：${engine.diag()}")
    }

    private fun hideDiag() {
        main.removeCallbacks(diagTick)
        main.removeCallbacks(diagHide)
        if (diagScreen.visibility != View.VISIBLE) return
        diagScreen.visibility = View.GONE
    }

    private val diagHide = Runnable { hideDiag() }

    /** 上一次采样的累计读取字节数与时刻，用来算实时读取速率。 */
    private var lastDiagTraffic = -1L
    private var lastDiagAt = 0L

    /**
     * 面板那一屏的行。
     *
     * 抽成独立函数是为了让**远程诊断（HTTP `/state`）拿到的是同一份内容**：
     * 我在电脑上看到的数字必须和用户电视上那一屏逐字一致，否则「他说 18.2、我看到 25」
     * 这种分歧会浪费一整轮排查。
     *
     * 行数上限 12（[DiagScreen.MAX_ROWS]），所以「兜底」这类只在出事时才占一行。
     */
    private fun diagRows(): List<DiagScreen.Line> {
        val d = engine.diag()
        val now = System.currentTimeMillis()
        val dt = now - lastDiagAt
        val readBps = if (lastDiagTraffic >= 0 && lastDiagAt > 0 && dt > 0 && d.trafficBytes >= lastDiagTraffic) {
            (d.trafficBytes - lastDiagTraffic) * 1000 / dt
        } else {
            0L
        }
        lastDiagTraffic = d.trafficBytes
        lastDiagAt = now

        val u = ui()
        val lines = ArrayList<DiagScreen.Line>(12)

        // 1) 屏幕：4K 电视「界面缩在中间」的根因就在这一行
        //    （逻辑分辨率与 dpi 是否自洽，直接决定缩放倍数）
        lines += DiagScreen.Line("屏幕", "${u.metricsLine()} @${PlaybackVerdict.one(refreshRateHz())}Hz")

        // 2) 画面：视频原始尺寸 + **编码** + **片源自己的帧率**（用户那句「25 甚至更低」的对照物）。
        //    编码必须显示：这台电视 4K HEVC 只有 ~18 帧/秒，而 4K H.264 能满帧，
        //    看不出编码就分不清是「片源太重」还是「编码踩坑」（2026-09-12 实机教训）。
        val size = if (d.videoWidth > 0) "${d.videoWidth}×${d.videoHeight}" else "还没解出画面"
        val codec = d.videoImpl?.trim()?.takeIf { it.isNotEmpty() }?.let { " ${it.uppercase()}" }.orEmpty()
        val srcFps = if (d.sourceFps > 0f) " · 片源 ${PlaybackVerdict.one(d.sourceFps)} 帧/秒" else ""
        lines += DiagScreen.Line("画面", "$size$codec$srcFps → 输出 ${surfaceView.width}×${surfaceView.height}")

        // 3) 解码通路：真值（ijkplayer 会静默回落软解，见 PlaybackEngine.decoderInUse）
        val dec = when (d.decoder) {
            PlaybackEngine.Decoder.HARDWARE -> "硬解 ${d.decoderName ?: d.videoImpl ?: ""}"
            PlaybackEngine.Decoder.SOFTWARE ->
                if (d.requestedHardware && d.codecOffered) "软解（硬解没建成！）" else "软解"

            PlaybackEngine.Decoder.UNKNOWN -> "还没起播"
        }
        lines += DiagScreen.Line(
            "解码",
            dec.trim(),
            warn = d.decoder == PlaybackEngine.Decoder.SOFTWARE && d.requestedHardware && d.codecOffered,
        )

        // 4) 帧率三兄弟：解码 / 送显 / 丢帧。
        //    ⚠️ 丢帧是**比例**（0~1，来自 drop_frame_count/decode_frame_count），不是每秒帧数
        lines += DiagScreen.Line(
            "帧率",
            "解码 ${PlaybackVerdict.one(d.decodeFps)} · 送显 ${PlaybackVerdict.one(d.outputFps)} · " +
                "丢帧 ${(d.dropFps * 100).toInt()}%",
            warn = d.sourceFps > 0f && d.outputFps > 0f &&
                d.outputFps < d.sourceFps * PlaybackVerdict.FPS_TOLERANCE,
        )

        // 4b) 像素率：电视视频通路「每秒能过多少像素」的尺子。
        //     真机上《大宅门》掉到 18.2 帧/秒 × 3840×2160 = 151 Mpx/秒，
        //     正好等于《猫和老鼠》的 153 —— 这一行就是用来验证那个天花板的
        val outMpx = d.outputFps * d.videoWidth * d.videoHeight / 1e6
        val srcMpx = d.sourceFps * d.videoWidth * d.videoHeight / 1e6
        if (d.videoWidth > 0 && d.outputFps > 0f) {
            lines += DiagScreen.Line(
                "像素率",
                "送显 ${PlaybackVerdict.one(outMpx.toFloat())} Mpx/秒" +
                    if (d.sourceFps > 0f) "（片源要 ${PlaybackVerdict.one(srcMpx.toFloat())}）" else "",
                warn = d.sourceFps > 0f && outMpx < srcMpx * PlaybackVerdict.FPS_TOLERANCE,
            )
        }

        // 5) 缓冲与读取（合成一行：两行留给「兜底 / 方案 / 远程」那些更有用的条件行）
        val need = if (d.bitRateBps > 0) "（要 ${PlaybackVerdict.mb(d.bitRateBps / 8)}）" else ""
        lines += DiagScreen.Line(
            "缓冲",
            "${d.cachedMs} 毫秒 / ${PlaybackVerdict.mb(d.cachedBytes)} MB · " +
                "读 ${PlaybackVerdict.mb(readBps)} MB/秒$need",
        )

        // 6) 音频
        lines += DiagScreen.Line(
            "音频",
            listOfNotNull(d.audioCodec?.takeIf { it.isNotBlank() }, if (audioStarted) "已出声" else "还没出声")
                .joinToString(" · "),
        )

        // 7) 结论：这一行才是「该动哪里」的答案
        val verdict = PlaybackVerdict.of(
            PlaybackVerdict.Input(
                playing = prepared,
                decoder = d.decoder,
                requestedHardware = d.requestedHardware,
                codecOffered = d.codecOffered,
                videoWidth = d.videoWidth,
                videoHeight = d.videoHeight,
                // 结论要按**编码**分（HEVC 的 4K 是死的、H.264 的 4K 是活的），
                // 所以这里必须把解码器报出来的编码名传下去
                videoCodec = d.videoImpl ?: "",
                sourceFps = d.sourceFps,
                decodeFps = d.decodeFps,
                outputFps = d.outputFps,
                dropRatio = d.dropFps,
                cachedMs = d.cachedMs,
                readBytesPerSec = readBps,
                bitRateBps = d.bitRateBps,
            ),
        )
        lines += DiagScreen.Line("结论", verdict.text, warn = verdict.problem)

        // 8) 兜底记录：**正常时不占行**，只有真出过事才显示
        if (d.silentFallbacks > 0 || d.bannedCodecs.isNotEmpty()) {
            lines += DiagScreen.Line(
                "兜底",
                "静默退回软解 ${d.silentFallbacks} 次" +
                    if (d.bannedCodecs.isEmpty()) "" else " · 已禁用 ${d.bannedCodecs.joinToString("、")}",
                warn = true,
            )
        }

        // 9) 实验方案：单击「设置键」当场切换（会重播）。
        //    组合对不上任何一档预设时，直接把非默认的旋钮列出来 ——
        //    否则「远程改了单个旋钮」之后面板会显示成上一档，看的人会被误导。
        if (EXPERIMENTS) {
            val pi = Knobs.presetIndex(this)
            val label = if (pi >= 0) {
                "${pi + 1}/${Knobs.PRESETS.size} ${Knobs.PRESETS[pi].label}"
            } else {
                // 自定义组合：只列前两项 —— 多了会把这一行撑成三行、把面板顶出屏幕
                val nd = Knobs.nonDefault(this)
                "自定义 " + nd.take(2).joinToString(" ") { "${it.first}=${it.second}" } +
                    if (nd.size > 2) " 等${nd.size}项" else ""
            }
            lines += DiagScreen.Line("实验", "$label（设置键切换，会重播）", warn = pi != 0)
        }

        // 10) 远程诊断地址：用户要把它念给我（没网时说明白是「没有网络」而不是空白）
        if (EXPERIMENTS) {
            lines += DiagScreen.Line("远程", DiagHub.url ?: "没有网络，开不了远程诊断")
        }

        // 11) 正在播什么（用户报问题时对着这一行说就够了）
        lines += DiagScreen.Line("内容", currentTitle.ifBlank { "—" })

        return lines
    }

    private fun renderDiag() {
        diagScreen.show("播放诊断", diagRows(), "OK 键关闭（30 秒后自动关闭）")
    }

    // ---- 远程诊断（局域网 HTTP；见 com.firefly.tv.diag.DiagHub）----
    //
    // 用户提的办法（原话）：「你可以从这个接口看到你想要的数据并进行简单操作」。
    // 电视上只有画面、没有 adb，而这次要查的恰恰是「换一个变量、量一次数字」，
    // 于是把现场快照 + 几个动作暴露成 HTTP：我在这边读数字/改旋钮，用户在电视前看画面。
    //
    // 两个设计约束：
    //  - **只在 debug 构建里开**（release 不该留一个无鉴权端口）；
    //  - **面板和接口同一份数据**（都走 [diagRows]），避免「他说 18.2、我看到 25」这种分歧。

    private val diagControl = object : DiagHub.Control {

        override fun state(): DiagHub.Live {
            val d = engine.diag()
            val u = ui()
            val dm = u.metrics
            val rows = runCatching { diagRows() }.getOrDefault(emptyList())
            val frame = runCatching { surfaceView.holder.surfaceFrame }.getOrNull()
            val verdictRow = rows.firstOrNull { it.label == "结论" }
            return DiagHub.Live(
                title = currentTitle,
                kind = engine.kindName().ifEmpty {
                    if (contentKind() == AudioFocusPolicy.Content.Live) "live" else "on_demand"
                } + if (adHocPath != null) "+file" else "",
                libraryName = libraries.getOrNull(libIndex)?.name.orEmpty(),
                libraryIndex = libIndex,
                libraries = libraries.map { it.name },
                showName = showName,
                showCount = shows.size,
                episodeCount = episodes.size,
                fault = runCatching { faultScreen.current() }.getOrDefault(""),
                positionMs = engine.positionMs(),
                durationMs = engine.durationMs(),
                screenW = dm.widthPixels,
                screenH = dm.heightPixels,
                dpi = dm.densityDpi,
                density = dm.density,
                refreshHz = refreshRateHz(),
                uiScale = u.scale,
                surfaceW = surfaceView.width,
                surfaceH = surfaceView.height,
                // SurfaceHolder 报的**面**尺寸：如果解码器把面重配成了 4K，这里会跟着变
                surfaceFrame = if (frame != null) "${frame.width()}×${frame.height()}" else "",
                surfaceFormat = Knobs.get(this@MainActivity, Knobs.K_FORMAT),
                surfaceZOrder = Knobs.zOrder(this@MainActivity),
                surfaceFixed = Knobs.fixedSize(this@MainActivity),
                videoW = d.videoWidth,
                videoH = d.videoHeight,
                sourceFps = d.sourceFps,
                decodeFps = d.decodeFps,
                presentFps = d.outputFps,
                dropRatio = d.dropFps,
                cachedMs = d.cachedMs,
                cachedBytes = d.cachedBytes,
                trafficBytes = d.trafficBytes,
                bitRateBps = d.bitRateBps,
                decoder = when (d.decoder) {
                    PlaybackEngine.Decoder.HARDWARE -> "hardware"
                    PlaybackEngine.Decoder.SOFTWARE -> "software"
                    PlaybackEngine.Decoder.UNKNOWN -> "unknown"
                },
                decoderName = d.decoderName,
                videoModule = d.videoModule,
                videoImpl = d.videoImpl,
                audioCodec = d.audioCodec,
                audioStarted = audioStarted,
                requestedHardware = d.requestedHardware,
                codecOffered = d.codecOffered,
                silentFallbacks = d.silentFallbacks,
                banned = d.bannedCodecs,
                prepared = prepared,
                panelVisible = diagScreen.visibility == View.VISIBLE,
                verdict = verdictRow?.value.orEmpty(),
                verdictProblem = verdictRow?.warn == true,
                panelRows = rows.map { DiagHub.Row(it.label, it.value) },
                extra = extraRows(),
            )
        }

        override fun act(a: DiagHub.Act): String = when (a.name) {
            // 方案号从 1 开始（面板上写的就是「3/12」），0/缺省 = 下一档
            "preset" -> {
                val n = a.int("n", 0)
                applyPreset(if (n <= 0) (experimentIndex + 1) % Knobs.PRESETS.size else n - 1)
            }

            "preset_next" -> applyPreset((experimentIndex + 1) % Knobs.PRESETS.size)

            // 旋钮已经在 DiagHub 里写进 prefs 了，这里只管「重新建面 + 重播」让它生效
            "set", "reset" -> {
                applyKnobs(replay = a.bool("replay", true))
                "旋钮已生效" + if (a.bool("replay", true)) "，正在重播" else ""
            }

            "replay" -> {
                applyKnobs(replay = true, resumeMs = a.long("ms", -1L).takeIf { it >= 0 })
                "已重播"
            }

            "pause" -> {
                engine.pause()
                "已暂停"
            }

            "resume" -> {
                engine.resume()
                "已继续"
            }

            "seek" -> {
                val d = a.long("d", Long.MIN_VALUE)
                val ms = if (d != Long.MIN_VALUE) engine.positionMs() + d else a.long("ms", 0L)
                engine.seekTo(ms)
                "已跳到 $ms 毫秒"
            }

            // 直接开一个 NAS 上的文件：让我能换片源对比（例如找一份 1080p 的《大宅门》）
            "open" -> {
                val path = a.str("path")
                if (path.isBlank()) {
                    "要给 path（相对共享根目录，例如 电视剧/大宅门/01.mkv）"
                } else {
                    adHocPath = path
                    currentTitle = a.str("title", path.substringAfterLast('/'))
                    playEpisodeNow(path, a.long("ms", 0L))
                    "正在打开 $path"
                }
            }

            "panel" -> {
                if (a.bool("on", true)) {
                    if (diagScreen.visibility != View.VISIBLE) toggleDiag()
                } else {
                    hideDiag()
                }
                "面板" + if (diagScreen.visibility == View.VISIBLE) "已打开" else "已关闭"
            }

            "key" -> when (a.str("n").lowercase()) {
                "ok", "center", "enter" -> {
                    onOkPressed(); "ok"
                }

                "left" -> {
                    switchLibrary(-1); "left"
                }

                "right" -> {
                    switchLibrary(+1); "right"
                }

                "up" -> {
                    switchShowOrChannel(-1); "up"
                }

                "down" -> {
                    switchShowOrChannel(+1); "down"
                }

                "settings", "menu", "info" -> {
                    toggleDiag(); "settings"
                }

                else -> "认不得这个键：${a.str("n")}"
            }

            "stop" -> {
                engine.stop()
                "已停"
            }

            "state" -> "state 走 /state"

            else -> "认不得这个动作：${a.name}（可用：preset/preset_next/set/reset/replay/pause/resume/seek/open/panel/key/stop）"
        }
    }

    /** 远程快照里的「杂项」行：旋钮、面尺寸变化历史、媒体与构建信息。 */
    private fun extraRows(): List<DiagHub.Row> {
        val rows = ArrayList<DiagHub.Row>(6)
        val pi = Knobs.presetIndex(this)
        rows += DiagHub.Row(
            "预设",
            if (pi >= 0) "${pi + 1}/${Knobs.PRESETS.size} ${Knobs.PRESETS[pi].label}" else "自定义",
        )
        rows += DiagHub.Row(
            "非默认旋钮",
            Knobs.nonDefault(this).joinToString(" ") { "${it.first}=${it.second}" }.ifEmpty { "（无）" },
        )
        rows += DiagHub.Row("面尺寸变化", surfaceChanges.joinToString(" ").ifEmpty { "（没报过）" })
        rows += DiagHub.Row("媒体", "${engine.diag().videoModule ?: "?"} / ${engine.diag().videoImpl ?: "?"}")
        rows += DiagHub.Row("构建", "debug=${BuildConfig.DEBUG} abi=${Build.SUPPORTED_ABIS.joinToString(",")}")
        rows += DiagHub.Row("线程提权", if (Knobs.boostThreads(this)) "开" else "关")
        rows += DiagHub.Row("临时文件", adHocPath ?: "（无，正在播库里的内容）")
        val io = com.firefly.tv.player.ReadStats
        rows += DiagHub.Row(
            "读路径",
            "moov 搬运 ${io.relocatedOpens} 次 · 搬运路径读 ${io.readCalls} 次 / " +
                "${io.readBytes / 1024} KB · 平均 ${io.avgBytes()} 字节/次 · 跨段 ${io.segmentCrossings}" +
                "（平均读长度小 = 上层在按小块反复读，那才是要修的读放大）",
        )
        return rows
    }

    /**
     * Surface 的尺寸/格式变化历史。
     *
     * 这条历史能直接回答一个关键问题：**视频层的缓冲到底是 1080p 还是 4K**。
     * 解码器把面重配成 4K 时 SurfaceFlinger 会再回调一次 `surfaceChanged`，
     * 所以「只有 1920×1080」和「先是 1920×1080、后来 3840×2160」是两种完全不同的证据。
     */
    private val surfaceChanges = ArrayList<String>(6)

    private var lastWeather: WeatherClient.Now? = null

    /** 上次**成功**拿到天气的时间（0 = 从没成功过）。频率策略看它（见 WeatherClient.shouldFetch）。 */
    private var lastWeatherOkAt = 0L

    /** 上次**尝试**的时间（含失败）。失败也要冷却，别拿坏 Key 一直重试把配额烧光。 */
    private var lastWeatherTryAt = 0L

    private fun renderOverlay() {
        val now = Date()
        val zone = TimeZone.getDefault()
        val clock = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        val lunar = Lunar.of(now, zone)
        val dateParts = ArrayList<String>(4)
        dateParts += SimpleDateFormat("M月d日", Locale.CHINA).format(now)
        dateParts += lunar.weekday
        dateParts += "农历${lunar.lunarFull}"
        lunar.term?.let { dateParts += it }
        lunar.festival?.let { dateParts += it }

        // 天气按老人的读法排三行：现在 / 今天 / 明天。
        // 心知同时给了实况和两天预报，没必要挤成一行。
        val w = lastWeather
        // 天气分成三块交给浮层：天气词、大号温度、两行预报。
        // 温度单独给是为了让它在卡片上明显更大（老人最先看的就是温度）。
        val condition = when {
            w != null && w.text.isNotBlank() -> "现在 ${w.text}"
            w != null -> "现在"
            Config.weather(this).ready -> "天气获取中"
            else -> "还没有设置天气"
        }
        val tempText = w?.temp?.takeIf { it.isNotBlank() }?.let { "$it°" }.orEmpty()
        val today = w?.today?.let { "今天 ${it.readable} ${it.range}" }.orEmpty()
        val tomorrow = w?.tomorrow?.let { "明天 ${it.readable} ${it.range}" }.orEmpty()

        overlay.show(
            OverlayScreen.Content(
                clock = clock,
                date = dateParts.joinToString("  "),
                condition = condition,
                temp = tempText,
                today = today,
                tomorrow = tomorrow,
                current = currentTitle,
                warning = audioWarning.orEmpty(),
            ),
        )

        // 天气过期就后台刷新，不阻塞浮层其他内容（DESIGN §8）
        maybeRefreshWeather()
    }

    /**
     * 该不该去打天气接口。
     *
     * 判断全在 [WeatherClient.shouldFetch] 里（纯函数、有测试）：
     * 成功 30 分钟内不再问，失败 5 分钟内不重试 —— 用户特意交代过频率。
     */
    private fun maybeRefreshWeather() {
        val cfg = Config.weather(this)
        if (!cfg.ready) return
        val now = System.currentTimeMillis()
        if (!WeatherClient.shouldFetch(now, lastWeatherOkAt, lastWeatherTryAt)) return
        refreshWeather()
    }

    private fun refreshWeather() {
        val cfg = Config.weather(this)
        if (!cfg.ready) return
        lastWeatherTryAt = System.currentTimeMillis()
        ioHandler.post {
            val result = runCatching { WeatherClient.fetch(cfg) }
            val ok = result.getOrNull()
            if (ok == null) {
                trace("天气获取失败：${result.exceptionOrNull()?.message}")
                return@post
            }
            main.post {
                lastWeather = ok
                lastWeatherOkAt = System.currentTimeMillis()
                // 落盘：重开电视不算「新的一次查询」，也不会一开机就是「天气获取中…」
                Config.saveWeatherCache(this, lastWeatherOkAt, ok.encode())
                if (overlay.visibility == View.VISIBLE) renderOverlay()
            }
        }
    }

    /** 开机先把上次的天气读出来顶着，等真的过期了再联网。 */
    private fun restoreWeatherCache() {
        val cached = Config.weatherCache(this) ?: return
        val now = WeatherClient.Now.decode(cached.second) ?: return
        lastWeather = now
        lastWeatherOkAt = cached.first
    }

    /**
     * 语音播报（已与用户确认文案）：
     * 「现在是下午三点二十，北京今天晴，气温二十五度，明天多云，十八到二十六度」
     */
    private fun speakOverlay() {
        if (!tts.available) return
        val now = Date()
        val w = lastWeather
        val sb = StringBuilder()

        val hour = SimpleDateFormat("H", Locale.CHINA).format(now).toIntOrNull() ?: 0
        val minute = SimpleDateFormat("m", Locale.CHINA).format(now).toIntOrNull() ?: 0
        sb.append("现在是")
        sb.append(partOfDay(hour))
        sb.append(chineseHour(hour))
        sb.append("点")
        if (minute > 0) {
            sb.append(chineseNumber(minute))
            sb.append("分")
        }

        if (w != null) {
            // 文案：现在是下午三点二十，北京晴，气温十七度，今天十一到二十七度，明天多云，九到二十八度
            if (w.place.isNotBlank()) sb.append("，").append(w.place)
            sb.append(w.text)
            sb.append("，气温")
            sb.append(chineseNumber(w.temp.toIntOrNull() ?: 0))
            sb.append("度")
            w.today?.let { t ->
                sb.append("，今天")
                sb.append(chineseNumber(t.tempMin.toIntOrNull() ?: 0))
                sb.append("到")
                sb.append(chineseNumber(t.tempMax.toIntOrNull() ?: 0))
                sb.append("度")
            }
            w.tomorrow?.let { t ->
                sb.append("，明天")
                sb.append(t.dayText)
                sb.append("，")
                sb.append(chineseNumber(t.tempMin.toIntOrNull() ?: 0))
                sb.append("到")
                sb.append(chineseNumber(t.tempMax.toIntOrNull() ?: 0))
                sb.append("度")
            }
        }
        tts.speak(sb.toString())
    }

    private fun partOfDay(hour: Int) = when {
        hour < 6 -> "凌晨"
        hour < 12 -> "上午"
        hour < 13 -> "中午"
        hour < 18 -> "下午"
        else -> "晚上"
    }

    private fun chineseHour(hour: Int): String {
        val h = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        return chineseNumber(h)
    }

    private fun chineseNumber(n: Int): String {
        if (n < 0) return "零"
        if (n < 11) return NUM_DIGITS[n]
        if (n < 20) return "十" + if (n == 10) "" else NUM_DIGITS[n - 10]
        val tens = n / 10
        val ones = n % 10
        return NUM_DIGITS[tens] + "十" + if (ones == 0) "" else NUM_DIGITS[ones]
    }

    /**
     * 音频焦点拿回来时重播。
     *
     * [resumeMs] 由 [AudioFocusPolicy] 给出：点播是真的进度，直播恒为 0。
     * 以前直播也走「按库类型原样重播」，而直播的 positionMs 是从开播算起的毫秒数，
     * 拿它去 seek 就是长时间音画不同步 —— 用户实测到的正是这个。
     */
    private fun replayCurrent(resumeMs: Long) {
        // 远程临时打开的文件优先：切实验档位时不能把被测对象换掉
        adHocPath?.let { playEpisodeNow(it, resumeMs); return }
        when (libraries.getOrNull(libIndex)) {
            is Library.Video -> if (episodes.isNotEmpty()) playEpisode(resumeMs)
            is Library.Live -> if (channels.isNotEmpty()) playChannel()
            null -> Unit
        }
    }

    /**
     * 申请音频焦点。电视上如果有别的应用（或系统音效）占着音频，
     * 不申请焦点会表现成「有画面没声音」。
     * 拿不到也照常播 —— 宁可没声音，也不能不出画面。
     */
    private fun requestAudioFocus() {
        val am = (getSystemService(AUDIO_SERVICE) as? android.media.AudioManager) ?: return
        audioManager = am
        runCatching {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        runCatching { audioManager?.abandonAudioFocus(focusListener) }
        audioManager = null
    }

    // ---- 故障页 ----

    private fun showFault(message: String?, retry: Boolean = false) {
        main.removeCallbacks(retryTick)
        if (message == null) {
            faultScreen.visibility = View.GONE
            return
        }
        // 故障页优先：诊断页不能盖在「出了什么事」这句话上面
        hideDiag()
        faultScreen.show(message)
        faultScreen.visibility = View.VISIBLE
        if (retry) {
            // 每 10 秒重试一次；销毁时必须 removeCallbacks，否则稳定泄漏（DESIGN §7）
            main.postDelayed(retryTick, RETRY_MS)
        }
    }

    // ---- PlaybackEngine.Listener ----

    override fun onPrepared(durationMs: Long) {
        prepared = true
        showFault(null)
        // 「正在打开…」换成内容名，1.6 秒后收起 —— 这一刻用户才算确认换台/换剧成功
        onContentPicked(currentTitle)
    }

    override fun onAudioStarted() {
        audioStarted = true
        Log.i(TAG, "音频已出声（AUDIO_RENDERING_START）")
    }

    override fun onCompletion() {
        val kind = PlaybackMode.of(libraries.getOrNull(libIndex))
        val dur = engine.durationMs()
        trace("onCompletion lib=${libraries.getOrNull(libIndex)?.name} duration=${dur}ms")
        // 时长不可信时绝不自动跳集：那种流（AC-3 的 TS）会被内核误判成「已经播完」，
        // 一集接一集地自己往前跑，用户什么都没按却停在了别的剧上。
        if (!PlaybackMode.canAutoAdvance(kind, dur)) {
            Log.w(TAG, "时长不可信（${dur}ms），不自动跳集，原地接着播")
            return
        }
        onEpisodeFinished()
    }

    override fun onError(friendlyMessage: String, fatal: Boolean) {
        hud.dismiss()
        renderHud()
        if (fatal) {
            // 单集文件损坏：3 秒后跳下一个（DESIGN §8）
            showFault(friendlyMessage, retry = false)
            main.postDelayed({
                showFault(null)
                // 这里同样要先确认时长可信，否则损坏文件会把整部剧一路跳完
                val kind = PlaybackMode.of(libraries.getOrNull(libIndex))
                if (PlaybackMode.canAutoAdvance(kind, engine.durationMs())) {
                    onEpisodeFinished()
                } else {
                    Log.w(TAG, "时长不可信，损坏后不自动跳集")
                }
            }, 3000)
        } else {
            showFault(friendlyMessage, retry = true)
        }
    }

    override fun onFirstFrame() {
        gotFirstFrame = true
        main.removeCallbacks(stallWatchdog)
        showFault(null)
        // 首帧之后换成「存活看门狗」：上面的 stallWatchdog 只管首帧之前，
        // 而用户实测的「卡住」发生在播放中（见 livenessWatchdog 的说明）
        armLivenessWatchdog()
        // 画面已经出来了，「正在打开…」的过渡页就没必要再占着屏幕
        main.removeCallbacks(configStartingTick)
        configStartingTick.run()
        // 首帧已经上屏，这时候再去读那 1MB 做音频判定，怎么都不会拖慢起播
        pendingAudioCheck?.let {
            pendingAudioCheck = null
            checkAudio(it)
        }
    }

    /**
     * 引擎判定硬解不可用，已经用软解把这一集重播了。
     *
     * 这是 4K 片源的兜底路径：电视的 MediaCodec 建得出来但一帧都不吐时不报错，
     * 只能靠超时发现。软解重播等于重新起播一次，看门狗必须跟着重新起算。
     */
    override fun onDecoderFallback(reason: String) {
        Log.w(TAG, "硬解退回软解：$reason")
        trace("onDecoderFallback（$reason）看门狗重新起算")
        armStallWatchdog()
    }

    /**
     * 直播连不上/断了，引擎在自动重连。
     *
     * 这是用户报的第二个「卡住」：**换到直播以后画面冻着不动，也一声不吭**。
     * 根因是直播的失败不走 [onError]（引擎自己会一直重连），消息到不了界面层。
     * 现在只要重连失败一次就把话说清楚，别让用户对着一个不动的画面猜。
     *
     * `retry` 必须传 false：重连由引擎负责（2 秒起退避、最多 10 秒一次），
     * 界面层再挂一个 10 秒的 [retryTick] 会变成两套重连互相打架 ——
     * [retryTick] 调的是 [startPlayback]，那会把当前频道重新挑一遍。
     */
    override fun onLiveRetry(attempt: Int, url: String?) {
        trace("直播重连 第 $attempt 次：$url")
        val text = if (attempt <= LIVE_QUIET_RETRIES) {
            "信号中断，正在重连…"
        } else {
            // 试了这么多次还不行，多半不是抖动，而是这个源本身有问题
            "这个频道暂时看不了，还在重试…"
        }
        showFault(text, retry = false)
    }

    // ---- 生命周期 ----

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        // 关电视/切后台：不管离上次写盘多久，立刻写一条。
        // 直播没有时长可记（存了下次就会拿它去 seek 出一条不同步的流），所以由
        // saveRecord 内部按库类型挡掉，这里不用再判一次。
        saveRecord(force = true)
    }

    override fun onDestroy() {
        // DESIGN §7 防泄漏硬要求，逐条来
        main.removeCallbacksAndMessages(null)
        ioHandler.removeCallbacksAndMessages(null)
        runCatching { hud.dismiss() }
        runCatching { main.removeCallbacks(stallWatchdog) }
        runCatching { disarmLivenessWatchdog() }
        runCatching { engine.release() }
        runCatching { tts.shutdown() }
        runCatching { abandonAudioFocus() }
        runCatching { closeConfigServer() }
        // 只解绑界面层；HTTP 服务留着继续跑（Activity 重建后要能接着读数字）
        runCatching { DiagHub.release(diagControl) }
        configScreen.recycle()
        runCatching { SmbStore.drop() }
        runCatching { io.quitSafely() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FireflyTV"
        private const val TAG_TRACE = "FireflyTrace"

        /** 排查切库/按键问题时改成 true，用 `adb logcat -s FireflyTrace` 看调用链。 */
        private const val DEBUG_TRACE = true
        private const val OVERLAY_MS = 10_000L
        private const val RETRY_MS = 10_000L
        private const val POSITION_INTERVAL_MS = 5_000L
        private const val SAVE_DEBOUNCE_MS = 5_000L
        private const val HUD_TICK_MS = 200L

        /** 诊断页刷新间隔（1 Hz）：再快没意义，还费 CPU。 */
        private const val DIAG_INTERVAL_MS = 1_000L

        /** 诊断页最多停留多久自动关闭（防止忘了关，一直盖着画面）。 */
        private const val DIAG_HIDE_MS = 30_000L

        /**
         * 双击「设置 / 信息」键的判定窗口。
         *
         * 1000 毫秒：遥控器上连按两下的间隔通常 150~400ms，
         * 而随手按一下、再想起来按第二下会明显超过这个窗口。
         * 留 1 秒是给「按得慢的遥控器」余量（实测某些遥控器的按键上报本身就有 300ms 抖动）。
         */
        private const val DIAG_DOUBLE_CLICK_MS = 1000L

        /**
         * 实验通道开关（诊断页、方案切换、局域网远程诊断都挂在它上面）。
         *
         * 绑在 `BuildConfig.DEBUG` 上是有意的：远程诊断那条通道**没有鉴权**
         * （用户要求「怎么简单怎么来」），不该出现在正式包里。
         */
        private val EXPERIMENTS = BuildConfig.DEBUG

        /** 部分遥控器的「设置」键报这个 keyCode（不是 KEYCODE_MENU）。 */
        private const val KEYCODE_SETTINGS = 176

        /** 起播前认音频编码时读多少字节：1MB 足够覆盖 PAT/PMT。 */
        private const val PROBE_BYTES = 1 shl 20

        /** 等 Surface 的最长时间；超过就报故障，不无声地卡住。 */
        private const val SURFACE_WAIT_MS = 8_000L

        /** 等 Surface 时的问询间隔。 */
        private const val SURFACE_POLL_MS = 500L

        /**
         * 目录缓存多久算「还新」，可以省掉一次后台刷新。
         *
         * 依据见 [cacheIsFresh]：那次刷新会和播放器抢 SMB 锁，直接拖慢切换首帧。
         */
        private const val CACHE_FRESH_MS = 10 * 60 * 1000L

        /**
         * 直播重连失败几次以内还说「正在重连」，超过就换成「这个频道暂时看不了」。
         *
         * 3 次 ≈ 头 12 秒（引擎退避是 2s/4s/6s…）。抖动一般几秒就恢复，
         * 超过这个数还连不上，就该让用户知道是源的问题，而不是干等。
         */
        private const val LIVE_QUIET_RETRIES = 3

        /** 「配置已保存，正在打开」最多显示这么久。 */
        private const val CONFIG_STARTING_MS = 15_000L

        /** 起播后多久没出首帧就判定卡死。4K 大文件要留足时间。 */
        private const val STALL_TIMEOUT_MS = 25_000L

        /** 播放中存活检查的间隔。 */
        private const val LIVENESS_INTERVAL_MS = 5_000L

        /**
         * 播放中存活看门狗的总开关。**暂时仍然关闭**：
         * 当初关掉它的理由是「`outputFps` 在硬解通路上恒为 0」——而那条理由
         * 后来被证伪了（硬解确实会更新 vfps，实机面板上的「送显 18.2」就是它）。
         * 但改用 vfps 当判据还缺一次验证：4K 送显本来就低于片源帧率（18.2 < 25），
         * 要先把「它在这台电视上稳定可用」量出来。这属于另一件事，
         * 不和「帧率为什么低」混在同一次改动里。
         */
        private const val LIVENESS_ENABLED = false

        /** 3 次 × 5 秒 = 15 秒。留这个缓冲是为了不把正常的缓冲抖动
         *  （换台、HLS 换分片、SMB 偶发卡顿）误判成死机 —— 误判会让画面无故重启，
         *  比重启晚几秒更烦人。 */
        private const val LIVENESS_STRIKES = 3

        /** 自动重连的次数上限与统计窗口，防止「一直闪」。 */
        private const val MAX_AUTO_RECOVER = 3
        private const val RECOVER_WINDOW_MS = 120_000L

        private val NUM_DIGITS = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
    }
}
