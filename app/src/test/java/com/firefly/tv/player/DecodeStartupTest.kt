package com.firefly.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 硬解首帧超时的宽限与归因（见 [DecodeStartup]）。
 *
 * 这条判据错了的表现是「电视有时流畅、有时像幻灯片」，而不是崩溃或报错：
 * 把 NAS 慢误判成硬解坏 → 退回软解；该退的时候不退 → 黑屏越来越久。
 */
class DecodeStartupTest {

    @Test
    fun `还没拿到数据时先宽限`() {
        // 硬盘休眠 / 探流 16MB：这段时间没首帧是正常的，不能算硬解坏
        assertTrue(DecodeStartup.waitLonger(graceUsed = 0, hasData = false, progressChanged = false))
        assertTrue(DecodeStartup.waitLonger(graceUsed = 1, hasData = false, progressChanged = false))
    }

    @Test
    fun `拿到数据而且还在增长时继续宽限`() {
        // 关键场景：解码器已有缓存，但 NAS 供得慢，首帧还没出来。
        // 旧逻辑在这里会立刻退回软解 —— 4K H.265 软解就是幻灯片。
        assertTrue(DecodeStartup.waitLonger(graceUsed = 0, hasData = true, progressChanged = true))
        assertTrue(DecodeStartup.waitLonger(graceUsed = 1, hasData = true, progressChanged = true))
    }

    @Test
    fun `宽限次数有上限`() {
        assertFalse(DecodeStartup.waitLonger(DecodeStartup.MAX_GRACE, hasData = false, progressChanged = true))
        assertFalse(DecodeStartup.waitLonger(DecodeStartup.MAX_GRACE, hasData = true, progressChanged = true))
        assertTrue(DecodeStartup.MAX_GRACE >= 2)
    }

    @Test
    fun `缓存攒够了却一帧不出才算解码器的错`() {
        assertTrue(
            "已经有 3 秒以上的视频数据还不出首帧：该退软解了",
            DecodeStartup.looksLikeDecoderFault(
                hasData = true,
                progressChanged = false,
                cachedMs = DecodeStartup.ENOUGH_CACHED_MS,
                cachedBytes = 0L,
            ),
        )
        assertTrue(
            "字节够多也算攒够",
            DecodeStartup.looksLikeDecoderFault(
                hasData = true,
                progressChanged = false,
                cachedMs = 0L,
                cachedBytes = DecodeStartup.ENOUGH_CACHED_BYTES,
            ),
        )
    }

    @Test
    fun `数据很少或还在流入时不许怪解码器`() {
        // 这就是「打开先卡顿」的现场：NAS 慢，缓存还没攒够。
        // 退软解救不了读取，只会把 4K 变成幻灯片 —— 应该原样重试、保留硬解。
        assertFalse(
            "数据还在涨：是读取慢，不是解码器坏",
            DecodeStartup.looksLikeDecoderFault(
                hasData = true,
                progressChanged = true,
                cachedMs = 10_000L,
                cachedBytes = 20L * 1024 * 1024,
            ),
        )
        assertFalse(
            "根本没读到数据：怪 NAS 去",
            DecodeStartup.looksLikeDecoderFault(
                hasData = false,
                progressChanged = false,
                cachedMs = 0L,
                cachedBytes = 0L,
            ),
        )
        assertFalse(
            "缓存太少、又不动了：先当作 NAS 卡住，重试一次比降级划算",
            DecodeStartup.looksLikeDecoderFault(
                hasData = true,
                progressChanged = false,
                cachedMs = 1_000L,
                cachedBytes = 512L * 1024,
            ),
        )
    }
}
