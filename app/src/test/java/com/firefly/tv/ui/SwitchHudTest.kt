package com.firefly.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「按了键屏幕上必须有反应」。
 *
 * 用户实测：点左键以后页面还是 IPTV，也不知道键到底生效没有，只能再按 OK 打开浮层去确认。
 * 所以这里的规则是硬性的 —— 换库那一刻就必须有加载提示，且在内容出来之前不许消失。
 */
class SwitchHudTest {

    private val t0 = 1_000_000L

    @Test
    fun `一开始什么都不显示`() {
        assertNull(SwitchHud().peek(t0))
    }

    @Test
    fun `换库立刻显示正在打开`() {
        val hud = SwitchHud()
        hud.onLibrarySwitch("电视剧", t0)

        val s = hud.peek(t0)
        assertNotNull("切库必须立刻有东西上屏，否则看起来就是卡死", s)
        assertEquals(SwitchHud.Style.LOADING, s!!.style)
        assertEquals("电视剧", s.title)
        assertEquals("正在打开…", s.subtitle)
    }

    /**
     * 这条原来是「加载提示**不会**自己超时消失」（`deadline = 0L`，永不超时）。
     *
     * 那个设计就是「刚开机卡在加载不动」的成因之一：提示没有出口，后面那条链子一旦断在
     * 半路，屏幕上就永远挂着这句话，用户只能重开应用。现在改成"很宽但有限"：
     * SMB 慢的几十秒照样留着，但真出事了要让位给故障页。
     */
    @Test
    fun `加载提示留得够久但不会永远挂着`() {
        val hud = SwitchHud(briefMs = 1_000L)
        hud.onLibrarySwitch("电视剧", t0)
        // SMB 慢的时候可能几十秒；这段时间提示必须一直在
        assertNotNull("等了 30 秒还在加载，提示不能提前消失", hud.peek(t0 + 30_000L))
        assertNotNull("刚好到期限时还在", hud.peek(t0 + SwitchHud.LOADING_MS - 1))
        assertNull("超过期限必须让位给故障页，不能永远挂着", hud.peek(t0 + SwitchHud.LOADING_MS))
    }

    @Test
    fun `内容出来后换成内容名并开始倒计时`() {
        val hud = SwitchHud(briefMs = 1_000L)
        hud.onLibrarySwitch("电视剧", t0)
        hud.onPlaying("娘道", t0)

        val s = hud.peek(t0 + 10L)!!
        assertEquals(SwitchHud.Style.BRIEF, s.style)
        assertEquals("娘道", s.title)
        assertEquals("", s.subtitle)

        assertNotNull("倒计时没到就还得显示", hud.peek(t0 + 999L))
        assertNull("到点必须自己消失", hud.peek(t0 + 1_000L))
    }

    @Test
    fun `换剧换台直接出名字`() {
        val hud = SwitchHud(briefMs = 1_000L)
        hud.onContentSwitch("CCTV5", t0)

        val s = hud.peek(t0)!!
        assertEquals(SwitchHud.Style.BRIEF, s.style)
        assertEquals("CCTV5", s.title)
        assertNull(hud.peek(t0 + 1_000L))
    }

    @Test
    fun `连按不会让旧的倒计时把新的提示吃掉`() {
        val hud = SwitchHud(briefMs = 1_000L)
        hud.onContentSwitch("CCTV1", t0)
        // 500ms 后用户又按了一次
        hud.onContentSwitch("CCTV5", t0 + 500L)

        // 第一次的 1000ms 到点了，但第二次的还没到
        val s = hud.peek(t0 + 1_000L)
        assertNotNull("第二次按键的提示不能被第一次的倒计时带走", s)
        assertEquals("CCTV5", s!!.title)
        assertNull(hud.peek(t0 + 1_500L))
    }

    @Test
    fun `出错时立刻收起让故障页独占屏幕`() {
        val hud = SwitchHud()
        hud.onLibrarySwitch("电视剧", t0)
        assertNotNull(hud.peek(t0))

        hud.dismiss()
        assertNull(hud.peek(t0))
    }

    /**
     * 加载提示到期限自己退场之后，内容才姗姗来迟 —— 这时**仍然要出一下名字**。
     *
     * 换库/换台真的成功了，屏幕上却一点确认都没有，用户会以为键没生效。
     */
    @Test
    fun `加载提示过期后内容才到也要报出名字`() {
        val hud = SwitchHud(briefMs = 1_000L)
        hud.onLibrarySwitch("电视剧", t0)
        assertNull(hud.peek(t0 + SwitchHud.LOADING_MS))

        hud.onPlaying("娘道", t0 + SwitchHud.LOADING_MS + 1)
        val s = hud.peek(t0 + SwitchHud.LOADING_MS + 2)
        assertNotNull("内容起来了就该出名字，哪怕加载条已经过期退场", s)
        assertEquals(SwitchHud.Style.BRIEF, s!!.style)
        assertEquals("娘道", s.title)
    }

    @Test
    fun `没有按键时 onPlaying 不会凭空造出提示`() {
        val hud = SwitchHud()
        hud.onPlaying("娘道", t0)
        assertNull("没按过键就不该弹东西出来", hud.peek(t0))
    }

    /**
     * 冷启动还没定位到库名时也要有东西在屏幕上。
     *
     * `startPlayback()` 先清故障页、再去列库，而"是哪个库"要列完才知道 ——
     * 这一段（没有缓存时尤其明显）以前是一片黑。
     */
    @Test
    fun `恢复播放时先出一句正在连接`() {
        val hud = SwitchHud()
        hud.onRestoring(t0)

        val s = hud.peek(t0)!!
        assertEquals(SwitchHud.Style.LOADING, s.style)
        assertEquals(SwitchHud.RESTORING, s.title)

        // 定位到库之后会被换库提示顶掉
        hud.onLibrarySwitch("电视剧", t0 + 10L)
        assertEquals("电视剧", hud.peek(t0 + 11L)!!.title)
    }

    @Test
    fun `倒计时结束后再 peek 保持静默`() {
        val hud = SwitchHud(briefMs = 100L)
        hud.onContentSwitch("CCTV5", t0)
        assertNull(hud.peek(t0 + 100L))
        assertNull(hud.peek(t0 + 100_000L))
    }
}
