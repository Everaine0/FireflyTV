package com.firefly.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搬 moov 时改写 chunk 偏移表 —— 逐字节钉住 `stco` / `co64` 的**表头**。
 *
 * ## 为什么必须有这组测试（真实故障，不是假想）
 *
 * 早先的实现把 `stco` 的字段位置算错了 4 个字节：`entry_count` 被当成在 `off+8`、
 * 偏移项被当成从 `off+8` 开始（漏掉了 `type` 那 4 个字节）：
 *
 * ```
 * [ size 4 ][ type 4 ][ version+flags 4 ][ entry_count 4 ][ 偏移 × N ]
 *   off       off+4      off+8             off+12           off+16
 *                        ↑ 被当成第 1 条偏移  ↑ 被当成第 2 条偏移
 * ```
 *
 * 偏移项本身其实**都改对了**（整体挪后两位），但 `entry_count` 被改成了
 * `N + delta` —— 真实片源《大宅门》里是 `70505 + 2535446 = 2605951`。
 * 解复用器于是去读 260 万条、越界 10,141,784 字节：
 *
 * ```
 * overread end of atom 'stco' by 10141784 bytes
 * wrong sample count
 * ```
 *
 * 解析位置整个错位之后，**音轨当场废掉（解出来只剩视频一条轨）**：
 * 用户看到的就是「有画面、没声音」，而且只有需要重排的那两部剧
 * （猫和老鼠 / 大宅门）会这样 —— 娘道是 MPEG-TS，根本不走重排。
 * 视频之所以还活着，是因为 `stco` 恰好是那个 trak 的最后一个 box。
 *
 * ## 为什么以前的测试没抓住
 *
 * 1. 老的 `MoovRelocationRealLayoutTest` 里那个会造 `stco` 的辅助函数
 *    （`moovWithStco`）**从来没被任何测试调用过** —— 偏移改写其实一直没人验证；
 * 2. 另一路验证（按偏移读到的字节是不是样本数据）只能证明**偏移项改对了**，
 *    证明不了**表头没被改坏**；
 * 3. 日志里 `已改写 141014 个 chunk 偏移项` 看着很正常，真实值是 141010
 *    （70505 × 2）—— 多出来的 2 就是被误改的表头字段。
 *
 * 所以这里的判据是**逐字节**的：表头一个字节都不许动，改动只允许落在偏移项上。
 */
class MoovChunkOffsetPatchTest {

