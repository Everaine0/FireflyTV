package com.firefly.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 切换过渡层。
 *
 * 两种模式，职责不同：
 *
 *  - LIBRARY：换库。整屏 92–95% 深色层横向滑入，中央“正在切换至 + 库名大字”，
 *    停留到新内容首帧/故障，最长 15 秒。它盖住的是 NAS 列目录/读片源那段空档，
 *    所以必须停住、必须有进度线；退场规则在 [FlashGate]（有单测）。
 *  - PAGE：换剧/换台。35–45% 深蓝灰半透明层 + 前缘 2dp 柔光线，从按键方向
 *    单向穿过，约 0.4 秒，不写字。它只是“翻了一页”的方向提示，不承担加载说明；
 *    内容名/加载状态仍由左下角 [HudView] 显示，所以不能挡住 HUD。
 *
 * 视频层是 SurfaceView，不参与任何位移/翻页；这里动的是覆盖在视频之上的普通 View。
 * 动画开销刻意压低：LIBRARY 模式 20Hz 只推进进度条，PAGE 模式只有一次 translationY。
 */
class SwitchFlash(context: Context) : FrameLayout(context) {

    enum class Mode { NONE, LIBRARY, PAGE }

    private val ui = Ui(context)

    private val content = LinearLayout(context)
    private val arrow = text(64f, ACCENT)
    private val lead = text(30f, SECONDARY)
    private val name = text(56f, Color.WHITE)
    private val subtitle = text(26f, SECONDARY)
    private val bar = PulseBar(context)
    private val edge = View(context)

    private val gate = FlashGate()
    private var mode = Mode.NONE
    private var direction = 1
    private var firstFrame = false
    private var dismissing = false

    init {
        visibility = GONE
        build()
    }

