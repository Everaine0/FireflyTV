package com.firefly.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选码器与 10bit 防线的回归测试。
 *
 * 背景（都是源码里核实过的行为）：
 *  - ijkplayer 的排名是**纯字符串匹配**，`omx.mtk.*` 恒为 `RANK_TESTED=800`，
 *    `omx.google.*`/`omx.ffmpeg.*` 是 `RANK_SOFTWARE=200`，
 *    低于 `RANK_LAST_CHANCE=600` 就整体退回软解；
 *  - 硬解失败是**静默回落软解**，所以我们需要一个「禁用名单」跨次记忆；
 *  - HEVC **不检查 profile**，Main10 交给只支持 8bit 的解码器不会报错，
 *    只会黑屏或极慢 —— 必须在应用层拦。
 */
class MediaCodecChoiceTest {

    private fun c(name: String, rank: Int, surface: Boolean = true, profiles: Set<Int> = emptySet()) =
        MediaCodecChoice.Candidate(name, rank, surface, profiles)

    // ---- 选取规则 ----

    @Test
    fun `取分数最高的`() {
        val list = listOf(
            c("OMX.google.h264.decoder", 200),
            c("OMX.MTK.VIDEO.DECODER.AVC", 800),
            c("OMX.Unknown.decoder", 700),
        )
        assertEquals("OMX.MTK.VIDEO.DECODER.AVC", MediaCodecChoice.pick(list)?.name)
    }

    @Test
    fun `分数相同的取先出现的（和 ijkplayer 默认一致）`() {
        val list = listOf(c("A.one", 800), c("B.two", 800))
        assertEquals("A.one", MediaCodecChoice.pick(list)?.name)
    }

    @Test
    fun `纯软件解码器一律不选`() {
        val list = listOf(c("OMX.google.hevc.decoder", 200), c("OMX.ffmpeg.hevc.decoder", 200))
        assertNull(MediaCodecChoice.pick(list))
    }

    @Test
    fun `刚好达到阈值就接受`() {
        assertNotNull(MediaCodecChoice.pick(listOf(c("X", MediaCodecChoice.MIN_RANK))))
        assertNull(MediaCodecChoice.pick(listOf(c("X", MediaCodecChoice.MIN_RANK - 1))))
    }

    @Test
    fun `禁用过的解码器会被跳过，改用次优的`() {
        val list = listOf(
            c("OMX.MTK.VIDEO.DECODER.HEVC", 800),
            c("OMX.Other.HEVC", 700),
        )
        assertEquals("OMX.Other.HEVC", MediaCodecChoice.pick(list, setOf("OMX.MTK.VIDEO.DECODER.HEVC"))?.name)
    }

    @Test
    fun `全被禁用就返回 null（老老实实软解）`() {
        val list = listOf(c("A", 800), c("B", 700))
        assertNull(MediaCodecChoice.pick(list, setOf("A", "B")))
    }

    // ---- 10bit / Main10 防线 ----

    @Test
    fun `HEVC Main 8bit 放行`() {
        val list = listOf(c("OMX.MTK.VIDEO.DECODER.HEVC", 800, profiles = setOf(MediaCodecChoice.HEVC_PROFILE_MAIN)))
        assertNull(MediaCodecChoice.profileRejection(MediaCodecChoice.MIME_HEVC, MediaCodecChoice.HEVC_PROFILE_MAIN, list))
    }

    @Test
    fun `HEVC Main10 没有任何解码器声明支持就拒绝硬解`() {
        val list = listOf(c("OMX.MTK.VIDEO.DECODER.HEVC", 800, profiles = setOf(MediaCodecChoice.HEVC_PROFILE_MAIN)))
        val reason = MediaCodecChoice.profileRejection(
            MediaCodecChoice.MIME_HEVC,
            MediaCodecChoice.HEVC_PROFILE_MAIN10,
            list,
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("Main10"))
    }

    @Test
    fun `HEVC Main10 有解码器声明支持就放行`() {
        val list = listOf(c("OMX.MTK.VIDEO.DECODER.HEVC", 800, profiles = setOf(1, 2)))
        assertNull(MediaCodecChoice.profileRejection(MediaCodecChoice.MIME_HEVC, 2, list))
    }

    @Test
    fun `HDR10 变体同样按 10bit 处理`() {
        assertTrue(MediaCodecChoice.isMain10(MediaCodecChoice.HEVC_PROFILE_MAIN10_HDR10))
        assertTrue(MediaCodecChoice.isMain10(MediaCodecChoice.HEVC_PROFILE_MAIN10_HDR10_PLUS))
        assertTrue(!MediaCodecChoice.isMain10(MediaCodecChoice.HEVC_PROFILE_MAIN))
    }

    @Test
    fun `H_264 的档次判断交给 ijkplayer 原生层，这里不插手`() {
        // H.264 的 High10/422/444 在 native 侧已有白名单，应用层再判会重复
        assertNull(MediaCodecChoice.profileRejection("video/avc", 110, emptyList()))
        assertNull(MediaCodecChoice.profileRejection("video/avc", 0, emptyList()))
    }
}
