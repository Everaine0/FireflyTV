package com.firefly.tv.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/proc` 解析的回归测试。
 *
 * 这一层必须用真实文本喂，因为它的坑全在**格式细节**上：
 *  - `comm` 字段带括号，而且**可能有空格**（`(a b)`），必须从最后一个 `)` 切开；
 *  - 字段下标是从 `state` 往后数的，差一格就会把 `nice` 读成 `priority`
 *    —— 数字照样有、看着挺像，但整个「线程被饿死了吗」的判断就全反了。
 *
 * 而这些在模拟器上是看不出来的（模拟器里所有线程 nice 都是 0）。
 */
class ProcStatsTest {

    /** 真实的一行（Pixel 风格内核；字段顺序见 `proc(5)`）。 */
    private val ffVout =
        "8077 (ff_vout) S 1 8077 0 0 -1 4194624 1234 0 0 0 512 384 0 0 20 0 12 0 123456 789012345 6789 " +
            "18446744073709551615 1 1 0 0 0 0 0 0 0 0 0 0 17 2 0 0 0 0 0"

    @Test
    fun `解析线程 stat 的 utime stime nice priority`() {
        val s = ProcStats.parseThreadStat(ffVout)
        assertNotNull(s)
        s!!
        assertEquals(8077, s.tid)
        assertEquals("ff_vout", s.name)
        assertEquals("S", s.state)
        // utime=512 stime=384 → 896 滴答 = 8.96 秒 CPU
        assertEquals(896L, s.ticks)
        assertEquals(20, s.priority)
        assertEquals(0, s.nice)
    }

    @Test
    fun `线程名里有空格和括号也不能错位`() {
        val raw = "42 (Binder_1 (x)) R 1 42 0 0 -1 0 0 0 0 0 100 200 0 0 20 -8 5 0 0 0"
        val s = ProcStats.parseThreadStat(raw)
        assertNotNull(s)
        assertEquals("Binder_1 (x)", s!!.name)
        assertEquals(300L, s.ticks)
        // nice 必须读成 -8（不是相邻的 priority 字段）
        assertEquals(-8, s.nice)
    }

    @Test
    fun `字段不够就返回 null 而不是瞎猜`() {
        assertNull(ProcStats.parseThreadStat(""))
        assertNull(ProcStats.parseThreadStat("1 (x) R 1 2 3"))
        assertNull(ProcStats.parseThreadStat("没有括号的一行"))
    }

    @Test
    fun `CPU 总量把 idle 和 iowait 都算空闲`() {
        val raw = """
            cpu  1000 20 300 8000 100 30 40 0 0 0
            cpu0 500 10 150 4000 50 15 20 0 0 0
            intr 12345
        """.trimIndent()
        val t = ProcStats.parseCpuTotal(raw)!!
        assertEquals(1000L + 20 + 300 + 8000 + 100 + 30 + 40, t.total)
        assertEquals(8000L + 100, t.idle)
    }

    @Test
    fun `在线核数支持区间和列表`() {
        assertEquals(4, ProcStats.parseOnlineCores("0-3"))
        assertEquals(2, ProcStats.parseOnlineCores("0,2"))
        assertEquals(1, ProcStats.parseOnlineCores("0"))
        assertEquals(0, ProcStats.parseOnlineCores(null))
    }

    @Test
    fun `温度毫度和摄氏度都要认`() {
        assertEquals(45f, ProcStats.parseTempC("45000")!!, 0.01f)
        assertEquals(45f, ProcStats.parseTempC("45")!!, 0.01f)
        assertNull(ProcStats.parseTempC(null))
        assertNull(ProcStats.parseTempC("n/a"))
    }

    @Test
    fun `meminfo 取一项`() {
        val raw = "MemTotal:        1800000 kB\nMemFree:          123456 kB\nMemAvailable:    900000 kB\n"
        assertEquals(123456L, ProcStats.parseMeminfoKb(raw, "MemFree"))
        assertEquals(1800000L, ProcStats.parseMeminfoKb(raw, "MemTotal"))
        assertNull(ProcStats.parseMeminfoKb(raw, "SwapTotal"))
    }

    @Test
    fun `提权表只认自己人`() {
        assertEquals(-8, ProcStats.boostFor("ff_vout", includeCodec = false))
        assertEquals(-16, ProcStats.boostFor("ff_aout_android", includeCodec = false))
        // 解码器自己的线程默认不动（它们由系统管，乱提权反而可能伤到别处）
        assertNull(ProcStats.boostFor("ACodec", includeCodec = false))
        assertEquals(-8, ProcStats.boostFor("ACodec", includeCodec = true))
        // 界面线程和未知线程一律不碰
        assertNull(ProcStats.boostFor("main", includeCodec = true))
        assertNull(ProcStats.boostFor("firefly-diag", includeCodec = true))
    }
}
