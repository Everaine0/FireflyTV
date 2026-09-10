package com.firefly.tv.player

/**
 * 整块已经在内存里的字节源。
 *
 * 用途：
 *  - 单元/插桩测试里替换 SMB 或文件，隔离「块缓存与重排逻辑」和「底层读」
 *  - 将来若要支持播放极小的本地片段（例如开机问候视频），可以直接塞内存
 */
class ByteArrayRandomAccessSource(private val data: ByteArray) : RandomAccessSource {

    override val size: Long get() = data.size.toLong()

    override fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int): Int {
        if (offset < 0 || offset >= data.size) return -1
        val n = minOf(len.toLong(), data.size - offset).toInt()
        System.arraycopy(data, offset.toInt(), buf, bufOffset, n)
        return n
    }

    override fun close() = Unit
}
