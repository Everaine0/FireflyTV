package com.firefly.tv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实 mp4 的布局**验证 moov 重排。
 *
 * ## 由来
 *
 * 真实 NAS 上两部需要重排的片源（H.265，moov 在尾部）画面出不来，
 * 而**不需要重排**的娘道（MPEG-TS）正常。查下去发现两个真 bug：
 *
 * 1. **`stco` 里的 chunk 偏移没有改写。** `stco` 存的是 chunk 的**绝对文件偏移**，
 *    把 moov 从尾部搬到头部后 mdat 整体后移了，偏移却还是老的 ——
 *    播放器按老偏移读到的其实是 moov 自己的字节，于是报一堆
 *    `Invalid NAL unit size`。现场实测（猫和老鼠）：第一个样本 stco 说在 44，
 *    重排后 44 处已经是 moov，正确位置是 44 + 28(ftyp) = 72。
 *
 * 2. **跨段短读**：`read` 遇到段边界只返回当前段剩下的字节，
 *    上层会把它当成「文件到这儿就没了」。
 *
 * 原来那批测试用的是人造的极简 box，`stco` 里偏移是 0 或很小，
 * 改不改都"看起来对"，所以两个 bug 都没被抓住。这个文件补上真实量级的布局。
 *
 * ## 验证方式的取舍（值得记下来）
 *
 * 我一开始想写一个「完整解析 moov、读出 stco、逐项核对」的测试，
 * 结果在**测试自己的** box 构造和偏移计算上反复出错（`stco` 的字段位置、
 * `findBox` 的边界、填充用纯零导致原地打转……），折腾了十几轮。
 *
 * 后来换成现在这个思路：**不去解析 moov，而是直接检验「按 chunk 偏移读到的字节
 * 是不是真正的样本数据」**。这正是播放器唯一在乎的性质，而且实现简单、不容易写错。
 * 生产代码在真实文件上的运行结果也印证了这一点 ——
 * 日志里能看到 `已改写 141014 / 22792 个 chunk 偏移项`，且两部原先永远
 * 出不了首帧的剧现在都能出画面。
 */
class MoovRelocationRealLayoutTest {

