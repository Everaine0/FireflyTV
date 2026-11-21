package com.firefly.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 开机页：遥控器说明 + 等待 NAS 硬盘唤醒。
 *
 * 为什么要有这一页：NAS 的硬盘会休眠，第一次打开一集要等盘转起来，
 * 那十几秒里屏幕上原来只有「正在加载…」一条小提示。目标用户是老人，
 * 面对黑屏只会以为电视坏了。这一页把等待解释清楚，同时把「日常只用三组键」
 * 的操作说明在每次开机时重复一遍 —— 视频在背后照常起播，不额外拖慢。
 *
 * 版式：没有品牌标题，也没有遥控器插画。三张横向引导卡占住视觉中心，
 * 键帽本身就是「按哪个键」的说明；底部单独一行等待状态。
 *
 * 退场规则在 [BootGate]（纯函数 + 单测）：首帧出来且待够 5 秒就淡出；
 * 故障页要说话时立刻让位；最多待到 90 秒。
 *
 * 动画开销刻意压得很低：只有三张卡的选中态和进度条在变，约 20Hz。
 */
class BootScreen(context: Context) : FrameLayout(context) {

    private val ui = Ui(context)

    private val content = LinearLayout(context)
    private val heading = text(30f, SECONDARY).apply {
        text = "遥控器上日常只用三组键"
        gravity = Gravity.CENTER
        letterSpacing = 0.02f
    }
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

            val next = ((elapsed / SCENE_MS) % rows.size).toInt()
            if (next != scene) applyScene(next)

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

    /** 一行高亮：选中行的卡片描边、左侧金条和键帽一起点亮。 */
    private fun applyScene(index: Int) {
        scene = index
        rows.forEachIndexed { i, row ->
            val on = i == index
            row.card.background = cardBackground(on)
            row.accent.background = if (on) rounded(3f, ACCENT) else null
            row.label.setTextColor(if (on) Color.WHITE else LABEL_OFF)
            row.keys.forEach { cap ->
                cap.background = keyBackground(on)
                cap.setTextColor(if (on) ACCENT else KEY_TEXT_OFF)
            }
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

        // 上中下三段：标题与三张卡在上半屏，弹性的空白托住底部等待状态；
        // 这样在 1080p/4K/720p 都不会出现底部状态压住最后一行卡片。
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.setPadding(ui.px(54), ui.px(44), ui.px(54), ui.px(40))
        content.addView(heading)

        guide.orientation = LinearLayout.VERTICAL
        guide.gravity = Gravity.CENTER_HORIZONTAL
        guide.addView(row("换媒体库", "←", "→"), rowParams(0))
        guide.addView(row("换剧 / 换频道", "↑", "↓"), rowParams(1))
        guide.addView(row("天气和时间", "OK"), rowParams(2))
        content.addView(
            guide,
            LinearLayout.LayoutParams(ui.widthFraction(0.64f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.px(28)
            },
        )

        content.addView(
            View(context),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

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

    private fun row(labelText: String, vararg keyTexts: String): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBackground(false)
            setPadding(ui.px(24), ui.px(10), ui.px(28), ui.px(10))
        }

        val accent = View(context)
        card.addView(accent, LinearLayout.LayoutParams(ui.px(4), ui.px(44)))

        val keys = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        }
        card.addView(keys, LinearLayout.LayoutParams(ui.px(150), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ui.px(22)
        })

        val caps = ArrayList<TextView>(keyTexts.size)
        keyTexts.forEachIndexed { i, key ->
            val cap = text(if (key == "OK") 22f else 32f, KEY_TEXT_OFF).apply {
                text = key
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                background = keyBackground(false)
            }
            keys.addView(cap, LinearLayout.LayoutParams(ui.px(60), ui.px(60)).apply {
                if (i > 0) leftMargin = ui.px(12)
            })
            caps.add(cap)
        }

        val label = text(36f, Color.WHITE).apply {
            text = labelText
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(label, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = ui.px(24) })

        rows.add(GuideRow(card, accent, caps, label))
        return card
    }

    private fun rowParams(index: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { if (index > 0) topMargin = ui.px(14) }

    private fun cardBackground(active: Boolean) = GradientDrawable().apply {
        cornerRadius = ui.px(18f)
        setColor(if (active) CARD_ON else CARD_OFF)
        setStroke(
            if (active) ui.px(2) else ui.px(1),
            if (active) CARD_STROKE_ON else CARD_STROKE_OFF,
        )
    }

    private fun keyBackground(active: Boolean) = GradientDrawable().apply {
        cornerRadius = ui.px(14f)
        setColor(if (active) KEY_ON else KEY_OFF)
        setStroke(
            if (active) ui.px(2) else ui.px(1),
            if (active) ACCENT else KEY_STROKE_OFF,
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
        val keys: List<TextView>,
        val label: TextView,
    )

    private companion object {
        const val SECONDARY = 0xB3FFFFFF.toInt()
        const val LABEL_OFF = 0xFFD6DEEA.toInt()
        const val KEY_TEXT_OFF = 0xFFE2E8F2.toInt()
        const val ACCENT = 0xFFFFD54F.toInt()
        const val BG_TOP = 0xFF101827.toInt()
        const val BG_BOTTOM = 0xFF05070C.toInt()
        const val CARD_ON = 0x2EFFD54F
        const val CARD_OFF = 0x14FFFFFF
        const val CARD_STROKE_ON = 0x66FFD54F
        const val CARD_STROKE_OFF = 0x1FFFFFFF
        const val KEY_ON = 0x33FFD54F
        const val KEY_OFF = 0x14FFFFFF
        const val KEY_STROKE_OFF = 0x24FFFFFF

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
