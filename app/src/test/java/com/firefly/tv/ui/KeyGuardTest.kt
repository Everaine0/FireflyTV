package com.firefly.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 400ms 方向键防抖的边界。
 *
 * 第一次按键必须立刻响应；窗口内的连按忽略；刚好到 400ms 放行；
 * 时钟回拨不能把用户永久锁住。
 */
class KeyGuardTest {

    private val t0 = 1_000_000L

    @Test
    fun `第一次立刻放行`() {
        assertTrue(KeyGuard().allow(t0))
    }

    @Test
    fun `防抖窗口内的连按被忽略`() {
        val guard = KeyGuard()
        assertTrue(guard.allow(t0))
        assertFalse(guard.allow(t0 + 1L))
        assertFalse(guard.allow(t0 + KeyGuard.WINDOW_MS - 1))
    }

    @Test
    fun `刚好到窗口边界放行`() {
        val guard = KeyGuard()
        assertTrue(guard.allow(t0))
        assertTrue(guard.allow(t0 + KeyGuard.WINDOW_MS))
    }

    @Test
    fun `隔久了再按放行`() {
        val guard = KeyGuard()
        assertTrue(guard.allow(t0))
        assertTrue(guard.allow(t0 + 5_000L))
    }

    @Test
    fun `时钟回拨不会把用户永久锁住`() {
        val guard = KeyGuard()
        assertTrue(guard.allow(t0))
        assertTrue(guard.allow(t0 - 1_000L))
    }
}
