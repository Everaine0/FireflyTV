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
        hud.onLibrarySwitch("电视剧")

        val s = hud.peek(t0)
        assertNotNull("切库必须立刻有东西上屏，否则看起来就是卡死", s)
        assertEquals(SwitchHud.Style.LOADING, s!!.style)
        assertEquals("电视剧", s.title)
        assertEquals("正在打开…", s.subtitle)
    }

    @Test
    fun `加载提示不会自己超时消失`() {
        val hud = SwitchHud(briefMs = 1_000L)
        hud.onLibrarySwitch("电视剧")
        // SMB 慢的时候可能几十秒；这段时间提示必须一直在
        assertNotNull(hud.peek(t0 + 60_000L))
    }

    @Test
    fun `内容出来后换成内容名并开始倒计时`() {
        val hud = SwitchHud(briefMs = 1_000L)
        hud.onLibrarySwitch("电视剧")
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
        hud.onLibrarySwitch("电视剧")
        assertNotNull(hud.peek(t0))

        hud.dismiss()
        assertNull(hud.peek(t0))
    }

    @Test
    fun `没有按键时 onPlaying 不会凭空造出提示`() {
        val hud = SwitchHud()
        hud.onPlaying("娘道", t0)
        assertNull("没按过键就不该弹东西出来", hud.peek(t0))
    }

    @Test
    fun `倒计时结束后再 peek 保持静默`() {
        val hud = SwitchHud(briefMs = 100L)
        hud.onContentSwitch("CCTV5", t0)
        assertNull(hud.peek(t0 + 100L))
        assertNull(hud.peek(t0 + 100_000L))
    }
}
