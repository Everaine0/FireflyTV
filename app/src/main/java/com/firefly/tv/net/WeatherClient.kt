package com.firefly.tv.net

import com.firefly.tv.core.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 心知天气（seniverse）v3。
 *
 * ## 为什么从和风换过来
 *
 * 和风自 2026 起改成「每账号一个专属 API Host」，配置页上要多填一栏，
 * 老人自己根本填不对；心知只有两栏：**私钥** + **地点**。
 *
 * ## 两个必须记住的坑（都是实测出来的）
 *
 * 1. **要用「私钥」，不是「公钥」。** 控制台给的是一对：
 *    公钥 `<公钥>…` 直接拿去请求会回 `AP010003 API 密钥 key 错误`，
 *    私钥 `<私钥>…` 才对。配置页的提示里写死了这一点。
 * 2. **城市名可能没权限，坐标反而可以。**
 *    `location=北京` → `AP010006 没有权限访问这个地点`；
 *    `location=<纬度:经度>` → 正常返回「北京,北京,内蒙古,中国」。
 *    所以默认地点给的是经纬度，不是名字（见 [Config.Weather.DEFAULT_LOCATION]）。
 *
 * ## 频率（用户特别交代过）
 *
 * 免费套餐按次计费，超了回 `AP010014`。所以：
 *  - 成功结果缓存 [POLICY_TTL_MS]（30 分钟），期间一次都不打接口；
 *  - 失败也要冷却 [POLICY_RETRY_MS]（5 分钟），不能拿坏 Key 疯狂重试；
 *  - 结果**落盘**（`Config.saveWeatherCache`），重开电视不算「新的一次查询」。
 * 算下来一天最多几十次，离免费额度很远。
 *
 * ## 可读性
 *
 * 返回的数据按老人的读法组织，不是把 JSON 原样丢出来：
 * 浮层第一行「晴 17°」、第二行「今夜 阴转晴 11~27°」、第三行「明天 多云 9~28°」；
 * 语音念的是「今天晴，气温十七度，明天多云，九到二十八度」（见 MainActivity.speakOverlay）。
 */
object WeatherClient {

    private const val BASE = "https://api.seniverse.com/v3/weather"

    /** 成功结果的保鲜期：30 分钟。 */
    const val POLICY_TTL_MS = 30 * 60 * 1000L

    /** 失败了也要等这么久再试，别把配额烧在重试上。 */
    const val POLICY_RETRY_MS = 5 * 60 * 1000L

    /** 浮层 + 语音要的全部信息。 */
    class Now(
        /** 接口认出来的地名，例如「北京」 */
        val place: String,
        /** 实况天气，例如「晴」 */
        val text: String,
        /** 实况气温（摄氏度，字符串，接口原样） */
        val temp: String,
        /** 今天：白天/夜间天气 + 最高最低 */
        val today: Day?,
        /** 明天 */
        val tomorrow: Day?,
    ) {
        class Day(val dayText: String, val nightText: String, val tempMax: String, val tempMin: String) {
            /** 「阴转晴」；白天夜间一样就只说一个。 */
            val readable: String
                get() = if (nightText.isNotBlank() && nightText != dayText) "$dayText 转$nightText" else dayText

            val range: String get() = "$tempMin~$tempMax°"
        }

        /** 落盘缓存（也方便测试断言）。 */
        fun encode(): String = JSONObject().apply {
            put("place", place)
            put("text", text)
            put("temp", temp)
            put("today", today?.let { day(it) })
            put("tomorrow", tomorrow?.let { day(it) })
        }.toString()

        companion object {
            private fun day(d: Day) = JSONObject().apply {
                put("d", d.dayText).put("n", d.nightText)
                put("hi", d.tempMax).put("lo", d.tempMin)
            }

            private fun day(o: JSONObject?): Day? {
                if (o == null || o == JSONObject.NULL) return null
                return Day(
                    o.optString("d"), o.optString("n"),
                    o.optString("hi"), o.optString("lo"),
                )
            }

            fun decode(s: String?): Now? {
                if (s.isNullOrBlank()) return null
                return try {
                    val o = JSONObject(s)
                    Now(
                        o.optString("place"),
                        o.optString("text"),
                        o.optString("temp"),
                        if (o.isNull("today")) null else day(o.optJSONObject("today")),
                        if (o.isNull("tomorrow")) null else day(o.optJSONObject("tomorrow")),
                    )
                } catch (t: Throwable) {
                    null
                }
            }
        }
    }

