package com.firefly.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕缩放公式的回归测试。
 *
 * 这一条是用户实机反馈「按下 OK 只有中心那一块有内容」的修复点，
 * 而它**在 1080p 模拟器上永远测不出来**（缩放恰好是 1.0）——
 * 所以必须用纯函数 + 单元测试把各种分辨率/density 组合钉住。
 */
class UiScaleTest {

    private fun scale(w: Int, h: Int, dpi: Int) = UiScale.of(w, h, dpi)

    @Test
    fun `正常 1080p 电视不缩放`() {
        assertEquals(1f, scale(1920, 1080, 320), 0.001f)
    }

    @Test
    fun `正常 720p 电视不缩放`() {
        // 1280 / (6 × 213) = 1.0016 —— tvdpi 的取整误差，允许 1% 偏差
        assertEquals(1f, scale(1280, 720, 213), 0.01f)
    }

    @Test
    fun `自洽的 4K 界面不缩放`() {
        // 3840×2160 + densityDpi 640：系统已经按 4K 解释 sp，再放大就会大两倍
        assertEquals(1f, scale(3840, 2160, 640), 0.001f)
    }

    @Test
    fun `4K 逻辑分辨率配 1080p 的 density 要放大两倍`() {
        // 这是长虹那台电视最可疑的组合，也是「内容缩在中间」的根因：
        // 64sp 只画 128px，在 2160 高的屏上占 5.9%（1080p 上应是 11.9%）
        assertEquals(2f, scale(3840, 2160, 320), 0.001f)
    }

    @Test
    fun `21比9 的超宽屏按高度折算，不按宽度`() {
        // 2560×1080：短边仍是 1080 的等价宽度 1920，所以不缩放
        assertEquals(1f, scale(2560, 1080, 320), 0.001f)
    }

    @Test
    fun `尺寸或 density 不可用时退回 1`() {
        assertEquals(1f, scale(0, 0, 320), 0.001f)
        assertEquals(1f, scale(1920, 1080, 0), 0.001f)
    }

    @Test
    fun `极端值不会算出离谱的缩放`() {
        // 8K + 320dpi 理论上是 4，允许；但不能出现 0 或负数
        val s = scale(7680, 4320, 320)
        assertEquals(4f, s, 0.001f)
        assertTrue(scale(320, 240, 320) > 0f)
    }

    @Test
    fun `缩放后占屏比例一致`() {
        // 设计值 64sp 在两种机器上都占屏高约 11.9%（320dpi 的 density 是 2.0）
        val fhd = 64f * 2f * scale(1920, 1080, 320)
        val uhd = 64f * 2f * scale(3840, 2160, 320)
        assertEquals(fhd / 1080f, uhd / 2160f, 0.001f)
    }
}