    // ---- 字节工具 ----

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    private fun putU64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr (56 - 8 * i)) and 0xFF).toByte()
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        putU32(out, 0, out.size.toLong())
        type.forEachIndexed { i, c -> out[4 + i] = c.code.toByte() }
        System.arraycopy(payload, 0, out, 8, payload.size)
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

    /** 会往下递归的容器 box（和 `MoovRelocatingSource` 里的那份保持一致）。 */
    private val containers = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "moof", "traf",
    )

    /**
     * 在 mp4 里找某个 box 的起点（**会进容器 box**，`stco` 藏在
     * `moov > trak > mdia > minf > stbl` 里面）；找不到返回 -1。
     */
    private fun findBox(b: ByteArray, want: String, from: Int = 0, to: Int = b.size): Int {
        var pos = from
        while (pos + 8 <= to) {
            val size = readU32(b, pos)
            if (size < 8 || pos + size > to) break
            val type = String(b, pos + 4, 4, Charsets.US_ASCII)
            if (type == want) return pos
            if (type in containers) {
                val inner = findBox(b, want, pos + 8, (pos + size).toInt())
                if (inner >= 0) return inner
            }
            pos += size.toInt()
        }
        return -1
    }

    // ---- 造样本 ----

    private val chunkOffsets = listOf(44, 512, 1024)

    /**
     * 造一个真实布局的 mp4：`ftyp | free | mdat | moov`。
     *
     * `mdat` 里在每个 chunk 偏移处放一段可识别的标记（`CHUNK0/1/2`），
     * 这样「偏移改对没有」就能用「虚拟文件里同一个位置读到的还是不是同一段标记」
     * 来判定 —— 不依赖任何增量公式。
     *
     * @param useCo64 true = 用 64 位偏移表 `co64`，false = 用 `stco`
     */
    private fun buildMp4(useCo64: Boolean): ByteArray {
        val ftyp = box("ftyp", ByteArray(20) { it.toByte() })
        val free = box("free", ByteArray(0))
        val mdatStart = ftyp.size + free.size // = 36，数据体从 44 开始

        val mdatPayload = ByteArray(4096)
        chunkOffsets.forEachIndexed { i, off ->
            val marker = "CHUNK$i".toByteArray(Charsets.US_ASCII)
            System.arraycopy(marker, 0, mdatPayload, off - (mdatStart + 8), marker.size)
        }
        val mdat = box("mdat", mdatPayload)

        val tableType = if (useCo64) "co64" else "stco"
        val entrySize = if (useCo64) 8 else 4
        val table = ByteArray(16 + chunkOffsets.size * entrySize)
        putU32(table, 0, table.size.toLong())
        tableType.forEachIndexed { i, c -> table[4 + i] = c.code.toByte() }
        // version/flags 用真实文件里的 0：老实现会把偏移增量写进这 4 个字节，
        // 于是它变成 delta（非 0）—— 断言「必须还是 0」就能抓住。
        //
        // 这里不能用非零值：co64 那条老实现把 version/flags 和 entry_count
        // 当成一个 64 位整数读出来，加上 delta 后超过改写上限被跳过，
        // 反而「侥幸没改坏」，测试就失去判别力了（这是踩过的坑）。
        putU32(table, 8, 0)
        putU32(table, 12, chunkOffsets.size.toLong())
        chunkOffsets.forEachIndexed { i, v ->
            if (useCo64) putU64(table, 16 + 8 * i, v.toLong()) else putU32(table, 16 + 4 * i, v.toLong())
        }

        val moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", table)))))
        return concat(listOf(ftyp, free, mdat, moov))
    }

    private fun moovSizeOf(data: ByteArray): Int {
        var pos = 0
        while (pos + 8 <= data.size) {
            val size = readU32(data, pos).toInt()
            if (String(data, pos + 4, 4, Charsets.US_ASCII) == "moov") return size
            pos += size
        }
        error("样本里找不到 moov")
    }

    /** 原始（未重排的）moov 字节。 */
    private fun originalMoovOf(data: ByteArray): ByteArray {
        val size = moovSizeOf(data)
        return data.copyOfRange(data.size - size, data.size)
    }

    private class Relocated(val view: ByteArray, val moov: ByteArray)

    /**
     * 走生产代码那条路（`MoovRelocatingSource.buildFrom`）拿到虚拟文件，
     * 并从中切出**改写后的 moov**。
     */
    private fun relocate(data: ByteArray): Relocated {
        val atoms = ArrayList<MoovRelocatingSource.Atom>()
        var pos = 0
        while (pos + 8 <= data.size) {
            val size = readU32(data, pos)
            atoms += MoovRelocatingSource.Atom(String(data, pos + 4, 4, Charsets.US_ASCII), pos.toLong(), size)
            if (size < 8) break
            pos += size.toInt()
        }
        val src = MoovRelocatingSource.buildFrom(ByteArrayRandomAccessSource(data), atoms)
            ?: error("这个布局必须触发重排")
        val view = ByteArray(src.size.toInt())
        var got = 0
        while (got < view.size) {
            val n = src.read(got.toLong(), view, got, minOf(256 * 1024, view.size - got))
            if (n <= 0) break
            got += n
        }
        val ftypSize = readU32(view, 0).toInt()
        val moovSize = moovSizeOf(data)
        return Relocated(view, view.copyOfRange(ftypSize, ftypSize + moovSize))
    }

    // ---- stco：32 位偏移表 ----

    @Test
    fun `stco 的表头一个字节都不许改`() {
        val data = buildMp4(useCo64 = false)
        val original = originalMoovOf(data)
        val stcoOff = findBox(original, "stco")
        assertTrue("测试样本里应该有 stco", stcoOff >= 0)

        val patched = relocate(data).moov

        assertEquals("size 字段不能被改", readU32(original, stcoOff), readU32(patched, stcoOff))
        assertEquals(
            "version/flags 被当成偏移项改掉了（老 bug：会变成 delta）",
            0L,
            readU32(patched, stcoOff + 8),
        )
        assertEquals(
            "entry_count 被当成偏移项改掉了（老 bug：会变成 N+delta，解复用器要越界读几百万条）",
            chunkOffsets.size.toLong(),
            readU32(patched, stcoOff + 12),
        )
    }

    @Test
    fun `改动必须只落在偏移项上`() {
        val data = buildMp4(useCo64 = false)
        val original = originalMoovOf(data)
        val patched = relocate(data).moov
        val stcoOff = findBox(original, "stco")

        assertEquals("moov 长度不能变", original.size, patched.size)
        val changed = original.indices.filter { original[it] != patched[it] }
        assertTrue("偏移项应该真的被改写了（否则等于没修）", changed.isNotEmpty())

        val entryFrom = stcoOff + 16
        val entryTo = entryFrom + 4 * chunkOffsets.size
        for (i in changed) {
            assertTrue(
                "第 $i 字节被改了，但它不在偏移项区域 [$entryFrom, $entryTo) 内 —— 表头或别的 box 被误改",
                i in entryFrom until entryTo,
            )
        }
    }

    @Test
    fun `改写后的偏移仍指向同一段样本数据`() {
        val data = buildMp4(useCo64 = false)
        val original = originalMoovOf(data)
        val relocated = relocate(data)
        val stcoOff = findBox(original, "stco")

        val deltas = chunkOffsets.indices.map { i ->
            readU32(relocated.moov, stcoOff + 16 + 4 * i) - chunkOffsets[i]
        }
        assertEquals("所有偏移项必须加同一个常量", 1, deltas.distinct().size)
        val delta = deltas.first()
        assertTrue("内容整体后移，增量必须为正", delta > 0)

        chunkOffsets.forEachIndexed { i, old ->
            assertEquals("原始偏移 $old 处应该是 CHUNK$i", "CHUNK$i", String(data, old, 6, Charsets.US_ASCII))
            assertEquals(
                "重排后新偏移处读到的必须还是同一段样本数据",
                "CHUNK$i",
                String(relocated.view, (old + delta).toInt(), 6, Charsets.US_ASCII),
            )
        }
    }

    // ---- co64：64 位偏移表，同样错不得 ----

    @Test
    fun `co64 的表头一个字节都不许改`() {
        val data = buildMp4(useCo64 = true)
        val original = originalMoovOf(data)
        val co64Off = findBox(original, "co64")
        assertTrue("测试样本里应该有 co64", co64Off >= 0)

        val relocated = relocate(data)
        val patched = relocated.moov
        assertEquals("size 字段不能被改", readU32(original, co64Off), readU32(patched, co64Off))
        assertEquals("version/flags 不能被改（老 bug：会变成 delta）", 0L, readU32(patched, co64Off + 8))
        assertEquals("entry_count 不能被改", chunkOffsets.size.toLong(), readU32(patched, co64Off + 12))

        val deltas = chunkOffsets.indices.map { i ->
            readU64(patched, co64Off + 16 + 8 * i) - chunkOffsets[i]
        }
        assertEquals("所有偏移项必须加同一个常量", 1, deltas.distinct().size)
        assertTrue("增量必须为正", deltas.first() > 0)
        chunkOffsets.forEachIndexed { i, old ->
            assertEquals(
                "重排后新偏移处读到的必须还是同一段样本数据",
                "CHUNK$i",
                String(relocated.view, (old + deltas.first()).toInt(), 6, Charsets.US_ASCII),
            )
        }
    }

    @Test
    fun `本来就没问题时不动源对象`() {
        // moov 已在头部（faststart）：wrap 应该原样返回
        val ftyp = box("ftyp", ByteArray(20) { it.toByte() })
        val moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", ByteArray(16))))))
        val mdat = box("mdat", ByteArray(64))
        val data = concat(listOf(ftyp, moov, mdat))
        val src = ByteArrayRandomAccessSource(data)
        assertNotNull(src)
        val wrapped = MoovRelocatingSource.wrap(src)
        assertEquals("不需要重排时不该包装", data.size.toLong(), wrapped.size)
    }
}
