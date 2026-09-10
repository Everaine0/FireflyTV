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
 *
 * ## read 必须尽量填满缓冲区
 *
 * 重排后的虚拟文件是由若干**物理上不连续**的段拼起来的（ftyp、moov、然后是 mdat 等），
 * 一次 `read` 很容易横跨段边界。早先的实现遇到边界就只返回当前段剩下的字节，
 * 调用方（`SmbMediaDataSource` 的块缓存、ijkplayer 的读线程）拿到的就比要的少。
 *
 * 上层不一定都会自己补齐，短读会被当成「文件到这儿就没了」——
 * 表现是播到一半「播完了」或者画面花掉，而**只有在需要重排的片源上才复现**。
 * 所以这里必须循环读到填满（或确实到文件末尾）为止。
 *
 * ## 搬 moov 的同时**必须改写 chunk 偏移**（这条是真 bug 的根因）
 *
 * `stco` / `co64` 里存的是 chunk 的**绝对文件偏移**。把 moov 从文件尾搬到头部，
 * 里面**指向 mdat 的那些偏移就全错了** —— 播放器会按老偏移去读，
 * 读到的其实是 moov 自己的字节，于是报一堆
 * `Invalid NAL unit size` / `Error splitting the input into NAL units`。
 *
 * 现场实测（猫和老鼠，245,453,247 字节）：
 * ```
 *   原始布局: ftyp(28) free(8) mdat(245,040,408) moov(412,803)
 *   重排之后: ftyp(28) moov(412,803) free(8) mdat(...)
 *   第一个视频样本: stco 说在 44，重排后 44 处已经是 moov 的字节
 *   正确的新位置  = 44 + 28(ftyp) = 72
 * ```
 *
 * 所以搬运时必须把偏移整体加上增量。增量对所有 chunk 是**同一个常量**：
 * moov 原本在文件尾（前面是全部数据），搬到头之后它前面只剩 ftyp，
 * 于是它后面所有内容都向后平移了 `ftyp.size`。
 *
 * 这个 bug 长期没被发现，是因为单元测试用的是人造的极简 box 布局 ——
 * `stco` 里的偏移是 0 或很小，改不改都"看起来对"。
 * `MoovRelocationRealLayoutTest` 现在会用带真实量级偏移的布局把它钉住。
 */
