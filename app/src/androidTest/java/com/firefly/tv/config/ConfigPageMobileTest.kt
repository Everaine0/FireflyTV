package com.firefly.tv.config

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * 配置页的**手机适配**与**等待反馈**。
 *
 * 用户的质问是「配置页面有做移动端适配吗」。这个页面几乎只用手机打开
 * （扫电视上的二维码），所以适配不是锦上添花而是主路径。这里把几条硬要求
 * 钉成断言，免得以后改样式时又把移动端弄坏 —— 那种问题在电脑浏览器上看不出来。
 */
@RunWith(AndroidJUnit4::class)
class ConfigPageMobileTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 起一个配置服务，抓一份真实的 HTML 出来。 */
    private fun fetchPage(): String {
        val server = ConfigServer(ctx) {}
        assertTrue("配置服务没起来", server.start())
        try {
            val url = URL("http://127.0.0.1:${server.port}/?t=${server.token}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            server.stop()
        }
    }

    private val html by lazy { fetchPage() }

    @Test
    fun `有手机视口声明_否则手机会按桌面宽度缩放`() {
        assertTrue(
            "缺 viewport，手机会按 980px 渲染再缩小，字会小到看不清",
            html.contains("""name="viewport"""") && html.contains("width=device-width"),
        )
    }

    @Test
    fun `输入框字号不小于16px_否则iOS聚焦时会自动放大页面`() {
        val m = Regex("""input\s*\{[^}]*font-size\s*:\s*([0-9.]+)px""").find(html)
        assertTrue("没找到 input 的 font-size 声明", m != null)
        val size = m!!.groupValues[1].toDouble()
        assertTrue("input 字号 $size px 小于 16，iOS 上聚焦会自动缩放页面", size >= 16.0)
    }

    @Test
    fun `窄屏有单独的内边距规则`() {
        assertTrue(
            "没有窄屏媒体查询，小屏手机上左右留白会吃掉输入框宽度",
            html.contains("@media (max-width: 420px)") || html.contains("@media(max-width:420px)"),
        )
    }

    @Test
    fun `底部留了安全区_避免按钮被手势条挡住`() {
        assertTrue(
            "没有 safe-area-inset-bottom，全面屏手机的保存按钮会被系统手势条压住",
            html.contains("safe-area-inset-bottom"),
        )
    }

    @Test
    fun `保存时有转圈和进度条`() {
        assertTrue("缺转圈元素 #spin", html.contains("""id="spin""""))
        assertTrue("缺进度条元素 #bar", html.contains("""id="bar""""))
        assertTrue("缺转圈动画", html.contains("@keyframes spin"))
        assertTrue("缺进度条动画", html.contains("@keyframes slide"))
    }

    @Test
    fun `保存请求发出后立刻进入等待态`() {
        // busy(true) 必须在 await 之前调用，否则等 NAS 那几秒里界面毫无变化
        val onclick = html.substringAfter("btnSave').onclick")
        val busyAt = onclick.indexOf("busy(true)")
        val awaitAt = onclick.indexOf("await call(")
        assertTrue("onclick 里没调用 busy(true)", busyAt >= 0)
        assertTrue("busy(true) 必须在等待响应之前调用，否则等待期间没有反馈", busyAt in 1..awaitAt)
    }

    @Test
    fun `移动端不会误触缩放`() {
        assertTrue(
            "缺 -webkit-tap-highlight-color，安卓上点按钮会有难看的蓝框",
            html.contains("-webkit-tap-highlight-color"),
        )
    }
}
