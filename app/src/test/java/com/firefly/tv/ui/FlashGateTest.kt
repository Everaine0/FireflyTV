package com.firefly.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 换库过渡页的退场边界。
 *
 * 这里错了不会崩，只会表现成“大字还没看清就闪走”“NAS 慢时页面一直挂着”
 * 或“故障原因被正在切换盖住”，事后很难复现，所以每条边界都用测试钉住。
 */
class FlashGateTest {

    private val t0 = 1_000_000L

    @Test
    fun `刚开始什么都不做`() {
        val gate = FlashGate()
        gate.show(t0)
        assertNull(gate.exitReason(t0, contentStarted = false, faultVisible = false))
    }

    @Test
    fun `首帧来得早也要等够最短展示`() {
        val gate = FlashGate()
        gate.show(t0)
        assertNull(gate.exitReason(t0 + 100L, contentStarted = true, faultVisible = false))
        assertNull(gate.exitReason(t0 + FlashGate.MIN_SHOW_MS - 1, contentStarted = true, faultVisible = false))
    }

    @Test
    fun `首帧且待够最短时间就退场`() {
        val gate = FlashGate()
        gate.show(t0)
        assertEquals(
            FlashGate.Exit.READY,
            gate.exitReason(t0 + FlashGate.MIN_SHOW_MS, contentStarted = true, faultVisible = false),
        )
    }

    @Test
    fun `故障页要说话时立刻退场`() {
        val gate = FlashGate()
        gate.show(t0)
        assertEquals(
            FlashGate.Exit.FAULT,
            gate.exitReason(t0 + 100L, contentStarted = false, faultVisible = true),
        )
    }

    @Test
    fun `一直没首帧也会到上限退场`() {
        val gate = FlashGate()
        gate.show(t0)
        assertNull(gate.exitReason(t0 + FlashGate.MAX_SHOW_MS - 1, contentStarted = false, faultVisible = false))
        assertEquals(
            FlashGate.Exit.TIMEOUT,
            gate.exitReason(t0 + FlashGate.MAX_SHOW_MS, contentStarted = false, faultVisible = false),
        )
    }

    @Test
    fun `用户主动换目标重置十五秒预算`() {
        val gate = FlashGate()
        gate.show(t0)
        // 第 10 秒用户又按了一个库：重新享有完整 15 秒
        gate.retarget(t0 + 10_000L)
        assertNull(gate.exitReason(t0 + 20_000L, contentStarted = false, faultVisible = false))
        assertEquals(
            FlashGate.Exit.TIMEOUT,
            gate.exitReason(t0 + 25_000L, contentStarted = false, faultVisible = false),
        )
    }

    @Test
    fun `内部跳过空库不重置十五秒预算`() {
        val gate = FlashGate()
        gate.show(t0)
        // 模拟内部自动跳过：只换文字，不调用 retarget。到原定的 15 秒仍然超时。
        assertEquals(
            FlashGate.Exit.TIMEOUT,
            gate.exitReason(t0 + FlashGate.MAX_SHOW_MS, contentStarted = false, faultVisible = false),
        )
    }

    @Test
    fun `时间倒流也不会算出负数`() {
        val gate = FlashGate()
        gate.show(t0)
        assertEquals(0L, gate.elapsedMs(t0 - 5_000L))
        assertEquals(FlashGate.MIN_SHOW_MS, gate.remainingMs(t0 - 5_000L))
    }
}
