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
 * 故障页（DESIGN §8）：全屏大字纯中文，不出现技术术语。
 * 重试由 MainActivity 的 Handler 每 10 秒触发一次；**销毁时必须清掉回调**，
 * 否则就是文档点名的最典型泄漏点。
 *
 * 尺寸走 [Ui]：4K 电视上按 dp 写会缩成一小块，老人根本看不清「出了什么事」。
 */
class FaultScreen(context: Context) : FrameLayout(context) {

    private val ui = Ui(context)

    private val message = TextView(context).apply {
        ui.text(this, 34f)
        setTextColor(Color.WHITE)
        setTypeface(Typeface.DEFAULT_BOLD)
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    init {
        setBackgroundColor(Color.BLACK)
        build()
    }

    /** 每次显示都对一遍缩放（电视可能中途切了显示模式，见 [Ui]）。 */
    private fun build() {
        removeAllViews()
        ui.text(message, 34f)

        val bar = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(0xFFFFB74D.toInt())
                cornerRadius = ui.px(3f)
            }
        }

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(bar, LinearLayout.LayoutParams(ui.px(120), ui.px(6)))
        col.addView(message, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = ui.px(30) })

        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setPadding(ui.px(54), ui.px(30), ui.px(54), ui.px(30))
    }

    fun show(text: String) {
        if (ui.refresh()) build()
        message.text = text
    }

    /**
     * 屏幕上正在说的那句话（没显示时是空串）。
     *
     * 给远程诊断口用：电视上写着「《XX》里没有能播放的视频」时，我在这边要能看见同一句话，
     * 而不是靠猜 —— 这类「它自己跳走了」的问题，故障页的原文就是唯一证据。
     */
    fun current(): String =
        if (visibility == View.VISIBLE) message.text?.toString().orEmpty() else ""
}
