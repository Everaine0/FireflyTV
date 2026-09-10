package com.firefly.tv.smb

/**
 * 测试用：判断某个视频的 moov box 在文件头还是文件尾。
 *
 * ijkplayer 0.8.8 在 IMediaDataSource 通道下只顺序读、不回尾读 moov，
 * 所以「moov 在尾部」的片源必须靠 [com.firefly.tv.player.MoovRelocatingSource] 重排才能播。
 * 这个探测用来确认重排到底有没有被触发。
 */
object SmbTestProbe {

    private const val HEADER_READS = 24

    /** 返回 "front" / "tail" / "unknown"（非 mp4、结构异常、读不到都算 unknown）。 */
    fun probeMoov(smb: SmbClient, path: String): String {
        val handle = smb.open(path)
        return try {
            val size = handle.size
            var pos = 0L
            val boxes = ArrayList<Pair<String, Long>>()
            var guard = 0

            while (pos + 8 <= size && guard < HEADER_READS) {
                guard++
                val head = ByteArray(16)
                val got = handle.read(pos, head, 0, 16)
                if (got < 8) break

                var bsize = u32(head, 0)
                val type = String(head, 4, 4, Charsets.US_ASCII)
                var header = 8L
                if (bsize == 1L) {
                    if (got < 16) break
                    bsize = u64(head, 8)
                    header = 16L
                } else if (bsize == 0L) {
                    bsize = size - pos
                }
                if (bsize < header || pos + bsize > size) return "unknown"

                boxes += type to pos
                if (boxes.any { it.first == "moov" } && boxes.any { it.first == "mdat" }) break
                pos += bsize
            }

            val moov = boxes.firstOrNull { it.first == "moov" }?.second ?: return "unknown"
            val mdat = boxes.firstOrNull { it.first == "mdat" }?.second ?: return "unknown"
            if (moov < mdat) "front" else "tail"
        } catch (_: Throwable) {
            "unknown"
        } finally {
            handle.close()
        }
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}
