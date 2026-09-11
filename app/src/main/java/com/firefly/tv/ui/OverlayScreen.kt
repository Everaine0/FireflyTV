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
 * OK 键浮层（DESIGN §3）：时间 + 日历 + 天气 + 当前剧集名。
 * 约 10 秒淡出，由 MainActivity 用 Handler 控制，销毁时必须 removeCallbacks。
 *
 * ## 布局：信息卡片，不是「一屏黑底 + 中间几行字」
 *
 * 用户实测反馈两条：一是「只有中心那一块有内容」，二是「可以美观一些」。
 * 前者是缩放问题（见 [UiScale]），后者是排版问题，这里一起改：
 *
 *  - 背景从「整屏 90% 纯黑」换成**上浅下深的渐变**：卡片浮在画面上，
 *    不再是「一屏黑里挖几个字」；
 *  - 内容装进一张**描边圆角卡片**，有明确边界，视觉上是一块「面板」；
 *  - 天气按「实况 = 大号温度 + 天气词」「预报 = 两行小字」分层，
 *    老人最关心的温度一眼就能看到；
 *  - 正在播放的剧名用暖色（[ACCENT]）单独一行，和天气区用分隔线隔开。
 *
 * 对比度：正文纯白 + 次要文字 #B3FFFFFF，底是 65%~90% 的黑，
 * 在电视亮度下足够（远高于 WCAG AA 的 4.5:1）。
 *
 * ## 每次显示都要重新对一遍缩放
 *
 * 电视在起播视频时可能切换显示模式（逻辑分辨率/density 变化），
 * 而 Activity 声明了 `configChanges="density"` **不会重建**，
 * TextView 也不会自己重算已缓存的字号像素值。
 * 所以 [show] 里先问一次 [Ui.refresh]，变了就整棵树重搭（内容随后的赋值会覆盖）。
 */
class OverlayScreen(context: Context) : FrameLayout(context) {

    /** 浮层要显示的全部内容。用具名参数传，避免 8 个位置参数传错顺序。 */
    class Content(
        val clock: String,
        val date: String,
        val condition: String,
        val temp: String,
        val today: String,
        val tomorrow: String,
        val current: String,
        val warning: String,
    )

    private val ui = Ui(context)

    private val clock = line(72f, Color.WHITE).apply {
        setTypeface(Typeface.DEFAULT_BOLD)
        letterSpacing = 0.04f
    }
    private val dateLine = line(30f, SECONDARY)
    private val condition = line(36f, Color.WHITE)
    private val temp = line(52f, Color.WHITE).apply { setTypeface(Typeface.DEFAULT_BOLD) }
    private val today = line(26f, SECONDARY)
    private val tomorrow = line(26f, SECONDARY)
    private val current = line(32f, ACCENT)
    private val warning = line(24f, WARN)

    private val card = LinearLayout(context)
    private val weatherRow = LinearLayout(context)

    init {
        isClickable = true
        build()
    }

    /** 按当前缩放搭一遍视图树。缩放变了就整棵重搭（见类注释）。 */
    private fun build() {
        removeAllViews()
        card.removeAllViews()
        weatherRow.removeAllViews()

        // ⚠️ 字号必须在这里重新设一遍。
        //
        // 这几个 TextView 是**字段**（只 new 一次），而 `setTextSize` 把 sp 换算成像素后
        // 缓存在 TextPaint 里 —— 重排时只把它们 addView 回新布局，字号仍然是**构造时**
        // 那一份。实测症状：电视切到 4K 显示模式后，卡片、内边距都按 ×2 重排了，
        // 唯独字还是 1080p 的大小 —— 看起来就是「字缩在卡片中间」。
        ui.text(clock, 72f)
        ui.text(dateLine, 30f)
        ui.text(condition, 36f)
        ui.text(temp, 52f)
        ui.text(today, 26f)
        ui.text(tomorrow, 26f)
        ui.text(current, 32f)
        ui.text(warning, 24f)

        // 渐变底：上方能看到画面，下方压暗保证文字对比度
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xA6000000.toInt(), 0xE6000000.toInt()),
        )

        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = GradientDrawable().apply {
            setColor(0x40000000)
            cornerRadius = ui.px(24f)
            setStroke(ui.px(1), 0x2EFFFFFF)
        }
        card.setPadding(ui.px(56), ui.px(34), ui.px(56), ui.px(34))

        card.addView(clock)
        card.addView(dateLine, ui.vertical(0, top = 12))
        card.addView(divider(), ui.vertical(0, top = 26))

        // 实况：天气词 + 大号温度
        weatherRow.orientation = LinearLayout.HORIZONTAL
        weatherRow.gravity = Gravity.CENTER
        weatherRow.addView(condition)
        weatherRow.addView(temp, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = ui.px(18) })
        card.addView(weatherRow, ui.vertical(0, top = 26))

        card.addView(today, ui.vertical(0, top = 14))
        card.addView(tomorrow, ui.vertical(0, top = 6))

        card.addView(divider(), ui.vertical(0, top = 26))
        card.addView(current, ui.vertical(0, top = 26))
        card.addView(warning, ui.vertical(0, top = 14))

        // 卡片给一个最小宽度，否则内容少的时候会缩成一条窄条，看起来像没排好版
        addView(card, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply { minimumWidth = ui.widthFraction(0.46f) })

        // 5% 安全边距：电视 overscan 会裁掉贴边内容（DESIGN §7）
        setPadding(ui.px(54), ui.px(30), ui.px(54), ui.px(30))
    }

    fun show(c: Content) {
        if (ui.refresh()) build()

        clock.text = c.clock
        dateLine.text = c.date
        condition.text = c.condition
        temp.text = c.temp
        today.text = c.today
        tomorrow.text = c.tomorrow
        current.text = c.current
        warning.text = c.warning

        bind(temp, c.temp.isNotBlank())
        bind(today, c.today.isNotBlank())
        bind(tomorrow, c.tomorrow.isNotBlank())
        bind(current, c.current.isNotBlank())
        bind(warning, c.warning.isNotBlank())
    }

    private fun bind(v: View, visible: Boolean) {
        v.visibility = if (visible) VISIBLE else GONE
    }

    private fun divider() = FrameLayout(context).apply {
        background = GradientDrawable().apply { setColor(0x33FFFFFF) }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(ui.px(420), ui.px(1))
    }

    private fun line(size: Float, color: Int) = TextView(context).apply {
        ui.text(this, size)
        setTextColor(color)
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    private companion object {
        const val SECONDARY = 0xB3FFFFFF.toInt()
        const val ACCENT = 0xFFFFD54F.toInt()
        const val WARN = 0xFFFFB74D.toInt()
    }
}
