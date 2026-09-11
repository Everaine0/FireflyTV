package com.firefly.tv.diag

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 极小的局域网 HTTP 服务：**排查用，无鉴权**（用户明确要求「怎么简单怎么来」）。
 *
 * 和配置页那套 [com.firefly.tv.config.ConfigServer] 的区别：
 *  - 那个带一次性 token、只在配置时开、保存完就关（因为它经手 NAS 密码）；
 *  - 这个**常开**、不带 token、端口固定（见 [DiagHub.PORT]），因为它的用途是
 *    让远端的我在电视播放时**随时读数字、随时切变量**。里面没有任何凭据。
 *
 * 只做 GET/POST，一次请求一个连接（`Connection: close`）——
 * 排查工具不需要 keep-alive，少一个状态就少一类「读了一半卡住」的怪问题。
 */
class DiagServer(
    private val wantPort: Int,
    private val handler: (path: String, query: Map<String, String>) -> Answer,
) {

    private var server: ServerSocket? = null
    private var thread: Thread? = null

    /** 实际绑上的端口（[wantPort] 被占用时会顺延）。 */
    var port: Int = 0
        private set

    val running: Boolean get() = server?.isClosed == false

    fun start(): Boolean {
        if (running) return true
        for (p in wantPort until wantPort + 10) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress("0.0.0.0", p))
                server = s
                port = s.localPort
                thread = Thread({ acceptLoop(s) }, "firefly-diag-http").apply {
                    isDaemon = true
                    start()
                }
                return true
            } catch (_: Throwable) {
                // 端口被占，试下一个
            }
        }
        return false
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        thread = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val sock = try {
                s.accept()
            } catch (_: Throwable) {
                return
            }
            Thread({ handle(sock) }, "firefly-diag-req").apply { isDaemon = true }.start()
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            // 超时给短一点：排查通道不值得为了一条卡住的连接留一个线程
            runCatching { s.soTimeout = 8_000 }
            val out = BufferedOutputStream(s.getOutputStream())
            try {
                val input = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]

                var contentLength = 0
                while (true) {
                    val h = input.readLine() ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = h.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength in 1..65536) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else {
                    ""
                }

                val path = target.substringBefore('?')
                val params = HashMap<String, String>()
                params.putAll(parseQuery(target.substringAfter('?', "")))
                if (method == "POST" && body.isNotEmpty()) params.putAll(parseQuery(body))

                val answer = try {
                    handler(path, params)
                } catch (t: Throwable) {
                    Answer.text("handler error: ${t.javaClass.simpleName}: ${t.message}\n", 500)
                }
                write(out, answer)
            } catch (_: Throwable) {
                // 单个请求失败不影响服务
            } finally {
                runCatching { out.flush() }
            }
        }
    }

    private fun write(out: BufferedOutputStream, a: Answer) {
        val bytes = a.body.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 ${a.status} ${statusText(a.status)}\r\n" +
            "Content-Type: ${a.contentType}; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes)
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            map[decode(k)] = decode(v)
        }
        return map
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Throwable) {
        s
    }

    /** 一个响应。 */
    class Answer(val status: Int, val contentType: String, val body: String) {
        companion object {
            fun json(body: String) = Answer(200, "application/json", body)
            fun text(body: String, status: Int = 200) = Answer(status, "text/plain", body)
            fun html(body: String) = Answer(200, "text/html", body)
            fun notFound() = Answer(404, "text/plain", "not found\n")
        }
    }
}
