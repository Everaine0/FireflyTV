package com.firefly.tv.config

import com.firefly.tv.core.Config
import com.firefly.tv.net.WeatherClient
import com.firefly.tv.smb.SmbClient
import com.firefly.tv.smb.SmbStore
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * 应用内置的极小 HTTP 服务，提供手机可访问的配置页（DESIGN §2）。
 *
 * 访问控制（已与用户确认）：二维码里带一次性随机 token，**只在未配置或用户主动呼出时开服**，
 * 保存成功后立即关服。同网段其他人拿不到 token 就看不到 NAS 密码。
 *
 * 配置保存时逐项实测：SMB 与天气分别试连，失败返回具体原因，全部通过才落盘。
 */
class ConfigServer(
    private val ctx: android.content.Context,
    private val onSaved: () -> Unit,
) {

    private var server: ServerSocket? = null
    private var thread: Thread? = null

    val token: String = randomToken()
    var port: Int = 0
        private set

    val running: Boolean get() = server?.isClosed == false

    /** 绑 0.0.0.0，端口让系统分配，避免和其它应用抢 8080。 */
    fun start(): Boolean {
        if (running) return true
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(0))
            server = s
            port = s.localPort
            thread = Thread({ acceptLoop(s) }, "firefly-http").apply {
                isDaemon = true
                start()
            }
            true
        } catch (_: Throwable) {
            server = null
            false
        }
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
                return // 关闭
            }
            // 单线程处理，配置页只有一台手机访问；避免并发改配置
            Thread({ handle(sock) }, "firefly-http-req").apply { isDaemon = true }.start()
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 20_000
            try {
                val input = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1]

                // 读掉请求体
                var contentLength = 0
                while (true) {
                    val h = input.readLine() ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = h.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                val out = BufferedOutputStream(s.getOutputStream())
                route(method, path, body, out)
                out.flush()
            } catch (_: Throwable) {
                // 单个请求失败不影响服务
            }
        }
    }

    private fun route(method: String, path: String, body: String, out: OutputStream) {
        val query = path.substringAfter('?', "")
        val route = path.substringBefore('?')

        // 配置页把 token 放在 URL，把表单字段放在 POST body（application/x-www-form-urlencoded）。
        // 两边都要解析：只读 query 会丢掉所有表单字段，而且 URL 编码后的值
        // （密码里带 @ & 之类）必须解码，否则存进去的是错的。
        val params = HashMap<String, String>()
        params.putAll(parseQuery(query))
        if (body.isNotEmpty()) params.putAll(parseQuery(body))

        if (params["t"] != token) {
            respond(out, 403, "text/plain; charset=utf-8", "链接已失效，请重新扫描电视上的二维码")
            return
        }

        when {
            method == "GET" && (route == "/" || route == "/index.html") ->
                respond(out, 200, "text/html; charset=utf-8", page())

            method == "POST" && route == "/api/test" -> respondJson(out, testAll(params))

            method == "POST" && route == "/api/save" -> respondJson(out, save(params))

            else -> respond(out, 404, "text/plain; charset=utf-8", "not found")
        }
    }

    /**
     * 逐项实测。SMB 是必填项，天气是可选项（DESIGN §2）。
     *
     * 天气做成可选的原因：老人看电视不依赖天气，而和风要单独申请 Key。
     * 为了填天气而卡住「连 NAS 看电视」是反的。留空就跳过，填了就必须测通。
     * 返回 JSON：{"ok":bool,"smb":"...","weather":"..."}，成功项文案为「连接成功」。
     */
    private fun testAll(p: Map<String, String>): String = result(testSmb(p), testWeather(p))

    private fun save(p: Map<String, String>): String {
        val smbMsg = testSmb(p)
        val wMsg = testWeather(p)
        if (smbMsg != null || wMsg != null) return result(smbMsg, wMsg)

        Config.saveSmb(
            ctx,
            Config.Smb(
                p["host"].orEmpty(), p["share"].orEmpty(), p["root"].orEmpty(),
                p["user"].orEmpty(), p["pass"].orEmpty(), p["domain"].orEmpty(),
            ),
        )
        Config.saveWeather(
            ctx,
            Config.Weather(p["wkey"].orEmpty(), p["whost"].orEmpty(), p["wloc"].orEmpty()),
        )
        Config.setConfigured(ctx, true)
        // 先回包再关服，否则手机会看到连接被重置
        Thread {
            Thread.sleep(400)
            stop()
            onSaved()
        }.apply { isDaemon = true }.start()
        return result(null, null)
    }

    private fun result(smbMsg: String?, wMsg: String?): String {
        val ok = smbMsg == null && wMsg == null
        return """{"ok":$ok,"smb":${json(smbMsg ?: "连接成功")},"weather":${json(wMsg ?: "连接成功")}}"""
    }

    /** 返回 null = 通过（含"没填，跳过"），否则是给老人看的中文原因。 */
    private fun testSmb(p: Map<String, String>): String? {
        val cfg = Config.Smb(
            p["host"].orEmpty(), p["share"].orEmpty(), p["root"].orEmpty(),
            p["user"].orEmpty(), p["pass"].orEmpty(), p["domain"].orEmpty(),
        )
        if (!cfg.ready) return "请填写 NAS 地址和共享文件夹名"
        return try {
            SmbStore.with(cfg) { it.probe() }
            null
        } catch (t: Throwable) {
            SmbClient.describe(t)
        }
    }

    /** 天气是可选项：三项全空 = 跳过（通过）；填了任意一项就必须测通。 */
    private fun testWeather(p: Map<String, String>): String? {
        val key = p["wkey"].orEmpty().trim()
        val host = p["whost"].orEmpty().trim()
        val loc = p["wloc"].orEmpty().trim()
        if (key.isBlank() && host.isBlank() && loc.isBlank()) return null // 可选，留空即跳过
        if (key.isBlank() || host.isBlank() || loc.isBlank()) return "天气三项要么都填，要么都留空"
        return WeatherClient.test(Config.Weather(key, host, loc))
    }

    // ---- HTTP 小工具 ----

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            map[urldecode(k)] = urldecode(v)
        }
        return map
    }

    private fun urldecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Throwable) {
        s
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes)
    }

    private fun respondJson(out: OutputStream, json: String) =
        respond(out, 200, "application/json; charset=utf-8", json)

    private fun json(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 手机要访问的地址：电视在局域网里的 IP。 */
        fun localIpv4(): String? {
            return try {
                val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
                for (nif in ifaces) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("169.254.")) continue
                            return ip
                        }
                    }
                }
                null
            } catch (_: Throwable) {
                null
            }
        }
    }

    // ---- 配置页 HTML ----

    private fun page(): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>萤火照夜 · 配置</title>