class MoovRelocatingSource private constructor(
    private val source: RandomAccessSource,
    private val segments: List<Segment>,
    /** moov 段的虚拟起始位置；-1 表示没有 moov 段。 */
    private val moovVirtualStart: Long,
    /** chunk 偏移需要加的增量。 */
    private val offsetDelta: Long,
) : RandomAccessSource {

    /**
     * 虚拟文件里的一段：从 [virtualStart] 开始读 [length] 字节，实际来自物理位置 [physicalStart]。
     *
     * [patched] 只给 moov 段用：搬动之后 chunk 偏移必须改写，
     * 而 moov 通常只有几百 KB，所以整段读进内存改一次最省事，
     * 也避免了「某个偏移项正好跨在块边界上」这类麻烦。
     */
    private class Segment(
        val virtualStart: Long,
        val physicalStart: Long,
        val length: Long,
        val patched: Boolean = false,
    )

    /** 顶层 box 的位置与长度。 */
    internal class Atom(val type: String, val offset: Long, val size: Long) {
        override fun toString() = "$type@$offset+$size"
    }

    override val size: Long get() = segments.sumOf { it.length }

    /** 改写后的 moov；只在第一次读到 moov 时构建。null 表示还没构建或不需要。 */
    @Volatile
    private var patchedMoov: ByteArray? = null

    @Volatile
    private var patchTried = false

    /**
     * 取（必要时构建并缓存）改写后的 moov。
     *
     * 用 `synchronized(this)` 保护：`read` 会从 ijkplayer 的多个线程进来
     * （读线程 + 解复用），不锁的话会重复读一遍几百 KB 的 moov，
     * 在 SMB 上白花时间。
     */
    private fun moovFor(seg: Segment): ByteArray? {
        patchedMoov?.let { return it }
        synchronized(this) {
            patchedMoov?.let { return it }
            if (patchTried) return null
            patchTried = true
            val patched = loadAndPatchMoov(seg, offsetDelta)
            patchedMoov = patched
            return patched
        }
    }

    override fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int): Int {
        if (offset < 0 || offset >= size) return -1

        var done = 0
        var pos = offset
        while (done < len && pos < size) {
            // 定位虚拟偏移落在哪一段
            var seg: Segment? = null
            for (s in segments) {
                if (pos >= s.virtualStart && pos < s.virtualStart + s.length) {
                    seg = s
                    break
                }
            }
            if (seg == null) break

            val inSeg = pos - seg.virtualStart
            val n = minOf((len - done).toLong(), seg.length - inSeg).toInt()
            if (n <= 0) break

            if (seg.patched) {
                val moov = moovFor(seg)
                if (moov == null) {
                    // 改写失败：退回原始字节。宁可偏移错，也不要整段读不出来 ——
                    // 上层至少还能拿到 moov 里的时长等信息。
                    val fail = source.read(seg.physicalStart + inSeg, buf, bufOffset + done, n)
                    if (fail <= 0) break
                    done += fail
                    pos += fail
                    continue
                }
                val avail = minOf(n, moov.size - inSeg.toInt())
                if (avail <= 0) break
                System.arraycopy(moov, inSeg.toInt(), buf, bufOffset + done, avail)
                done += avail
                pos += avail
                continue
            }

            val got = source.read(seg.physicalStart + inSeg, buf, bufOffset + done, n)
            if (got <= 0) break
            done += got
            pos += got
        }
        return if (done == 0) -1 else done
    }

    override fun close() {
        source.close()
    }

    /**
     * 读整个 moov 并改写其中的 chunk 偏移表。
     *
     * 为什么整段读进内存：moov 通常只有几百 KB（实测 412 KB ~ 2.5 MB），
     * 一次读完最省事，也避免了「某个偏移项正好跨在块边界上」这类容易出错的边界情况。
     * 超过 [MAX_PATCH_BYTES] 就不改写了 —— 宁可退化，也不要吃爆内存。
     */
    private fun loadAndPatchMoov(seg: Segment, delta: Long): ByteArray? {
        if (seg.length > MAX_PATCH_BYTES) {
            Log.w(TAG, "moov 有 ${seg.length} 字节，超过改写上限，跳过偏移改写")
            return null
        }
        val buf = ByteArray(seg.length.toInt())
        var got = 0
        // 注意：这里必须直接读底层源，不能走 this.read —— 否则会递归回到本函数
        while (got < buf.size) {
            val n = source.read(seg.physicalStart + got, buf, got, buf.size - got)
            if (n <= 0) break
            got += n
        }
        if (got != buf.size) {
            Log.w(TAG, "moov 只读到 $got/${buf.size} 字节，跳过偏移改写")
            return null
        }
        val patched = patchChunkOffsets(buf, delta)
        if (patched == 0) Log.w(TAG, "moov 里没找到 stco/co64，偏移未改写")
        else Log.i(TAG, "已改写 $patched 个 chunk 偏移项（+$delta）")
        return buf
    }

    companion object {
        private const val TAG = "FireflyMoov"

        private const val MAX_HEADER_BYTES = 32L * 1024 * 1024 // 只扫前 32MB 的 box 头

        /** moov 超过这个大小就不做偏移改写了（正常 moov 是几百 KB 到几 MB）。 */
        private const val MAX_PATCH_BYTES = 64L * 1024 * 1024

        /** 会往下递归的容器 box；其余当叶子。 */
        private val CONTAINER_BOXES = setOf(
            "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "moof", "traf",
        )

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

            // 找出 moov 之后、虚拟顺序里最靠前的那个 box：
            // 偏移的增量就是「它前面所有东西的总大小」减去「它原本前面的大小」。
            //
            // 常见情况（ftyp | free | mdat | moov）：
            //   重排后 mdat 前面多了 moov，而 moov 自己从尾部搬走了，净增量 = ftyp.size
            val firstAfterMoov = ordered.drop(2).firstOrNull { it.size > 0 }
            var precedingVirtual = 0L
            var delta = 0L
            if (firstAfterMoov != null) {
                for (a in ordered) {
                    if (a === firstAfterMoov) break
                    precedingVirtual += a.size
                }
                delta = precedingVirtual - firstAfterMoov.offset
            }

            val segments = ArrayList<Segment>(ordered.size)
            var virtual = 0L
            for (a in ordered) {
                if (a.size <= 0) continue
                segments += Segment(
                    virtualStart = virtual,
                    physicalStart = a.offset,
                    length = a.size,
                    patched = a === moov && delta != 0L,
                )
                virtual += a.size
            }

            Log.i(TAG, "chunk 偏移增量 = $delta（${firstAfterMoov?.type} 前面的内容变多了这么多）")
            val moovStart = segments.firstOrNull { it.patched }?.virtualStart ?: -1L
            return MoovRelocatingSource(source, segments, moovStart, delta)
        }

        /** 单个偏移项的最大增量，超过就认为是解析错了，不要冒险改写。 */
        private const val MAX_PATCH_OFFSET = 1L shl 40 // 1 TB

        /** 在已缓存的 moov 上改写偏移。返回改写的项数；0 表示没找到表。 */
        private fun patchChunkOffsets(moov: ByteArray, delta: Long): Int {
            var patched = 0
            walk(moov, 0, moov.size) { type, off, size ->
                when (type) {
                    "stco" -> patched += patchStco(moov, off, size, delta)
                    "co64" -> patched += patchCo64(moov, off, size, delta)
                }
            }
            return patched
        }

        private fun patchStco(b: ByteArray, off: Int, size: Int, delta: Long): Int {
            // 4 字节 version/flags + 4 字节 entry_count，之后是 N 个 32 位偏移
            val countOff = off + 4 + 4
            if (size < 16 || countOff + 4 > b.size) return 0
            val n = readUInt32(b, countOff - 4).toInt()
            var done = 0
            for (i in 0 until n) {
                val p = countOff + 4 * i
                if (p + 4 > off + size || p + 4 > b.size) break
                val old = readUInt32(b, p)
                val nv = old + delta
                if (nv < 0 || nv > MAX_PATCH_OFFSET) continue
                writeUInt32(b, p, nv)
                done++
            }
            return done
        }

        private fun patchCo64(b: ByteArray, off: Int, size: Int, delta: Long): Int {
            // 4 字节 version/flags + 4 字节 entry_count，之后是 N 个 64 位偏移
            val countOff = off + 4 + 4
            if (size < 20 || countOff + 4 > b.size) return 0
            val n = readUInt32(b, countOff - 4).toInt()
            var done = 0
            for (i in 0 until n) {
                val p = countOff + 8 * i
                if (p + 8 > off + size || p + 8 > b.size) break
                val old = readUInt64(b, p)
                val nv = old + delta
                if (nv < 0 || nv > MAX_PATCH_OFFSET) continue
                writeUInt64(b, p, nv)
                done++
            }
            return done
        }

        /**
         * 深度优先走 box 树，对每个 box 调用 [onBox]。
         *
         * 只认容器 box（`moov/trak/mdia/minf/stbl/edts/dinf` 等），
         * 其余一律当叶子跳过内容 —— 这样既快又不会误入 `stsz` 那种大表里瞎找。
         */
        private fun walk(b: ByteArray, start: Int, end: Int, onBox: (String, Int, Int) -> Unit) {
            var pos = start
            var guard = 0
            while (pos + 8 <= end && guard++ < 10_000) {
                var size = readUInt32(b, pos)
                val type = String(b, pos + 4, 4, Charsets.US_ASCII)
                var hdr = 8
                if (size == 1L) {
                    if (pos + 16 > end) return
                    size = readUInt64(b, pos + 8)
                    hdr = 16
                } else if (size == 0L) {
                    size = (end - pos).toLong()
                }
                if (size < hdr || pos + size > end) return
                onBox(type, pos, size.toInt())
                if (type in CONTAINER_BOXES) {
                    walk(b, pos + hdr, (pos + size).toInt(), onBox)
                }
                pos += size.toInt()
            }
        }

        private fun writeUInt32(b: ByteArray, off: Int, v: Long) {
            b[off] = ((v ushr 24) and 0xFF).toByte()
            b[off + 1] = ((v ushr 16) and 0xFF).toByte()
            b[off + 2] = ((v ushr 8) and 0xFF).toByte()
            b[off + 3] = (v and 0xFF).toByte()
        }

        private fun writeUInt64(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = ((v ushr (56 - 8 * i)) and 0xFF).toByte()
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
