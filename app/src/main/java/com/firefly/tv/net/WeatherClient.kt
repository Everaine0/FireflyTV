package com.firefly.tv.net

import com.firefly.tv.core.Config
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 和风天气。自 2026 年起废弃共享域名，每账号有专属 API Host，配置页必须单独收集（DESIGN §6）。
 *
 * 实测确认：
 *  - 认证用请求头 `X-QW-Api-Key`（也可放 query `key=`，但不要同时用两种）
 *  - `/v7/weather/now` 与 `/v7/weather/3d` 仍在服务，`location` 支持 LocationID 或「经度,纬度」
 *  - 加 `lang=zh` 才返回中文，否则老人看到的是 "Sunny"
 *
 * 风险 5：Android 5.1 上 TLS 握手可能失败 → [test] 会把原因带出来，不静默。
 */
object WeatherClient {

    class Now(
        val text: String,
        val temp: String,
        val tomorrow: Day?,
    ) {
        class Day(val dayText: String, val nightText: String, val tempMax: String, val tempMin: String)
    }

    fun fetch(cfg: Config.Weather): Now {
        val w = cfg.normalized()
        val now = get(w, "/v7/weather/now")
        val nowObj = now.optJSONObject("now") ?: throw IllegalStateException("天气数据为空")

        // 明天预报失败不能拖垮浮层其他内容（DESIGN §8）
        val tomorrow = runCatching {
            val daily = get(w, "/v7/weather/3d").optJSONArray("daily")
            if (daily != null && daily.length() >= 2) {
                val d = daily.getJSONObject(1)
                Now.Day(
                    d.optString("textDay"),
                    d.optString("textNight"),
                    d.optString("tempMax"),
                    d.optString("tempMin"),
                )
            } else null
        }.getOrNull()

        return Now(nowObj.optString("text"), nowObj.optString("temp"), tomorrow)
    }

    /** 配置页用：返回 null = 成功，否则是给老人看的中文原因。 */
    fun test(cfg: Config.Weather): String? {
        val w = cfg.normalized()
        if (w.key.isBlank() || w.host.isBlank() || w.location.isBlank()) return "请填写天气 Key、Host 和城市"
        return try {
            val json = get(w, "/v7/weather/now")
            if (json.optString("code") == "200") {
                val n = json.optJSONObject("now")
                "连接成功，当前${n?.optString("text").orEmpty()} ${n?.optString("temp").orEmpty()}度"
            } else {
                "天气接口返回错误代码 ${json.optString("code")}"
            }
        } catch (t: Throwable) {
            describe(t)
        }
    }

    private fun get(w: Config.Weather, path: String): JSONObject {
        val loc = URLEncoder.encode(w.location, "UTF-8")
        val conn = (URL("https://${w.host}$path?location=$loc&lang=zh").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            // 和风用 header 传 Key，不要放 URL 里
            setRequestProperty("X-QW-Api-Key", w.key)
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(t: Throwable): String {
        val m = (t.message ?: "").lowercase()
        return when {
            t is java.net.UnknownHostException -> "找不到天气服务器，检查 API Host 是否填对"
            t is javax.net.ssl.SSLException || m.contains("ssl") || m.contains("handshake") ->
                "电视系统太老，连不上天气服务器（不影响看视频）"
            t is java.net.SocketTimeoutException -> "天气服务器响应超时"
            m.contains("http 401") || m.contains("http 403") -> "天气 Key 不正确或没有权限"
            m.contains("http 404") -> "天气接口地址不对，检查 API Host"
            else -> "天气获取失败"
        }
    }
}
