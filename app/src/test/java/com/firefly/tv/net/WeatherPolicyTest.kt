package com.firefly.tv.net

import com.firefly.tv.core.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天气的**频率策略**（纯逻辑，能在单测里跑）。
 *
 * 用户特意交代过「注意频率」：心知免费套餐按次计费，超了回 `AP010014`。
 * 这个判断写错的代价是「要么一开机就把配额打光，要么天气永远停在几天前」，
 * 两种都要很久才被发现，所以逐条钉住。
 *
 * （跟 JSON 有关的断言在 androidTest 的 `WeatherClientParseTest` —— 单测里的
 * `org.json` 是空壳，`optString()` 一律返回 null。）
 */
class WeatherPolicyTest {

    /** 用真实量级的时间戳：`System.currentTimeMillis()` 那种。
     *  小数字会让「0 = 从没成功过」的哨兵值失去意义（踩过一次）。 */
    private val now = 1_700_000_000_000L

    @Test
    fun `刚拿到数据就不该再打接口`() {
        assertFalse(
            "30 分钟内重复请求就是在烧配额",
            WeatherClient.shouldFetch(now, lastOkAt = now - 60_000, lastTryAt = now - 60_000),
        )
    }

    @Test
    fun `过了保鲜期就该刷一次`() {
        assertTrue(
            WeatherClient.shouldFetch(
                now,
                lastOkAt = now - WeatherClient.POLICY_TTL_MS - 1,
                lastTryAt = now - WeatherClient.POLICY_TTL_MS - 1,
            ),
        )
    }

    @Test
    fun `失败之后要等冷却，不能拿坏 Key 疯狂重试`() {
        assertFalse(
            "刚失败过（比如私钥填错）别马上再来一次",
            WeatherClient.shouldFetch(now, lastOkAt = 0, lastTryAt = now - 1_000),
        )
        assertTrue(
            "冷却过了还可以再试",
            WeatherClient.shouldFetch(
                now,
                lastOkAt = 0,
                lastTryAt = now - WeatherClient.POLICY_RETRY_MS - 1,
            ),
        )
    }

    @Test
    fun `从没查过时开机立刻查一次`() {
        // 「0 = 从没成功过」不能被当成「刚刚成功过」——
        // 写成 `now - lastOkAt < ttl` 就会永远不查，这条就是那个回归测试
        assertTrue(WeatherClient.shouldFetch(now, lastOkAt = 0, lastTryAt = 0))
    }

    @Test
    fun `有落盘缓存时开机不会为了刷新而立刻联网`() {
        // 重启电视不该算「新的一次查询」：缓存时间被读回来当 lastOkAt
        assertFalse(WeatherClient.shouldFetch(now, lastOkAt = now - 10_000, lastTryAt = 0))
    }

    @Test
    fun `一天最多也就几十次，离免费额度很远`() {
        // 一次成功的请求管 30 分钟 → 一天最多 48 次；失败时按 5 分钟冷却 → 最多 288 次
        val perDayOk = 24 * 60 * 60 * 1000L / WeatherClient.POLICY_TTL_MS
        val perDayWorst = 24 * 60 * 60 * 1000L / WeatherClient.POLICY_RETRY_MS
        assertEquals(48L, perDayOk)
        assertTrue("就算一整天全失败也不该超过几百次", perDayWorst <= 300)
    }

    // ---- 配置 ----

    @Test
    fun `默认地点就是用户家，而且坐标比地名权限宽`() {
        // 实测：location=北京 → AP010006 没权限访问这个地点；
        //       <纬度:经度> → 正常返回「北京,北京,内蒙古,中国」。
        // 所以默认值必须是坐标
        assertEquals("<纬度:经度>", Config.Weather.DEFAULT_LOCATION)
        assertTrue(Config.Weather.DEFAULT_LOCATION.contains(":"))
        assertEquals(
            "地点留空时要用默认坐标，而不是留空去请求",
            Config.Weather.DEFAULT_LOCATION,
            Config.Weather("key", "").normalized().location,
        )
    }

    @Test
    fun `只填了私钥也算配置好了`() {
        assertTrue(Config.Weather("k", "").normalized().ready)
        assertFalse(Config.Weather("", "北京").ready)
    }

    @Test
    fun `地点和私钥两边的空格会被去掉`() {
        val w = Config.Weather("  <私钥>  ", "  <纬度:经度>  ").normalized()
        assertEquals("<私钥>", w.key)
        assertEquals("<纬度:经度>", w.location)
    }
}
