package com.firefly.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 一次按键的即时反馈条（[SwitchHud] 的显示层）。
 *
 * 故意做得比 OK 浮层轻：半透明小条贴在下方，不遮画面主体 ——
 * 老人要的是「知道键生效了」，不是每次按键都被一整屏盖住。
 */
class HudView(context: Context) : FrameLayout(context) {

    private val title = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        setTextColor(Color.WHITE)
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    private val subtitle = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(Color.parseColor("#B3FFFFFF"))
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    init {
        isClickable = false
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(34), dp(16), dp(34), dp(16))
        }
        col.addView(title)
        col.addView(subtitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        addView(col, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** 左对齐贴在画面下方 —— 居中的话会正好盖住字幕。 */
    fun render(state: SwitchHud.State) {
        title.text = state.title
        subtitle.text = state.subtitle
        subtitle.visibility = if (state.subtitle.isBlank()) GONE else VISIBLE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
