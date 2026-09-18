package com.firefly.tv.net

import com.firefly.tv.core.Config
import org.json.JSONObject
import org.junit.Assert.assertEquals

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心知天气（seniverse）的**解析**部分。
 *
 * 为什么放在 androidTest 而不是单测：单测跑在 JVM 上，`testOptions.returnDefaultValues`
 * 让 AGP 的 mockable-android.jar 排在依赖前面，`JSONObject.optString()` 一律返回 null
 * （实测过，加一个真的 `org.json:json` 也盖不过它）。所以凡是跟 JSON 有关的断言
 * 都在这里跑 —— 真机/模拟器上是真的 org.json。
 *
 * 下面两段报文是**从接口上抓下来的真实响应**（地名与 location id 做过匿名替换，
 * 字段结构原样）。字段名打错的话接口不报错、只是电视上永远显示空白，
 * 所以必须拿真报文钉住。
 *
 * 结果看 `adb logcat -s FireflyWeather`。
 */
class WeatherClientParseTest {

    private fun log(msg: String) = android.util.Log.i("FireflyWeather", msg)

    private val realNow = """
    {"results":[{"location":{"id":"WXPLACEHOLDER","name":"北京","country":"CN",
    "path":"北京,北京,中国","timezone":"Asia/Shanghai","timezone_offset":"+08:00"},
    "now":{"text":"晴","code":"1","temperature":"17"},
    "last_update":"2026-09-11T21:20:00+08:00"}]}
    """.trimIndent()

    private val realDaily = """
    {"results":[{"location":{"id":"WXPLACEHOLDER","name":"北京"},
    "daily":[
    {"date":"2026-09-11","text_day":"阴","code_day":"9","text_night":"晴","code_night":"1",
     "high":"27","low":"11","precip":"0.00","wind_direction":"西","wind_scale":"5","humidity":"39"},
    {"date":"2026-09-12","text_day":"多云","code_day":"4","text_night":"多云","code_night":"4",
     "high":"28","low":"9","precip":"0.00","wind_direction":"西南","wind_scale":"4","humidity":"40"}],
    "last_update":"2026-09-11T08:00:00+08:00"}]}
    """.trimIndent()

    @Test
    fun 实况和两天预报都按浮层要的样子取出来() {
        val now = WeatherClient.combine(JSONObject(realNow), JSONObject(realDaily))
        assertEquals("北京", now.place)
        assertEquals("晴", now.text)
        assertEquals("17", now.temp)
        assertEquals("阴", now.today?.dayText)
        assertEquals("晴", now.today?.nightText)
        assertEquals("27", now.today?.tempMax)
        assertEquals("11", now.today?.tempMin)
        assertEquals("多云", now.tomorrow?.dayText)
        assertEquals("28", now.tomorrow?.tempMax)
        assertEquals("9", now.tomorrow?.tempMin)
        // 白天夜间一样时不显示「多云转多云」
        assertEquals("阴 转晴", now.today?.readable)
        assertEquals("多云", now.tomorrow?.readable)
        assertEquals("11~27°", now.today?.range)
        log("实况=${now.place} ${now.text} ${now.temp}° 今天=${now.today?.readable} ${now.today?.range} 明天=${now.tomorrow?.readable} ${now.tomorrow?.range}")
    }

    @Test
    fun 预报拿不到也能出实况浮层不会整块空掉() {
        val now = WeatherClient.combine(JSONObject(realNow), null)
        assertEquals("晴", now.text)
        assertEquals("17", now.temp)
        assertNull(now.today)
        assertNull(now.tomorrow)
    }

    @Test
    fun 天气结果能原样存下来再读出来() {
        val now = WeatherClient.combine(JSONObject(realNow), JSONObject(realDaily))
        val back = WeatherClient.Now.decode(now.encode())
        assertEquals(now.place, back?.place)
        assertEquals(now.text, back?.text)
        assertEquals(now.temp, back?.temp)
        assertEquals(now.today?.readable, back?.today?.readable)
        assertEquals(now.tomorrow?.range, back?.tomorrow?.range)
        // 坏掉的缓存当作没有
        assertNull(WeatherClient.Now.decode(null))
        assertNull(WeatherClient.Now.decode("不是 JSON"))
    }

    @Test
    fun 错误码都翻成人话() {
        assertEquals(
            "天气 Key 不对：要用控制台里的「私钥」，不是公钥",
            WeatherClient.errorText(JSONObject("""{"status":"The API key is invalid.","status_code":"AP010003"}""")),
        )
        assertEquals(
            "这个地点查不了，换个城市名或坐标试试",
            WeatherClient.errorText(JSONObject("""{"status":"x","status_code":"AP010006"}""")),
        )
        assertEquals(
            "天气查得太频繁，等一会再试",
            WeatherClient.errorText(JSONObject("""{"status":"x","status_code":"AP010014"}""")),
        )
        assertTrue(WeatherClient.errorText(JSONObject("""{"status_code":"AP019999"}"""))!!.isNotBlank())
        assertNull("没有 status_code 就当没错误", WeatherClient.errorText(JSONObject("""{"results":[]}""")))
    }

    /**
     * 真连一次，并把私钥写进应用配置。
     *
     * 私钥不写进仓库（临时的、以后可能换），走的是项目已有的那条路：
     * `local.properties` → gradle 的 instrumentation 参数 → 测试读出来。
     * 没配就跳过，不会让整套测试变红。
     *
     * ```
     * .\build.ps1 connectedDebugAndroidTest `
     *   -Pandroid.testInstrumentationRunnerArguments.class=com.firefly.tv.net.WeatherClientParseTest
     * adb logcat -s FireflyWeather
     * ```
     */
    @Test
    fun 配了私钥就打一次真实接口并写进应用配置() {
        val inst = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val argKey = args.getString("ff.weather.key").orEmpty()
        val argLoc = args.getString("ff.weather.location").orEmpty()

        // 参数优先；没有就用应用里已经配好的（配置页保存过）
        val cfg = if (argKey.isNotBlank()) Config.Weather(argKey, argLoc) else Config.weather(ctx)
        if (!cfg.ready) {
            log("没配天气私钥，跳过真实接口测试")
            return
        }
        // 写回应用配置：这样模拟器/电视上的浮层马上就能显示天气
        Config.saveWeather(ctx, cfg)

        // 只打一次接口：免费套餐按次计费（超了回 AP010014），测试也一样要省着用。
        // test() 内部就是 fetch()，所以这里只调它，另外打印一次详情即可。
        val verdict = WeatherClient.test(cfg)
        log("配置页试连：ok=${verdict.ok} ${verdict.message}")
        assertTrue("真实请求应该成功，实际：${verdict.message}", verdict.ok)
    }
}
