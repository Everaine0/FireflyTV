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
            Config.Weather(p["wkey"].orEmpty(), p["wloc"].orEmpty()),
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

    /**
     * 组装回包。
     *
     * 措辞上区分「测通了」和「没填、跳过了」—— **跳过不等于通过**。
     * 早先两者都回「连接成功」，用户留空天气却看到「天气 连接成功」，
     * 会以为天气已经配好了，等发现电视上不显示天气又要重新排查一遍。
     * （同类问题之前出现过一次：配置页说「检查通过，正在开始播放」但其实没保存。）
     */
    private fun result(smbMsg: String?, wMsg: String?): String {
        val ok = smbMsg == null && wMsg == null
        val smbText = smbMsg ?: if (smbSkipped) "没填，已跳过" else "连接成功"
        val wText = wMsg ?: if (weatherSkipped) {
            "没填，已跳过（电视上不显示天气）"
        } else {
            // 试通时把「查到的是哪儿、现在多少度」带回去：默认地点是坐标，
            // 用户能一眼看出是不是查到了自己家
            weatherOkDetail ?: "连接成功"
        }
        return """{"ok":$ok,"smb":${json(smbText)},"weather":${json(wText)}}"""
    }

    /** 上一次 [testSmb] / [testWeather] 是不是「没填所以跳过」。 */
    private var smbSkipped = false
    private var weatherSkipped = false

    /** 天气试连成功时的那句话（给 [result] 用）。 */
    private var weatherOkDetail: String? = null

    /** 返回 null = 通过（含"没填，跳过"），否则是给老人看的中文原因。 */
    private fun testSmb(p: Map<String, String>): String? {
        val cfg = Config.Smb(
            p["host"].orEmpty(), p["share"].orEmpty(), p["root"].orEmpty(),
            p["user"].orEmpty(), p["pass"].orEmpty(), p["domain"].orEmpty(),
        )
        smbSkipped = false
        if (!cfg.ready) return "请填写 NAS 地址和共享文件夹名"
        return try {
            SmbStore.with(cfg) { it.probe() }
            null
        } catch (t: Throwable) {
            SmbClient.describe(t)
        }
    }

    /**
     * 天气是可选项：两项全空 = 跳过（不算失败，但也不能说成「连接成功」）。
     *
     * 心知只要**私钥 + 地点**两项，地点留空会用默认坐标（北京），
     * 所以只有「填了地点却没填私钥」才算是填错了。
     */
    private fun testWeather(p: Map<String, String>): String? {
        val key = p["wkey"].orEmpty().trim()
        val loc = p["wloc"].orEmpty().trim()
        weatherSkipped = false
        weatherOkDetail = null
        if (key.isBlank() && loc.isBlank()) {
            weatherSkipped = true
            return null // 可选，留空即跳过
        }
        if (key.isBlank()) return "填了城市但没填天气私钥"
        val verdict = WeatherClient.test(Config.Weather(key, loc))
        if (verdict.ok) weatherOkDetail = verdict.message
        return if (verdict.ok) null else verdict.message
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
  /* 保存时的等待反馈：转圈 + 一条来回走的进度条。
     保存要连 NAS 实测，可能好几秒；没有反馈用户会以为卡死并反复点。 */
  #spin { display:none; width:15px; height:15px; margin-right:8px; vertical-align:-2px;
          border:2px solid #6b5c48; border-top-color:var(--accent); border-radius:50%;
          animation:spin .8s linear infinite; }
  @keyframes spin { to { transform:rotate(360deg); } }
  #bar { display:none; position:relative; overflow:hidden; height:4px; margin-top:14px;
         border-radius:2px; background:#2a2420; }
  #bar::after { content:''; position:absolute; top:0; left:-40%; width:40%; height:100%;
                background:var(--accent); border-radius:2px; animation:slide 1.1s ease-in-out infinite; }
  @keyframes slide { 0% { left:-40%; } 100% { left:100%; } }
  @media (prefers-reduced-motion: reduce) {
    #spin, #bar::after { animation:none; }
  }
  /* ---- 手机适配 ----
     配置页几乎只用手机打开（扫电视上的二维码），所以按手机优先来做：
      · 已设 width=device-width：不会按 980px 桌面宽度缩放
      · 输入框字号锁 16px：iOS 上小于 16px 会在聚焦时自动放大页面，很难退回
      · 窄屏收掉左右留白；底部留出「手势条」的安全区，避免按钮被系统条挡住 */
  @media (max-width: 420px) {
    body { padding:14px 12px 40px; }
    section { padding:14px; }
    h1 { font-size:19px; }
    button { padding:15px; font-size:17px; }
  }
  @supports (padding: env(safe-area-inset-bottom)) {
    body { padding-bottom: calc(48px + env(safe-area-inset-bottom)); }
  }
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
     填了会当场试连一次。</p>
  <label>心知天气 私钥</label>
  <input id="wkey" autocomplete="off" autocapitalize="off">
  <div class="hint">在 seniverse.com 控制台复制<b>私钥</b>（一串字母数字）。<b>不要填公钥</b>，公钥会被直接拒绝</div>
  <label>城市或坐标</label>
  <input id="wloc" placeholder="<纬度:经度>" autocomplete="off" autocapitalize="off">
  <div class="hint">留空就用默认：北京市北京市区（<纬度:经度>）。<br>
    也可以填城市名（如「北京」）；有些地名套餐里没权限，这时改用坐标一定行</div>
</section>

<button class="test" id="btnTest" type="button">先测试一下</button>
<button id="btnSave" type="button"><span id="spin"></span>保存并开始使用</button>
<div id="bar"></div>
<div class="msg" id="msg"></div>

<script>
const ids = ['host','share','root','user','domain','pass','wkey','wloc'];
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
async function call(path, btn, okText) {
  btn.disabled = true;
  const old = btn.textContent;
  btn.textContent = '正在检查…';
  msg.className = 'msg';
  try {
    const r = await fetch(path, {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded; charset=utf-8'},
      body: body()
    });
    const j = await r.json();
    if (j.ok) { show(okText, true); }
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

// 保存要连 NAS 实测，可能要等好几秒 —— 这期间必须让人看到「在做事」，
// 按钮转圈 + 一条会动的提示，否则用户会以为卡死了然后反复点。
function busy(on) {
  const spinner = document.getElementById('spin');
  const bar = document.getElementById('bar');
  spinner.style.display = on ? 'inline-block' : 'none';
  bar.style.display = on ? 'block' : 'none';
}

// 「先测试一下」只检查，不会保存、也不会开始播放 —— 文案必须说清楚，
// 否则用户以为已经生效，看着电视还停在二维码页会一头雾水。
document.getElementById('btnTest').onclick = e =>
  call('/api/test', e.target, '检查通过。确认没问题后，请点下面的「保存并开始使用」。');
document.getElementById('btnSave').onclick = async e => {
  busy(true);
  msg.className = 'msg';
  const ok = await call('/api/save', e.target, '已保存，电视马上开始播放…');
  busy(false);
  if (ok) {
    document.getElementById('btnSave').disabled = true;
    document.getElementById('btnTest').disabled = true;
  }
};
</script>
</body>
</html>
""".trimIndent()
}