<style>
  :root { --bg:#141210; --card:#211d1a; --fg:#f5efe6; --dim:#a99e90; --accent:#ffcc66; --err:#ff7a6b; --ok:#8fd694; }
  * { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
  body { margin:0; padding:18px 16px 48px; background:var(--bg); color:var(--fg);
         font:16px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif; }
  h1 { font-size:21px; margin:6px 0 2px; }
  p.sub { color:var(--dim); font-size:14px; margin:0 0 20px; }
  section { background:var(--card); border-radius:14px; padding:16px; margin-bottom:16px; }
  h2 { font-size:17px; margin:0 0 14px; color:var(--accent); font-weight:600; }
  label { display:block; font-size:14px; color:var(--dim); margin:12px 0 6px; }
  input { width:100%; padding:13px 14px; font-size:16px; color:var(--fg);
          background:#0e0c0b; border:1px solid #3a322c; border-radius:10px; }
  input:focus { outline:none; border-color:var(--accent); }
  .hint { font-size:13px; color:var(--dim); margin-top:6px; }
  button { width:100%; padding:16px; font-size:18px; font-weight:600; border:0;
           border-radius:12px; background:var(--accent); color:#241a05; margin-top:20px; }
  button:disabled { opacity:.5; }
  button.test { background:#3a322c; color:var(--fg); margin-top:22px; }
  .msg { margin-top:16px; padding:13px 14px; border-radius:10px; font-size:15px; display:none; white-space:pre-line; }
  .msg.err { display:block; background:#3a1f1c; color:var(--err); }
  .msg.ok  { display:block; background:#1e3320; color:var(--ok); }
  .row { display:flex; gap:10px; }
  .row > div { flex:1; }
</style>
</head>
<body>
<h1>萤火照夜</h1>
<p class="sub">填好后点「保存」，电视会自己检查一遍；两项都通过就立刻开始播放。</p>

<section>
  <h2>一、NAS 上的媒体文件夹</h2>
  <label>NAS 地址（建议填 IP）</label>
  <input id="host" inputmode="url" placeholder="192.168.1.100" autocomplete="off" autocapitalize="off">
  <div class="hint">不要填 http:// 或 \\，只填地址本身</div>

  <label>共享文件夹名</label>
  <input id="share" placeholder="media" autocomplete="off" autocapitalize="off">
  <div class="hint">例如共享名是 \\NAS\media，就填 media</div>

  <label>媒体库根目录（可留空）</label>
  <input id="root" placeholder="留空表示共享根目录" autocomplete="off" autocapitalize="off">
  <div class="hint">例如共享下还有一层 tv，就填 tv</div>

  <div class="row">
    <div>
      <label>账号</label>
      <input id="user" autocomplete="off" autocapitalize="off">
    </div>
    <div>
      <label>域（可留空）</label>
      <input id="domain" autocomplete="off" autocapitalize="off">
    </div>
  </div>
  <label>密码</label>
  <input id="pass" type="password" autocomplete="off">
  <div class="hint">只存在电视本机，不会上传到任何地方</div>
</section>

<section>
  <h2>二、天气预报（可以不填）</h2>
  <p class="hint" style="margin:0 0 12px">不填也能正常看电视，只是按 OK 时看不到天气、也不会播报天气。
     要填就三项都填，填了会一起检查。</p>
  <label>和风天气 Key</label>
  <input id="wkey" autocomplete="off" autocapitalize="off">
  <label>和风天气 API Host</label>
  <input id="whost" placeholder="abc123.def.qweatherapi.com" autocomplete="off" autocapitalize="off">
  <div class="hint">2026 年起每个账号有专属 Host，在控制台「设置」里能看到</div>
  <label>城市</label>
  <input id="wloc" placeholder="101080601 或 116.41,42.30" autocomplete="off" autocapitalize="off">
  <div class="hint">填城市编号最准，也可填「经度,纬度」</div>
</section>

<button class="test" id="btnTest" type="button">先测试一下</button>
<button id="btnSave" type="button">保存并开始使用</button>
<div class="msg" id="msg"></div>

<script>
const ids = ['host','share','root','user','domain','pass','wkey','whost','wloc'];
const msg = document.getElementById('msg');
const qs = new URLSearchParams(location.search);
const t = qs.get('t') || '';

function body() {
  const o = new URLSearchParams();
  ids.forEach(id => o.set(id, document.getElementById(id).value.trim()));
  o.set('t', t);
  return o.toString();
}
function show(text, ok) {
  msg.textContent = text;
  msg.className = 'msg ' + (ok ? 'ok' : 'err');
}
async function call(path, btn) {
  btn.disabled = true;
  const old = btn.textContent;
  btn.textContent = '正在检查…';
  try {
    const r = await fetch(path, {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded; charset=utf-8'},
      body: body()
    });
    const j = await r.json();
    if (j.ok) { show('检查通过，正在开始播放…', true); }
    else { show('NAS：' + j.smb + '\n天气：' + j.weather, false); }
    return j.ok;
  } catch (e) {
    show('连接电视失败，请确认手机和电视在同一个网络', false);
    return false;
  } finally {
    btn.disabled = false;
    btn.textContent = old;
  }
}
document.getElementById('btnTest').onclick = e => call('/api/test', e.target);
document.getElementById('btnSave').onclick = async e => {
  const ok = await call('/api/save', e.target);
  if (ok) { document.getElementById('btnSave').disabled = true; }
};
</script>
</body>
</html>
""".trimIndent()
}
