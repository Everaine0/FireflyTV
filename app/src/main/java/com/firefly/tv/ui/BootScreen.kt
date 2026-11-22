package com.firefly.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sin

/**
 * 开机页：左边一只简洁遥控器 + 右边三组键的提示，底部等待 NAS。
 *
 * 目标用户是老人：遥控器画出按键位置，右侧同步高亮「现在讲哪一组」，
 * 比纯文字更容易对上号。没有品牌标题，也没有多余的插画。
 *
 * 退场规则在 [BootGate]（纯函数 + 单测）：首帧出来且**从真正画到屏幕上算起**待够 5 秒
 * 就淡出；故障页要说话时让位（会自动重试的失败先在这里写原因、挡两次）；最多待到 90 秒。
 *
 * 动画开销刻意压得很低：遥控器按键光晕 + 进度条约 20Hz，右侧只有选中态在变。
 */
class BootScreen(context: Context) : FrameLayout(context) {

    private val ui = Ui(context)

    private val content = LinearLayout(context)
    private val remote = RemoteGuideView(context)
    private val guide = LinearLayout(context)
    private val rows = ArrayList<GuideRow>(3)

    private val status = text(30f, Color.WHITE).apply { gravity = Gravity.CENTER }
    private val bar = PulseBar(context)
    private val note = text(22f, SECONDARY).apply {
        text = "第一次打开，NAS 的硬盘可能要转一会儿，属正常现象"
        gravity = Gravity.CENTER
    }
    private val bottom = LinearLayout(context)

    private var scene = -1

    /**
     * 首次真正绘制到屏幕上的时刻（[dispatchDraw] 第一次跑）。
     *
     * 最短 5 秒必须从这一刻算：`show()` 是在 onCreate 里调用的，而电视冷启动时
     * 窗口出画可能晚好几秒 —— 从 show() 计时会让用户看到的动画明显短于 5 秒
     * （实测反馈「大概 2 秒就没了」）。
     */
    private var drawnAt = 0L

    /** 上一次退场的原因与开机页真正露脸了多久（诊断页复盘用）。 */
    private var exitReason: BootGate.Exit? = null
    private var visibleMs = 0L
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
        statusAt = System.currentTimeMillis()
        drawnAt = 0L
        exitReason = null
        visibleMs = 0L
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
        val now = System.currentTimeMillis()
        val reason = BootGate.exitReason(
            now, BootGate.visibleAt(drawnAt, now), firstFrame = true, faultVisible = faultVisible,
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

    /** 上一次退场原因；还没退过场是 null。给诊断页复盘「开机页为什么走得早」用。 */
    fun lastExit(): BootGate.Exit? = exitReason

    /** 上一次开机页真正露脸了多少毫秒（没画过就是 0）。 */
    fun lastVisibleMs(): Long = visibleMs

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
        exitReason = reason
        visibleMs = if (drawnAt > 0L) (System.currentTimeMillis() - drawnAt).coerceAtLeast(0L) else 0L
        val cb = onDismiss
        onDismiss = null
        cb?.invoke(reason)
    }

