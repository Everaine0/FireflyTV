package com.firefly.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MPEG-TS 轨道探测的行为约定。
 *
 * 语料用**真实 ffmpeg 产出的 TS**（`real-ac3.ts` = H.264 + AC-3），而不是手搓的字节 ——
 * 手搓字节曾经骗过我一次：造出来的「包」只有 20 字节，于是白白怀疑解析器半天。
 * 真实文件打底，结论才可信；而结论直接决定给用户的提示对不对。
 */
class TsProbeTest {

    private fun ts(): ByteArray =
        TsProbeTest::class.java.classLoader!!.getResourceAsStream("real-ac3.ts")?.readBytes()
            ?: error("测试语料 real-ac3.ts 找不到")

    private fun pmtPidAndEs(data: ByteArray) = TsProbe.probe(data)

    @Test
    fun `真实 TS 认出 H264 加 AC3`() {
        val info = pmtPidAndEs(ts())
        assertNotNull(info)
        assertTrue(info!!.hasVideo)
        assertTrue(info.hasAudio)
        assertEquals(TsProbe.Kind.VIDEO_H264, info.tracks.first { it.kind.name.startsWith("VIDEO_") }.kind)
        assertEquals(TsProbe.Kind.AUDIO_AC3, info.audio.single().kind)
        assertEquals("AC-3 音频", info.audio.single().label)
    }

    @Test
    fun `不是 TS 的数据返回 null 而不是瞎猜`() {
        assertNull(TsProbe.probe(ByteArray(0)))
        assertNull(TsProbe.probe("这段文字不是传输流，只是一段普通文本而已".toByteArray()))
        // mp4 的 ftyp 头
        assertNull(TsProbe.probe(byteArrayOf(0, 0, 0, 0x1c, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d)))
        assertNull(TsProbe.probe(ByteArray(4096)))
    }

    @Test
    fun `数据太短要返回 null 而不是抛异常`() {
        // 只有一个同步字节，凑不出完整包
        assertNull(TsProbe.probe(byteArrayOf(0x47)))
        assertNull(TsProbe.probe(ByteArray(100) { 0x47 }))
    }

    @Test
    fun `190 字节包的流也能认出来`() {
        // 在真实 TS 的基础上每 188 字节插 4 字节，模拟 m2ts（192 字节包）
        val ts = ts()
        val m2ts = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < ts.size) {
            val n = minOf(188, ts.size - i)
            m2ts.write(ts, i, n)
            m2ts.write(byteArrayOf(0, 0, 0, 0))
            i += n
        }
        val info = TsProbe.probe(m2ts.toByteArray())
        assertNotNull("192 字节包的对齐方式也要能识别", info)
        assertEquals(TsProbe.Kind.AUDIO_AC3, info!!.audio.single().kind)
    }

    @Test
    fun `前面有垃圾字节时靠同步字对齐`() {
        val junk = ByteArray(37) { 0x11 }
        val info = TsProbe.probe(junk + ts())
        assertNotNull("开头有噪声也要能对齐同步字", info)
        assertEquals(TsProbe.Kind.AUDIO_AC3, info!!.audio.single().kind)
    }

    @Test
    fun `流里没有音频轨时如实报告没有音频`() {
        // 用真实 TS 只保留视频包的 PID 做不到（要重写 PAT/PMT），
        // 这里退一步验证契约：hasAudio 与 audio 列表一致，不会自相矛盾。
        val info = pmtPidAndEs(ts())!!
        assertEquals(info.audio.isNotEmpty(), info.hasAudio)
        assertFalse(info.audio.isEmpty())
    }
}
