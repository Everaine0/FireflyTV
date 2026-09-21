package com.firefly.tv.ui

import android.content.Intent
import android.graphics.Color
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
import com.firefly.tv.config.ConfigServer
import com.firefly.tv.core.Config
import com.firefly.tv.core.Lunar
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

    /**
     * 播放器专用线程。**这个线程存在的唯一理由是 ijkplayer 的回调线程归属。**
     *
     * `IjkMediaPlayer.initPlayer` 里是这么绑事件队列的（核对过 `app/libs/ijkplayer-full-0.8.8.aar`
     * 里 `IjkMediaPlayer.class` 的字节码，顺序就是下面这样）：
     *
     * ```
     * Looper.myLooper()                                  ← 先取「**构造播放器的那个线程**」的 looper
     *   new IjkMediaPlayer$EventHandler(this, looper)
     * Looper.getMainLooper()                             ← 只有 myLooper() 为 null 才回退主线程
     *   new IjkMediaPlayer$EventHandler(this, looper)
     * ```
     *
     * 也就是说 `onPrepared` / `onFirstFrame` / `onError` **不在什么"它自己的 EventHandler 线程"上**
     * （[com.firefly.tv.player.IjkPlaybackEngine] 里原来的注释写错了），而是排在
     * 「谁 new 的播放器就排谁的 MessageQueue」。
     *
     * 以前播放器是在 [ioHandler] 上 new 的，而那条队列上还排着 NAS 扫描、剧集列目录、
     * 音频探测那 1MB 读 —— 于是**画面早就上屏了，撤掉「正在打开…」的那条回调还堵在后面**，
     * 屏幕上就永远是加载条（实测用户报的「刚开机卡住加载不动」）。分成两条线程之后，
     * 播放/回调这条路上只剩播放自己的活。
     */
    private lateinit var playerThread: HandlerThread
    private lateinit var playerHandler: Handler

    private var configServer: ConfigServer? = null
    private var surfaceReady = false
    private var audioManager: android.media.AudioManager? = null
    private val focusListener by lazy {
        object : android.media.AudioManager.OnAudioFocusChangeListener {
            override fun onAudioFocusChange(focusChange: Int) {
                // 决策交给 AudioFocusPolicy（有单元测试钉住），这里只负责执行。
                // 关键点：直播永远不 seek，所以重播时进度必须是 0。
                //
                // ⚠️ 点播不能拿「问不出来的 0」去重播：那一刻播放器可能已经 stop/释放
                // （见 AudioFocusPolicy.Action.StopAndAbandon），positionMs() 回 0 ——
                // 拿它重播既把画面拉回片头，又会在起播时把 0 写进记录
                // （[playEpisode] 一起播就写一条），等于主动丢掉续播点。
                // 问不到就用记录里最后存下的位置。
                val pos = engine.positionMs().takeIf { WatchHistory.usablePosition(it) }
                    ?: lastKnownPos()
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
     * 分散到 IO 线程反而容易写出竞态。读盘在 [onCreate] 里**同步**做（见 [loadHistory]）——
     * 异步读会让「读回来之前先写了一次」变成「拿空记录覆盖整份文件」。
     */
    private var history: WatchHistory.Book = WatchHistory.Book.EMPTY

    /**
     * 「现在在播的是哪一集」——库 / 剧 / 集。
     *
     * 为什么不能直接拿 [showName] + [episodeIndex] 现读：换剧时 [playShow] 会**先**改
     * [showName]，而这部剧的集列表是**异步**去 NAS 列的（前面还排着 [refreshShows]）。
     * 那几秒里 [episodeIndex] 还是**上一部剧**的集号、播放器也还在放上一部剧 ——
     * 定时写盘这时就会把上一部剧的集号和进度记到新剧头上（用户看到的「记录乱了/丢了」）。
     *
     * 所以身份只在**真的把这一集交给播放器**时认领（[playEpisodeNow]），离开视频内容时
     * 清掉（[selectLibrary] / [playChannel] / 排队等 Surface 期间）；[saveRecord] 只认它。
     */
    private class Spot(val lib: String, val show: String, val episode: Int)

    /** 当前正在播的那一集；直播、故障页、还没起播时是 null（此时不写记录）。 */
    private var spot: Spot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))

        io = HandlerThread("firefly-io").apply { start() }
        ioHandler = Handler(io.looper)
        // 播放器与它的回调单独一条线程，绝不和 NAS 扫描共用（见 [playerThread] 的说明）
        playerThread = HandlerThread("firefly-player").apply { start() }
        playerHandler = Handler(playerThread.looper)

        buildViews()
        requestAudioFocus()

        engine = IjkPlaybackEngine(this).apply { setListener(this@MainActivity) }
        tts = TtsSpeaker(this).apply { init() }
        // 上次的天气先顶上，过期了在浮层里再联网刷（省心知那边的调用次数）
        restoreWeatherCache()

        // 观看记录**必须在任何东西写它之前读出来**。
        //
        // 它是一份整体落盘的文本（`WatchHistory.serialize` 的全文），每个写点都是
        // 「拿内存里这份重新序列化一遍」。以前这里是「先挂一份空的、再去 IO 线程补读」，
        // 补读回来再 `main.post { history = book }` 无条件盖掉内存 —— 这个窗口里
        // 任何一次 force 写（换剧/换库/退到后台）都会把**整份记录覆盖成空的**，
        // 用户看到的就是「记录丢了」。改成先同步读出来，窗口从结构上不存在。
        //
        // 读的是同一个 SharedPreferences 文件（Config.smb / configured 早就在主线程读它了），
        // 这里多出来的只是一次十几 KB 的字符串解析；目录缓存那种大对象仍然留在 IO 线程读。
        history = loadHistory()
        ioHandler.post { cache = readCache() }

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
        // 这里**故意什么都不设**：格式 / 层级 / 固定尺寸都保持 SurfaceView 的默认值。
        // 老代码写死过 `setFormat(PixelFormat.RGBA_8888)`（抄来的），而带 alpha 的格式提示
        // 会把这一层变成非不透明层、逼 SurfaceFlinger 多合成一次 —— 实测这条路没有任何好处。
        // （四种格式提示 / 三种层级 / 固定 1080p 面都量过，送显帧率与默认值没有差别。）
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

    // ---- 启动兜底看门狗 ----

    /** 已经自动重启过几轮（见 [startupWatchdog]）。 */
    private var startupRecover = 0

    /** 上一次因为启动兜底而重启的时刻，用来给重启次数做"冷却"（见 [startupWatchdog]）。 */
    private var startupRecoverAt = 0L

    /**
     * 启动兜底：**从"决定要播"到"真的有画面"这条路上，任何一段卡住都能自己好。**
     *
     * 为什么单独要一个：[stallWatchdog] 是 `playEpisodeNow` 里才挂的，只保护"播放器拿到活之后"；
     * 而列库（[Scanner.libraries]）、列剧、列集这三段排在它前面，之前**完全没有保护**。
     * 那几段卡住时的屏幕状态是 SwitchHud 的「正在打开…」，于是用户看到的就是
     * 「刚开机卡在加载不动」，而且永远不会自己好（实机反馈）。
     *
     * 判据故意用 `engine.isPlaying()` 而不是界面上的标志位：
     * ijkplayer 的回调也可能被拖后（它的事件队列绑在构造线程上，见 [playerThread]），
     * 所以"界面以为还没起播"不等于"真的没在播"。已经在放就当场撤哨，绝不打断。
     */
    private val startupWatchdog = Runnable {
        if (engine.isPlaying()) {
            trace("startupWatchdog：其实已经在播了，撤哨")
            startupRecover = 0
            return@Runnable
        }
        // 故障页已经写着原因了（而且多半带着 10 秒重试），别抢它的话。
        // 尤其是直播：引擎自己会退避重连，界面层再插一脚只会打架（见 onLiveRetry）。
        if (faultScreen.visibility == View.VISIBLE) return@Runnable
        // 配置页占屏时不管：那是用户在配置，不是卡住
        if (configScreen.visibility == View.VISIBLE) return@Runnable

        // 次数做"冷却"：一段时间没再出事就把额度还回去，免得偶尔卡一次就永久用光
        val now = System.currentTimeMillis()
        if (now - startupRecoverAt > STARTUP_RECOVER_COOLDOWN_MS) startupRecover = 0

        startupRecover++
        if (startupRecover > MAX_STARTUP_RECOVER) {
            Log.w(TAG, "启动兜底已自动重启 $MAX_STARTUP_RECOVER 次仍没有画面，交回故障页")
            trace("startupWatchdog 自动重启额度用尽 -> 故障页重试")
            startupRecoverAt = now
            startupRecover = 0
            // 带重试：10 秒后再走一遍 startPlayback，不是死页面
            showFault("这台电视还没打开片源，正在重试", retry = true)
            return@Runnable
        }
        startupRecoverAt = now
        Log.w(TAG, "启动 ${STARTUP_TIMEOUT_MS}ms 还没画面，自动重启第 $startupRecover 次")
        trace("startupWatchdog 自动重启第 $startupRecover 次")
        // 画面没起来，那个还在等 Surface 的排队请求已经没意义了；交给 startPlayback 重来
        pendingEpisode = null
        pendingChannel = null
        startPlayback()
    }

    private fun armStartupWatchdog() {
        main.removeCallbacks(startupWatchdog)
        main.postDelayed(startupWatchdog, STARTUP_TIMEOUT_MS)
    }

    private fun disarmStartupWatchdog() {
        main.removeCallbacks(startupWatchdog)
        startupRecover = 0
    }

    // ---- Surface ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        engine.attach(holder.surface)
        launchPendingEpisode()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 尺寸变化由 SurfaceView 自己处理，播放器只认 surface 对象。
        // 解码器把面重配成 4K 时会再回调一次，日志里能看出来（排查 4K 时用过）。
        Log.i(TAG, "Surface 变化：${width}×${height} format=$format")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        engine.detachSurface()
    }

    /** Surface 现在到底能不能用（按对象问，不靠标志位猜）。 */
    private fun surfaceUsable(): Boolean =
        surfaceView.holder.surface?.isValid == true

    /**
     * 把排队等 Surface 的那一集（或那一路直播）放出去。
     *
     * 这里原来只有一个隐患很大的前提：「播放请求排上队以后，surfaceCreated 一定会再来一次」。
     * 实测不成立 —— 从后台回到前台时 `dispatchKeyEvent` 是可以触发的，但 Surface 早就建好了，
     * `surfaceCreated` 不会再回调，于是 [pendingEpisode] 永远躺在队列里：
     * **屏幕停在上一集或上一个库的画面，按键却都有反应**，用户看到的就是「卡在当前页面」。
     * （日志里那一行 `startMs=1180132 surfaceReady=false` 就是它。）
     *
     * 现在改成：只要有排队的东西，就当场问一次 Surface 能不能用，能用就立刻起播；
     * 不能用就**留着队列**继续等（[surfacePoll] 每 500ms 问一次，[pendingTimeout] 退避重问），
     * 一直等不到由 [startupWatchdog] 接手自动重启 —— 不再有"超时就丢掉请求"这条路。
     */
    private fun launchPendingEpisode() {
        val e = pendingEpisode
        val c = pendingChannel
        if (e == null && c == null) return
        if (!surfaceUsable()) {
            trace("launchPendingEpisode 仍在等 Surface")
            return
        }
        // 先清队列再起播：起播路径里可能又排进新东西（例如硬解兜底重播），别被自己覆盖
        pendingEpisode = null
        pendingChannel = null
        main.removeCallbacks(pendingTimeout)
        main.removeCallbacks(surfacePoll)
        surfaceReady = true
        if (e != null) {
            trace("launchPendingEpisode 起播《${e.show}》startMs=${e.startMs}")
            // 身份跟着排队的那一集走，不现读界面字段：排队期间用户可能又换过剧/换过库
            playEpisodeNow(e.path, e.startMs, e.spot)
            return
        }
        trace("launchPendingEpisode 起播频道《${c!!.name}》")
        playChannelNow(c.name, c.url)
    }

    /**
     * 等 Surface 超时。
     *
     * ⚠️ 这里**不再丢掉排队的那一集**。老实现是"超时就放弃 + 报故障且不重试"，
     * 而提示还写着「请按一下遥控器上的返回键」—— 可那个键在本应用里是被明确吞掉的
     * （`dispatchKeyEvent` 的 `else -> true`），于是用户按什么都不管用，只能重开应用。
     *
     * 现在改成：请求留着继续等（Surface 一好就起播），期间给一句在动的话，
     * 并按 3/6/10/15/20 秒退避重问。真正的兜底交给 [startupWatchdog]，
     * 它到点会走「故障页 + 自动重启」这条路。
     */
    private val pendingTimeout = object : Runnable {
        override fun run() {
            val e = pendingEpisode
            val c = pendingChannel
            if (e == null && c == null) return
            if (surfaceUsable()) {
                launchPendingEpisode()
                return
            }
            val waited = System.currentTimeMillis() - queueWaitAnchor
            surfaceWaits++
            Log.w(TAG, "等 Surface 已 ${waited}ms（第 $surfaceWaits 次），继续等并重问")
            if (surfaceWaits == 1) {
                showFault("电视画面正在准备，稍等一下", retry = false)
            }
            val backoff = SURFACE_RETRY_BACKOFF_MS[
                (surfaceWaits - 1).coerceIn(0, SURFACE_RETRY_BACKOFF_MS.size - 1)
            ]
            // 自引用必须写成匿名对象：`Runnable { ... this ... }` 那种 lambda 形式会让
            // Kotlin 报 "recursive problem / must be initialized"（这条是编译器逼出来的写法）
            main.removeCallbacks(this)
            main.postDelayed(this, backoff)
        }
    }

    private var surfaceWaits = 0

    /**
     * 真的把一集交给播放器。
     *
     * [spot] 在**这里**认领，而不是在 [playEpisode]：Surface 没就绪时那一集只是排队
     * （见 [pendingEpisode]），这段时间播放器还在放**上一集** —— 提前认领就会把上一集
     * 正在走的进度记到这一集头上（用户看到的「记录乱了」）。
     */
    private fun playEpisodeNow(path: String, startMs: Long, s: Spot) {
        spot = s
        val cfg = Config.smb(this)
        prepared = false
        audioStarted = false
        armStallWatchdog()
        // 记下"这一集是什么时候排上队的"：下面这条工作一旦排在别的活后面（例如上一集的
        // 硬解兜底重播、或者 SMB 连接还在忙着），等待时间要能从起播超时里扣掉，
        // 否则排队的时间会被算成播放器卡死，冤枉它一次。
        queueWaitAnchor = System.currentTimeMillis()
        // ⚠️ 这里必须是 [playerHandler] 而不是 [ioHandler]：ijkplayer 的事件回调绑在
        // **构造播放器的线程**上（见 [playerThread]），跟 NAS 扫描共用一条队列就会出现
        // 「画面在放、加载条不消失」。
        playerHandler.post {
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
     * 这一集**排上队**的时刻（见 [playEpisodeNow]）。
     *
     * 起播超时只该量"播放器拿到活之后过了多久"，不该把排队等 NAS / 等上一条播放任务的时间
     * 也算进去 —— 原来就是这么算的：`armStallWatchdog()` 在把 `engine.playSmb` 投出去之前
     * 就起算，而那条请求可能还排在剧集列目录、音频探测那 1MB 读后面。于是 SMB 一慢，
     * 看门狗就在**播放器还没开始干活**的时候判它卡死。
     */
    private var queueWaitAnchor = 0L

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
        // 起算时先把"排队锚点"清掉：软解重播时 [playEpisodeNow] 会重新写它，
        // 而那之前的时间本来就该算在播放器头上。
        queueWaitAnchor = 0L
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
    private val stallWatchdog = object : Runnable {
        override fun run() {
            if (gotFirstFrame) return
            val waited = System.currentTimeMillis() - startedAt
            // 扣掉排队等 NAS 的那段：那是网络的账，不是播放器的账（见 [queueWaitAnchor]）
            val queued = queueWaitAnchor
            val waitedPlaying = if (queued in 1..startedAt) waited - (startedAt - queued) else waited
            if (waitedPlaying < STALL_TIMEOUT_MS) {
                // 还在排队：重新起算，别把"排队"判成"卡死"
                trace("stallWatchdog 还在排队（已等 ${waited}ms，其中播放器实际只等了 ${waitedPlaying}ms）")
                main.removeCallbacks(this)
                main.postDelayed(this, STALL_TIMEOUT_MS - waitedPlaying + 200L)
                return
            }
            // ⚠️ 到点了也**先别急着判卡死**：`onFirstFrame` 只由 VIDEO_RENDERING_START 触发，
            // 而实测这个事件在有些通路上根本不来（模拟器纯软解 + GLES2：画面正常在放，
            // 事件一次都没收到 —— A/B 对照过，改动前的代码同样收不到，只是没有一道恰好
            // 25 秒的闸门去打断它）。只认它的话，一到点就会把**正在播的画面**打断并报故障页。
            // 所以补一个「真的在出帧吗」的真值：解码/送显帧率不为 0 就说明它活着。
            if (engine.decodedAnyFrame()) {
                gotFirstFrame = true
                Log.w(TAG, "起播 ${waitedPlaying}ms 没收到渲染事件，但帧率非 0：按已出画面处理")
                trace("stallWatchdog：无渲染事件但帧率非 0，撤哨")
                return
            }
            if (engine.canFallbackToSoftware() && engine.retryInSoftware()) {
                trace("stallWatchdog 卡死 ${waitedPlaying}ms，先退回软解重播")
                armStallWatchdog()
                return
            }
            Log.w(TAG, "起播 ${waitedPlaying}ms 还没出首帧，判定卡死，走故障页重试")
            trace("stallWatchdog 卡死 ${waitedPlaying}ms")
            onError("这个视频打不开，正在换下一个", fatal = false)
        }
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
        // release() 之后 positionMs() 恒为 0（`player` 已经被置空），所以先把还能写的
        // 那一条写下来、再把重播位置从**记录**里取出来 —— 否则 playEpisode(0L) 会把
        // 这一集的续播点抹成 0：老人重看一遍是小事，记录没了才是大事。
        saveRecord(force = true)
        val resume = lastKnownPos()
        // 把死的那个彻底丢掉，否则新起的播放器会和它抢 Surface
        runCatching { engine.release() }
        trace("自动重连第 $recoverCount 次 lib=${lib?.name}")
        if (lib is Library.Live) {
            currentTitle = channels.getOrNull(channelIndex)?.name ?: currentTitle
            playChannel()
        } else {
            playEpisode(resume)
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
        // 诊断页：**双击**遥控器上的「设置 / 信息」键打开，**OK 键关闭**。
        //
        // 为什么是双击不是单击：用户实测这台电视的「信息」键其实就是**设置键**，
        // 单击就弹一屏字太重（也容易和别的功能撞车）。双击误触概率极低，
        // 而且不用记组合键。
        //
        // （清理版说明：这里原来还有「打开面板后单击切实验方案」，
        //   那是配合远程诊断口的实验台；实验台已移除，见 README「诊断页」一节。）
        when (event.keyCode) {
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU, KEYCODE_SETTINGS -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastDiagKeyAt <= DIAG_DOUBLE_CLICK_MS) {
                        lastDiagKeyAt = 0L
                        toggleDiag()
                    } else {
                        lastDiagKeyAt = now
                        Log.i(TAG, "收到设置/信息键（keyCode=${event.keyCode}），再按一次开诊断页")
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

        // ⚠️ 重试不能把"为什么没播成"从屏幕上拿掉。
        //
        // 每 10 秒重试一次，每次都会走到这里。原来的顺序是「先清故障页、再挂一条
        // 『正在连接 NAS』提示」—— 而真正去列库还要几秒（实测不可达的 NAS：5 秒超时）。
        // 于是屏幕在「让人看不懂的加载条」和「看得懂的原因」之间来回切，而且**每次切都在
        // 用户刚读出原因之后**。实测（模拟器 + 不可达的 NAS，120 秒采样）看到的现象就是
        // 「一直卡在加载」。
        //
        // 现在：刚失败不久就把原因继续挂在屏幕上，只有真的隔了很久（用户重新进来的那种）
        // 才有必要说"正在连接 NAS"。阈值取 60 秒而不是一个重试周期（10 秒）：
        // 取 10 秒时实测正好卡在边界上（上一轮 10 秒整点触发），结果又退回"正在连接"。
        val showingReason = lastFault != null &&
            System.currentTimeMillis() - lastFaultAt < REASON_KEEP_MS
        showFault(null, keepHud = showingReason)
        trace("startPlayback 开始：挂启动兜底看门狗（显示原因=$showingReason）")
        // ⚠️ 从这里到 [playEpisodeNow] 之间（列库 → 列剧 → 列集）原本**没有任何看门狗**：
        // 起播看门狗是在 playEpisodeNow 里才挂的，而这段时间屏幕上只有 SwitchHud 那条
        // 「正在打开…」。SMB 卡在这几段任何一处，就永远停在那句话上，只能重进应用。
        // 所以"决定要播"这一刻就先挂一道兜底。
        armStartupWatchdog()
        if (!showingReason) {
            hud.onRestoring(System.currentTimeMillis())
            postHudTick()
        }
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
                trace("startPlayback 列库完成：${libs.size} 个库（cached=${cached != null}）")
                main.post {
                    if (libs.isEmpty()) {
                        // ⚠️ `retry` 必须为 true。这里看着像"NAS 上确实没东西"，其实
                        // 更常见的是**开机瞬间**的假空：`Scanner.classify` 会把一次
                        // 列目录失败也表达成"这个库是空的"，于是整轮扫下来一个库都不剩。
                        // 老的 `retry = false` 配上"libraries 为空时左右上下键全都不响应"，
                        // 就是一个只能重开应用的死页面（实机反馈的"卡住"之一）。
                        showFault("NAS 上还没有可以播放的内容，正在重试", retry = true)
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
                trace("startPlayback 列库失败：${t.javaClass.simpleName} / $msg")
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
        // ⚠️ 空表必须当场挡住。缓存里的 `libraries` 完全可能是空的（`Snapshot.isEmpty`
        // 只看「库/剧/集三者是不是全空」，一次失败的扫描会把 libraries 写成空表、
        // 而旧的 shows 还留着，于是缓存看着"有内容"），接着下面 `libs[0]` 就是
        // 主线程上一发 IndexOutOfBoundsException —— 用户看到的是开机就闪退/打不开。
        if (libs.isEmpty()) {
            Log.w(TAG, "restoreSpot：库列表是空的，先什么都不播")
            return
        }
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
     * 两件事必须守住，否则会把好记录写坏（用户报的「多次切换以后记录丢了」）：
     *
     *  1. **只认 [spot]**：记录记的是「真的在播的那一集」，不是「界面上选中的那部剧」。
     *     换剧时新剧的集列表是异步列的，那几秒里 [showName]/[episodeIndex] 互相矛盾。
     *  2. **问不到进度就不写**：换剧/换库/关电视这些 `force` 写点正好落在
     *     「播放器刚重建、进度还问不出来」的窗口里，`positionMs()` 回 0。
     *     0 是「不知道」而不是「片头」，照收就等于把上一次存的好进度抹掉 ——
     *     判据在 [WatchHistory.shouldStore]（纯函数，有单测）。
     *
     * @param force true = 不等防抖立刻写（换剧/换库/退到后台时用）
     */
    private fun saveRecord(force: Boolean) {
        val s = spot ?: return
        val pos = engine.positionMs()
        val now = System.currentTimeMillis()
        if (!WatchHistory.shouldStore(pos, force, now, lastSavedAt, lastSavedPos)) {
            // 排查「记录怎么没记上」时这一行是判据：问不到进度时我们**故意不写**
            if (force && !WatchHistory.usablePosition(pos)) {
                trace("saveRecord：《${s.show}》第 ${s.episode + 1} 集进度问不出来（${pos}ms），保留上一次的记录")
            }
            return
        }

        lastSavedPos = pos
        lastSavedAt = now
        history = WatchHistory.with(
            history,
            WatchHistory.Record(s.lib, s.show, s.episode, pos, now),
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

    /**
     * 播放器问不到进度时的兜底：**记录里**这一集最后写到哪儿。
     *
     * 只用在「重播同一集」的两条路上（音频焦点拿回来、播放器死掉后重建）：
     * 那两处一旦传 0，[playEpisode] 起播时会立刻把 0 写进记录 ——
     * 用户什么都没干，续播点就没了。位置 < 15 秒时 [WatchHistory.Record.resumeMs]
     * 本来就当 0（见 [WatchHistory.MIN_RESUME_MS]），所以直接用 `resumeMs`。
     */
    private fun lastKnownPos(): Long {
        // [spot] 为空时退回界面字段：换剧那一瞬间（正在列集、排队等 Surface，还没交给
        // 播放器）界面上选中的就是**即将起播**的那一集，记录里存着它的起播点；
        // 直播/没起播时这两个字段是空的，下面直接返回 0。
        val s = spot ?: Spot(libraries.getOrNull(libIndex)?.name.orEmpty(), showName, episodeIndex)
        if (s.lib.isBlank() || s.show.isBlank()) return 0L
        val rec = history.find(s.lib, s.show) ?: return 0L
        return if (rec.episode == s.episode) rec.resumeMs else 0L
    }

    /** 起播锚点：在剧列表里的下标 + 从第几集第几毫秒起播。 */
    private class Anchor(val index: Int, val episode: Int, val startMs: Long)

    /**
     * 在 [list] 里找到「上次看的那部剧」并给出起播点。
     *
     * 找不到（这部剧在 NAS 上被删了/改名了）就退到第 1 部 —— 但**退过去也要用它自己的
     * 记录**。原来这里直接给 `(0, 0L)`：于是「上次那部剧不在了」会顺手把第 1 部剧存着的
     * 续播点抹成 0（[playEpisode] 起播时就写了记录），用户看到的还是「记录丢了」。
     */
    private fun anchorFor(
        lib: Library.Video,
        list: List<String>,
        resumeShow: String,
        resumeEpisode: Int,
        resumePos: Long,
    ): Anchor {
        val found = list.indexOf(resumeShow)
        if (found >= 0) return Anchor(found, resumeEpisode, resumePos)
        if (resumeShow.isNotBlank()) {
            trace("《$resumeShow》不在《${lib.name}》的剧列表里（删了/改名了？）-> 退到第 1 部")
        }
        val at = resumeOf(lib.name, list.firstOrNull().orEmpty())
        return Anchor(0, at.episode, at.positionMs)
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

    /**
     * 先撤掉旧的刷新链再重挂。
     *
     * 为什么必须 remove 一下：提示条在 [SwitchHud] 里是"到点自己消失"的，
     * 每条链子都要跑到自己那个 deadline 才肯停。不过滤的话，每按一次键、
     * 每换一次库都会再挂一条 —— 几十条 5Hz 的链子同时刷同一个 View，
     * 而屏幕上只有一条提示。这是纯浪费（电视 CPU 本来就紧）。
     */
    private fun postHudTick() {
        main.removeCallbacks(hudTick)
        if (renderHud()) main.postDelayed(hudTick, HUD_TICK_MS)
    }

    /** 内容真的开始播了：把「正在打开…」换成名字，1.6 秒后自动收起。 */
    private fun onContentPicked(title: String) {
        if (!Config.configured(this)) return
        hud.onPlaying(title, System.currentTimeMillis())
        postHudTick()
    }

    /** 换剧/换台：先出一条名字，别让屏幕一动不动。 */
    private fun announce(title: String) {
        hud.onContentSwitch(title, System.currentTimeMillis())
        postHudTick()
    }

    private fun announceLibrary(name: String) {
        // 换库就把上一条「没声音」的提示清掉：那是上一个片源的毛病，
        // 留着会让人以为新片源也没声音
        audioWarning = null
        // 库名同时写进 OK 浮层：按左右键时用户本来就习惯按 OK 确认，现在按下就看到库名变了
        currentTitle = SwitchHud.libraryTitle(name)
        hud.onLibrarySwitch(name, System.currentTimeMillis())
        postHudTick()
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
        // 内容身份也跟着作废：这一刻起还没起播任何一集，定时写盘不该再写记录
        // （离开上一个库之前，调用方已经 force 写过一条了）
        spot = null
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
                    val at = anchorFor(lib, cachedShows, resumeShow, resumeIndex, resumePos)
                    playShow(lib, at.index, at.episode, at.startMs, seq)
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
                    val at = anchorFor(lib, list, resumeShow, resumeIndex, resumePos)
                    playShow(lib, at.index, at.episode, at.startMs, seq)
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
     * 必须有调用链。
     *
     * 走 `Log.w` 是**故意的**：正式包的 R8 会去掉 `v/d/i` 三档，只留 `w/e`
     * （见 `proguard-rules.pro`）。原先这里用 `Log.i` + `DEBUG_TRACE` 编译期开关，
     * 结果就是电视上跑的正式包**一个字都留不下** —— 用户报「卡住」时手里没有任何证据，
     * 只能让对方换成 debug 包重装一遍再等复现（这次排查就卡在这儿）。
     * 换成 `w` 之后正式包也能留痕，而消息体量很小（一次启动十几行），不影响性能。
     *
     * 规则：**只记真值，不记密码**（SMB 配置里的密码绝不进日志）。
     */
    private fun trace(msg: String) {
        if (DEBUG_TRACE) Log.w(TAG_TRACE, msg)
    }

    private fun playEpisode(startMs: Long) {
        val lib = libraries.getOrNull(libIndex) as? Library.Video ?: return
        val ep = episodes.getOrNull(episodeIndex) ?: return
        // 真有东西要播了：把「连续空剧」的计数清掉（它只用来给自动换剧封顶）
        emptyShowStreak = 0
        val path = Scanner.episodePath(lib, showName, ep)
        currentTitle = showName
        // 先出名字，再去做后面那些可能慢的事
        announce(showName)
        // 立刻落一条记录：换集/换剧之后马上拔电源，起来的也是这一集。
        // ⚠️ 但**内容身份（[spot]）这一刻还不能认领**：这一集可能因为 Surface 没就绪
        // 而只是排队（见 [pendingEpisode]），排队期间播放器还在放上一集 ——
        // 认领了就会把上一集正在走的进度记到这一集头上。
        // 身份在真正交给播放器时更新（见 [playEpisodeNow]）。
        val s = Spot(lib.name, showName, episodeIndex)
        spot = null
        lastSavedPos = startMs
        lastSavedAt = System.currentTimeMillis()
        history = WatchHistory.with(
            history,
            WatchHistory.Record(s.lib, s.show, s.episode, startMs.coerceAtLeast(0L), lastSavedAt),
        )
        Config.saveWatchHistoryText(this, WatchHistory.serialize(history))
        trace("playEpisode 《$showName》[$ep] startMs=$startMs surfaceUsable=${surfaceUsable()}")

        if (!surfaceUsable()) {
            // Surface 还没建好：排队等着，并在 surfaceCreated 或超时时处理
            pendingEpisode = PendingEpisode(path, startMs, showName, s)
            pendingChannel = null
            surfaceWaits = 0
            // 排队期间不该留着"首帧后探音频"的指针：那是给真正起播的那一集用的，
            // 留着它会让之后某次首帧去探一个已经不是当前内容（甚至已切库）的路径。
            pendingAudioCheck = null
            main.removeCallbacks(pendingTimeout)
            main.postDelayed(pendingTimeout, SURFACE_WAIT_MS)
            main.postDelayed(surfacePoll, SURFACE_POLL_MS)
            return
        }
        playEpisodeNow(path, startMs, s)
        // 音频探测**故意不在这里做**：它要读 1MB，走的是同一条 SMB 连接，
        // 会和播放器读首帧抢锁（实测切换首帧为此多等 100~300ms）。
        // 挪到首帧之后做，结论一样，代价为零 —— 见 onFirstFrame()。
        pendingAudioCheck = path
    }

    /**
     * 排队等 Surface 时的轮询兜底。
     *
     * 不想只依赖 `surfaceCreated`：它是回调，不是状态 —— 回调错过一次就永远不会再来。
     * 每 500ms 自己问一次「Surface 现在能用了吗」，问到了就起播，问不到就一直问下去
     * （超时那一档由 [pendingTimeout] 退避重问，最终由 [startupWatchdog] 接手）。
     *
     * 判据是"队列里还有没有东西"，不是"有没有某一集"：直播排队走的是同一条路
     * （见 [playChannel]），老代码只看 [pendingEpisode]，于是排队的直播永远不会被放出去。
     */
    private val surfacePoll = object : Runnable {
        override fun run() {
            if (pendingEpisode == null && pendingChannel == null) return
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

    private class PendingEpisode(val path: String, val startMs: Long, val show: String, val spot: Spot)
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
            // 下一部剧也接着**它自己的**记录看。原来这里写死 `(0, 0L)`，
            // 而一起播 [playEpisode] 就会写记录 —— 等于「看完一部剧」顺手把下一部剧
            // 存着的续播点抹成 0（和换剧的规矩不一致：换到哪部剧就接着哪部看）。
            val at = resumeOf(lib.name, list[next])
            playShow(lib, next, at.episode, at.positionMs, librarySeq)
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
        // ⚠️ 直播这里原来**不查 Surface**（点播那条路是查的）。没有 Surface 时
        // ijkplayer 会建一个"收数据、不出画面"的解码器，之后再 setSurface 只能靠
        // reconfigure 补救 —— 表现就是黑屏、没提示、没重试（见 PlaybackEngine 里
        // 「假的解码器」那段注释）。所以和点播一样排队等。
        if (!surfaceUsable()) {
            trace("playChannel 《${ch.name}》Surface 还没好，排队等")
            pendingChannel = PendingChannel(ch.name, ch.url)
            pendingEpisode = null
            surfaceWaits = 0
            queueWaitAnchor = System.currentTimeMillis()
            main.removeCallbacks(pendingTimeout)
            main.postDelayed(pendingTimeout, SURFACE_WAIT_MS)
            main.postDelayed(surfacePoll, SURFACE_POLL_MS)
            return
        }
        playChannelNow(ch.name, ch.url)
    }

    /**
     * 真的把一路直播交给播放器（Surface 已经确认可用）。
     *
     * 拆出来是因为「排队等 Surface」那条路也要用它（见 [launchPendingEpisode]）：
     * 以前这个函数是私有的内联逻辑，排队那条路根本没有直播入口。
     */
    private fun playChannelNow(name: String, url: String) {
        // 直播不记进度（见 PlaybackMode）：把内容身份清掉，定时写盘就不会碰记录
        spot = null
        currentTitle = name
        announce(name) // 立刻出频道名，换台不能看起来没反应
        // 上一个频道留下的「信号中断」提示要马上撤掉，否则换到好频道也还挂着那句话
        showFault(null)
        prepared = false
        audioStarted = false
        // 换台之后不该再去探上一集的音频（探针是排在首帧之后跑的）
        pendingAudioCheck = null
        // 直播没有首帧之前也得有人盯着：引擎的存活看门狗是首帧之后才挂的
        // （见 PlaybackEngine.fireFirstFrame），这段空档由启动兜底看门狗兜住。
        armStartupWatchdog()
        engine.setMode(PlaybackMode.Kind.LIVE)
        engine.setLiveReconnect(true) { channels.getOrNull(channelIndex)?.url }
        engine.playUrl(url)
    }

    private class PendingChannel(val name: String, val url: String)
    private var pendingChannel: PendingChannel? = null

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

        // 9) 正在播什么（用户报问题时对着这一行说就够了）
        lines += DiagScreen.Line("内容", currentTitle.ifBlank { "—" })

        return lines
    }

    private fun renderDiag() {
        diagScreen.show("播放诊断", diagRows(), "OK 键关闭（30 秒后自动关闭）")
    }

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

    /**
     * @param keepHud true = 只藏起故障页，**保留**屏幕上那条提示（重试期间用，见 [startPlayback]）。
     *   默认 false：报故障时把提示收掉，让那句人话独占屏幕（提示条画在故障页上面，
     *   不收掉就会把原因盖住 —— 用户实测的「卡在加载不动」就是这么来的）。
     */
    private fun showFault(message: String?, retry: Boolean = false, keepHud: Boolean = false) {
        main.removeCallbacks(retryTick)
        // 记住"上一次为什么没播成"：10 秒后的重试会先清掉故障页，而重试本身又要花时间，
        // 这段时间必须把原因继续挂在屏幕上（见 [startPlayback] 里的说明）。
        lastFault = message
        lastFaultAt = System.currentTimeMillis()
        if (message == null) {
            faultScreen.visibility = View.GONE
            return
        }
        // ⚠️ 报故障时必须把那条「正在打开…」收掉。
        //
        // hudView 是在 faultScreen **之后** add 进 root 的（见 buildViews），所以那条
        // 加载条画在故障页**上面**。而 SwitchHud 的 LOADING 状态又没有出口 —— 于是
        // 用户看到的永远是「《库名》正在打开…」，底下那句人话（"连不上 NAS"之类）被盖住，
        // 看起来就是"卡在加载不动"。用户实测报的就是这个现象。
        if (!keepHud) {
            hud.dismiss()
            renderHud()
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

    /**
     * 上一次报给用户的故障原因（null = 最近一次是成功的），以及它是什么时候报的。
     *
     * 只用来在**重试期间**把那句话继续挂在屏幕上：10 秒重试一到就先清故障页去重连，
     * 而重连可能又是十几秒 —— 没有它的话，用户在这十几秒里看到的是"正在连接"，
     * 而不是"连不上 NAS"。实测就是靠这一条把「无限加载」变成「看得见的原因」。
     */
    private var lastFault: String? = null
    private var lastFaultAt = 0L

    // ---- PlaybackEngine.Listener ----

    override fun onPrepared(durationMs: Long) {
        prepared = true
        showFault(null)
        // 播放器已经起播：启动兜底看门狗不用再盯着"有没有画面"了。
        // （真正的首帧判据是 [onFirstFrame]，但播放器都说准备好了，
        //   再自动重启一遍只会把刚起来的画面打断。）
        disarmStartupWatchdog()
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
                // 这里同样要先确认时长可信，否则损坏文件会把整部剧一路跳完
                val kind = PlaybackMode.of(libraries.getOrNull(libIndex))
                if (PlaybackMode.canAutoAdvance(kind, engine.durationMs())) {
                    showFault(null)
                    onEpisodeFinished()
                } else {
                    // ⚠️ 以前这里只写了一行日志：`showFault(null)` 把屏幕清空，
                    // 又没有重试、又不跳集 —— 用户看到的是一片黑，什么提示都没有，
                    // 只能重开应用。而 `fatal=true` 最常见的来源恰恰是**网络类错误**
                    // （SMB 读失败会被 ijkplayer 报成 MEDIA_ERROR_IO，见 PlaybackEngine），
                    // 开机那几秒正好最容易撞上：时长问不出来（= 不可信）→ 掉进这个分支。
                    // 网络问题本来就该重试，所以这里改成"留着原因 + 10 秒重试"。
                    Log.w(TAG, "时长不可信，不自动跳集：改成挂着原因重试")
                    showFault(friendlyMessage, retry = true)
                }
            }, 3000)
        } else {
            showFault(friendlyMessage, retry = true)
        }
    }

    override fun onFirstFrame() {
        gotFirstFrame = true
        main.removeCallbacks(stallWatchdog)
        // 真的出画面了，启动兜底看门狗可以撤了
        disarmStartupWatchdog()
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
        // 直播、故障页、还没起播时 [spot] 是 null（见 [Spot]），saveRecord 会直接返回；
        // 进度问不出来时也不写（0 是「不知道」，写下去就是把记录抹了）——
        // 两种情况都在 saveRecord 里挡掉，这里不用再判一次。
        saveRecord(force = true)
    }

    override fun onDestroy() {
        // DESIGN §7 防泄漏硬要求，逐条来
        main.removeCallbacksAndMessages(null)
        ioHandler.removeCallbacksAndMessages(null)
        // 播放器那条线程也要收掉：它上面挂着 ijkplayer 的事件队列
        runCatching { playerHandler.removeCallbacksAndMessages(null) }
        runCatching { hud.dismiss() }
        runCatching { main.removeCallbacks(stallWatchdog) }
        runCatching { disarmLivenessWatchdog() }
        runCatching { engine.release() }
        runCatching { tts.shutdown() }
        runCatching { abandonAudioFocus() }
        runCatching { closeConfigServer() }
        configScreen.recycle()
        runCatching { SmbStore.drop() }
        runCatching { io.quitSafely() }
        runCatching { playerThread.quitSafely() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FireflyTV"
        private const val TAG_TRACE = "FireflyTrace"

        /**
         * 调用链日志开关。**正式包保持 false**（消息量小，但没必要天天写）。
         *
         * 排查实机问题时把它改成 true 重打一次包即可；日志走 `Log.w`，
         * 所以**正式包里也留得下来**（R8 只去 v/d/i，见 [trace]）。
         */
        private const val DEBUG_TRACE = false
        private const val OVERLAY_MS = 10_000L
        private const val RETRY_MS = 10_000L

        /**
         * 上一次的故障原因在屏幕上保留多久（见 [startPlayback] 的 `showingReason`）。
         *
         * 取 60 秒：重试是每 10 秒一轮，取一个周期会在边界上抖动（实测踩过）；
         * 而只要还在连续重试，用户就该一直看得见原因。
         */
        private const val REASON_KEEP_MS = 60_000L
        private const val POSITION_INTERVAL_MS = 5_000L

        // 写盘的防抖与「进度不前进就不写」的判据都在 WatchHistory.shouldStore 里
        // （纯函数，有单测）；这里不再留一份同名常量，免得两处各改一半。

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

        /** 部分遥控器的「设置」键报这个 keyCode（不是 KEYCODE_MENU）。 */
        private const val KEYCODE_SETTINGS = 176

        /** 起播前认音频编码时读多少字节：1MB 足够覆盖 PAT/PMT。 */
        private const val PROBE_BYTES = 1 shl 20

        /** 等 Surface 的最长时间；超过就报故障，不无声地卡住。 */
        private const val SURFACE_WAIT_MS = 8_000L

        /**
         * 等 Surface 超时后的重问退避（毫秒）。
         *
         * 取"越等越久"而不是固定 500ms：Surface 迟迟不来通常是开机时系统还忙，
         * 高频轮询没有意义；但**绝不能放弃** —— 老实现是超时就丢掉排队的那一集
         * 并且不重试，屏幕就永远停在那儿了。
         */
        private val SURFACE_RETRY_BACKOFF_MS = longArrayOf(3_000L, 6_000L, 10_000L, 15_000L, 20_000L)

        /**
         * 从「决定要播」到「真的在播」最多允许多久，超了就自动重启一轮（见 [startupWatchdog]）。
         *
         * 取 40 秒：要盖得过后台列表刷新碰上的慢 NAS，又要明显早于
         * SwitchHud 那条 60 秒的提示期限，这样自动重启发生在提示自己退场之前。
         */
        private const val STARTUP_TIMEOUT_MS = 40_000L

        /** 启动兜底最多自动重启几轮；再不行就出故障页（带 10 秒重试），不无限重启。 */
        private const val MAX_STARTUP_RECOVER = 3

        /** 距离上次自动重启超过这么久，就把重启额度还回去（见 [startupWatchdog]）。 */
        private const val STARTUP_RECOVER_COOLDOWN_MS = 5 * 60 * 1000L

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
