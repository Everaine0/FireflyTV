package com.firefly.tv.player

import com.firefly.tv.media.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「直播不许有进度」这条规则。
 *
 * 这是用户实测到的「IPTV 起播音画不同步」的根：直播的 currentPosition 是从频道开播
 * 算起的毫秒数，被当成续播进度写盘以后，下次起播就拿它去 seek。直播流没有可 seek
 * 的时间轴，结果就是音画长时间不同步。规则必须能用测试钉死。
 */
class PlaybackModeTest {

    private val video = Library.Video("电视剧")
    private val live = Library.Live("IPTV", "央视.m3u")

    @Test
    fun `库类型决定播放模式`() {
        assertEquals(PlaybackMode.Kind.ON_DEMAND, PlaybackMode.of(video))
        assertEquals(PlaybackMode.Kind.LIVE, PlaybackMode.of(live))
    }

    @Test
    fun `库还不知道时按直播处理`() {
        // 宁可不续播，也不要 seek 出一条不同步的流
        assertEquals(PlaybackMode.Kind.LIVE, PlaybackMode.of(null))
    }

    @Test
    fun `只有点播才配拥有进度`() {
        assertTrue(PlaybackMode.remembersPosition(PlaybackMode.Kind.ON_DEMAND))
        assertFalse(PlaybackMode.remembersPosition(PlaybackMode.Kind.LIVE))
    }

    @Test
    fun `直播永远从零开始不管外面传了什么`() {
        // 这就是那个 bug：以前会把 7200000（两小时）原样透传给 seekTo
        assertEquals(0L, PlaybackMode.startPositionMs(PlaybackMode.Kind.LIVE, 7_200_000L))
        assertEquals(0L, PlaybackMode.startPositionMs(PlaybackMode.Kind.LIVE, -1L))
    }

    @Test
    fun `点播保留请求的进度`() {
        assertEquals(7_200_000L, PlaybackMode.startPositionMs(PlaybackMode.Kind.ON_DEMAND, 7_200_000L))
        // 负数是脏数据，夹到 0
        assertEquals(0L, PlaybackMode.startPositionMs(PlaybackMode.Kind.ON_DEMAND, -5L))
    }

    @Test
    fun `直播与点播用不同的缓冲参数`() {
        val t = PlaybackMode.tuning(PlaybackMode.Kind.LIVE)
        val v = PlaybackMode.tuning(PlaybackMode.Kind.ON_DEMAND)

        // 直播要开包缓冲：完全关掉时视频钟会先跑，音频还在等 AudioTrack 起播，
        // 起播那几秒就是音画不同步。开一个小缓冲把两者起点对齐。
        assertTrue(t.packetBuffering)
        assertFalse(v.packetBuffering)

        // 直播缓冲必须小，否则延迟越积越多
        assertTrue("直播缓冲应远小于点播", t.maxBufferBytes < v.maxBufferBytes)
        // 直播探流要快，否则换台要等
        assertTrue("直播探流应比点播快", t.probesizeBytes < v.probesizeBytes)
        assertTrue(t.analyzeDurationUs < v.analyzeDurationUs)
    }
}
