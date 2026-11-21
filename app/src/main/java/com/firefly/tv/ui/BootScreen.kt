package com.firefly.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sin

/**
 * 开机页：遥控器说明 + 等待 NAS 硬盘唤醒。
 *
 * 为什么要有这一页：NAS 的硬盘会休眠，第一次打开一集要等盘转起来，
 * 那十几秒里屏幕上原来只有「正在加载…」一条小提示。目标用户是老人，
 * 面对黑屏只会以为电视坏了。这一页把等待解释清楚，同时把「日常只用三组键」
 * 的操作说明在每次开机时重复一遍 —— 视频在背后照常起播，不额外拖慢。
 *
 * 版式（16:9 电视）：
 *
 * ```
 *             萤火照夜
 *       遥控器上日常只用三组键
 *
 *    ┌──────┐   ┌─────────────────────┐
 *    │ 遥控 │   │ ← →   换媒体库       │  ← 三行说明轮流高亮，
 *    │  器  │   │ ↑ ↓   换剧 / 换频道  │     每行 1.6 秒
 *    │      │   │ OK    天气和时间      │
 *    └──────┘   └─────────────────────┘
 *
 *        正在连接 NAS…
 *        ▓▓▓▓▓░░░░░░░░░░░░
 *   第一次打开，NAS 硬盘要转一会儿，属正常现象
 * ```
 *
 * 退场规则在 [BootGate]（纯函数 + 单测）：首帧出来且待够 5 秒就淡出；
 * 故障页要说话时立刻让位；最多待到 90 秒。
 *
 * 动画开销刻意压得很低：整页只有遥控器那一小块和进度条在重绘（约 20Hz），
 * 文字与背景都是静态的。老电视的 CPU 本来就紧，开机时更不该抢它的时间。
 */
class BootScreen(context: Context) : FrameLayout(context) {

    private val ui = Ui(context)

    private val content = LinearLayout(context)
    private val title = text(40f, Color.WHITE).apply {
        text = "萤火照夜"
        setTypeface(Typeface.DEFAULT_BOLD)
    }
    private val subtitle = text(22f, SECONDARY).apply { text = "遥控器上日常只用三组键" }
    private val remote = RemoteView(context)
    private val hintList = LinearLayout(context)
    private val hints = ArrayList<Hint>(3)
    private val status = text(26f, Color.WHITE).apply { gravity = Gravity.CENTER }
    private val bar = PulseBar(context)
    private val note = text(20f, SECONDARY).apply {
        text = "第一次打开，NAS 的硬盘可能要转一会儿，属正常现象"
        gravity = Gravity.CENTER
    }

    private var scene = -1
    private var shownAt = 0L
    private var statusAt = 0L
    private var firstFrame = false
    private var faultVisible = false
    private var autoWakeHint = false
    private var dismissed = false
    private var onDismiss: ((BootGate.Exit) -> Unit)? = null

    init {
        visibility = GONE
        build()
    }

    /**
     * 盖上整屏并开始计时。
     *
     * @param statusText 第一句状态（通常是「正在连接 NAS…」）
     * @param wakeHintAfter 若为 true，[WAKE_HINT_AFTER_MS] 后把状态换成「硬盘正在唤醒」。
     *   调用方在真的开始读片源时应该用 [setStatus] 把它覆盖掉。
     * @param onDismiss 淡出完成后回调，参数说明为什么退场（见 [BootGate.Exit]）
     */
    fun show(
        statusText: String,
        wakeHintAfter: Boolean = true,
        onDismiss: (BootGate.Exit) -> Unit,
    ) {
        this.onDismiss = onDismiss
        shownAt = System.currentTimeMillis()
        statusAt = shownAt
        firstFrame = false
        faultVisible = false
        autoWakeHint = wakeHintAfter
        dismissed = false
        scene = -1
        status.text = statusText

        if (ui.refresh()) build()
        applyScene(0)

        // 上一次淡出如果还没跑完，先取消：它的 withEndAction 会把页面设回 GONE。
        animate().cancel()
        removeCallbacks(tick)
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(FADE_IN_MS).start()
        post(tick)
    }

