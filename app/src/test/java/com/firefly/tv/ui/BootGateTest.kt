package com.firefly.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 开机页的退场边界。
 *
 * 这里错了不会崩，只会让用户「教程没看完就闪走」或者「错误原因被挡住」，
 * 事后很难复现，所以每条边界都用测试钉住。
 */
class BootGateTest {

    private val t0 = 1_000_000L

    @Test
    fun `刚开始什么都不做`() {
        assertNull(BootGate.exitReason(t0, t0, firstFrame = false, faultVisible = false))
    }

    @Test
    fun `首帧来得早也要等够最短展示`() {
        // 1 秒就出画面：教程还没轮完，继续留到 5 秒
        assertNull(BootGate.exitReason(t0 + 1_000L, t0, firstFrame = true, faultVisible = false))
        assertNull(BootGate.exitReason(t0 + BootGate.MIN_SHOW_MS - 1, t0, firstFrame = true, faultVisible = false))
    }

    @Test
    fun `首帧且待够最短时间就退场`() {
        assertEquals(
            BootGate.Exit.READY,
            BootGate.exitReason(t0 + BootGate.MIN_SHOW_MS, t0, firstFrame = true, faultVisible = false),
        )
    }

    @Test
    fun `故障页要说话时立刻退场`() {
        // 1 秒就报错：不能把「连不上 NAS」盖在「正在唤醒硬盘」后面
        assertEquals(
            BootGate.Exit.FAULT,
            BootGate.exitReason(t0 + 1_000L, t0, firstFrame = false, faultVisible = true),
        )
    }

    @Test
    fun `一直没画面也会到上限退场`() {
        assertNull(BootGate.exitReason(t0 + BootGate.MAX_SHOW_MS - 1, t0, firstFrame = false, faultVisible = false))
        assertEquals(
            BootGate.Exit.TIMEOUT,
            BootGate.exitReason(t0 + BootGate.MAX_SHOW_MS, t0, firstFrame = false, faultVisible = false),
        )
    }

    @Test
    fun `首帧已到但超过上限也按 READY 退场`() {
        // 极端情况：首帧来了，但页面因为主线程卡顿一直没退。到 90 秒上限时
        // 必须仍按 READY 收（onBootExit 会恢复播放），不能返回 TIMEOUT 把画面留在暂停态。
        assertEquals(
            BootGate.Exit.READY,
            BootGate.exitReason(t0 + BootGate.MAX_SHOW_MS, t0, firstFrame = true, faultVisible = false),
        )
    }

    @Test
    fun `时间倒流也不会算出负数`() {
        assertEquals(0L, BootGate.elapsedMs(t0 - 5_000L, t0))
        assertEquals(BootGate.MIN_SHOW_MS, BootGate.remainingMs(t0 - 5_000L, t0))
    }

    @Test
    fun `还没真正画出来时计时起点就是现在`() {
        // 冷启动时 onCreate 里就 show() 了，但窗口可能几秒后才出画；
        // 这之前不能开始算最短展示，否则用户看到的动画会短一大截（实测「2 秒就没了」）。
        assertEquals(t0, BootGate.visibleAt(drawnAt = 0L, now = t0))
    }

    @Test
    fun `画出来之后从首次绘制那一刻计时`() {
        val drawnAt = t0 + 3_000L
        assertEquals(drawnAt, BootGate.visibleAt(drawnAt, now = t0 + 3_500L))
        assertEquals(500L, BootGate.elapsedMs(t0 + 3_500L, BootGate.visibleAt(drawnAt, now = t0 + 3_500L)))
    }

    @Test
    fun `会自动重试的故障先留在开机页上说明原因`() {
        // 前两次：把原因写进开机页状态行，不顶掉页面
        assertEquals(false, BootGate.yieldsToFault(retryable = true, transientFaults = 1))
        assertEquals(false, BootGate.yieldsToFault(retryable = true, transientFaults = BootGate.MAX_TRANSIENT_FAULTS))
    }

    @Test
    fun `可重试故障连续挡满次数后让位给故障页`() {
        assertEquals(true, BootGate.yieldsToFault(retryable = true, transientFaults = BootGate.MAX_TRANSIENT_FAULTS + 1))
    }

    @Test
    fun `不会重试的故障立刻让位`() {
        // 片源损坏这类失败重试也没用，原因必须马上可见
        assertEquals(true, BootGate.yieldsToFault(retryable = false, transientFaults = 1))
    }
}
