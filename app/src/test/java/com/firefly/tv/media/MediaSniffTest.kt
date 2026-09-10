package com.firefly.tv.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内容探测的单元测试。
 *
 * 用例取自实测：整季《娘道》76 集后缀写成 `.mp4`，实际是 MPEG-TS。
 * 只看后缀会把整部剧当成空目录跳过 —— 这正是这些测试要防住的。
 */
class MediaSniffTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** 用真实的 TS 头造一段数据：每 188 字节一个 0x47。 */
    private fun tsHead(packetSize: Int, count: Int): ByteArray {
        val b = ByteArray(packetSize * count)
        for (i in 0 until count) b[i * packetSize] = 0x47
        // 头几个字节照抄实测样本
        b[1] = 0x40; b[2] = 0x00; b[3] = 0x10
        return b
    }

    @Test
    fun `实测的娘道文件头被认出是 TS`() {
        // 真实样本头部：47 40 00 10 00 00 b0 0d 00 2a c3 00 00 1a 23 f0 ...
        val head = ByteArray(1024)
        val sample = intArrayOf(
            0x47, 0x40, 0x00, 0x10, 0x00, 0x00, 0xb0, 0x0d,
            0x00, 0x2a, 0xc3, 0x00, 0x00, 0x1a, 0x23, 0xf0,
        )
        for (i in sample.indices) head[i] = sample[i].toByte()
        head[188] = 0x47
        assertTrue("188 字节包间隔的 TS 应被认出", MediaSniff.looksLikeVideoByContent(head))
    }

    @Test
    fun `192字节包的TS也认`() {
        assertTrue(MediaSniff.looksLikeVideoByContent(tsHead(192, 6)))
    }

    @Test
    fun `只有首字节是47但包不对齐的不认`() {
        val head = ByteArray(1024)
        head[0] = 0x47
        // 188 和 192 处都不是 0x47
        assertFalse("不能只凭一个 0x47 就当成 TS", MediaSniff.looksLikeVideoByContent(head))
    }

    @Test
    fun `mp4 的 ftyp 被认出`() {
        val b = ByteArray(64)
        b[0] = 0; b[1] = 0; b[2] = 0; b[3] = 0x20
        b[4] = 'f'.code.toByte(); b[5] = 't'.code.toByte()
        b[6] = 'y'.code.toByte(); b[7] = 'p'.code.toByte()
        assertTrue(MediaSniff.looksLikeVideoByContent(b))
    }

    @Test
    fun `matroska 被认出`() {
        assertTrue(MediaSniff.looksLikeVideoByContent(bytes(0x1A, 0x45, 0xDF, 0xA3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `avi 被认出`() {
        val b = ByteArray(64)
        "RIFF".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        "AVI ".forEachIndexed { i, c -> b[8 + i] = c.code.toByte() }
        assertTrue(MediaSniff.looksLikeVideoByContent(b))
    }

    @Test
    fun `asf wmv 被认出`() {
        val b = ByteArray(64)
        val guid = intArrayOf(
            0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11,
            0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C,
        )
        for (i in guid.indices) b[i] = guid[i].toByte()
        assertTrue(MediaSniff.looksLikeVideoByContent(b))
    }

    @Test
    fun `普通文本不会被当成视频`() {
        val text = "this is a readme file, not a video at all, really not".toByteArray()
        assertFalse(MediaSniff.looksLikeVideoByContent(text))
        assertFalse(MediaSniff.looksLikeVideoByContent(ByteArray(0)))
        assertFalse(MediaSniff.looksLikeVideoByContent(ByteArray(8)))
    }

    @Test
    fun `jpg 和 srt 不会被当成视频`() {
        // JPEG 头
        val jpg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01)
        assertFalse(MediaSniff.looksLikeVideoByContent(jpg))
        val srt = "1\n00:00:01,000 --> 00:00:04,000\n你好\n".toByteArray()
        assertFalse(MediaSniff.looksLikeVideoByContent(srt))
    }

    @Test
    fun `后缀判断仍然优先且认得常见视频后缀`() {
        assertTrue(MediaSniff.looksLikeVideoByName("01.mp4"))
        assertTrue(MediaSniff.looksLikeVideoByName("剧集.MKV"))
        assertFalse(MediaSniff.looksLikeVideoByName("readme.txt"))
    }
}
