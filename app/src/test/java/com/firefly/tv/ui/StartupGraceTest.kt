package com.firefly.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「起播失败先别急着说无法播放」。
 *
 * 依据是用户实测：「偶尔打开会显示这个视频无法播放，等一段时间正常」，
 * 现场是 NAS 硬盘休眠 —— 第一次读要等盘转起来，SMB 的读请求 20 秒超时，
 * ijkplayer 把它报成一次播放错误（`onError`）。原来的代码立刻弹「无法播放」，等于冤枉片源。
 *
 * 这几条判据错了都不会报错，只会表现成「电视有时好有时坏」，
 * 事后没法复盘 —— 所以用测试钉死。
 */
class StartupGraceTest {

    @Test
    fun `没出过画面的失败先重试`() {
        assertTrue(
            "刚起播就失败（硬盘还在转起来）：该等，不该报故障",
            StartupGrace.shouldRetry(rendered = false, attempts = 0, elapsedMs = 0L),
        )
    }

    @Test
    fun `出过画面就不再宽限`() {
        // 已经在放的片子中途断了（SMB 掉线、解码器报错），用户需要立刻知道原因，
        // 而不是对着黑屏再等一分钟
        assertFalse(StartupGrace.shouldRetry(rendered = true, attempts = 0, elapsedMs = 0L))
        assertFalse(StartupGrace.shouldRetry(rendered = true, attempts = 3, elapsedMs = 1L))
    }

    @Test
    fun `重试次数用完就报故障`() {
        assertFalse(
            "已经试满 MAX_ATTEMPTS 次，不能无限重试",
            StartupGrace.shouldRetry(rendered = false, attempts = StartupGrace.MAX_ATTEMPTS, elapsedMs = 1L),
        )
        assertTrue(
            "还差一次时仍可重试",
            StartupGrace.shouldRetry(rendered = false, attempts = StartupGrace.MAX_ATTEMPTS - 1, elapsedMs = 1L),
        )
    }

    @Test
    fun `超过宽限窗口就报故障`() {
        // 「超过某个时间你再显示这个」—— 用户要的那条时限
        assertTrue(StartupGrace.shouldRetry(rendered = false, attempts = 0, elapsedMs = StartupGrace.WINDOW_MS - 1))
        assertFalse(StartupGrace.shouldRetry(rendered = false, attempts = 0, elapsedMs = StartupGrace.WINDOW_MS))
    }

    @Test
    fun `宽限窗口至少容得下一次SMB读超时`() {
        // 读超时是 20 秒一刀（SmbClient 的 SmbConfig.withTimeout）。
        // 窗口要是比它短，第一次超时就直接报故障了，这个宽限等于没做。
        assertTrue(
            "窗口必须大于 20 秒的读超时，否则等不到第二次机会",
            StartupGrace.WINDOW_MS > 20_000L,
        )
    }

    @Test
    fun `重试退避越来越久并且有上限`() {
        var prev = 0L
        for (attempt in 1..StartupGrace.MAX_ATTEMPTS) {
            val backoff = StartupGrace.backoffMs(attempt)
            assertTrue("第 $attempt 次重试的等待必须比上一次久", backoff > prev)
            assertTrue("第 $attempt 次重试不能等超过 20 秒", backoff <= 20_000L)
            prev = backoff
        }
        // 越界的入参不能抛异常（计数是外部传进来的）
        assertEquals(StartupGrace.backoffMs(0), StartupGrace.backoffMs(1))
        assertEquals(
            StartupGrace.backoffMs(StartupGrace.MAX_ATTEMPTS),
            StartupGrace.backoffMs(StartupGrace.MAX_ATTEMPTS + 5),
        )
    }
}