    /**
     * 换一句状态。
     *
     * @param wakeHintAfter true = 这句如果一直没被换掉，几秒后自动补一句「硬盘正在唤醒」
     *   （用于等 NAS 的阶段；读片源阶段传 false）
     */
    fun setStatus(text: String, wakeHintAfter: Boolean = false) {
        status.text = text
        statusAt = System.currentTimeMillis()
        autoWakeHint = wakeHintAfter
    }

    /**
     * 首帧已经上屏。
     *
     * @return true = 还没到最短展示时间、页面会再停留一会儿。
     *   调用方这时应该把播放器暂停，否则用户会先听到声音、几秒后才看到画面。
     *   false = 已经在退场（或刚退场），不要再暂停。
     */
    fun notifyFirstFrame(): Boolean {
        firstFrame = true
        val reason = BootGate.exitReason(
            System.currentTimeMillis(), shownAt, firstFrame = true, faultVisible = faultVisible,
        )
        if (reason != null) {
            dismiss(reason)
            return false
        }
        // 首帧之后不需要再等 NAS 了，把状态改成一句让人放心的话
        status.text = READY_HINT
        autoWakeHint = false
        return true
    }

    /** 故障页要说话：立刻让位，别把原因盖住。 */
    fun notifyFault() {
        faultVisible = true
        dismiss(BootGate.Exit.FAULT)
    }

    fun isShowing(): Boolean = visibility == View.VISIBLE && !dismissed

    /** 页面离开屏幕（销毁）时调用：停掉定时器、丢弃回调，不触发 [onDismiss]。 */
    fun stop() {
        removeCallbacks(tick)
        animate().cancel()
        visibility = GONE
        dismissed = true
        onDismiss = null
    }

    private fun dismiss(reason: BootGate.Exit) {
        if (dismissed) return
        dismissed = true
        removeCallbacks(tick)
        if (visibility != View.VISIBLE) {
            finish(reason)
            return
        }
        animate().alpha(0f).setDuration(BootGate.FADE_MS).withEndAction {
            visibility = GONE
            alpha = 1f
            finish(reason)
        }.start()
    }

