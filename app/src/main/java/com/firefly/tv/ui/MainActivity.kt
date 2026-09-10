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
import com.firefly.tv.media.Library
import com.firefly.tv.media.LiveSource
import com.firefly.tv.media.Scanner
import com.firefly.tv.net.WeatherClient
import com.firefly.tv.player.IjkPlaybackEngine
import com.firefly.tv.player.PlaybackEngine
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
                when (focusChange) {
                    android.media.AudioManager.AUDIOFOCUS_LOSS,
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    -> engine.stop()

                    // 拿回焦点后按库类型原样重播，别把直播当剧集重放
                    android.media.AudioManager.AUDIOFOCUS_GAIN -> replayCurrent()
                }
            }
        }
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

    private var currentTitle: String = ""

    // ---- 定时任务 ----
    private val overlayHide = Runnable { hideOverlay() }
    private val retryTick = Runnable { startPlayback() }
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

        if (!Config.configured(this)) {
            openConfigServer()
        } else {
            startPlayback()
            main.postDelayed(positionTick, POSITION_INTERVAL_MS)
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

        configScreen.visibility = View.GONE
        faultScreen.visibility = View.GONE
        overlay.visibility = View.GONE
        root.addView(configScreen, matchParent())
        root.addView(faultScreen, matchParent())
        root.addView(overlay, matchParent())

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
        // 播放请求可能早于 Surface 就绪，这里补上
        pendingEpisode?.let { p ->
            pendingEpisode = null
            val cfg = Config.smb(this)
            ioHandler.post {
                try {
                    engine.playSmb(cfg, p.path, p.startMs)
                } catch (t: Throwable) {
                    Log.w(TAG, "播放失败 ${p.path}", t)
                    main.post { onError("这个视频无法播放", fatal = true) }
                }
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 尺寸变化由 SurfaceView 自己处理，播放器只认 surface 对象
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        engine.detachSurface()
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
        configScreen.recycle()
        configScreen.visibility = View.GONE
        startPlayback()
        main.removeCallbacks(positionTick)
        main.postDelayed(positionTick, POSITION_INTERVAL_MS)
    }

    private fun closeConfigServer() {
        configServer?.stop()
        configServer = null
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
            try {
                val libs = Scanner.libraries(cfg)
                main.post {
                    if (libs.isEmpty()) {
                        showFault("NAS 上还没有可以播放的内容")
                    } else {
                        libraries = libs
                        restoreSpot(libs)
                    }
                }
            } catch (t: Throwable) {
                val msg = SmbClient.describe(t)
                SmbStore.drop()
                main.post { showFault(msg, retry = true) }
            }
        }
    }

    /** 恢复上次的库/剧/集/精确进度（DESIGN §5）。 */
    private fun restoreSpot(libs: List<Library>) {
        val spot = Config.spot(this)
        var li = libs.indexOfFirst { it.name == spot.lib }
        if (li < 0) li = 0
        // 只有「上次看的就是这个库」时才谈得上续播；否则从第 1 集开始
        val sameLibrary = libs[li].name == spot.lib
        selectLibrary(li, resumeShow = if (sameLibrary) spot.show else "", resumeIndex = spot.index, resumePos = if (sameLibrary) spot.posMs else 0L)
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

        when (lib) {
            is Library.Video -> {
                ioHandler.post {
                    val list = try {
                        Scanner.shows(cfg, lib)
                    } catch (t: Throwable) {
                        postIf(seq) { showFault(SmbClient.describe(t), retry = true) }
                        return@post
                    }
                    postIf(seq) {
                        if (list.isEmpty()) {
                            // 这个库一部剧都没有：换下一个库
                            selectLibrary(libIndex + dir, "", 0, 0L, dir)
                        } else {
                            cachedShowsLib = lib.name
                            shows = list
                            val found = list.indexOf(resumeShow)
                            val si = if (found >= 0) found else 0
                            val ep = if (found >= 0) resumeIndex else 0
                            val pos = if (found >= 0) resumePos else 0L
                            playShow(lib, si, ep, pos, seq)
                        }
                    }
                }
            }

            is Library.Live -> loadChannels(lib, Config.channel(this), seq)
        }
    }

    /** 换库：循环，播放目标库的记忆位置。 */
    private fun switchLibrary(delta: Int) {
        if (libraries.isEmpty()) return
        val target = Navigator.horizontal(libIndex, libraries.size, delta)
        // 只有在「目标库 == 上次记录的库」时才有记忆位置可用
        val spot = Config.spot(this)
        val same = libraries[target].name == spot.lib
        selectLibrary(
            target,
            resumeShow = if (same) spot.show else "",
            resumeIndex = spot.index,
            resumePos = if (same) spot.posMs else 0L,
            dir = if (delta == 0) 1 else delta,
        )
    }

    /** ↑ / ↓：决策交给 [Navigator]（有单元测试钉住），这里只负责执行。 */
    private fun switchShowOrChannel(delta: Int) {
        if (libraries.isEmpty()) return
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

    /** 当前库的剧列表；只在首次需要时问一次 NAS。 */
    private fun loadShows(lib: Library.Video): List<String> {
        if (cachedShowsLib == lib.name && shows.isNotEmpty()) return shows
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
        ioHandler.post {
            val eps = try {
                Scanner.episodes(cfg, lib, wanted)
            } catch (t: Throwable) {
                postIf(seq) { showFault(SmbClient.describe(t), retry = true) }
                return@post
            }
            postIf(seq) {
                if (eps.isEmpty()) {
                    // 无视频文件的文件夹一律跳过（DESIGN §4）
                    showFault("《$wanted》里没有能播放的视频", retry = false)
                    main.postDelayed({ switchShowOrChannel(1) }, 3000)
                } else {
                    episodes = eps
                    cachedShow = wanted
                    episodeIndex = epIdx.coerceIn(0, eps.size - 1)
                    playEpisode(startMs)
                }
            }
        }
    }

    /**
     * 回到主线程执行，但只在「还是同一轮切库」时才执行。
     * 用它可以避免在嵌套 post 里写 `return@post` —— 那会产生
     * “more than one label with such a name” 的歧义警告。
     */
    private inline fun postIf(seq: Int, crossinline block: () -> Unit) {
        main.post { if (seq == librarySeq) block() }
    }

    private fun playEpisode(startMs: Long) {
        val lib = libraries.getOrNull(libIndex) as? Library.Video ?: return
        val ep = episodes.getOrNull(episodeIndex) ?: return
        val path = Scanner.episodePath(lib, showName, ep)
        currentTitle = showName
        Config.saveSpot(this, lib.name, showName, episodeIndex, startMs)

        if (!surfaceReady) {
            // Surface 还没就绪，等 surfaceCreated
            pendingEpisode = PendingEpisode(path, startMs)
            return
        }
        val cfg = Config.smb(this)
        ioHandler.post {
            try {
                // 在 IO 线程打开 SMB 文件（playSmb 会阻塞），避免卡主线程
                engine.playSmb(cfg, path, startMs)
            } catch (t: Throwable) {
                Log.w(TAG, "播放失败 $path", t)
                main.post { onError("这个视频无法播放", fatal = true) }
            }
        }
    }

    private class PendingEpisode(val path: String, val startMs: Long)
    private var pendingEpisode: PendingEpisode? = null

    /** 一集播完自动下一集 → 全剧播完下一部剧 → 最后一部回到该库第一部（DESIGN §5）。 */
    private fun onEpisodeFinished() {
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
        val cfg = Config.smb(this)
        ioHandler.post {
            val list = try {
                LiveSource.channels(cfg, lib)
            } catch (t: Throwable) {
                postIf(seq) { showFault("直播源暂时无法加载", retry = true) }
                return@post
            }
            postIf(seq) {
                if (list.isEmpty()) {
                    showFault("直播源暂时无法加载", retry = true)
                } else {
                    channels = list
                    channelLib = lib.name
                    channelIndex = startIndex.coerceIn(0, list.size - 1)
                    playChannel()
                }
            }
        }
    }

    private fun playChannel() {
        val ch = channels.getOrNull(channelIndex) ?: return
        currentTitle = ch.name
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

        overlay.show(clock, dateParts.joinToString("  "), weatherText, tomorrow, currentTitle)

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

    /** 按当前库类型原样重播当前位置（音频焦点拿回来时用）。 */
    private fun replayCurrent() {
        when (libraries.getOrNull(libIndex)) {
            is Library.Video -> if (episodes.isNotEmpty()) playEpisode(engine.positionMs())
            is Library.Live -> if (channels.isNotEmpty()) {
                // 直播没有进度概念，直接重新起播当前频道
                engine.setLiveReconnect(true) { channels.getOrNull(channelIndex)?.url }
                playChannel()
            }
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
    }

    override fun onCompletion() {
        onEpisodeFinished()
    }

    override fun onError(friendlyMessage: String, fatal: Boolean) {
        if (fatal) {
            // 单集文件损坏：3 秒后跳下一个（DESIGN §8）
            showFault(friendlyMessage, retry = false)
            main.postDelayed({
                showFault(null)
                onEpisodeFinished()
            }, 3000)
        } else {
            showFault(friendlyMessage, retry = true)
        }
    }

    override fun onFirstFrame() {
        showFault(null)
    }

    // ---- 续播写盘（5 秒防抖，避免频繁 IO 卡顿） ----

    private fun savePositionDebounced() {
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
        val pos = engine.positionMs()
        if (pos > 0) Config.savePosition(this, pos)
    }

    override fun onDestroy() {
        // DESIGN §7 防泄漏硬要求，逐条来
        main.removeCallbacksAndMessages(null)
        ioHandler.removeCallbacksAndMessages(null)
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
        private const val OVERLAY_MS = 10_000L
        private const val RETRY_MS = 10_000L
        private const val POSITION_INTERVAL_MS = 5_000L
        private const val SAVE_DEBOUNCE_MS = 5_000L
        private const val WEATHER_TTL_MS = 30 * 60 * 1000L
        private val NUM_DIGITS = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
    }
}
