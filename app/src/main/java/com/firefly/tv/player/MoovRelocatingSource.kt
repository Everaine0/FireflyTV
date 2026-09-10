package com.firefly.tv.player

import android.util.Log

/**
 * 把 mp4 的 moov box 搬到文件头，让「非 faststart」的片源也能播。
 *
 * 为什么需要这一层（DESIGN 风险 8）：
 * ijkplayer 0.8.8 在 `IMediaDataSource` 通道下**只顺序读**，从不回尾读 moov。
 * 相机录的、下载来的 mp4 大多把 moov 放在文件尾，于是直接报 "moov atom not found"。
 *
 * 做法：在 Java 侧解析顶层 box 结构，然后把字节流**重新排布**成
 * `ftyp + moov + 其余按原顺序`，并对外谎报这个虚拟长度。
 * 解复用器看到 moov 在头部，就正常了 —— 不需要重新编码，也不需要临时文件。
 *
 * 只在确实需要时才启用（moov 在 mdat 之后）；本来就在头部的文件原样返回。
 */
class MoovRelocatingSource private constructor(
    private val source: RandomAccessSource,
    private val segments: List<Segment>,
) : RandomAccessSource {

    /** 虚拟文件里的一段：从 [virtualStart] 开始读 [length] 字节，实际来自物理位置 [physicalStart]。 */
    private class Segment(val virtualStart: Long, val physicalStart: Long, val length: Long)

    /** 顶层 box 的位置与长度。 */
    internal class Atom(val type: String, val offset: Long, val size: Long) {
        override fun toString() = "$type@$offset+$size"
    }

    override val size: Long get() = segments.sumOf { it.length }

    override fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int): Int {
        if (offset < 0 || offset >= size) return -1

        // 定位虚拟偏移落在哪一段
        var seg: Segment? = null
        for (s in segments) {
            if (offset >= s.virtualStart && offset < s.virtualStart + s.length) {
                seg = s
                break
            }
        }
        if (seg == null) return -1

        val inSeg = offset - seg.virtualStart
        // 不跨段读：上层是 256KB 块，这里最多多几次物理读，换来实现简单不易错
        val n = minOf(len.toLong(), seg.length - inSeg).toInt()
        return source.read(seg.physicalStart + inSeg, buf, bufOffset, n)
    }

    override fun close() {
        source.close()
    }

    companion object {
        private const val TAG = "FireflyMoov"

        private const val MAX_HEADER_BYTES = 32L * 1024 * 1024 // 只扫前 32MB 的 box 头

        /**
         * 需要重排就返回包装后的字节源，否则原样返回 [source]。
         * 两种情况下 [source] 的所有权都归调用方，关闭由调用方负责（包装层会转发 close）。
         */
        fun wrap(source: RandomAccessSource): RandomAccessSource {
            val atoms = try {
                scanTopLevelAtoms(source)
            } catch (t: Throwable) {
                Log.w(TAG, "解析 mp4 box 结构失败，按原样播放", t)
                return source
            }
            return build(source, atoms) ?: source
        }

        /**
         * 用已知的顶层 box 列表构造重排字节源；不需要重排时返回 null。
         *
         * 给单元测试直接注入 box 布局用 —— 这条路和 [wrap] 在扫描之后走的是同一段逻辑，
         * 所以测出来的行为和线上一致。
         */
        internal fun buildFrom(source: RandomAccessSource, atoms: List<Atom>): RandomAccessSource? =
            build(source, atoms)

        private fun build(source: RandomAccessSource, atoms: List<Atom>?): RandomAccessSource? {
            if (atoms == null || atoms.isEmpty()) return null

            val ftyp = atoms.firstOrNull { it.type == "ftyp" } ?: return null
            val moov = atoms.firstOrNull { it.type == "moov" } ?: return null
            val mdat = atoms.firstOrNull { it.type == "mdat" } ?: return null

            // moov 已经在 mdat 之前 —— 本来就是 faststart，不用动
            if (moov.offset < mdat.offset) {
                Log.d(TAG, "moov 已在头部，无需重排")
                return null
            }

            Log.i(TAG, "moov 在 mdat 之后（$moov），移到头部再播")

            // 虚拟顺序：ftyp, moov, 其余按物理顺序
            val ordered = ArrayList<Atom>(atoms.size + 2)
            ordered += ftyp
            ordered += moov
            ordered += atoms.filter { it !== ftyp && it !== moov }

            val segments = ArrayList<Segment>(ordered.size)
            var virtual = 0L
            for (a in ordered) {
                if (a.size <= 0) continue
                segments += Segment(virtual, a.offset, a.size)
                virtual += a.size
            }
            return MoovRelocatingSource(source, segments)
        }

        /**
         * 扫顶层 box。只读 box 头，不读数据体，所以在网络上也很便宜。
         * 返回 null 表示不是能识别的 mp4 结构。
         */
        private fun scanTopLevelAtoms(source: RandomAccessSource): List<Atom>? {
            val total = source.size
            if (total < 16) return null

            val atoms = ArrayList<Atom>(16)
            var pos = 0L
            var headerBytes = 0L

            while (pos + 8 <= total) {
                headerBytes += 16
                if (headerBytes > MAX_HEADER_BYTES) break

                val head = ByteArray(16)
                val got = source.read(pos, head, 0, 16)
                if (got < 8) break

                var size = readUInt32(head, 0)
                val type = String(head, 4, 4, Charsets.US_ASCII)
                var headerSize = 8L

                if (size == 1L) {
                    // 64 位长度
                    if (got < 16) break
                    size = readUInt64(head, 8)
                    headerSize = 16L
                } else if (size == 0L) {
                    // 一直到文件末尾
                    size = total - pos
                }

                if (size < headerSize || pos + size > total) {
                    // 结构不合法：可能不是标准 mp4，交给 FFmpeg 自己判断
                    return null
                }

                // 只关心这几种顶层 box，其余（free/skip/wide 等）照样计入
                if (type == "ftyp" || type == "moov" || type == "mdat" || type == "free" || type == "skip" || type == "wide") {
                    atoms += Atom(type, pos, size)
                } else {
                    // 未知顶层 box：仍然记下来，重排时保留原样
                    atoms += Atom(type, pos, size)
                }

                // ftyp/moov/mdat 都拿到了就不必再往后扫
                if (atoms.any { it.type == "moov" } && atoms.any { it.type == "mdat" }) break

                pos += size
            }

            return if (atoms.isEmpty()) null else atoms
        }

        private fun readUInt32(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xFF) shl 24) or
                ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or
                (b[off + 3].toLong() and 0xFF)

        private fun readUInt64(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
    }
}