    private fun finish(reason: BootGate.Exit) {
        val cb = onDismiss
        onDismiss = null
        cb?.invoke(reason)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (visibility != View.VISIBLE || dismissed) return
            val now = System.currentTimeMillis()
            val elapsed = BootGate.elapsedMs(now, shownAt)

            val next = ((elapsed / SCENE_MS) % hints.size).toInt()
            if (next != scene) applyScene(next)

            remote.pulse = ((sin(elapsed / 230.0) + 1.0) / 2.0).toFloat()
            bar.phase = (elapsed % PULSE_MS).toFloat() / PULSE_MS

            if (autoWakeHint && now - statusAt >= WAKE_HINT_AFTER_MS) {
                status.text = WAKE_HINT
                autoWakeHint = false
            }

            val reason = BootGate.exitReason(now, shownAt, firstFrame, faultVisible)
            if (reason != null) {
                dismiss(reason)
                return
            }
            postDelayed(this, TICK_MS)
        }
    }

    private fun applyScene(index: Int) {
        scene = index
        remote.active = index
        hints.forEachIndexed { i, h ->
            val on = i == index
            h.row.background = rounded(HINT_RADIUS, if (on) ACTIVE_BG else INACTIVE_BG)
            h.accent.setBackgroundColor(if (on) ACCENT else Color.TRANSPARENT)
            h.keys.setTextColor(if (on) ACCENT else SECONDARY)
            h.action.setTextColor(if (on) Color.WHITE else SECONDARY)
        }
    }

    private fun build() {
        removeAllViews()
        content.removeAllViews()
        hintList.removeAllViews()
        hints.clear()

        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(BG_TOP, BG_BOTTOM),
        )

        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL

        // 标题旁边一颗暖色小点：和播放页/浮层用的是同一个强调色，不额外引入品牌色
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACCENT)
            }
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(dot, LinearLayout.LayoutParams(ui.px(10), ui.px(10)))
            addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = ui.px(14) })
        }
        content.addView(titleRow)
        content.addView(subtitle, wrap(top = 10))

        remote.start(ui.px(136), ui.px(260))
        hintList.orientation = LinearLayout.VERTICAL
        hintList.addView(hint("←  →", "换媒体库").row, hintParams(0))
        hintList.addView(hint("↑  ↓", "换剧 / 换频道").row, hintParams(1))
        hintList.addView(hint("OK", "天气和时间").row, hintParams(2))

        val middle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(remote, LinearLayout.LayoutParams(ui.px(136), ui.px(260)))
            addView(hintList, LinearLayout.LayoutParams(
                ui.widthFraction(0.46f),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = ui.px(40) })
        }
        content.addView(middle, wrap(top = 22))

        content.addView(status, wrap(top = 22))
        content.addView(bar, LinearLayout.LayoutParams(
            ui.widthFraction(0.52f), ui.px(8),
        ).apply { topMargin = ui.px(14) })
        content.addView(note, LinearLayout.LayoutParams(
            ui.widthFraction(0.62f),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = ui.px(10) })

        addView(content, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        // 5% 安全边距：电视 overscan 会裁掉贴边内容（DESIGN §7）
        setPadding(ui.px(54), ui.px(30), ui.px(54), ui.px(30))
    }

    private fun hint(keysText: String, actionText: String): Hint {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(HINT_RADIUS, INACTIVE_BG)
            setPadding(ui.px(20), ui.px(12), ui.px(24), ui.px(12))
        }
        val accent = View(context)
        row.addView(accent, LinearLayout.LayoutParams(ui.px(5), ViewGroup.LayoutParams.MATCH_PARENT))

        val keys = text(30f, SECONDARY).apply {
            text = keysText
            setTypeface(Typeface.DEFAULT_BOLD)
            minWidth = ui.px(100)
        }
        row.addView(keys, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = ui.px(18) })

        val action = text(30f, SECONDARY).apply { text = actionText }
        row.addView(action, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = ui.px(16) })

        return Hint(row, keys, action, accent).also { hints += it }
    }

    private fun hintParams(index: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { if (index > 0) topMargin = ui.px(14) }

    private fun text(sp: Float, color: Int) = TextView(context).apply {
        ui.text(this, sp)
        setTextColor(color)
        includeFontPadding = false
    }

    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = ui.px(top) }

    private fun rounded(radius: Int, color: Int) = GradientDrawable().apply {
        cornerRadius = ui.px(radius.toFloat())
        setColor(color)
    }

    private class Hint(
        val row: LinearLayout,
        val keys: TextView,
        val action: TextView,
        val accent: View,
    )

    /**
     * 一只极简遥控器：方向键 + OK。只画真正用得到的键，
     * 画上「音量」「返回」只会让老人多问一句「这个按了怎么没用」。
     */
    private class RemoteView(context: Context) : View(context) {

        var active = 0
            set(value) {
                field = value
                invalidate()
            }

        var pulse = 0.5f
            set(value) {
                field = value
                invalidate()
            }

        private var ui: Ui? = null

        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BODY }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = STROKE_COLOR
        }
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DISC }
        private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KEY_OFF }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val okText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        /** 把 [Ui] 记录进来用于字号；尺寸由调用方通过 LayoutParams 定死。 */
        fun start(width: Int, height: Int) {
            minimumWidth = width
            minimumHeight = height
            ui = Ui(context)
            okText.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                17f * ui!!.scale,
                resources.displayMetrics,
            )
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            // 机身
            body.color = BODY
            canvas.drawRoundRect(RectF(w * 0.08f, 0f, w * 0.92f, h), w * 0.11f, w * 0.11f, body)
            stroke.strokeWidth = w * 0.012f
            stroke.color = STROKE_COLOR
            canvas.drawRoundRect(RectF(w * 0.08f, 0f, w * 0.92f, h), w * 0.11f, w * 0.11f, stroke)

            // 顶部红外窗 + 底部喇叭缝：只为了让它是「一只遥控器」，不承载信息
            body.color = TOP_DECOR
            canvas.drawRoundRect(RectF(w * 0.34f, h * 0.035f, w * 0.66f, h * 0.055f), w * 0.02f, w * 0.02f, body)
            canvas.drawRoundRect(RectF(w * 0.38f, h * 0.94f, w * 0.62f, h * 0.955f), w * 0.01f, w * 0.01f, body)

            val cx = w * 0.5f
            val cy = h * 0.44f
            val ring = w * 0.30f

            disc.color = DISC
            canvas.drawCircle(cx, cy, ring, disc)
            stroke.strokeWidth = w * 0.035f
            stroke.color = STROKE_COLOR
            canvas.drawCircle(cx, cy, ring, stroke)

            // 高亮光晕画在按键下面
            glow.color = Color.argb(
                (36 + 46 * pulse.coerceIn(0f, 1f)).toInt(),
                0xFF, 0xD5, 0x4F,
            )
            val glowR = ring * 0.44f
            if (active == 0) {
                canvas.drawCircle(cx - ring * 0.70f, cy, glowR, glow)
                canvas.drawCircle(cx + ring * 0.70f, cy, glowR, glow)
            }
            if (active == 1) {
                canvas.drawCircle(cx, cy - ring * 0.70f, glowR, glow)
                canvas.drawCircle(cx, cy + ring * 0.70f, glowR, glow)
            }
            if (active == 2) canvas.drawCircle(cx, cy, ring * 0.48f, glow)

            // 四个方向键
            val on = ACCENT
            val activeH = active == 0
            val activeV = active == 1
            keyPaint.color = if (activeH) on else KEY_OFF
            drawArrow(canvas, cx - ring * 0.70f, cy, -1f, 0f, w * 0.075f, keyPaint)
            drawArrow(canvas, cx + ring * 0.70f, cy, 1f, 0f, w * 0.075f, keyPaint)
            keyPaint.color = if (activeV) on else KEY_OFF
            drawArrow(canvas, cx, cy - ring * 0.70f, 0f, -1f, w * 0.075f, keyPaint)
            drawArrow(canvas, cx, cy + ring * 0.70f, 0f, 1f, w * 0.075f, keyPaint)

            // OK 键
            disc.color = if (active == 2) OK_ON else OK_OFF
            canvas.drawCircle(cx, cy, ring * 0.42f, disc)
            stroke.strokeWidth = w * 0.02f
            stroke.color = if (active == 2) ACCENT else STROKE_COLOR
            canvas.drawCircle(cx, cy, ring * 0.42f, stroke)
            canvas.drawText("OK", cx, cy + okText.textSize * 0.36f, okText)
        }

        private fun drawArrow(c: Canvas, cx: Float, cy: Float, dx: Float, dy: Float, size: Float, p: Paint) {
            val path = Path()
            if (dx != 0f) {
                path.moveTo(cx + dx * size, cy)
                path.lineTo(cx - dx * size * 0.55f, cy - size)
                path.lineTo(cx - dx * size * 0.55f, cy + size)
            } else {
                path.moveTo(cx, cy + dy * size)
                path.lineTo(cx - size, cy - dy * size * 0.55f)
                path.lineTo(cx + size, cy - dy * size * 0.55f)
            }
            path.close()
            c.drawPath(path, p)
        }

        private companion object {
            const val BODY = 0xFF1A2233.toInt()
            const val DISC = 0xFF232C3D.toInt()
            const val STROKE_COLOR = 0x2EFFFFFF
            const val TOP_DECOR = 0x33FFFFFF
            const val KEY_OFF = 0xFFD9FFFFFF.toInt()
            const val OK_OFF = 0xFF111827.toInt()
            const val OK_ON = 0xFF3A3010.toInt()
            const val ACCENT = 0xFFFFD54F.toInt()
        }
    }

    private companion object {
        const val SECONDARY = 0xB3FFFFFF.toInt()
        const val ACCENT = 0xFFFFD54F.toInt()
        const val BG_TOP = 0xFF101827.toInt()
        const val BG_BOTTOM = 0xFF05070C.toInt()
        const val INACTIVE_BG = 0x14FFFFFF
        const val ACTIVE_BG = 0x2EFFD54F
        const val HINT_RADIUS = 14

        const val FADE_IN_MS = 220L
        const val TICK_MS = 50L
        const val SCENE_MS = 1_600L
        const val PULSE_MS = 1_800L
        /** 比最短展示 5 秒长：快 NAS 上不会闪一句「硬盘正在唤醒」，真的等久了才出现。 */
        const val WAKE_HINT_AFTER_MS = 6_000L

        const val WAKE_HINT = "NAS 硬盘正在唤醒，请稍等…"
        const val READY_HINT = "画面已经准备好了"
    }
}
