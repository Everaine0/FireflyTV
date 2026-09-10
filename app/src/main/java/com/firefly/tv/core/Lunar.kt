package com.firefly.tv.core

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.sin

/**
 * 农历、节气、节日。
 *
 * 不依赖 android.icu.util.ChineseCalendar（那是 API 24+），
 * 而是自带 1900–2100 的农历数据表 —— 少一个兼容性风险，也少一个 desugar 依赖。
 * 节气用真实太阳视黄经计算，不用「几月几日」的近似口诀表（那种表会差一天）。
 */
object Lunar {

    private val DAYS = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
    )

    private val MONTHS = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月",
    )

    private val TERMS = arrayOf(
        "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
        "立夏", "小满", "芒种", "夏至", "小暑", "大暑", "立秋", "处暑",
        "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至",
    )

    private val WEEKDAYS = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    /**
     * @param lunarShort 挂历式的短写法：初一只写月（「八月」），其余只写日（「十五」）
     * @param lunarFull  完整写法（「八月十五」），浮层和语音播报用
     */
    class Info(
        val lunarShort: String,
        val lunarFull: String,
        val term: String?,
        val festival: String?,
        val weekday: String,
    )

    fun of(date: Date, zone: TimeZone): Info {
        val gc = GregorianCalendar(zone).apply { time = date }
        val weekday = WEEKDAYS[gc.get(Calendar.DAY_OF_WEEK) - 1]
        val term = termOf(gc)
        val lunar = toLunar(gc)
        val festival = festivalOf(gc, lunar)

        // toLunar 已经把月限制在 1-12；这里再夹一次，避免表数据异常时把数组读穿。
        // 月为 0 时 MONTHS[month - 1] 会抛异常，而显示层不该因为日历崩掉。
        val monthIndex = (lunar.month - 1).coerceIn(0, MONTHS.size - 1)
        val dayIndex = (lunar.day - 1).coerceIn(0, DAYS.size - 1)
        val monthText = (if (lunar.leap) "闰" else "") + MONTHS[monthIndex]
        val dayText = DAYS[dayIndex]
        // 初一的短写法只显示月份，和挂历习惯一致
        val short = if (lunar.day == 1) monthText else dayText
        return Info(short, monthText + dayText, term, festival, weekday)
    }

    /** 只给单元测试用：暴露换算出的农历月/日/闰标记，用来断言表数据没读穿。 */
    internal fun debugDate(date: Date, zone: TimeZone): String {
        val gc = GregorianCalendar(zone).apply { time = date }
        val l = toLunar(gc)
        return "month=${l.month} day=${l.day} leap=${l.leap}"
    }

    /** 只给单元测试用：距离农历表基准日（1900-01-31）的天数。 */
    internal fun debugOffset(y: Int, m: Int, d: Int): Long = daysFromCivil(y, m, d) - BASE_1900_01_31

    private class LunarDate(val month: Int, val day: Int, val leap: Boolean)

    /**
     * 天数基准：1900-01-31。
     *
     * 注意别写成 1900-02-19 —— 那一天是农历 1900 年的正月初二（1900 年正月初一就是 1 月 31 日），
     * 用错基准会让整张表偏 19 天，而且因为「差得不多」，肉眼看日历很难发现。
     */
    private val BASE_1900_01_31 = daysFromCivil(1900, 1, 31)

    /**
     * 公历日期 → 天数序号（Howard Hinnant 的 days_from_civil）。
     * 刻意不用 Calendar.getTimeInMillis() 相减：在东八区取当天零点，
     * 两个时刻之差会落在 N 天差一点点，整数除法截断后整整少一天。
     */
    private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
        val year = if (m <= 2) y - 1 else y
        val era = (if (year >= 0) year else year - 399) / 400
        val yoe = year - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146097L + doe - 719468L
    }

    private fun toLunar(gc: GregorianCalendar): LunarDate {
        var offset = daysFromCivil(
            gc.get(Calendar.YEAR),
            gc.get(Calendar.MONTH) + 1,
            gc.get(Calendar.DAY_OF_MONTH),
        ) - BASE_1900_01_31

        if (offset < 0) return LunarDate(1, 1, false) // 表范围之外，防御
        var remaining = offset.toInt()

        // 年循环沿用农历表的标准约定：offset 用「剩余天数」语义，循环退出后回退一年。
        // 换成 `index < yearDays` 之类看起来更直观的写法，边界会差一天。
        var lunarYear = 1900
        var lastYearDays = 0
        while (lunarYear < 2101 && remaining > 0) {
            lastYearDays = lunarYearDays(lunarYear)
            remaining -= lastYearDays
            lunarYear++
        }
        if (remaining < 0) {
            remaining += lastYearDays
            lunarYear--
        }

        val leap = leapMonthOf(lunarYear)
        var lunarMonth = 1
        var isLeap = false
        var lastMonthDays = 0

        while (lunarMonth < 13 && remaining > 0) {
            if (leap > 0 && lunarMonth == leap + 1 && !isLeap) {
                // 进入闰月：月份下标不动，先按闰月天数扣一次
                lunarMonth--
                isLeap = true
                lastMonthDays = leapDays(lunarYear)
            } else {
                lastMonthDays = monthDays(lunarYear, lunarMonth)
            }
            if (isLeap && lunarMonth == leap + 1) isLeap = false
            remaining -= lastMonthDays
            lunarMonth++
        }

        // 闰月导致下标重叠，需要回正
        if (remaining == 0 && leap > 0 && lunarMonth == leap + 1) {
            if (isLeap) {
                isLeap = false
            } else {
                isLeap = true
                lunarMonth--
            }
        }
        if (remaining < 0) {
            remaining += lastMonthDays
            lunarMonth--
        }

        return LunarDate(lunarMonth.coerceIn(1, 12), remaining + 1, isLeap)
    }

    private fun lunarYearDays(y: Int): Int {
        var sum = 348 // 12 个月 × 29 天
        var mask = 0x8000
        while (mask > 0x8) {
            if (yearInfo(y) and mask != 0) sum++
            mask = mask shr 1
        }
        return sum + leapDays(y)
    }

    private fun leapDays(y: Int): Int =
        if (leapMonthOf(y) > 0) {
            if (yearInfo(y) and 0x10000 != 0) 30 else 29
        } else 0

    /** 1-12，0 表示无闰月。 */
    private fun leapMonthOf(y: Int): Int = yearInfo(y) and 0xf

    private fun monthDays(y: Int, m: Int): Int =
        if (yearInfo(y) and (0x10000 shr m) != 0) 30 else 29

    private fun yearInfo(y: Int): Int = LUNAR_INFO[(y - 1900).coerceIn(0, LUNAR_INFO.size - 1)]

    private fun festivalOf(gc: GregorianCalendar, lunar: LunarDate): String? {
        // 公历节日
        val m = gc.get(Calendar.MONTH) + 1
        val d = gc.get(Calendar.DAY_OF_MONTH)
        when {
            m == 1 && d == 1 -> return "元旦"
            m == 5 && d == 1 -> return "劳动节"
            m == 6 && d == 1 -> return "儿童节"
            m == 10 && d == 1 -> return "国庆节"
        }
        // 农历节日：用换算出的月和日判断，不要用显示文案，初一的文案里没有日子
        if (lunar.leap) return null
        return when (lunar.month to lunar.day) {
            1 to 1 -> "春节"
            1 to 15 -> "元宵"
            5 to 5 -> "端午"
            7 to 7 -> "七夕"
            7 to 15 -> "中元"
            8 to 15 -> "中秋"
            9 to 9 -> "重阳"
            12 to 8 -> "腊八"
            12 to 23 -> "小年"
            else -> null
        }
    }

    /** 当天若是节气日则返回节气名。 */
    fun termOf(gc: GregorianCalendar): String? {
        val year = gc.get(Calendar.YEAR)
        val doy = gc.get(Calendar.DAY_OF_YEAR)
        for (i in TERMS.indices) {
            val t = termDay(year, i)
            if (t.year == year && t.dayOfYear == doy) return TERMS[i]
        }
        return null
    }

    private class DayOfYear(val year: Int, val dayOfYear: Int)

    /**
     * 第 [index] 个节气（0 = 小寒）在某年落在哪一天（北京时间）。
     *
     * 逐个节气递推，不用「一年中的固定第几天」当猜测值：
     * 那种猜测在年尾会偏出十几天，牛顿迭代遇到 ±180° 的环绕就会收敛到隔壁节气。
     * 这里每个节气的初值都是上一个节气的解 + 15.2 天，与真值相差不到一天，
     * 再用二分法夹逼到时刻，必定落在正确的节气上。
     */
    private fun termDay(year: Int, index: Int): DayOfYear {
        var jd = julianDay(year, 1, 6)
        for (i in 0 until index) {
            jd = solveTerm(jd + 15.2, (285.0 + (i + 1) * 15.0) % 360.0)
        }
        if (index == 0) jd = solveTerm(jd, 285.0)

        val (y, mo, d) = julianToDate(jd + 8.0 / 24.0) // 北京时间
        return DayOfYear(y, dayOfYear(y, mo, d))
    }

    /**
     * 求太阳视黄经等于 [targetLon] 的时刻（儒略日）。
     * 先用牛顿法快速逼近，再用二分法夹逼，避免环绕导致跳到相邻节气。
     */
    private fun solveTerm(guessJd: Double, targetLon: Double): Double {
        var jd = guessJd
        repeat(6) {
            val diff = longitudeDiff(jd, targetLon)
            jd += diff * 365.2422 / 360.0
        }
        // 夹逼区间取 ±4 天：一个节气间隔约 15.2 天，±4 天足以覆盖初值误差且不会跨到隔壁
        var lo = jd - 4.0
        var hi = jd + 4.0
        if (longitudeDiff(lo, targetLon) > 0) lo = jd - 0.01
        if (longitudeDiff(hi, targetLon) < 0) hi = jd + 0.01
        repeat(40) {
            val mid = (lo + hi) / 2.0
            if (longitudeDiff(mid, targetLon) < 0) lo = mid else hi = mid
        }
        return (lo + hi) / 2.0
    }

    /** 该时刻太阳视黄经相对目标还差多少度，结果落在 (-180, 180]。 */
    private fun longitudeDiff(jd: Double, targetLon: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        val m = (357.52911 + 35999.05029 * t) % 360.0
        val lambda = (280.46646 + 36000.76983 * t + 0.0003032 * t * t +
            1.914602 * sinDeg(m) + 0.019993 * sinDeg(2 * m) + 0.000289 * sinDeg(3 * m)) % 360.0
        return ((targetLon - lambda + 540.0) % 360.0) - 180.0
    }

    private fun julianDay(y: Int, m: Int, d: Int): Double {
        var year = y
        var month = m
        if (month <= 2) {
            year -= 1
            month += 12
        }
        val a = floor(year / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (year + 4716)) + floor(30.6001 * (month + 1)) + d + b - 1524.5
    }

    private fun julianToDate(jd: Double): Triple<Int, Int, Int> {
        val z = floor(jd + 0.5).toInt()
        var a = z
        if (z >= 2299161) {
            val alpha = floor((z - 1867216.25) / 36524.25).toInt()
            a = z + 1 + alpha - alpha / 4
        }
        val b = a + 1524
        val c = floor((b - 122.1) / 365.25).toInt()
        val d = floor(365.25 * c).toInt()
        val e = floor((b - d) / 30.6001).toInt()
        val day = b - d - floor(30.6001 * e).toInt()
        val month = if (e < 14) e - 1 else e - 13
        val year = if (month > 2) c - 4716 else c - 4715
        return Triple(year, month, day)
    }

    private fun dayOfYear(y: Int, m: Int, d: Int): Int {
        val cum = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        return cum[m - 1] + d + if (leap && m > 2) 1 else 0
    }

    private fun sinDeg(deg: Double) = sin(Math.toRadians(deg))

    /**
     * 农历数据表，1900–2100，每年一个 17 位掩码：
     *  - bit 16      闰月是否 30 天
     *  - bit 15..4   正月到十二月是否 30 天（1 = 30 天）
     *  - bit 3..0    闰月月份（0 = 无闰月）
     */
    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0,
        0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4,
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0,
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160,
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252,
        0x0d520,
    )
}
