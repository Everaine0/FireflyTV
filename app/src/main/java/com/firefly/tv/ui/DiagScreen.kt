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
 * 播放诊断页：**给「电视上帧率不高」这类问题用的现场证据**。
 *
 * ## 为什么必须有这一页
 *
 * 用户实机反馈「明显感觉帧率不高」，而电视上**没有 adb**（用户确认过：只能看画面）。
 * 那就只能在屏幕上把话说清楚 —— 这一页要回答的是四个只能用数字区分的问题：
 *
 * | 现象 | 结论 |
 * | :--- | :--- |
 * | 解码 ≈ 片源帧率、丢帧 ≈ 0 | 片源本来就是 25/24 帧，没问题 |
 * | 解码 ≈ 片源帧率、丢帧 > 0 | 解码跟得上，是**渲染/合成**来不及 |
 * | 解码 < 片源帧率 | **解码**本身不够快 → 看「解码通路」是不是硬解 |
 * | 缓存长期接近 0、读取速率很低 | 是 **NAS 读取**供不上，不是解码 |
 *
 * 另外一行是 [UiScale] 的自述：4K 电视上「界面缩在中间」的根因就在那里，
 * 这一行能直接看出这台电视的逻辑分辨率和 density 是否自洽。
 *
 * 打开方式见 `MainActivity.dispatchKeyEvent`：遥控器「信息/菜单」键，
 * 或者**长按 OK**（任何遥控器都能用）。
 */
class DiagScreen(context: Context) : FrameLayout(context) {

    /** 一行「标签 + 值」。 */
    class Line(val label: String, val value: String, val warn: Boolean = false)

    private val ui = Ui(context)

    private val title = TextView(context)
    private val hint = TextView(context)
    private val card = LinearLayout(context)
    private val rows = ArrayList<Row>(MAX_ROWS)

    private class Row(val label: TextView, val value: TextView, val root: LinearLayout)

    init {
        isClickable = true
        build()
    }

    /** 按当前缩放搭一遍。缩放变了（电视切显示模式）就整棵重搭。 */
    private fun build() {
        removeAllViews()
        card.removeAllViews()
        rows.clear()

        setBackgroundColor(0xE6000000.toInt())

        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply {
            setColor(0x59000000)
            cornerRadius = ui.px(24f)
            setStroke(ui.px(1), 0x33FFFFFF)
        }
        card.setPadding(ui.px(56), ui.px(24), ui.px(56), ui.px(24))

        title.apply {
            ui.text(this, 30f)
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            includeFontPadding = false
        }
        card.addView(title)

        val divider = FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(0x33FFFFFF) }
        }
        card.addView(divider, LinearLayout.LayoutParams(ui.px(760), ui.px(1)).apply {
            topMargin = ui.px(12)
            bottomMargin = ui.px(12)
        })

        for (i in 0 until MAX_ROWS) {
            val label = TextView(context).apply {
                ui.text(this, 18f)
                setTextColor(SECONDARY)
                includeFontPadding = false
                gravity = Gravity.END
            }
            val value = TextView(context).apply {
                ui.text(this, 20f)
                setTextColor(Color.WHITE)
                includeFontPadding = false
                gravity = Gravity.START
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(label, LinearLayout.LayoutParams(ui.px(140), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(value, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = ui.px(22) })
            row.visibility = View.GONE
            card.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ui.px(4) })
            rows += Row(label, value, row)
        }

        hint.apply {
            ui.text(this, 18f)
            setTextColor(HINT)
            includeFontPadding = false
            gravity = Gravity.CENTER_HORIZONTAL
        }
        card.addView(hint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = ui.px(14) })

        addView(card, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply { minimumWidth = ui.widthFraction(0.52f) })

        // 5% 安全边距：电视 overscan 会裁掉贴边内容（DESIGN §7）。
        // 12 行内容刚好放得下（1080p 实测 ≈ 973px / 1080px）。
        setPadding(ui.px(54), ui.px(30), ui.px(54), ui.px(30))
    }

    fun show(titleText: String, lines: List<Line>, hintText: String) {
        if (ui.refresh()) build()
        title.text = titleText
        hint.text = hintText
        for ((i, row) in rows.withIndex()) {
            val line = lines.getOrNull(i)
            if (line == null) {
                row.root.visibility = View.GONE
                continue
            }
            row.root.visibility = View.VISIBLE
            row.label.text = line.label
            row.value.text = line.value
            row.value.setTextColor(if (line.warn) WARN else Color.WHITE)
        }
    }

    private companion object {
        /** 一屏放得下的行数（1080p 实测：12 行 + 标题刚好不溢出）。 */
        const val MAX_ROWS = 12
        const val SECONDARY = 0xB3FFFFFF.toInt()
        const val HINT = 0x80FFFFFF.toInt()
        const val WARN = 0xFFFFB74D.toInt()
    }
}
