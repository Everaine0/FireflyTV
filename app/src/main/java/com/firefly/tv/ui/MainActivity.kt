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

    // ---- 当前播放位置 ----
    private var libraries: List<Library> = emptyList()
    private var libIndex = 0
    private var showName: String = ""
    private var episodeIndex = 0
    private var episodes: List<String> = emptyList()
    private var channels: List<Library.Channel> = emptyList()
    private var channelIndex = 0

    private var currentTitle: String = ""
    private var pendingResumeMs = 0L

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
        libIndex = li
        when (val lib = libs[li]) {
            is Library.Video -> {
                pendingResumeMs = spot.posMs
                ioHandler.post {
                    val shows = try {
                        Scanner.shows(Config.smb(this), lib)
                    } catch (t: Throwable) {
                        emptyList()
                    }
                    main.post {
                        if (shows.isEmpty()) {
                            advanceLibrary(1)
                        } else {
                            val si = shows.indexOf(spot.show).takeIf { it >= 0 } ?: 0
                            playShow(lib, shows, si, spot.index, pendingResumeMs)
                        }
                    }
                }
            }
            is Library.Live -> {
                loadChannels(lib, Config.channel(this))
            }
        }
    }

    /** 换库：循环，播放目标库的记忆位置。 */
    private fun switchLibrary(delta: Int) {
        if (libraries.isEmpty()) return
        advanceLibrary(delta)
    }

    private fun advanceLibrary(delta: Int) {
        if (libraries.isEmpty()) return
        libIndex = ((libIndex + delta) % libraries.size + libraries.size) % libraries.size
        when (val lib = libraries[libIndex]) {
            is Library.Video -> {
                val savedShow = if (lib.name == Config.spot(this).lib) Config.spot(this).show else ""
                val savedEp = if (lib.name == Config.spot(this).lib) Config.spot(this).index else 0
                val savedPos = if (lib.name == Config.spot(this).lib) Config.spot(this).posMs else 0L
                val cfg = Config.smb(this)
                ioHandler.post {
                    val shows = try {
                        Scanner.shows(cfg, lib)
                    } catch (t: Throwable) {
                        main.post { showFault(SmbClient.describe(t), retry = true) }
                        return@post
                    }
                    main.post {
                        if (shows.isEmpty()) {
                            advanceLibrary(if (delta == 0) 1 else delta)
                        } else {
                            val si = shows.indexOf(savedShow).takeIf { it >= 0 } ?: 0
                            val ep = if (si >= 0 && savedShow.isNotEmpty()) savedEp else 0
                            val pos = if (savedShow.isNotEmpty()) savedPos else 0L
                            playShow(lib, shows, si, ep, pos)
                        }
                    }
                }
            }
            is Library.Live -> loadChannels(lib, Config.channel(this))
        }
    }

    /** ↑ / ↓：视频库换剧（第 1 集），直播库换频道。 */
    private fun switchShowOrChannel(delta: Int) {
        if (libraries.isEmpty()) return
        when (val lib = libraries[libIndex]) {
            is Library.Video -> {
                val cfg = Config.smb(this)
                ioHandler.post work@{
                    val shows = try {
                        Scanner.shows(cfg, lib)
                    } catch (t: Throwable) {
                        main.post { showFault(SmbClient.describe(t), retry = true) }
                        return@work
                    }
                    main.post {
                        if (shows.isNotEmpty()) {
                            var next = shows.indexOf(showName)
                            next = if (next < 0) 0 else ((next + delta) % shows.size + shows.size) % shows.size
                            playShow(lib, shows, next, 0, 0L)
                        }
                    }
                }
            }
            is Library.Live -> {
                if (channels.isEmpty()) return
                channelIndex = ((channelIndex + delta) % channels.size + channels.size) % channels.size
                Config.saveChannel(this, channelIndex)
                playChannel()
            }
        }
    }

    private fun playShow(
        lib: Library.Video,
        shows: List<String>,
        showIdx: Int,
        epIdx: Int,
        startMs: Long,
    ) {
        showName = shows[showIdx]
        val cfg = Config.smb(this)
        ioHandler.post {
            val eps = try {
                Scanner.episodes(cfg, lib, showName)
            } catch (t: Throwable) {
                main.post { showFault(SmbClient.describe(t), retry = true) }
                return@post
            }
            main.post {
                if (eps.isEmpty()) {
                    // 无视频文件的文件夹一律跳过（DESIGN §4）
                    showFault("《$showName》里没有能播放的视频", retry = false)
                    main.postDelayed({ switchShowOrChannel(1) }, 3000)
                } else {
                    episodes = eps
                    episodeIndex = epIdx.coerceIn(0, eps.size - 1)
                    playEpisode(startMs)
                }
            }
        }
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
        // 全剧播完 → 下一部剧
        val cfg = Config.smb(this)
        ioHandler.post {
            val lib = libraries.getOrNull(libIndex) as? Library.Video
            if (lib == null) {
                main.post { playEpisode(0L) }
                return@post
            }
            val shows = runCatching { Scanner.shows(cfg, lib) }.getOrDefault(emptyList())
            main.post {
                if (shows.isEmpty()) {
                    playEpisode(0L)
                } else {
                    var next = shows.indexOf(showName)
                    next = if (next < 0) 0 else (next + 1) % shows.size
                    playShow(lib, shows, next, 0, 0L)
                }
            }
        }
    }

    private fun loadChannels(lib: Library.Live, startIndex: Int) {
        val cfg = Config.smb(this)
        ioHandler.post {
            val list = try {
                LiveSource.channels(cfg, lib)
            } catch (t: Throwable) {
                main.post { showFault("直播源暂时无法加载", retry = true) }
                return@post
            }
            main.post {
                if (list.isEmpty()) {
                    showFault("直播源暂时无法加载", retry = true)
                } else {
                    channels = list
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
