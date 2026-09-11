package com.firefly.tv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `IMediaDataSource.readAt` 的返回值约定 —— 这里踩过一个真坑，钉死它。
 *
 * ## 零长度读不是「无效参数」，而是 ijkplayer 的 seek
 *
 * `ijkmedia/ijkplayer/ijkavformat/ijkmediadatasource.c` 里的 `ijkmds_seek` 是这么写的：
 *
 * ```c
 * ret = J4AC_IMediaDataSource__readAt(env, c->media_data_source, new_logical_pos, jbuffer, 0, 0);
 * if (J4A_ExceptionCheck__catchAll(env)) return AVERROR(EIO);
 * else if (ret < 0)                       return AVERROR_EOF;   // ← 返回负数 = seek 失败
 * ```
 *
 * 而 `readAt` 最早写的是 `if (len <= 0 || ...) return -1` —— 于是**每一次 avio_seek
 * 都返回 EOF**。现场症状：一次播放刷出 4418 条
 * `stream 0/1, offset 0x...: partial file`，mov 解复用器只能退化成顺序读，
 * 1 GB 的 4K 片源慢到没法看，续播/拖进度也永远失灵。
 *
 * 所以：
 *  - `len == 0` → 合法 seek，位置没越过文件末尾就返回 **0**；
 *  - `len > 0` 且位置已在末尾 → 返回 **-1**（这才是 EOF，`ijkmds_read` 靠它结束）；
 *  - 读到的字节数可以**短于**请求长度（文件末尾），但不能是 0。
 */
class SmbMediaDataSourceTest {

    /** 头 4 字节故意很大，让 `MoovRelocatingSource` 判定「不是 mp4 结构」并原样透传。 */
    private fun bytes(n: Int) = ByteArray(n) { i ->
        if (i < 4) 0xFF.toByte() else (i and 0xFF).toByte()
    }

    private fun source(n: Int = 1000) = SmbMediaDataSource(ByteArrayRandomAccessSource(bytes(n)))

    // ---- 零长度读 = seek ----

    @Test
    fun `零长度读是 seek 必须返回零`() {
        val src = source()
        try {
            val buf = ByteArray(16)
            assertEquals("seek 到 0 必须成功（返回 -1 会让每次 avio_seek 都失败）", 0, src.readAt(0, buf, 0, 0))
            assertEquals(0, src.readAt(500, buf, 0, 0))
            assertEquals("seek 到文件末尾也算成功", 0, src.readAt(src.size, buf, 0, 0))
        } finally {
            src.close()
        }
    }

    @Test
    fun `越过文件末尾的 seek 返回负一`() {
        val src = source()
        try {
            assertEquals(-1, src.readAt(src.size + 1, ByteArray(8), 0, 0))
            assertEquals(-1, src.readAt(-1, ByteArray(8), 0, 0))
        } finally {
            src.close()
        }
    }

    @Test
    fun `负长度当成非法参数`() {
        val src = source()
        try {
            assertEquals(-1, src.readAt(0, ByteArray(8), 0, -1))
        } finally {
            src.close()
        }
    }

    // ---- 真正的读 ----

    @Test
    fun `正常读返回请求的字节数且内容正确`() {
        val src = source()
        try {
            val buf = ByteArray(10)
            assertEquals(10, src.readAt(0, buf, 0, 10))
            assertArrayEquals(bytes(1000).copyOfRange(0, 10), buf)
        } finally {
            src.close()
        }
    }

    @Test
    fun `文件末尾允许短读但不能返回零`() {
        val src = source()
        try {
            val buf = ByteArray(10)
            assertEquals("末尾应短读 4 字节", 4, src.readAt(src.size - 4, buf, 0, 10))
        } finally {
            src.close()
        }
    }

    @Test
    fun `在文件末尾读返回负一`() {
        val src = source()
        try {
            assertEquals("位置已到末尾 = EOF（不能返回 0，0 在 ijkplayer 里是 EAGAIN 会重试）",
                -1, src.readAt(src.size, ByteArray(10), 0, 10))
        } finally {
            src.close()
        }
    }

    @Test
    fun `随机跳读都必须读到正确字节`() {
        val total = 700_000
        val src = source(total)
        try {
            val buf = ByteArray(8)
            // 顺序 → 跳到最后 → 跳回开头 → 跳到中间（跨越 256KB 块缓存边界）
            for (pos in listOf(0L, 600_000L, 8L, 700L, 260_000L, 699_992L)) {
                val n = src.readAt(pos, buf, 0, 8)
                assertEquals("位置 $pos 应该读满 8 字节", 8, n)
                assertArrayEquals(
                    "位置 $pos 读到的字节不对（块缓存串了？）",
                    bytes(total).copyOfRange(pos.toInt(), pos.toInt() + 8),
                    buf,
                )
            }
        } finally {
            src.close()
        }
    }

    @Test
    fun `跨越块边界的大读必须填满`() {
        val src = source(700_000)
        try {
            val buf = ByteArray(300_000)
            val n = src.readAt(200_000, buf, 0, buf.size)
            assertEquals("跨块读必须填满（256KB 一块）", buf.size, n)
            assertArrayEquals(bytes(700_000).copyOfRange(200_000, 500_000), buf)
        } finally {
            src.close()
        }
    }

    @Test
    fun `关闭之后一律返回负一`() {
        val src = source()
        src.close()
        assertEquals(-1, src.readAt(0, ByteArray(8), 0, 8))
        assertEquals(-1, src.readAt(0, ByteArray(8), 0, 0))
    }
}
