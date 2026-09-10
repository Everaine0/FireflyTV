package com.firefly.tv.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 故障页（DESIGN §8）：全屏大字纯中文，不出现技术术语。
 * 重试由 MainActivity 的 Handler 每 10 秒触发一次；**销毁时必须清掉回调**，
 * 否则就是文档点名的最典型泄漏点。
 */
class FaultScreen(context: Context) : FrameLayout(context) {

    private val message = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
        setTextColor(Color.WHITE)
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setPadding(dp(54), dp(30), dp(54), dp(30))
    }

    fun show(text: String) {
        message.text = text
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
