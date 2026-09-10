package com.firefly.tv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * moov 重排的单元测试（DESIGN 风险 8）。
 *
 * 这里直接构造字节流来验证「虚拟视图」的正确性：
 * 重排之后从虚拟偏移 0 开始顺序读，必须得到一份 moov 在前的合法 mp4。
 */
class MoovRelocatingSourceTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** 造一个 box：4 字节长度 + 4 字节类型 + 填充。 */
    private fun box(type: String, payloadSize: Int, fill: Byte): ByteArray {
        val total = 8 + payloadSize
        val out = ByteArray(total)
        out[0] = ((total ushr 24) and 0xFF).toByte()
        out[1] = ((total ushr 16) and 0xFF).toByte()
        out[2] = ((total ushr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()
        for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
        for (i in 8 until total) out[i] = fill
        return out
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var p = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, p, part.size)
            p += part.size
        }
        return out
    }

    /** moov 在文件尾的典型布局：ftyp | mdat | moov。 */
    private fun tailMoovFile(): ByteArray = concat(
        box("ftyp", 16, 'F'.code.toByte()),
        box("mdat", 64, 'D'.code.toByte()),
        box("moov", 32, 'M'.code.toByte()),
    )

    /** 已经是 faststart 的布局：ftyp | moov | mdat。 */
    private fun faststartFile(): ByteArray = concat(
        box("ftyp", 16, 'F'.code.toByte()),
        box("moov", 32, 'M'.code.toByte()),
        box("mdat", 64, 'D'.code.toByte()),
    )

    private fun scan(data: ByteArray): List<MoovRelocatingSource.Atom> {
        val atoms = ArrayList<MoovRelocatingSource.Atom>()
        var pos = 0
        while (pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                ((data[pos + 1].toLong() and 0xFF) shl 16) or
                ((data[pos + 2].toLong() and 0xFF) shl 8) or
                (data[pos + 3].toLong() and 0xFF)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)
            atoms += MoovRelocatingSource.Atom(type, pos.toLong(), size)
            if (size <= 0) break
            pos += size.toInt()
        }
        return atoms
    }

    private fun readAll(src: RandomAccessSource): ByteArray {
        val out = ByteArray(src.size.toInt())
        var got = 0
        while (got < out.size) {
            val n = src.read(got.toLong(), out, got, minOf(7, out.size - got)) // 故意用零碎长度，覆盖跨段读
            if (n <= 0) break
            got += n
        }
        return out
    }

    @Test
    fun `moov 在尾部时会被搬到头部`() {
        val data = tailMoovFile()
        val base = ByteArrayRandomAccessSource(data)
        val relocated = MoovRelocatingSource.buildFrom(base, scan(data))
        assertNotNull("应该需要重排", relocated)

        val view = readAll(relocated!!)
        // 长度不变
        assertEquals(data.size.toLong(), relocated.size)

        // 虚拟视图的前 4 个字节必须是 ftyp，紧接着是 moov
        assertEquals("ftyp", String(view, 4, 4, Charsets.US_ASCII))
        val ftypSize = ((view[0].toInt() and 0xFF) shl 24) or
            ((view[1].toInt() and 0xFF) shl 16) or
            ((view[2].toInt() and 0xFF) shl 8) or (view[3].toInt() and 0xFF)
        assertEquals("moov", String(view, ftypSize + 4, 4, Charsets.US_ASCII))
        // moov 之后就是原样的 mdat
        val moovSize = 8 + 32
        assertEquals("mdat", String(view, ftypSize + moovSize + 4, 4, Charsets.US_ASCII))

        // 内容必须逐字节等于原文件重排后的结果，不能有丢字节/串位
        val ftyp = data.copyOfRange(0, ftypSize)
        val moov = data.copyOfRange(ftypSize + (8 + 64), data.size)
        val mdat = data.copyOfRange(ftypSize, ftypSize + (8 + 64))
        assertArrayEquals(concat(ftyp, moov, mdat), view)
    }

    @Test
    fun `moov 已在头部时不重排`() {
        val data = faststartFile()
        val base = ByteArrayRandomAccessSource(data)
        assertNull("本来就在头部，不该动它", MoovRelocatingSource.buildFrom(base, scan(data)))
    }

    @Test
    fun `没有 moov 时不重排`() {
        val data = concat(box("ftyp", 16, 'F'.code.toByte()), box("mdat", 64, 'D'.code.toByte()))
        assertNull(MoovRelocatingSource.buildFrom(ByteArrayRandomAccessSource(data), scan(data)))
    }

    @Test
    fun `没有 ftyp 时不重排`() {
        val data = concat(box("mdat", 64, 'D'.code.toByte()), box("moov", 32, 'M'.code.toByte()))
        assertNull(MoovRelocatingSource.buildFrom(ByteArrayRandomAccessSource(data), scan(data)))
    }

    @Test
    fun `越界读返回负一`() {
        val data = tailMoovFile()
        val relocated = MoovRelocatingSource.buildFrom(ByteArrayRandomAccessSource(data), scan(data))!!
        val buf = ByteArray(16)
        assertEquals(-1, relocated.read(relocated.size, buf, 0, 16))
        assertEquals(-1, relocated.read(relocated.size + 100, buf, 0, 16))
        assertEquals(-1, relocated.read(-1, buf, 0, 16))
    }

    @Test
    fun `close 会转发到底层`() {
        var closed = false
        val data = tailMoovFile()
        val base = object : RandomAccessSource {
            private val inner = ByteArrayRandomAccessSource(data)
            override val size get() = inner.size
            override fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int) =
                inner.read(offset, buf, bufOffset, len)
            override fun close() { closed = true }
        }
        val relocated = MoovRelocatingSource.buildFrom(base, scan(data))!!
        relocated.close()
        assertEquals("close 必须传到最底层，否则 SMB 句柄泄漏", true, closed)
    }

    @Test
    fun `不认识的普通 mp4 结构原样透传`() {
        // 用真实文件那种带 free box 的布局
        val data = concat(
            box("ftyp", 24, 'F'.code.toByte()),
            box("free", 8, 0),
            box("moov", 40, 'M'.code.toByte()),
            box("mdat", 128, 'D'.code.toByte()),
        )
        val base = ByteArrayRandomAccessSource(data)
        // moov 在 mdat 前 → 不重排
        assertNull(MoovRelocatingSource.buildFrom(base, scan(data)))
    }

    @Test
    fun `本来就没问题时 wrap 返回同一个对象`() {
        val data = faststartFile()
        val base = ByteArrayRandomAccessSource(data)
        val wrapped = MoovRelocatingSource.wrap(base)
        assertSame("不该白包一层", base, wrapped)
    }
}