    /** 用户按键换库：显示整屏过渡页。 */
    fun showLibrary(dir: Int, nameText: String) {
        direction = if (dir < 0) -1 else 1
        mode = Mode.LIBRARY
        firstFrame = false
        dismissing = false
        gate.show(System.currentTimeMillis())
        name.text = nameText
        arrow.text = if (direction > 0) ARROW_RIGHT else ARROW_LEFT
        subtitle.text = SUBTITLE_OPENING
        if (ui.refresh()) build()
        applyMode()
        removeCallbacks(tick)
        animate().cancel()
        visibility = VISIBLE
        translationY = 0f
        alpha = 0f
        post {
            // stop()/故障让位可能发生在这一帧之前：别再把它拉回屏幕
            if (mode != Mode.LIBRARY || visibility != VISIBLE) return@post
            translationX = direction * width.toFloat()
            alpha = 1f
            animate().translationX(0f)
                .setDuration(ENTER_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        post(tick)
    }

    /** 用户又按了一次换库：重置 15 秒预算；方向变了就重新滑入。 */
    fun retarget(dir: Int, nameText: String) {
        if (mode != Mode.LIBRARY || !isShowing()) {
            showLibrary(dir, nameText)
            return
        }
        val next = if (dir < 0) -1 else 1
        gate.retarget(System.currentTimeMillis())
        firstFrame = false
        name.text = nameText
        subtitle.text = SUBTITLE_OPENING
        if (next != direction) {
            direction = next
            arrow.text = if (direction > 0) ARROW_RIGHT else ARROW_LEFT
            animate().cancel()
            translationX = direction * width.toFloat()
            animate().translationX(0f)
                .setDuration(ENTER_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** 内部自动跳过空库：只换文字，不重置 15 秒。 */
    fun updateText(nameText: String) {
        if (mode == Mode.LIBRARY) name.text = nameText
    }

    fun setSubtitle(value: String) {
        if (mode == Mode.LIBRARY) subtitle.text = value
    }

    /** 新内容首帧已经上屏；达到最短展示时间就退场。 */
    fun notifyFirstFrame() {
        if (mode != Mode.LIBRARY || !isShowing()) return
        firstFrame = true
        val reason = gate.exitReason(System.currentTimeMillis(), firstFrame, false)
        if (reason != null) dismiss()
    }

    /** 故障页要说话：立刻让位（故障页本身在过渡页之上）。 */
    fun notifyFault() {
        stop()
    }

    /** 上下换剧/换台：半透明单向翻页，不写字。 */
    fun showPage(dir: Int) {
        if (mode == Mode.LIBRARY && isShowing()) return
        direction = if (dir < 0) -1 else 1
        mode = Mode.PAGE
        firstFrame = false
        dismissing = false
        if (ui.refresh()) build()
        applyMode()
        removeCallbacks(tick)
        animate().cancel()
        visibility = VISIBLE
        translationX = 0f
        alpha = 0f
        post {
            // 同 LIBRARY：被 stop() 取消后不要再启动翻页动画
            if (mode != Mode.PAGE || visibility != VISIBLE) return@post
            translationY = direction * height.toFloat()
            alpha = 1f
            animate().translationY(-direction * height.toFloat())
                .setDuration(PAGE_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { finishPage() }
                .start()
        }
    }

    fun isShowing(): Boolean = visibility == VISIBLE && mode != Mode.NONE

    fun isLibraryShowing(): Boolean = isShowing() && mode == Mode.LIBRARY

    /** 销毁/配置页接管时立即收起：停定时器、取消动画、不触发任何回调。 */
    fun stop() {
        removeCallbacks(tick)
        animate().cancel()
        visibility = GONE
        translationX = 0f
        translationY = 0f
        alpha = 1f
        mode = Mode.NONE
        firstFrame = false
        dismissing = false
    }

    private val tick = object : Runnable {
        override fun run() {
            if (mode != Mode.LIBRARY || visibility != VISIBLE || dismissing) return
            val now = System.currentTimeMillis()
            bar.phase = ((now - gate.shownAt) % PULSE_MS).toFloat() / PULSE_MS
            val reason = gate.exitReason(now, firstFrame, false)
            if (reason != null) dismiss() else postDelayed(this, TICK_MS)
        }
    }

    private fun dismiss() {
        if (mode != Mode.LIBRARY || dismissing) return
        dismissing = true
        removeCallbacks(tick)
        animate().cancel()
        animate().translationX(-direction * width.toFloat())
            .setDuration(EXIT_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { finishLibrary() }
            .start()
    }

    private fun finishLibrary() {
        if (mode != Mode.LIBRARY) return
        visibility = GONE
        translationX = 0f
        alpha = 1f
        mode = Mode.NONE
        dismissing = false
        firstFrame = false
    }

    private fun finishPage() {
        if (mode != Mode.PAGE) return
        visibility = GONE
        translationY = 0f
        alpha = 1f
        mode = Mode.NONE
    }

    private fun applyMode() {
        when (mode) {
            Mode.LIBRARY -> {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(LIB_TOP, LIB_BOTTOM),
                )
                content.visibility = VISIBLE
                edge.visibility = GONE
            }
            Mode.PAGE -> {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(PAGE_TOP, PAGE_BOTTOM),
                )
                content.visibility = GONE
                edge.visibility = VISIBLE
                val lp = edge.layoutParams as LayoutParams
                lp.gravity = if (direction > 0) Gravity.TOP else Gravity.BOTTOM
                edge.layoutParams = lp
                edge.background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0x00FFD54F, 0xCCFFD54F.toInt(), 0x00FFD54F),
                )
            }
            Mode.NONE -> {
                background = null
                content.visibility = GONE
                edge.visibility = GONE
            }
        }
    }

    private fun build() {
        removeAllViews()
        content.removeAllViews()

        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL

        ui.text(arrow, 64f)
        ui.text(lead, 30f)
        ui.text(name, 56f)
        ui.text(subtitle, 26f)

        arrow.setTextColor(ACCENT)
        arrow.setTypeface(Typeface.DEFAULT_BOLD)
        arrow.text = if (direction > 0) ARROW_RIGHT else ARROW_LEFT
        lead.setTextColor(SECONDARY)
        lead.text = LEAD
        name.setTextColor(Color.WHITE)
        name.setTypeface(Typeface.DEFAULT_BOLD)
        name.maxLines = 2
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        name.maxWidth = ui.widthFraction(0.72f)
        subtitle.setTextColor(SECONDARY)
        bar.phase = 0f

        content.addView(arrow)
        content.addView(lead, wrap(top = 10))
        content.addView(name, wrap(top = 8))
        content.addView(subtitle, wrap(top = 16))
        content.addView(
            bar,
            LinearLayout.LayoutParams(ui.widthFraction(0.52f), ui.px(8)).apply { topMargin = ui.px(18) },
        )

        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        edge.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00FFD54F, 0xCCFFD54F.toInt(), 0x00FFD54F),
        )
        addView(edge, LayoutParams(LayoutParams.MATCH_PARENT, ui.px(2), Gravity.TOP))

        // 5% 安全边距：电视 overscan 会裁掉贴边内容
        setPadding(ui.px(54), ui.px(30), ui.px(54), ui.px(30))
    }

    private fun text(sp: Float, color: Int) = TextView(context).apply {
        ui.text(this, sp)
        setTextColor(color)
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = ui.px(top) }

    private companion object {
        const val ENTER_MS = 280L
        const val EXIT_MS = 220L
        const val PAGE_MS = 400L
        const val TICK_MS = 50L
        const val PULSE_MS = 1_800L

        const val ARROW_LEFT = "←"
        const val ARROW_RIGHT = "→"
        const val LEAD = "正在切换至"
        const val SUBTITLE_OPENING = "正在打开…"

        const val SECONDARY = 0xB3FFFFFF.toInt()
        const val ACCENT = 0xFFFFD54F.toInt()

        /** 换库过渡层：接近不透明，盖住加载空档。 */
        const val LIB_TOP = 0xF2101827.toInt()
        const val LIB_BOTTOM = 0xF205070C.toInt()

        /** 上下翻页层：35–45% 深蓝灰，旧画面仍可见。 */
        const val PAGE_TOP = 0x5A101827
        const val PAGE_BOTTOM = 0x73101827
    }
}
