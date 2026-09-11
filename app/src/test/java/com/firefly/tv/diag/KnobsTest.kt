package com.firefly.tv.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旋钮表的自洽性测试。
 *
 * 这一层的价值不在「算法对不对」，而在**防止排查工具自己出岔子**：
 * 远程改旋钮时我看不到电视，一旦某个键名写错、某个取值写错、或者某一档预设
 * 悄悄漏了一项，表现都是「改了没反应」—— 而我会把它当成「这个变量无效」，
 * 于是得出一条完全错误的结论。所以把这几件事钉在单元测试里。
 */
class KnobsTest {

    @Test
    fun `键名不重复`() {
        val keys = Knobs.SPECS.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.contains('.') })
    }

    @Test
    fun `每个旋钮的默认值本身就是合法值`() {
        for (s in Knobs.SPECS) {
            if (s.def.isEmpty()) continue // 空 = 「跟随程序」，合法
            assertNotNull("${s.key} 的默认值 ${s.def} 不合法", Knobs.normalize(s, s.def))
        }
    }

    /**
     * 第 1 档预设必须是**全默认**。
     *
     * 它是「这套代码原本的行为」，也是排查里随时要能退回的基准 ——
     * 如果它偷偷带了某项覆盖，「切回默认」量到的就不是基线。
     */
    @Test
    fun `第一档预设是纯默认`() {
        assertTrue(Knobs.PRESETS.first().values.isEmpty())
    }

    @Test
    fun `预设里的每一项都必须存在且取值合法`() {
        for (p in Knobs.PRESETS) {
            for ((k, v) in p.values) {
                val spec = Knobs.specOf(k)
                assertNotNull("预设「${p.label}」引用了不存在的旋钮 $k", spec)
                assertNotNull("预设「${p.label}」里 $k=$v 不合法", Knobs.normalize(spec!!, v))
            }
        }
    }

    /** 每一档预设都必须**真的改到了什么**（空档会让「切换了但没变化」）。 */
    @Test
    fun `第二档之后的预设都不是空档`() {
        for (p in Knobs.PRESETS.drop(1)) {
            assertTrue("预设「${p.label}」没有任何取值", p.values.isNotEmpty())
        }
    }

    @Test
    fun `真值写法收敛成 on off`() {
        val boost = Knobs.specOf(Knobs.K_THREAD_BOOST)!!
        assertEquals("on", Knobs.normalize(boost, "1"))
        assertEquals("on", Knobs.normalize(boost, "true"))
        assertEquals("on", Knobs.normalize(boost, "开"))
        assertEquals("off", Knobs.normalize(boost, "0"))
        assertEquals("off", Knobs.normalize(boost, "FALSE"))
        assertNull(Knobs.normalize(boost, "也许"))
    }

    @Test
    fun `整数旋钮拒绝越界和乱输入`() {
        val drop = Knobs.specOf(Knobs.K_FRAMEDROP)!!
        assertEquals("0", Knobs.normalize(drop, "0"))
        assertEquals("120", Knobs.normalize(drop, " 120 "))
        assertNull(Knobs.normalize(drop, "-1"))
        assertNull(Knobs.normalize(drop, "121"))
        assertNull(Knobs.normalize(drop, "abc"))
    }

    /**
     * `max-buffer-size` 的上限必须是 15MB。
     *
     * ijkplayer 的 `MAX_QUEUE_SIZE` 就是 15MB，超过它 `av_opt_set_dict` 会整体失败，
     * **后面所有选项一起静默失效**（`ff_ffplay.c` 的选项批量应用）——
     * 那时候我改的其它旋钮全都不生效，却看不出任何报错。
     */
    @Test
    fun `缓冲上限不许超过 ijkplayer 的 15MB`() {
        val mb = Knobs.specOf(Knobs.K_MAX_BUFFER_MB)!!
        assertEquals(15, mb.max)
        assertNull(Knobs.normalize(mb, "16"))
    }

    @Test
    fun `留空的选项表示跟随程序默认`() {
        val pb = Knobs.specOf(Knobs.K_PACKET_BUFFERING)!!
        assertEquals(Knobs.DEFAULT, pb.def)
        assertEquals(Knobs.DEFAULT, Knobs.normalize(pb, ""))
    }
}