    /**
     * 现在该不该去打接口。
     *
     * 抽成纯函数是为了能测：这个判断错了的代价是「要么一开机就把配额打光，
     * 要么天气永远停在几天前」——两种都要很久才被发现。
     *
     * 规则只有两条，都靠 `> 0` 把「从来没有过」和「刚刚」区分开：
     *  - 成功过、而且还在保鲜期内 → 不问；
     *  - 试过（无论成败）、而且还在冷却期内 → 不重试。
     *
     * （0 是「没有」的哨兵值。写成 `now - lastOkAt < ttl` 的话，
     * `lastOkAt = 0` 会被当成「刚刚成功过」，第一次就永远查不出去 —— 别再写回去。）
     *
     * @param lastOkAt 上次**成功**的时间（0 = 从没成功过）
     * @param lastTryAt 上次**尝试**的时间（含失败，0 = 从没试过）
     */
    fun shouldFetch(
        now: Long,
        lastOkAt: Long,
        lastTryAt: Long,
        ttlMs: Long = POLICY_TTL_MS,
        retryMs: Long = POLICY_RETRY_MS,
    ): Boolean {
        if (lastOkAt > 0 && now - lastOkAt < ttlMs) return false
        if (lastTryAt > 0 && now - lastTryAt < retryMs) return false
        return true
    }

    fun fetch(cfg: Config.Weather): Now {
        val w = cfg.normalized()
        if (!w.ready) throw IllegalStateException("还没设置天气")

        val nowJson = call(w, "now.json", "")
        // 预报拿不到不该拖垮浮层其他内容（DESIGN §8）
        val dailyJson = runCatching { call(w, "daily.json", "&start=0&days=2") }.getOrNull()
        return combine(nowJson, dailyJson)
    }

    /**
     * 把两个接口的响应拼成浮层要的那份数据。
     *
     * 单独拆出来是为了能用**真实响应报文**做单元测试：字段名（`temperature` /
     * `text_day` / `high` / `low`）打错的话，接口不报错、只是永远显示空白，
     * 那种问题在电视上很难发现。
     */
    internal fun combine(nowJson: JSONObject, dailyJson: JSONObject?): Now {
        val results = nowJson.optJSONArray("results")
            ?: throw IllegalStateException(errorText(nowJson) ?: "天气数据为空")
        val first = results.optJSONObject(0) ?: throw IllegalStateException("天气数据为空")
        val nowObj = first.optJSONObject("now") ?: JSONObject()
        val daily = dailyJson?.optJSONArray("results")?.optJSONObject(0)?.optJSONArray("daily")
        return Now(
            place = first.optJSONObject("location")?.optString("name").orEmpty(),
            text = nowObj.optString("text"),
            temp = nowObj.optString("temperature"),
            today = dayAt(daily, 0),
            tomorrow = dayAt(daily, 1),
        )
    }

    /**
     * 配置页用的一次试连。
     *
     * 返回值是**明确的两件事**，不是「null 代表成功、字符串代表失败」那种约定 ——
     * 那个约定踩过坑：原来成功时返回的是「连接成功，当前晴 17 度」这句话，
     * 而调用方只认 null，于是配置页把**试连成功显示成了失败**
     * （SMB 那半边的注释里记着同类问题：措辞必须能区分「通过」和「没填」）。
     */
    class Verdict(val ok: Boolean, val message: String)

