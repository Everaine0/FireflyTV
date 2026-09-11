package com.firefly.tv.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.firefly.tv.config.Qr

/**
 * 首次开机、尚未配置时显示（已与用户确认）：二维码大字 + 地址小字，不提供手动刷新。
 * 配置保存成功后由 MainActivity 直接切到播放。
 *
 * 布局按**屏幕比例**给尺寸，不用写死的 dp 高度堆叠。
 * 起因：实测在 1080p 电视上，写死的 420dp 二维码 + 两行文字把整列撑到超出屏幕，
 * 最下面那行地址被切掉了一半 —— 而那行地址是唯一能让人手动输入入口，
 * 被切掉等于彻底没法配置。
 */
class ConfigScreen(context: Context) : FrameLayout(context) {

    private val ui = Ui(context)

    private val qrView = ImageView(context)
    private val titleView = text(30f, Color.WHITE)
    private val urlView = text(22f, Color.parseColor("#B3FFFFFF"))
    private val hintView = text(18f, Color.parseColor("#80FFFFFF"))

    init {
        setBackgroundColor(Color.BLACK)

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        titleView.text = "用手机扫下面的二维码，填一次就能用了"
        titleView.gravity = Gravity.CENTER
        col.addView(titleView, wrap())

        // 二维码按屏幕较短边的 34% 给，不写死 dp
        qrView.adjustViewBounds = true
        col.addView(qrView, qrParams())

        urlView.gravity = Gravity.CENTER
        urlView.maxLines = 2
        col.addView(urlView, wrap(top = 14))

        hintView.gravity = Gravity.CENTER
        hintView.text = "也可以在上面这个地址里手动填"
        col.addView(hintView, wrap(top = 6))

        addView(col, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // 5% 安全边距：电视 overscan 会裁掉贴边内容（DESIGN §7）
        setPadding(dp(54), dp(30), dp(54), dp(30))
    }

    /** 二维码边长：屏幕短边的 34%，并把上下留白算进高度预算里。 */
    private fun qrSize(): Int {
        val m = resources.displayMetrics
        val shorter = minOf(m.widthPixels, m.heightPixels)
        // 再留 12% 给标题/地址/提示，保证整列一定放得下
        return (shorter * 0.34).toInt().coerceAtMost((m.heightPixels * 0.5).toInt())
    }

    /** [url] 为 null 表示电视没连上局域网，此时二维码无意义。 */
    fun show(url: String?) {
        if (url == null) {
            qrView.visibility = GONE
            hintView.visibility = GONE
            urlView.text = "电视还没有连上网络\n请先让电视连上家里的无线网"
            titleView.text = "电视还没连上网"
            return
        }
        qrView.visibility = VISIBLE
        hintView.visibility = VISIBLE
        val side = qrSize()
        qrView.setImageBitmap(Qr.bitmap(url, side))
        urlView.text = url
    }

    /** 二维码可能还没生成完就被销毁，这里主动释放。 */
    fun recycle() {
        qrView.setImageBitmap(null)
    }

    /**
     * 保存成功后、真正出画面之前的过渡态。
     *
     * 保存成功到第一帧之间要连 NAS、列目录、起播，实测能有好几秒。
     * 这段时间如果还停在二维码页，用户会以为保存失败了，然后反复点保存。
     */
    fun showStarting() {
        qrView.visibility = GONE
        hintView.visibility = GONE
        titleView.text = "配置已保存"
        urlView.text = "电视正在打开，请稍等…"
    }

    private fun text(sizeSp: Float, color: Int) = TextView(context).apply {
        ui.text(this, sizeSp)
        setTextColor(color)
        includeFontPadding = false
    }

    private fun dp(v: Int): Int = ui.px(v)

    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun qrParams() = LinearLayout.LayoutParams(qrSize(), qrSize()).apply {
        topMargin = dp(20)
        bottomMargin = dp(4)
    }
}
