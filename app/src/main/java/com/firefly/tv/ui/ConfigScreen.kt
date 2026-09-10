package com.firefly.tv.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.firefly.tv.config.Qr

/**
 * 首次开机、尚未配置时显示（已与用户确认）：二维码大字 + 地址小字，不提供手动刷新。
 * 配置保存成功后由 MainActivity 直接切到播放。
 */
class ConfigScreen(context: Context) : FrameLayout(context) {

    private val qrView = ImageView(context)
    private val urlView = text(24f, Color.parseColor("#B3FFFFFF"))
    private val titleView = text(32f, Color.WHITE)

    init {
        setBackgroundColor(Color.BLACK)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        titleView.text = "用手机扫下面的二维码，填一次就能用了"
        titleView.gravity = Gravity.CENTER
        col.addView(titleView, lp(top = 0))

        qrView.setPadding(0, dp(24), 0, dp(24))
        col.addView(qrView, LayoutParams(dp(420), dp(420)).apply { topMargin = dp(24) })

        urlView.gravity = Gravity.CENTER
        col.addView(urlView, lp(top = 16))

        addView(col, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // 5% 安全边距：电视 overscan 会裁掉贴边内容（DESIGN §7）
        setPadding(dp(54), dp(30), dp(54), dp(30))
    }

    /** [url] 为 null 表示电视没连上局域网，此时二维码无意义。 */
    fun show(url: String?) {
        if (url == null) {
            qrView.visibility = GONE
            urlView.text = "电视还没有连上网络\n请先让电视连上家里的无线网"
            return
        }
        qrView.visibility = VISIBLE
        qrView.setImageBitmap(Qr.bitmap(url, dp(420)))
        urlView.text = url
    }

    /** 二维码可能还没生成完就被销毁，这里主动释放。 */
    fun recycle() {
        qrView.setImageBitmap(null)
    }

    private fun text(sizeSp: Float, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        includeFontPadding = false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }
}
