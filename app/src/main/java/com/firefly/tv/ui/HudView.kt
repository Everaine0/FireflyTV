package com.firefly.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 一次按键的即时反馈条（[SwitchHud] 的显示层）。
 *
 * 故意做得比 OK 浮层轻：半透明小条贴在**左下角**，不遮画面主体、也不压字幕 ——
 * 老人要的是「知道键生效了」，不是每次按键都被一整屏盖住。
 *
 * 尺寸走 [Ui]（屏幕相对），并且**每次显示都重新对一遍缩放**：
 * 电视切显示模式（1080p ↔ 4K）后 Activity 不会重建，
 * 而 `setTextSize` 换算出来的像素值缓存在 TextPaint 里，不重设就一直是旧字号。
 */
class HudView(context: Context) : FrameLayout(context) {

    private val ui = Ui(context)

    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        includeFontPadding = false
        gravity = Gravity.START
    }

    private val subtitle = TextView(context).apply {
        setTextColor(SECONDARY)
        includeFontPadding = false
        gravity = Gravity.START
    }

    private val row = LinearLayout(context)

    init {
        isClickable = false
        build()
    }

    private fun build() {
        removeAllViews()
        row.removeAllViews()

        ui.text(title, 36f)
        ui.text(subtitle, 24f)

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        col.addView(title)
        col.addView(subtitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = ui.px(6) })

        // 左侧一道暖色竖条：既是装饰，也让「这是刚按出来的提示」一眼可辨
        val accent = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = ui.px(3f)
            }
        }

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = GradientDrawable().apply {
            setColor(0xCC000000.toInt())
            cornerRadius = ui.px(14f)
        }
        row.setPadding(ui.px(26), ui.px(18), ui.px(34), ui.px(18))
        row.addView(accent, LinearLayout.LayoutParams(ui.px(6), ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(col, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = ui.px(22) })

        // 左下角 + 5% 安全边距：和 OK 浮层（居中）互不干扰
        addView(row, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        ).apply {
            leftMargin = ui.px(54)
            bottomMargin = ui.px(48)
        })
    }

    fun render(state: SwitchHud.State) {
        if (ui.refresh()) build()
        title.text = state.title
        subtitle.text = state.subtitle
        subtitle.visibility = if (state.subtitle.isBlank()) GONE else VISIBLE
    }

    private companion object {
        const val SECONDARY = 0xB3FFFFFF.toInt()
        const val ACCENT = 0xFFFFD54F.toInt()
    }
}
