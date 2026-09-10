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
 * OK 键浮层（DESIGN §3）：天气 + 时间 + 日历 + 当前剧集名。
 * 约 10 秒淡出，由 MainActivity 用 Handler 控制，销毁时必须 removeCallbacks。
 */
class OverlayScreen(context: Context) : FrameLayout(context) {

    private val clock = huge()
    private val dateLine = major()
    private val weather = major()
    private val tomorrow = body()
    private val current = body()

    init {
        setBackgroundColor(Color.parseColor("#E6000000"))
        isClickable = true

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        clock.gravity = Gravity.CENTER
        col.addView(clock)

        dateLine.gravity = Gravity.CENTER
        col.addView(dateLine, marginTop(10))

        col.addView(divider(), marginTop(22))

        weather.gravity = Gravity.CENTER
        col.addView(weather, marginTop(22))

        tomorrow.gravity = Gravity.CENTER
        col.addView(tomorrow, marginTop(8))

        current.gravity = Gravity.CENTER
        col.addView(current, marginTop(26))

        addView(col, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // 5% 安全边距
        setPadding(dp(54), dp(30), dp(54), dp(30))
    }

    fun show(
        timeText: String,
        dateText: String,
        weatherText: String,
        tomorrowText: String,
        currentText: String,
    ) {
        clock.text = timeText
        dateLine.text = dateText
        weather.text = weatherText
        tomorrow.text = tomorrowText
        current.text = currentText
        tomorrow.visibility = if (tomorrowText.isBlank()) GONE else VISIBLE
        current.visibility = if (currentText.isBlank()) GONE else VISIBLE
    }

    private fun divider() = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#4DFFFFFF"))
        }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(dp(240), dp(1))
    }

    private fun huge() = textView(64f, Color.WHITE)
    private fun major() = textView(32f, Color.WHITE)
    private fun body() = textView(24f, Color.parseColor("#B3FFFFFF"))

    private fun textView(sizeSp: Float, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(v) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
