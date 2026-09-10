package com.firefly.tv.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
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
import com.firefly.tv.media.LiveSource
import com.firefly.tv.media.Scanner
import com.firefly.tv.net.WeatherClient
import com.firefly.tv.player.IjkPlaybackEngine
import com.firefly.tv.player.PlaybackEngine
import com.firefly.tv.player.PlaybackMode
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
    private val positionTick = object : Runnable {
        override fun run() {
            savePositionDebounced()
            main.postDelayed(this, POSITION_INTERVAL_MS)
        }
    }

    private var lastSavedPos = 0L
    private var lastSavedAt = 0L

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

        // 缓存读盘放在 IO 线程，别在主线程碰 SharedPreferences
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

        surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.RGBA_8888)
            holder.addCallback(this@MainActivity)
        }
        root.addView(surfaceView, matchParent())

        configScreen = ConfigScreen(this)
        faultScreen = FaultScreen(this)
        overlay = OverlayScreen(this)
        hudView = HudView(this)

        configScreen.visibility = View.GONE
        faultScreen.visibility = View.GONE
        overlay.visibility = View.GONE
        hudView.visibility = View.GONE
        root.addView(configScreen, matchParent())
        root.addView(faultScreen, matchParent())
        root.addView(overlay, matchParent())
        root.addView(hudView, matchParent())

        setContentView(root)
    }

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
        // 尺寸变化由 SurfaceView 自己处理，播放器只认 surface 对象
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        engine.detachSurface()
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
        startedAt = System.currentTimeMillis()
        gotFirstFrame = false
        main.removeCallbacks(stallWatchdog)
        main.postDelayed(stallWatchdog, STALL_TIMEOUT_MS)
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
     * 起播卡死看门狗。
     *
     * 「卡在当前页面」是用户实测到的最烦人的一种故障：按键都有反应、浮层也出得来，
     * 就是画面不动，而且**永远不会自己好**。以前的代码在这种情况下一声不吭。
     *
     * 这里给每次起播都挂一个超时：到点还没出首帧就当作播放失败处理
     * （跳故障页 → 自动重试）。宁可让它自己重试几次，也不要留一个死的画面。
     */
    private val stallWatchdog = Runnable {
        if (gotFirstFrame) return@Runnable
        val waited = System.currentTimeMillis() - startedAt
        Log.w(TAG, "起播 ${waited}ms 还没出首帧，判定卡死，走故障页重试")
        trace("stallWatchdog 卡死 ${waited}ms")
        onError("这个视频打不开，正在换下一个", fatal = false)
    }

    // ---- 按键（只有三类） ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 长按也当一次处理，避免老人按住不放导致疯狂切台
        if (event.repeatCount > 0) return true

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> consume(event) { switchLibrary(-1) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> consume(event) { switchLibrary(+1) }
            KeyEvent.KEYCODE_DPAD_UP -> consume(event) { switchShowOrChannel(-1) }
            KeyEvent.KEYCODE_DPAD_DOWN -> consume(event) { switchShowOrChannel(+1) }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> consume(event) { toggleOverlay() }
            else -> true // 返回、主页、菜单一律不响应
        }
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
                        libraries = libs
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

    /** 恢复上次的库/剧/集/精确进度（DESIGN §5）。 */
    private fun restoreSpot(libs: List<Library>) {
        val spot = Config.spot(this)
        var li = libs.indexOfFirst { it.name == spot.lib }
        if (li < 0) li = 0
        // 只有「上次看的就是这个库」时才谈得上续播；否则从第 1 集开始
        val start = Navigator.startPoint(spot.lib, libs[li].name, spot.show, spot.index, spot.posMs)
        selectLibrary(li, resumeShow = start.show, resumeIndex = start.episode, resumePos = start.positionMs)
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
    ) {
        if (libraries.isEmpty()) return
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
                refreshShows(lib, cfg, seq, resumeShow, resumeIndex, resumePos, dir)
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
                    // 这个库一部剧都没有：换下一个库
                    selectLibrary(libIndex + dir, "", 0, 0L, dir)
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

    /** 换库：循环，播放目标库的记忆位置。 */
    private fun switchLibrary(delta: Int) {
        if (libraries.isEmpty()) return
        trace("switchLibrary delta=$delta from=${libraries.getOrNull(libIndex)?.name}")
        val target = Navigator.horizontal(libIndex, libraries.size, delta)
        // 记忆位置只对「上次看的那个库」有效，否则会拿着别的内容的名字去这个库里找
        val spot = Config.spot(this)
        val start = Navigator.startPoint(spot.lib, libraries[target].name, spot.show, spot.index, spot.posMs)
        selectLibrary(
            target,
            resumeShow = start.show,
            resumeIndex = start.episode,
            resumePos = start.positionMs,
            dir = if (delta == 0) 1 else delta,
        )
    }

    /** ↑ / ↓：决策交给 [Navigator]（有单元测试钉住），这里只负责执行。 */
    private fun switchShowOrChannel(delta: Int) {
        if (libraries.isEmpty()) return
        trace("switchShowOrChannel delta=$delta lib=${libraries.getOrNull(libIndex)?.name} shows=${shows.size} chans=${channels.size}")
        val action = Navigator.vertical(
            lib = libraries.getOrNull(libIndex),
            shows = shows,
            channels = channels,
            current = currentContent(),
            delta = delta,
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
                        // 无视频文件的文件夹一律跳过（DESIGN §4）
                        showFault("《$wanted》里没有能播放的视频", retry = false)
                        main.postDelayed({ switchShowOrChannel(1) }, 3000)
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
        val ep = episodes.getOrNull(episodeIndex) ?: return
        val path = Scanner.episodePath(lib, showName, ep)
        currentTitle = showName
        // 先出名字，再去做后面那些可能慢的事
        announce(showName)
        Config.saveSpot(this, lib.name, showName, episodeIndex, startMs)
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
        checkAudio(path)
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
        announce(ch.name) // 立刻出频道名，换台不能看起来没反应
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

    private var lastWeather: WeatherClient.Now? = null
    private var lastWeatherAt = 0L

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

        val w = lastWeather
        val weatherText = when {
            w != null -> "${w.text} ${w.temp}°"
            Config.weather(this).ready -> "天气获取中…"
            else -> "还没有设置天气"
        }
        val tomorrow = w?.tomorrow?.let {
            val night = if (it.nightText.isNotBlank() && it.nightText != it.dayText) "转${it.nightText}" else ""
            "明天 $it.dayText$night ${it.tempMin}~${it.tempMax}°"
        }.orEmpty()

        overlay.show(clock, dateParts.joinToString("  "), weatherText, tomorrow, currentTitle, audioWarning.orEmpty())

        // 天气过期就后台刷新，不阻塞浮层其他内容（DESIGN §8）
        if (Config.weather(this).ready && System.currentTimeMillis() - lastWeatherAt > WEATHER_TTL_MS) {
            refreshWeather()
        }
    }

    private fun refreshWeather() {
        val cfg = Config.weather(this)
        if (!cfg.ready) return
        lastWeatherAt = System.currentTimeMillis()
        ioHandler.post {
            val result = runCatching { WeatherClient.fetch(cfg) }.getOrNull()
            if (result != null) {
                main.post {
                    lastWeather = result
                    if (overlay.visibility == View.VISIBLE) renderOverlay()
                }
            }
        }
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
            sb.append("，今天")
            sb.append(w.text)
            sb.append("，气温")
            sb.append(chineseNumber(w.temp.toIntOrNull() ?: 0))
            sb.append("度")
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

    private fun showFault(message: String?, retry: Boolean = false) {
        main.removeCallbacks(retryTick)
        if (message == null) {
            faultScreen.visibility = View.GONE
            return
        }
        faultScreen.show(message)
        faultScreen.visibility = View.VISIBLE
        if (retry) {
            // 每 10 秒重试一次；销毁时必须 removeCallbacks，否则稳定泄漏（DESIGN §7）
            main.postDelayed(retryTick, RETRY_MS)
        }
    }

    // ---- PlaybackEngine.Listener ----

    override fun onPrepared(durationMs: Long) {
        showFault(null)
        // 「正在打开…」换成内容名，1.6 秒后收起 —— 这一刻用户才算确认换台/换剧成功
        onContentPicked(currentTitle)
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
        // 画面已经出来了，「正在打开…」的过渡页就没必要再占着屏幕
        main.removeCallbacks(configStartingTick)
        configStartingTick.run()
    }

    // ---- 续播写盘（5 秒防抖，避免频繁 IO 卡顿） ----

    /**
     * 只给**点播**记进度。
     *
     * 直播的 currentPosition 是从频道开播算起的毫秒数，会一路涨到几十小时；
     * 它一旦被写进 last_pos，下次就会拿这个假进度去 seek，结果就是起播后长时间
     * 音画不同步（用户实测到的现象）。所以直播直接不写（见 [PlaybackMode]）。
     */
    private fun savePositionDebounced() {
        if (!PlaybackMode.remembersPosition(PlaybackMode.of(libraries.getOrNull(libIndex)))) return
        val pos = engine.positionMs()
        if (pos <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastSavedAt < SAVE_DEBOUNCE_MS) return
        if (kotlin.math.abs(pos - lastSavedPos) < 1000) return
        lastSavedPos = pos
        lastSavedAt = now
        Config.savePosition(this, pos)
    }

    // ---- 生命周期 ----

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        // 直播没有进度可存，存了下次就会拿它去 seek 出一条不同步的流
        if (!PlaybackMode.remembersPosition(PlaybackMode.of(libraries.getOrNull(libIndex)))) return
        val pos = engine.positionMs()
        if (pos > 0) Config.savePosition(this, pos)
    }

    override fun onDestroy() {
        // DESIGN §7 防泄漏硬要求，逐条来
        main.removeCallbacksAndMessages(null)
        ioHandler.removeCallbacksAndMessages(null)
        runCatching { hud.dismiss() }
        runCatching { main.removeCallbacks(stallWatchdog) }
        runCatching { engine.release() }
        runCatching { tts.shutdown() }
        runCatching { abandonAudioFocus() }
        runCatching { closeConfigServer() }
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

        /** 起播前认音频编码时读多少字节：1MB 足够覆盖 PAT/PMT。 */
        private const val PROBE_BYTES = 1 shl 20

        /** 等 Surface 的最长时间；超过就报故障，不无声地卡住。 */
        private const val SURFACE_WAIT_MS = 8_000L

        /** 等 Surface 时的问询间隔。 */
        private const val SURFACE_POLL_MS = 500L

        /** 「配置已保存，正在打开」最多显示这么久。 */
        private const val CONFIG_STARTING_MS = 15_000L

        /** 起播后多久没出首帧就判定卡死。4K 大文件要留足时间。 */
        private const val STALL_TIMEOUT_MS = 25_000L
        private const val WEATHER_TTL_MS = 30 * 60 * 1000L
        private val NUM_DIGITS = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
    }
}