    /**
     * 第一次真正绘制时才开始计时。
     *
     * 用 dispatchDraw 而不是 onDraw：本页的背景和内容都在子 View 里，dispatchDraw
     * 才是「这一页确实要上屏了」的那一刻；它同时发生在窗口可见的同一帧。
     */
    override fun dispatchDraw(canvas: Canvas) {
        if (drawnAt == 0L) {
            drawnAt = System.currentTimeMillis()
            // 「硬盘正在唤醒」那句也从这个时刻重新计，避免页面还没露面就到了
            statusAt = drawnAt
        }
        super.dispatchDraw(canvas)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (visibility != View.VISIBLE || dismissed) return
            val now = System.currentTimeMillis()
            // 没真正画过就不算等待；画过之后从露脸那一刻算
            val elapsed = BootGate.elapsedMs(now, BootGate.visibleAt(drawnAt, now))

            val next = ((elapsed / SCENE_MS) % rows.size).toInt()
            if (next != scene) applyScene(next)

            remote.pulse = ((sin(elapsed / 230.0) + 1.0) / 2.0).toFloat()
            bar.phase = (elapsed % PULSE_MS).toFloat() / PULSE_MS

            if (autoWakeHint && now - statusAt >= WAKE_HINT_AFTER_MS) {
                status.text = WAKE_HINT
                autoWakeHint = false
            }

            val reason = BootGate.exitReason(
                now, BootGate.visibleAt(drawnAt, now), firstFrame, faultVisible,
            )
            if (reason != null) {
                dismiss(reason)
                return
            }
            postDelayed(this, TICK_MS)
        }
    }

    /** 一行高亮：右侧卡片、左侧金条和遥控器上的按键一起点亮。 */
    private fun applyScene(index: Int) {
        scene = index
        remote.active = index
        rows.forEachIndexed { i, row ->
            val on = i == index
            row.card.background = cardBackground(on)
            row.accent.background = if (on) rounded(3f, ACCENT) else null
            row.keys.setTextColor(if (on) ACCENT else KEY_OFF)
            row.label.setTextColor(if (on) Color.WHITE else LABEL_OFF)
        }
    }

    private fun build() {
        removeAllViews()
        content.removeAllViews()
        guide.removeAllViews()
        rows.clear()

        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(BG_TOP, BG_BOTTOM),
        )

        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.setPadding(ui.px(54), ui.px(40), ui.px(54), ui.px(40))
        content.addView(spacer())

        val middle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        middle.addView(
            remote,
            LinearLayout.LayoutParams(ui.px(150), ui.px(260)),
        )

        guide.orientation = LinearLayout.VERTICAL
        guide.gravity = Gravity.CENTER_VERTICAL
        guide.addView(row("←  →", "换媒体库"), rowParams(0))
        guide.addView(row("↑  ↓", "换剧 / 换频道"), rowParams(1))
        guide.addView(row("OK", "天气和时间"), rowParams(2))
        middle.addView(
            guide,
            LinearLayout.LayoutParams(ui.widthFraction(0.52f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = ui.px(44)
            },
        )
        content.addView(
            middle,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        content.addView(spacer())

        bottom.orientation = LinearLayout.VERTICAL
        bottom.gravity = Gravity.CENTER_HORIZONTAL
        bottom.addView(status)
        bottom.addView(
            bar,
            LinearLayout.LayoutParams(ui.widthFraction(0.52f), ui.px(8)).apply { topMargin = ui.px(14) },
        )
        bottom.addView(
            note,
            LinearLayout.LayoutParams(ui.widthFraction(0.72f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.px(10)
            },
        )
        content.addView(
            bottom,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun row(keysText: String, labelText: String): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBackground(false)
            setPadding(ui.px(26), ui.px(16), ui.px(30), ui.px(16))
        }

        val accent = View(context)
        card.addView(accent, LinearLayout.LayoutParams(ui.px(4), ui.px(46)))

        val keys = text(32f, KEY_OFF).apply {
            text = keysText
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        card.addView(keys, LinearLayout.LayoutParams(ui.px(130), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ui.px(22)
        })

        val label = text(36f, Color.WHITE).apply { text = labelText }
        card.addView(label, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = ui.px(24) })

        rows.add(GuideRow(card, accent, keys, label))
        return card
    }

    private fun rowParams(index: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { if (index > 0) topMargin = ui.px(16) }

    private fun spacer() = View(context).also {
        it.layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
    }

    private fun cardBackground(active: Boolean) = GradientDrawable().apply {
        cornerRadius = ui.px(18f)
        setColor(if (active) CARD_ON else CARD_OFF)
        setStroke(
            if (active) ui.px(2) else ui.px(1),
            if (active) CARD_STROKE_ON else CARD_STROKE_OFF,
        )
    }

    private fun rounded(radiusDp: Float, color: Int) = GradientDrawable().apply {
        cornerRadius = ui.px(radiusDp)
        setColor(color)
    }

    private fun text(sp: Float, color: Int) = TextView(context).apply {
        ui.text(this, sp)
        setTextColor(color)
        includeFontPadding = false
    }

    private class GuideRow(
        val card: LinearLayout,
        val accent: View,
        val keys: TextView,
        val label: TextView,
    )

    /**
     * 左侧遥控器：只画方向键 + OK，按键位置按真实遥控器排。
     * 选中某一组时，对应的按键会亮金色并带呼吸光晕，右侧同一行也在高亮。
     */
    private class RemoteGuideView(context: Context) : View(context) {

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

        private val body = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val key = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val okText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
        private val ir = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2A3A50 }
        private val bottomPill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2A3A50 }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val left = w * 0.10f
            val top = h * 0.02f
            val right = w * 0.90f
            val bottom = h * 0.98f
            val radius = w * 0.18f
            val bodyRect = RectF(left, top, right, bottom)

            shadow.setShadowLayer(w * 0.05f, 0f, h * 0.012f, 0x55000000)
            canvas.drawRoundRect(bodyRect, radius, radius, shadow)
            shadow.clearShadowLayer()

            body.shader = LinearGradient(
                left, top, right, bottom,
                0xFF202C3E.toInt(), 0xFF0F1724.toInt(), Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(bodyRect, radius, radius, body)
            body.shader = null
            stroke.strokeWidth = w * 0.009f
            stroke.color = 0x3AFFFFFF
            canvas.drawRoundRect(bodyRect, radius, radius, stroke)

            // 顶部红外窗 + 底部一道细缝：够像遥控器即可，不堆装饰
            canvas.drawRoundRect(
                RectF(w * 0.36f, h * 0.055f, w * 0.64f, h * 0.070f),
                h * 0.008f, h * 0.008f, ir,
            )
            canvas.drawRoundRect(
                RectF(w * 0.40f, h * 0.885f, w * 0.60f, h * 0.897f),
                h * 0.006f, h * 0.006f, bottomPill,
            )

            val cx = w * 0.5f
            val cy = h * 0.50f
            val ring = w * 0.30f

            body.color = 0xFF1A2434.toInt()
            canvas.drawCircle(cx, cy, ring, body)
            stroke.strokeWidth = w * 0.012f
            stroke.color = 0xFF33445A.toInt()
            canvas.drawCircle(cx, cy, ring, stroke)

            val p = pulse.coerceIn(0f, 1f)
            glow.color = Color.argb((28 + 60 * p).toInt(), 0xFF, 0xD5, 0x4F)
            val halo = ring * 0.34f
            when (active) {
                0 -> {
                    canvas.drawCircle(cx - ring * 0.64f, cy, halo, glow)
                    canvas.drawCircle(cx + ring * 0.64f, cy, halo, glow)
                }
                1 -> {
                    canvas.drawCircle(cx, cy - ring * 0.64f, halo, glow)
                    canvas.drawCircle(cx, cy + ring * 0.64f, halo, glow)
                }
                else -> canvas.drawCircle(cx, cy, ring * 0.36f, glow)
            }

            val scale = 1f + 0.05f * p
            key.strokeWidth = w * 0.024f
            key.color = if (active == 0) ACCENT else KEY_OFF
            chevron(canvas, cx - ring * 0.64f, cy, -1f, 0f, w * 0.055f * scale)
            chevron(canvas, cx + ring * 0.64f, cy, 1f, 0f, w * 0.055f * scale)
            key.color = if (active == 1) ACCENT else KEY_OFF
            chevron(canvas, cx, cy - ring * 0.64f, 0f, -1f, w * 0.055f * scale)
            chevron(canvas, cx, cy + ring * 0.64f, 0f, 1f, w * 0.055f * scale)

            body.color = if (active == 2) 0xFF3A3010.toInt() else 0xFF202C3E.toInt()
            canvas.drawCircle(cx, cy, ring * 0.36f, body)
            stroke.strokeWidth = w * 0.012f
            stroke.color = if (active == 2) ACCENT else 0xFF3B4B60.toInt()
            canvas.drawCircle(cx, cy, ring * 0.36f, stroke)
            okText.color = if (active == 2) ACCENT else Color.WHITE
            okText.textSize = w * 0.100f
            canvas.drawText("OK", cx, cy + okText.textSize * 0.36f, okText)
        }

        private fun chevron(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, size: Float) {
            val path = Path()
            if (dx != 0f) {
                // 外侧是尖端，开口朝圆心：左键指向左，右键指向右
                path.moveTo(x - dx * size, y - size)
                path.lineTo(x + dx * size, y)
                path.lineTo(x - dx * size, y + size)
            } else {
                // 上键指向上，下键指向下
                path.moveTo(x - size, y - dy * size)
                path.lineTo(x, y + dy * size)
                path.lineTo(x + size, y - dy * size)
            }
            c.drawPath(path, key)
        }

        private companion object {
            const val ACCENT = 0xFFFFD54F.toInt()
            const val KEY_OFF = 0xFFC9D4E4.toInt()
        }
    }

    private companion object {
        const val SECONDARY = 0xB3FFFFFF.toInt()
        const val LABEL_OFF = 0xFFD6DEEA.toInt()
        const val KEY_OFF = 0xFFE2E8F2.toInt()
        const val ACCENT = 0xFFFFD54F.toInt()
        const val BG_TOP = 0xFF101827.toInt()
        const val BG_BOTTOM = 0xFF05070C.toInt()
        const val CARD_ON = 0x2EFFD54F
        const val CARD_OFF = 0x14FFFFFF
        const val CARD_STROKE_ON = 0x66FFD54F
        const val CARD_STROKE_OFF = 0x1FFFFFFF

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
