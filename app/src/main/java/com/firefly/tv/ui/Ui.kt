package com.firefly.tv.ui

import android.content.Context
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 界面缩放：**按「逻辑分辨率 / 系统声明的 density」校正**，不直接信 density。
 *
 * ## 为什么不能直接用 dp / sp（用户实测的真实故障）
 *
 * 用户原话：「电视上按下 OK 大概就中心那一块有内容，应该是没有缩放」。
 *
 * dp/sp 只有在**系统 density 和逻辑分辨率自洽**时才等于「占屏比例」。
 * 而这台电视不自洽：Android 5.1 上**没有任何框架机制**保证界面按 1080p 渲染
 * （`config_maxUiWidth` 是 Android 8.0 才有的东西，电视端要 Android 11 的 overlay
 * 才会把 UI 卡在 1920），所以 4K 面板完全可能是
 * **逻辑分辨率 3840×2160 + densityDpi 仍是 320（xhdpi）**。
 * 这时 64sp 只画出 128px，在 2160 高的屏上占 **5.9%**；
 * 同一行字在 1080p 上是 **11.9%** —— 看起来就是「内容缩在中间一小块」。
 *
 * ## 判据：`短边(按 16:9 折算) / (6 × densityDpi)`
 *
 * `6` 是「1080p 电视的标准 densityDpi 320」对应的比例（1920 / 320 = 6），
 * 所以分母 `6 × densityDpi` 就是**系统认为的短边像素数**。比值就是
 * 「实际逻辑像素 / 系统以为的逻辑像素」：
 *
 * | 逻辑分辨率 | densityDpi | 缩放 | 说明 |
 * | :--- | :--- | :--- | :--- |
 * | 1920×1080 | 320 | 1.00 | 正常 1080p 电视：不动 |
 * | 1280×720 | 213 | 1.00 | 正常 720p 电视：不动 |
 * | 3840×2160 | 640 | 1.00 | 正常 4K 界面：**不能**再放大，否则会大两倍 |
 * | **3840×2160** | **320** | **2.00** | 这台电视的可疑组合：放大 2 倍才回到正确比例 |
 *
 * 这一条同时修好了「密度撒谎」和「真的 4K 界面」两种情况，
 * 而单纯用 `短边/1080` 会在 4K@640 上放大两倍（那是错的）。
 *
 * 为什么取 `min(宽, 高×16/9)`：横竖屏、非 16:9 面板都不会算歪。
 *
 * ## 应用方式
 *
 *  - 字号：`setTextSize(SP, 设计值 × 缩放)` —— 走 sp，
 *    这样电视系统里的「字体大小」（Android TV 12+ 有这个设置）仍然生效；
 *  - 间距/尺寸：`dp × density × 缩放`。
 *
 * ## 运行时可能变化，必须重算
 *
 * Activity 声明了 `configChanges="density|screenSize|..."`，**不会重建**；
 * 而 `TextView` 自己不会在配置变化时重新解释 sp（像素值已缓存在 TextPaint 里）。
 * 更要紧的是：这台电视在起播视频时可能切换显示模式/density。
 * 所以浮层每次显示都要用**当时的** metrics 重新算一遍（见 [OverlayScreen.syncScale]）。
 */
object UiScale {

    /** 1080p 电视的标准 densityDpi。 */
    const val REF_DPI = 320

    /** `1920 / 320`：把 densityDpi 折算成系统以为的短边像素数。 */
    const val PX_PER_DPI = 6f

    /** 纯函数，便于单元测试。 */
    fun of(widthPx: Int, heightPx: Int, densityDpi: Int): Float {
        if (widthPx <= 0 || heightPx <= 0 || densityDpi <= 0) return 1f
        val shortEdge = min(widthPx.toFloat(), heightPx * 16f / 9f)
        val nominal = PX_PER_DPI * densityDpi
        return (shortEdge / nominal).coerceIn(0.25f, 8f)
    }
}

/**
 * 一个界面元素拿得到的缩放工具。
 *
 * 用法上刻意保留改造前的 sp 数值（[text] 收的还是原来那个 64f），
 * 免得改造过程中把字号抄错 —— 在自洽的 1080p 电视上结果和改造前**逐像素一致**。
 */
class Ui(private val context: Context) {

    private val TAG = "FireflyUI"

    var metrics: DisplayMetrics = context.resources.displayMetrics
        private set

    var screenWidth: Int = metrics.widthPixels
        private set

    var screenHeight: Int = metrics.heightPixels
        private set

    var scale: Float = compute(metrics)
        private set

    /** 一行排错自述：逻辑分辨率 + dpi + density + 缩放倍数。 */
    fun metricsLine(): String {
        val m = metrics
        return "${m.widthPixels}×${m.heightPixels} · dpi ${m.densityDpi} · " +
            "density ${fmt(m.density)} · 缩放 ×${fmt(scale)}"
    }

    /** 固定用 Locale.US：某些区域的小数点是逗号，会把「×2.00」写成「×2,00」。 */
    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

    /** 重新采样（配置可能变了）。返回是否发生了变化。 */
    fun refresh(): Boolean {
        val m = context.resources.displayMetrics
        val s = compute(m)
        val changed = s != scale || m.widthPixels != screenWidth || m.heightPixels != screenHeight
        metrics = m
        screenWidth = m.widthPixels
        screenHeight = m.heightPixels
        scale = s
        if (changed) android.util.Log.i(TAG, "屏幕参数变了，界面重排：${describe()}")
        return changed
    }

    private fun compute(m: DisplayMetrics): Float {
        val dpi = if (m.densityDpi > 0) m.densityDpi else (m.density * 160f).roundToInt()
        return UiScale.of(m.widthPixels, m.heightPixels, dpi)
    }

    /** 设计值（dp）→ 本机像素。 */
    fun px(design: Float): Float = design * metrics.density * scale

    /** 设计值（dp）→ 本机像素（取整）。 */
    fun px(design: Int): Int = (design * metrics.density * scale).roundToInt()

    /** 按设计值设置字号（走 sp，保留系统字体大小设置）。 */
    fun text(view: TextView, design: Float) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, design * scale)
    }

    /** 竖排布局里的一项。[width]/[height] 为设计值，<= 0 表示 WRAP_CONTENT。 */
    fun vertical(width: Int, height: Int = 0, top: Int = 0) =
        LinearLayout.LayoutParams(
            if (width > 0) px(width) else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (height > 0) px(height) else ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = px(top) }

    /** 内容宽度：屏幕宽度的 [fraction]，用来给信息卡片一个「有分量」的宽度。 */
    fun widthFraction(fraction: Float): Int = (screenWidth * fraction).toInt()

    /** 给排错用的一行自述（会进日志和诊断面板）。 */
    fun describe(): String = metricsLine().replace(" · ", " ")
}
