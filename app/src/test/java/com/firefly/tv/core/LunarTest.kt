package com.firefly.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * 农历表与节气算法的回归测试。
 *
 * 期望值来自公开万年历。改农历表或改节气算法时**必须先跑这个**：
 * 表里一个十六进制位写错，就会让整年偏好几天，而且肉眼看日历根本发现不了。
 */
class LunarTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun infoOf(y: Int, m: Int, d: Int): Lunar.Info {
        val gc = GregorianCalendar(zone).apply {
            clear()
            set(y, m - 1, d, 12, 0, 0)
        }
        return Lunar.of(gc.time, zone)
    }

    /**
     * 1984–2043 年春节（农历正月初一）公历日期。
     * 60 个锚点足以覆盖整张农历表的累计天数，任何一位写错都会在这里暴露。
     */
    private val springFestivals = mapOf(
        1984 to intArrayOf(2, 2), 1985 to intArrayOf(2, 20), 1986 to intArrayOf(2, 9),
        1987 to intArrayOf(1, 29), 1988 to intArrayOf(2, 17), 1989 to intArrayOf(2, 6),
        1990 to intArrayOf(1, 27), 1991 to intArrayOf(2, 15), 1992 to intArrayOf(2, 4),
        1993 to intArrayOf(1, 23), 1994 to intArrayOf(2, 10), 1995 to intArrayOf(1, 31),
        1996 to intArrayOf(2, 19), 1997 to intArrayOf(2, 7), 1998 to intArrayOf(1, 28),
        1999 to intArrayOf(2, 16), 2000 to intArrayOf(2, 5), 2001 to intArrayOf(1, 24),
        2002 to intArrayOf(2, 12), 2003 to intArrayOf(2, 1), 2004 to intArrayOf(1, 22),
        2005 to intArrayOf(2, 9), 2006 to intArrayOf(1, 29), 2007 to intArrayOf(2, 18),
        2008 to intArrayOf(2, 7), 2009 to intArrayOf(1, 26), 2010 to intArrayOf(2, 14),
        2011 to intArrayOf(2, 3), 2012 to intArrayOf(1, 23), 2013 to intArrayOf(2, 10),
        2014 to intArrayOf(1, 31), 2015 to intArrayOf(2, 19), 2016 to intArrayOf(2, 8),
        2017 to intArrayOf(1, 28), 2018 to intArrayOf(2, 16), 2019 to intArrayOf(2, 5),
        2020 to intArrayOf(1, 25), 2021 to intArrayOf(2, 12), 2022 to intArrayOf(2, 1),
        2023 to intArrayOf(1, 22), 2024 to intArrayOf(2, 10), 2025 to intArrayOf(1, 29),
        2026 to intArrayOf(2, 17), 2027 to intArrayOf(2, 6), 2028 to intArrayOf(1, 26),
        2029 to intArrayOf(2, 13), 2030 to intArrayOf(2, 3), 2031 to intArrayOf(1, 23),
        2032 to intArrayOf(2, 11), 2033 to intArrayOf(1, 31), 2034 to intArrayOf(2, 19),
        2035 to intArrayOf(2, 8), 2036 to intArrayOf(1, 28), 2037 to intArrayOf(2, 15),
        2038 to intArrayOf(2, 4), 2039 to intArrayOf(1, 24), 2040 to intArrayOf(2, 12),
        2041 to intArrayOf(2, 1), 2042 to intArrayOf(1, 22), 2043 to intArrayOf(2, 10),
    )

    @Test
    fun `换算出的月日始终在合法范围内`() {
        // 表里任何一位写错都可能让月变成 0 或 13、日变成 0 或 31，
        // 那样 MONTHS/DAYS 就会被读穿（旧实现会静默返回空串）。这里逐日扫 20 年。
        val bad = ArrayList<String>()
        val gc = GregorianCalendar(zone).apply {
            clear()
            set(2020, 0, 1, 12, 0, 0)
        }
        val end = GregorianCalendar(zone).apply {
            clear()
            set(2040, 0, 1, 12, 0, 0)
        }.timeInMillis
        while (gc.timeInMillis < end) {
            val text = Lunar.debugDate(gc.time, zone)
            val month = Regex("month=(-?\\d+)").find(text)!!.groupValues[1].toInt()
            val day = Regex("day=(-?\\d+)").find(text)!!.groupValues[1].toInt()
            if (month !in 1..12 || day !in 1..30) {
                bad += "${gc.get(Calendar.YEAR)}-${gc.get(Calendar.MONTH) + 1}-${gc.get(Calendar.DAY_OF_MONTH)} -> $text"
            }
            gc.add(Calendar.DAY_OF_MONTH, 1)
        }
        assertEquals("月日越界：\n" + bad.take(20).joinToString("\n"), 0, bad.size)
    }

    @Test
    fun `天数换算与 Java 标准库一致`() {
        // 农历表基准日是 1900-01-31
        for (date in listOf(
            intArrayOf(1900, 1, 31),
            intArrayOf(1900, 2, 19),
            intArrayOf(1984, 2, 2),
            intArrayOf(2025, 1, 29),
            intArrayOf(2026, 2, 17),
            intArrayOf(2100, 12, 31),
        )) {
            val (y, m, d) = date
            val expected = java.time.LocalDate.of(y, m, d).toEpochDay() -
                java.time.LocalDate.of(1900, 1, 31).toEpochDay()
            assertEquals("$y-$m-$d", expected, Lunar.debugOffset(y, m, d))
        }
    }

    @Test
    fun `每个春节都落在正月初一`() {
        val wrong = ArrayList<String>()
        for ((year, md) in springFestivals) {
            val actual = infoOf(year, md[0], md[1]).lunarShort
            // 初一只显示月份，不显示日子
            if (actual != "正月") {
                wrong += "$year-${md[0]}-${md[1]} 期望 正月 实际 $actual"
            }
        }
        assertEquals("春节日期对不上：\n" + wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test
    fun `春节前一天是上一年的腊月最后一天`() {
        val wrong = ArrayList<String>()
        for ((year, md) in springFestivals) {
            val gc = GregorianCalendar(zone).apply {
                clear()
                set(year, md[0] - 1, md[1], 12, 0, 0)
            }
            gc.add(java.util.Calendar.DAY_OF_MONTH, -1)
            val prev = Lunar.of(gc.time, zone).lunarShort
            // 腊月只有 29 或 30 天；非初一不显示月份，所以这里是纯日子
            if (prev != "廿九" && prev != "三十") {
                wrong += "$year 春节前一天 实际 $prev"
            }
        }
        assertEquals("春节前一天不是腊月末：\n" + wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test
    fun `中秋是八月十五`() {
        assertEquals("八月十五", infoOf(2026, 9, 25).lunarFull)
        assertEquals("八月十五", infoOf(2025, 10, 6).lunarFull)
        // 短写法：初一只写月份
        assertEquals("八月", infoOf(2026, 9, 11).lunarShort)
        // 短写法：十五只写日子
        assertEquals("十五", infoOf(2026, 9, 25).lunarShort)
    }

    @Test
    fun `闰月能识别`() {
        // 2025 年闰六月，闰六月初一是 2025-07-25
        assertEquals("闰六月初一", infoOf(2025, 7, 25).lunarFull)
        assertEquals("闰六月初九", infoOf(2025, 8, 2).lunarFull)
        assertEquals("闰六月初十", infoOf(2025, 8, 3).lunarFull)
    }

    @Test
    fun `普通日子只显示日`() {
        assertEquals("初五", infoOf(2026, 2, 21).lunarShort)
    }

    @Test
    fun `节日能被标出`() {
        assertEquals("春节", infoOf(2026, 2, 17).festival)
        assertEquals("元宵", infoOf(2026, 3, 3).festival)
        assertEquals("中秋", infoOf(2026, 9, 25).festival)
        assertEquals("元旦", infoOf(2026, 1, 1).festival)
        assertEquals("国庆节", infoOf(2026, 10, 1).festival)
        assertNull(infoOf(2026, 3, 20).festival)
    }

    @Test
    fun `节气落在正确的日子`() {
        // 期望值对照公开万年历
        assertEquals("立春", infoOf(2026, 2, 4).term)
        assertEquals("清明", infoOf(2026, 4, 5).term)
        assertEquals("谷雨", infoOf(2026, 4, 20).term)
        assertEquals("夏至", infoOf(2026, 6, 21).term)
        assertEquals("立秋", infoOf(2026, 8, 7).term)
        assertEquals("冬至", infoOf(2026, 12, 22).term)
        // 非节气日必须没有节气名
        assertNull(infoOf(2026, 4, 10).term)
        assertNull(infoOf(2026, 2, 14).term)
    }

    @Test
    fun `每个节气都在合理的公历日期区间内`() {
        // 24 节气在各月的正常日期范围（公历）
        val ranges = arrayOf(
            1 to 4..7, 1 to 19..22, // 小寒 大寒
            2 to 3..5, 2 to 18..20, // 立春 雨水
            3 to 4..7, 3 to 19..22, // 惊蛰 春分
            4 to 4..6, 4 to 19..21, // 清明 谷雨
            5 to 4..7, 5 to 20..22, // 立夏 小满
            6 to 4..7, 6 to 20..22, // 芒种 夏至
            7 to 6..8, 7 to 22..24, // 小暑 大暑
            8 to 6..9, 8 to 22..24, // 立秋 处暑
            9 to 6..9, 9 to 22..24, // 白露 秋分
            10 to 7..9, 10 to 22..24, // 寒露 霜降
            11 to 6..8, 11 to 21..23, // 立冬 小雪
            12 to 6..8, 12 to 20..23, // 大雪 冬至
        )
        val wrong = ArrayList<String>()
        for (year in intArrayOf(2024, 2025, 2026, 2027)) {
            for ((index, range) in ranges.withIndex()) {
                val (month, days) = range
                val hits = days.filter { infoOf(year, month, it).term != null }
                if (hits.size != 1) {
                    wrong += "$year 第${index + 1}个节气 在 $month 月落在 $hits"
                }
            }
        }
        assertEquals("节气日期超出正常范围：\n" + wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test
    fun `星期正确`() {
        // 2026-02-17 是星期二
        assertEquals("星期二", infoOf(2026, 2, 17).weekday)
    }
}

