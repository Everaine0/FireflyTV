package com.firefly.tv.config

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 配置页的 HTTP 服务是手写 ServerSocket + 手写请求解析，最容易在边界上出错，
 * 所以要有真实 HTTP 往返的测试，而不是只靠肉眼看手机能不能打开。
 */
@RunWith(AndroidJUnit4::class)
class ConfigServerTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Response(val code: Int, val body: String)

    private fun request(
        method: String,
        url: String,
        form: Map<String, String>? = null,
    ): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 10000
        }
        if (form != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { w ->
                w.write(form.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" })
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        conn.disconnect()
        return Response(code, body)
    }

    @Test
    fun 带对token能拿到配置页_不带token被拒() {
        val server = ConfigServer(context) {}
        assertTrue("服务没起来", server.start())
        try {
            val base = "http://127.0.0.1:${server.port}"

            // 没有 token：必须拒绝，否则同网段任何人都能看到 NAS 密码
            val denied = request("GET", "$base/")
            assertEquals(403, denied.code)

            // token 错误：同样拒绝
            val wrong = request("GET", "$base/?t=deadbeef")
            assertEquals(403, wrong.code)

            // token 正确：拿到配置页，且页面里要有保存按钮和必填项
            val ok = request("GET", "$base/?t=${server.token}")
            assertEquals(200, ok.code)
            assertTrue("配置页缺少标题", ok.body.contains("萤火照夜"))
            assertTrue("配置页缺少保存按钮", ok.body.contains("保存并开始使用"))
            assertTrue("配置页缺少 NAS 地址输入", ok.body.contains("id=\"host\""))
            // 心知只要私钥 + 地点两项（和风那栏专属 Host 已经删掉了）
            assertTrue("配置页缺少天气私钥输入", ok.body.contains("id=\"wkey\""))
            assertTrue("配置页缺少天气地点输入", ok.body.contains("id=\"wloc\""))
            assertTrue("配置页不该再出现和风那栏 Host", !ok.body.contains("id=\"whost\""))
            // 页面脚本从 location.search 读 token 再回填到请求里，
            // 所以这里只要确认「脚本会带上 t 参数」即可
            assertTrue("页面脚本没把 token 带上请求", ok.body.contains("o.set('t', t)"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun 测试接口会逐项报错而不是笼统失败() {
        val server = ConfigServer(context) {}
        assertTrue(server.start())
        try {
            val url = "http://127.0.0.1:${server.port}/api/test?t=${server.token}"

            // 全部留空：SMB 是必填，必须给出原因；天气可选，留空算通过
            val empty = request("POST", url, mapOf("t" to server.token))
            assertEquals(200, empty.code)
            assertTrue("应报告失败", empty.body.contains("\"ok\":false"))
            assertTrue("缺 SMB 提示：${empty.body}", empty.body.contains("请填写 NAS 地址"))
            // 天气留空必须是「跳过」而不是「失败」，否则没和风 Key 的人永远过不了配置页。
            // 但措辞**不能说成「连接成功」** —— 那样用户会以为天气配好了，
            // 等发现电视上不显示天气又要重新排查一遍。
            // （同类假反馈之前出现过：配置页说「检查通过，正在开始播放」但其实没保存。）
            assertTrue(
                "天气留空应说清是「跳过」：${empty.body}",
                empty.body.contains("\"weather\":\"没填，已跳过"),
            )

            // 天气只填一半：**地点填了但没填私钥** —— 这半边必须报出来，
            // 不能因为「地点有默认值」就静默通过。
            // （心知只有两项，私钥是唯一必填的那项；原来和风是三项全填或全空。）
            val halfWeather = request(
                "POST", url,
                mapOf("t" to server.token, "host" to "127.0.0.1", "share" to "nosuchshare", "wloc" to "北京"),
            )
            assertEquals(200, halfWeather.code)
            assertFalse("不该成功：${halfWeather.body}", halfWeather.body.contains("\"ok\":true"))
            assertTrue("天气填一半应提示：${halfWeather.body}", halfWeather.body.contains("没填天气私钥"))

            // 地点留空是允许的：会用默认坐标（北京），所以只填私钥不算填错，
            // 但会真的去打一次接口 —— 假 Key 要能拿到一句人话，而不是空白
            val fakeKey = request(
                "POST", url,
                mapOf("t" to server.token, "host" to "127.0.0.1", "share" to "nosuchshare", "wkey" to "not-a-real-key"),
            )
            assertEquals(200, fakeKey.code)
            assertFalse("不该成功：${fakeKey.body}", fakeKey.body.contains("\"ok\":true"))
            assertTrue(
                "假 Key 要给出一句人能看懂的原因：${fakeKey.body}",
                Regex("\"weather\":\"[^\"]{4,}\"").containsMatchIn(fakeKey.body),
            )

            // SMB 填了但连不上：必须给出一句中文原因，而不是空白
            val partial = request(
                "POST", url,
                mapOf("t" to server.token, "host" to "127.0.0.1", "share" to "nosuchshare"),
            )
            assertEquals(200, partial.code)
            assertFalse("不该成功", partial.body.contains("\"ok\":true"))
            assertTrue("SMB 项没有给出原因：${partial.body}", Regex("\"smb\":\"[^\"]{2,}\"").containsMatchIn(partial.body))

            // 未知路径
            assertEquals(404, request("GET", "http://127.0.0.1:${server.port}/nope?t=${server.token}").code)
        } finally {
            server.stop()
        }
    }

    @Test
    fun 天气留空不算失败() {
        val server = ConfigServer(context) {}
        assertTrue(server.start())
        try {
            val url = "http://127.0.0.1:${server.port}/api/test?t=${server.token}"
            // 天气三项全空 + SMB 填了但连不上：整体仍失败（SMB 是必填），
            // 但天气那一项不能算失败，否则没 Key 的人过不了配置页。
            // 措辞必须是「跳过」而不是「连接成功」—— 跳过不等于通过。
            val res = request(
                "POST", url,
                mapOf("t" to server.token, "host" to "127.0.0.1", "share" to "nosuchshare"),
            )
            assertEquals(200, res.code)
            assertTrue(
                "天气留空应说清是「跳过」：${res.body}",
                res.body.contains("\"weather\":\"没填，已跳过"),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun 表单字段按URL编码提交也能正确解析() {
        val server = ConfigServer(context) {}
        assertTrue(server.start())
        try {
            val url = "http://127.0.0.1:${server.port}/api/test?t=${server.token}"
            // 密码/地址里常见的特殊字符必须能正确解码；
            // 如果服务端只读 query 不读 body，这里就会退化成「请填写 NAS 地址」
            val res = request(
                "POST", url,
                mapOf(
                    "t" to server.token,
                    "host" to "NAS 名称 with space",       // 空格 → %20
                    "share" to "media&more",              // & → %26
                    "user" to "user@domain",              // @ → %40
                    "pass" to "p@ss&word=1",              // 三种特殊字符
                    "wkey" to "k", "wloc" to "<纬度:经度>",
                ),
            )
            assertEquals(200, res.code)
            // 关键点：SMB 项不能是「没填写」，必须是「连不上」——
            // 说明 host/share 确实被解析出来了
            assertFalse("字段没被解析出来：${res.body}", res.body.contains("请填写 NAS 地址"))
            assertFalse("不该连接成功：${res.body}", res.body.contains("\"ok\":true"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun 保存成功后才关服并回调() {
        val saved = java.util.concurrent.CountDownLatch(1)
        val server = ConfigServer(context) { saved.countDown() }
        assertTrue(server.start())
        val port = server.port
        try {
            // 用假 Key/Host 走一次：两项都会失败，所以不应该落盘、也不应该回调
            val res = request(
                "POST", "http://127.0.0.1:$port/api/save?t=${server.token}",
                mapOf(
                    "t" to server.token,
                    "host" to "127.0.0.1", "share" to "nosuchshare",
                    "wkey" to "fake", "wloc" to "<纬度:经度>",
                ),
            )
            assertEquals(200, res.code)
            assertTrue("失败时不该 ok：${res.body}", res.body.contains("\"ok\":false"))
            assertFalse("失败时不应关服", saved.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            server.stop()
        }
    }
}