    fun test(cfg: Config.Weather): Verdict {
        val w = cfg.normalized()
        if (w.key.isBlank()) return Verdict(false, "请填写心知天气的私钥")
        return try {
            val n = fetch(w)
            val where = n.place.ifBlank { w.location }
            Verdict(true, "$where 现在${n.text} ${n.temp}度")
        } catch (t: Throwable) {
            Verdict(false, describe(t))
        }
    }

    private fun dayAt(arr: JSONArray?, i: Int): Now.Day? {
        if (arr == null || arr.length() <= i) return null
        val d = arr.optJSONObject(i) ?: return null
        return Now.Day(
            d.optString("text_day"),
            d.optString("text_night"),
            d.optString("high"),
            d.optString("low"),
        )
    }

    /**
     * 一次真实请求。
     *
     * @param extra now 用不到预报、daily 要用 `start/days`
     */
    private fun call(w: Config.Weather, endpoint: String, extra: String): JSONObject {
        val loc = URLEncoder.encode(w.location, "UTF-8")
        val key = URLEncoder.encode(w.key, "UTF-8")
        val url = URL("$BASE/$endpoint?key=$key&location=$loc&language=zh-Hans&unit=c$extra")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            // 心知的 UA 校验（AP010009）默认是关的，但有的账号开了；给一个稳定的 UA 没坏处
            setRequestProperty("User-Agent", "FireflyTV/1.0 (Android)")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            }.orEmpty()
            if (text.isBlank()) throw IllegalStateException("HTTP $code")
            val json = JSONObject(text)
            // 心知的错误是 200 之外的 HTTP 码 + JSON 里的 status_code，两种都要看
            if (code !in 200..299 || json.has("status_code")) {
                throw IllegalStateException(errorText(json) ?: "HTTP $code")
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    /** 把 `status_code` 翻成一句人话（文案对着官方错误码表写）。 */
    internal fun errorText(json: JSONObject): String? {
        val code = json.optString("status_code").orEmpty()
        if (code.isBlank()) return json.optString("status").takeIf { it.isNotBlank() }
        return when (code) {
            "AP010001" -> "天气参数不对，检查城市或坐标"
            "AP010002" -> "这个 Key 没有天气接口的权限"
            "AP010003" -> "天气 Key 不对：要用控制台里的「私钥」，不是公钥"
            "AP010004" -> "天气签名错误"
            "AP010005" -> "天气接口不存在"
            "AP010006" -> "这个地点查不了，换个城市名或坐标试试"
            "AP010007" -> "天气请求需要签名验证"
            "AP010008" -> "天气账号还没有绑定域名"
            "AP010009" -> "天气请求标识不一致"
            "AP010010" -> "找不到这个地点，检查城市名或坐标"
            "AP010011" -> "无法根据 IP 判断城市"
            "AP010012" -> "心知天气服务已经过期"
            "AP010013" -> "心知天气查询次数用完了"
            "AP010014" -> "天气查得太频繁，等一会再试"
            else -> "天气接口返回错误 $code"
        }
    }

    private fun describe(t: Throwable): String {
        val m = (t.message ?: "").lowercase()
        return when {
            // 上面那些中文原因是自己抛的，原样带出去
            (t.message ?: "").any { it.code > 127 } -> t.message.orEmpty()
            t is java.net.UnknownHostException -> "找不到天气服务器，检查电视的网络"
            t is javax.net.ssl.SSLException || m.contains("ssl") || m.contains("handshake") ->
                "电视系统太老，连不上天气服务器（不影响看视频）"
            t is java.net.SocketTimeoutException -> "天气服务器响应超时"
            m.contains("http 403") || m.contains("http 401") -> "天气 Key 不正确或没有权限"
            m.contains("http 404") -> "天气接口地址不对"
            else -> "天气获取失败"
        }
    }
}
