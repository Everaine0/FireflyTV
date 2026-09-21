package com.firefly.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直播断流判据的单测。
 *
 * 对应一次完整实测（模拟器 + 运营商 IPTV，2026-09-21）：把某一路直播的入向包全丢掉，
 * 画面立刻冻住，而**旧的判据（只看送显帧率）等了 3 分钟一次都没触发** —— 因为帧率停在
 * 冻住前的 25.00 上不动。用户的感受就是「卡住了，得重进软件」。
 *
 * 中间还试错过一版用「流量字节数」当判据的实现：那个量在 RTSP 通路上**恒为 0**
 * （探针实测：正常播 25 帧/秒时也是 `流量=0B`），于是看门狗一声不吭。
 * 下面 `恒为 0 的流量计数骗过了一版实现` 一条就是给这个坑立的碑。
 *
 * 所以这里既要钉住「真断流必须判出来」，也要钉住「别把正常的播放/暂停判成断流」——
 * 后者代价更大：会把正在看的画面打断重来。
 */
class LiveStallTest {

    // 实测现场：画面冻住后，送显帧率**停在** 25.00 不动
    private val frozenFps = 25.0f

    @Test
    fun `断流：播放器持续自报缓冲，判卡死`() {
        assertTrue(
            LiveStall.strike(
                playing = true,
                outputFps = frozenFps,
                bufferingMs = LiveStall.BUFFERING_MS,
            ),
        )
    }

    @Test
    fun `这就是旧判据漏掉的那一格：帧率还很高，但播放器已经在等数据了`() {
        // 旧判据 `outputFps <= 0.5` 在这里为 false —— 于是它每一轮都清零计数，永不触发。
        assertFalse(frozenFps <= LiveStall.MIN_FPS)
        // 新判据必须靠「持续缓冲」把它抓住。
        assertTrue(
            LiveStall.strike(
                playing = true,
                outputFps = frozenFps,
                bufferingMs = 60_000L,
            ),
        )
    }

    @Test
    fun `恒为 0 的流量计数骗过了一版实现：判据里不能再依赖那个量`() {
        // 探针实测：RTSP 正常播放时 `trafficStatisticByteCount` 也是 0，
        // 所以「流量没涨」这件事在直播上没有任何信息量。
        // 现在判据只认播放器自报的缓冲时长 —— 这条用例锁住"不再引入流量条件"。
        val stalled = LiveStall.strike(playing = true, outputFps = frozenFps, bufferingMs = 30_000L)
        val healthy = LiveStall.strike(playing = true, outputFps = frozenFps, bufferingMs = 0L)
        assertTrue(stalled)
        assertFalse(healthy)
    }

    @Test
    fun `正常播放不判卡死`() {
        assertFalse(
            LiveStall.strike(
                playing = true,
                outputFps = 25.0f,
                bufferingMs = 0L,
            ),
        )
    }

    @Test
    fun `起播瞬间的缓冲不算：首帧之后会被清零，真断流要等满门槛`() {
        // fireFirstFrame() 会把缓冲时刻清零；这里钉住"差一点不算"。
        assertFalse(
            LiveStall.strike(
                playing = true,
                outputFps = frozenFps,
                bufferingMs = LiveStall.BUFFERING_MS - 1,
            ),
        )
    }

    @Test
    fun `用户按暂停不算断流：暂停时不会报缓冲`() {
        assertFalse(
            LiveStall.strike(
                playing = false,
                outputFps = 0f,
                bufferingMs = 0L,
            ),
        )
    }

    @Test
    fun `辅助判据仍然有效：播放器说在播却一帧都不出`() {
        // 真机上「解码器卡住」是这种：没有缓冲，但一帧都不送显。
        assertTrue(
            LiveStall.strike(
                playing = true,
                outputFps = 0f,
                bufferingMs = 0L,
            ),
        )
        // 但暂停时不能算（playing = false）—— 暂停时帧率本来就是 0。
        assertFalse(
            LiveStall.strike(
                playing = false,
                outputFps = 0f,
                bufferingMs = 0L,
            ),
        )
    }

    @Test
    fun `门槛取值：10 秒缓冲 + 5 秒巡检 × 3 次，最坏约 25 秒内重连`() {
        // 这三个数共同决定「用户要等多久」，所以钉成一个显式契约（见 LiveStall.WORST_CASE_MS）。
        assertEquals(25_000L, LiveStall.WORST_CASE_MS)
        assertTrue("最坏情况 ${LiveStall.WORST_CASE_MS}ms 太久了", LiveStall.WORST_CASE_MS <= 25_000L)
        // 门槛至少跨两个巡检周期：一次抖动不该被判死。
        assertTrue(LiveStall.BUFFERING_MS >= LiveStall.RECHECK_MS * 2)
    }
}
