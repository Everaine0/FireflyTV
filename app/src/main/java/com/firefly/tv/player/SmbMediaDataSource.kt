package com.firefly.tv.player

import android.util.Log
import com.firefly.tv.core.Config
import tv.danmaku.ijk.media.player.misc.IMediaDataSource

/**
 * ijkplayer 不认 SMB（DESIGN §6 的核心难点），必须自己桥接数据。
 *
 * [readAt] 必须是**随机读**，否则拖进度与续播会失灵。ijkplayer 的读线程基本是顺序读，
 * 所以按块缓存最近若干块，命中即返回；真正跳读才回源。
 *
 * 构造时就套上 [MoovRelocatingSource]：非 faststart 的 mp4 需要把 moov 搬到头部才能播
 * （DESIGN 风险 8）。本来就在头部的文件会原样透传，没有额外开销。
 *
 * 不做断线自愈重试：会话失效时直接抛错，由播放器触发 onError，
 * 界面层统一走「无法连接 NAS」故障页并每 10 秒重试（DESIGN §8）。
 */
class SmbMediaDataSource(underlying: RandomAccessSource) : IMediaDataSource {

    private val source: RandomAccessSource = MoovRelocatingSource.wrap(underlying)

    constructor(cfg: Config.Smb, relativePath: String) : this(SmbRandomAccessSource(cfg, relativePath))

    /** accessOrder = true：表头是最久未用的块。 */
    private val blocks = LinkedHashMap<Long, ByteArray>(CACHE_BLOCKS + 1, 1f, true)

    private var closed = false
    private val callCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun getSize(): Long = source.size

    @Synchronized
    override fun readAt(position: Long, buf: ByteArray, offset: Int, len: Int): Int {
        if (closed) return -1
        val total = source.size
        if (len <= 0 || position < 0 || position >= total) return -1

        var written = 0
        while (written < len) {
            val pos = position + written
            if (pos >= total) break

            val blockStart = pos / BLOCK * BLOCK
            val block = loadBlock(blockStart, total)
            val inBlock = (pos - blockStart).toInt()
            if (inBlock >= block.size) break // 防御：不该发生，避免死循环

            val n = minOf(len - written, block.size - inBlock)
            System.arraycopy(block, inBlock, buf, offset + written, n)
            written += n
        }
        val result = if (written == 0) -1 else written
        // 只记前几次，避免刷屏；排查播放问题时这几行就够定位
        val n = callCount.incrementAndGet()
        if (n <= 8) {
            Log.d(TAG, "readAt#$n pos=$position len=$len -> $result (size=$total)")
        }
        return result
    }

    /**
     * 以块为单位回源。否则每次 readAt 都是一次 SMB 往返，4K 片源必然卡死。
     * 返回值可能短于 [BLOCK]（文件末尾）。
     */
    private fun loadBlock(blockStart: Long, total: Long): ByteArray {
        blocks[blockStart]?.let { return it }

        val want = minOf(BLOCK.toLong(), total - blockStart).toInt()
        val buf = ByteArray(want)
        var got = 0
        while (got < want) {
            // 单次 SMB 读也有上限，循环补齐
            val n = source.read(blockStart + got, buf, got, want - got)
            if (n <= 0) break
            got += n
        }

        val result = if (got == want) buf else buf.copyOf(got)
        if (result.isEmpty()) return result

        blocks[blockStart] = result
        while (blocks.size > CACHE_BLOCKS) {
            val oldest = blocks.keys.first()
            blocks.remove(oldest)
        }
        return result
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        blocks.clear()
        runCatching { source.close() }
        Log.d(TAG, "data source closed")
    }

    companion object {
        private const val TAG = "FireflySMB"

        /** 256KB/块：4K 片源一次回源能喂饱解码器，又不至于在 2GB RAM 上太占。 */
        private const val BLOCK = 256 * 1024

        /** 8 块 ≈ 2MB，够覆盖顺序读 + 偶尔的跳读。 */
        private const val CACHE_BLOCKS = 8
    }
}