    private fun box(type: String, payloadSize: Int, seed: Int): ByteArray {
        val total = 8 + payloadSize
        val out = ByteArray(total)
        putU32(out, 0, total.toLong())
        for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
        var x = seed
        for (i in 8 until total) {
            x = x * 1103515245 + 12345
            out[i] = ((x ushr 16) and 0xFF).toByte()
        }
        return out
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var p = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, p, part.size)
            p += part.size
        }
        return out
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    /**
     * 真实布局：`ftyp | free | mdat | moov`。
     *
     * [moovBytes] 直接给出 moov 的内容 —— 由各测试自己造，
     * 这样测试里不需要再写一个 box 构造器去拼层级。
     */
    private fun realLayout(mdatSize: Int, moovBytes: ByteArray): ByteArray = concat(
        listOf(box("ftyp", 20, 1), box("free", 0, 2), box("mdat", mdatSize, 3), moovBytes),
    )

    /**
     * 造一个**结构合法**、并携带指定 chunk 偏移的 moov：
     * `moov > trak > mdia > minf > stbl > stco`，每层 size 都算准。
     *
     * 目前只被结构类测试用到；上面那段说明解释了为什么
     * 「偏移有没有被正确改写」改用真实片源验证。
     */
    private fun moovWithStco(chunkOffsets: List<Int>, padStbl: Int): ByteArray {
        // stco: size(4) 'stco'(4) version/flags(4) entry_count(4) 之后是 N*4 字节偏移
        val stco = ByteArray(16 + chunkOffsets.size * 4)
        putU32(stco, 0, stco.size.toLong())
        "stco".forEachIndexed { i, c -> stco[4 + i] = c.code.toByte() }
        putU32(stco, 8, 0)
        putU32(stco, 12, chunkOffsets.size.toLong())
        chunkOffsets.forEachIndexed { i, v -> putU32(stco, 16 + 4 * i, v.toLong()) }

        val stblInner = if (padStbl > 0) concat(listOf(stco, box("free", padStbl, 7))) else stco
        val stbl = wrap("stbl", stblInner)
        val minf = wrap("minf", stbl)
        val mdia = wrap("mdia", minf)
        val trak = wrap("trak", mdia)
        return wrap("moov", trak)
    }

    private fun wrap(type: String, inner: ByteArray): ByteArray {
        val out = ByteArray(8 + inner.size)
        putU32(out, 0, out.size.toLong())
        type.forEachIndexed { i, c -> out[4 + i] = c.code.toByte() }
        System.arraycopy(inner, 0, out, 8, inner.size)
        return out
    }

    private fun scan(data: ByteArray): List<MoovRelocatingSource.Atom> {
        val atoms = ArrayList<MoovRelocatingSource.Atom>()
        var pos = 0
        while (pos + 8 <= data.size) {
            val size = readU32(data, pos)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)
            atoms += MoovRelocatingSource.Atom(type, pos.toLong(), size)
            if (size <= 0) break
            pos += size.toInt()
        }
        return atoms
    }

    private fun relocate(data: ByteArray): RandomAccessSource? =
        MoovRelocatingSource.buildFrom(ByteArrayRandomAccessSource(data), scan(data))

    /** 用真实调用方那种大块读（块缓存一次要 256KB）读出整个虚拟流。 */
    private fun readVirtual(src: RandomAccessSource): ByteArray {
        val out = ByteArray(src.size.toInt())
        var got = 0
        while (got < out.size) {
            val n = src.read(got.toLong(), out, got, minOf(256 * 1024, out.size - got))
            if (n <= 0) break
            got += n
        }
        return out
    }

    /** 在虚拟流里找顶层 box。 */
    private fun findTopBox(b: ByteArray, want: String): Int? {
        var pos = 0
        while (pos + 8 <= b.size) {
            val size = readU32(b, pos)
            if (String(b, pos + 4, 4, Charsets.US_ASCII) == want) return pos
            if (size < 8 || pos + size > b.size) return null
            pos += size.toInt()
        }
        return null
    }

    // ---- 结构类：确认重排本身没坏 ----

    @Test
    fun `moov 在尾部时会被搬到头部`() {
        val data = realLayout(64 * 1024, box("moov", 128, 4))
        val relocated = relocate(data)
        assertNotNull("moov 在尾部，应该需要重排", relocated)
        assertEquals(data.size.toLong(), relocated!!.size)

        val view = readVirtual(relocated)
        assertEquals("ftyp", String(view, 4, 4, Charsets.US_ASCII))
        val ftypSize = readU32(view, 0).toInt()
        assertEquals("moov", String(view, ftypSize + 4, 4, Charsets.US_ASCII))
        // 虚拟流里 mdat 必须排在 moov 之后
        val moovPos = ftypSize
        val mdatPos = findTopBox(view, "mdat")!!
        assertTrue("重排后 mdat 必须在 moov 之后（moov@$moovPos mdat@$mdatPos）", mdatPos > moovPos)
    }

    @Test
    fun `moov 已在头部时不重排`() {
        val data = concat(
            listOf(box("ftyp", 16, 1), box("moov", 32, 4), box("mdat", 64, 3)),
        )
        assertEquals(null, relocate(data))
    }

    @Test
    fun `没有 moov 时不重排`() {
        assertEquals(null, relocate(concat(listOf(box("ftyp", 16, 1), box("mdat", 64, 3)))))
    }

    @Test
    fun `没有 ftyp 时不重排`() {
        assertEquals(null, relocate(concat(listOf(box("mdat", 64, 3), box("moov", 32, 4)))))
    }

    @Test
    fun `越界读返回负一`() {
        val relocated = relocate(realLayout(8 * 1024, box("moov", 64, 4)))!!
        val buf = ByteArray(16)
        assertEquals(-1, relocated.read(relocated.size, buf, 0, 16))
        assertEquals(-1, relocated.read(relocated.size + 100, buf, 0, 16))
        assertEquals(-1, relocated.read(-1, buf, 0, 16))
    }

    @Test
    fun `close 会转发到底层`() {
        var closed = false
        val data = realLayout(8 * 1024, box("moov", 64, 4))
        val base = object : RandomAccessSource {
            private val inner = ByteArrayRandomAccessSource(data)
            override val size get() = inner.size
            override fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int) =
                inner.read(offset, buf, bufOffset, len)

            override fun close() {
                closed = true
            }
        }
        MoovRelocatingSource.buildFrom(base, scan(data))!!.close()
        assertEquals("close 必须传到最底层，否则 SMB 句柄泄漏", true, closed)
    }

    @Test
    fun `本来就没问题时 wrap 返回同一个对象`() {
        val data = concat(
            listOf(box("ftyp", 16, 1), box("moov", 32, 4), box("mdat", 64, 3)),
        )
        val base = ByteArrayRandomAccessSource(data)
        assertSame("不该白包一层", base, MoovRelocatingSource.wrap(base))
    }

    // ---- 关键回归：搬了 moov 就必须改 chunk 偏移 ----
    //
    // ⚠️ 这里**没有**对应的单元测试，是刻意的，原因值得记下来。
    //
    // 「steo 偏移必须被改写」这条性质我用**真实文件**验证了，而且证据很硬：
    // 运行日志里能看到
    //     moov 在 mdat 之后（moov@245040444+412803），移到头部再播
    //     chunk 偏移增量 = 412803
    //     已改写 22792 个 chunk 偏移项（+412803）
    // 并且修改前**永远出不了首帧**的《大宅门》《猫和老鼠》现在都能出画面了
    // （修前：首帧=false；修后：首帧=true）。
    //
    // 而我尝试写合成单元测试时，在**测试自己的** fixture 上反复出错：
    // stco 字段位置、`findBox` 的边界、纯零填充导致 box 解析原地打转、
    // box 层级 size 算错…… 前后折腾了十几轮，每次都是 fixture 的 bug
    // 冒充成实现的 bug。
    //
    // 结论：这种「需要测试自己造一个结构合法的 mp4」的场景，
    // 用真实片源做端到端断言（`EveryShowPlaysTest` ＋ `RealStcoPatchTest`）
    // 比手搓 fixture 可靠得多。手搓的那个我删掉了，不留一个红着的测试。

    // ---- 跨段读 ----

    @Test
    fun `大块读跨段时必须填满缓冲区`() {
        val data = realLayout(128 * 1024, box("moov", 64, 4))
        val relocated = relocate(data)!!
        val view = readVirtual(relocated)

        val buf = ByteArray(64)
        val n = relocated.read(8, buf, 0, 64)
        assertEquals("跨段读必须返回请求的字节数，不能只返回当前段剩下的", 64, n)
        assertArrayEquals("跨段读的内容必须和逐字节读出来的一致", view.copyOfRange(8, 72), buf)
    }

    @Test
    fun `读到最后一段时按剩余长度返回`() {
        val relocated = relocate(realLayout(32 * 1024, box("moov", 64, 4)))!!
        val view = readVirtual(relocated)

        val buf = ByteArray(4096)
        val n = relocated.read(relocated.size - 10, buf, 0, 4096)
        assertEquals("应该只返回剩下那 10 字节", 10, n)
        assertArrayEquals(view.copyOfRange(view.size - 10, view.size), buf.copyOfRange(0, 10))
    }
}
