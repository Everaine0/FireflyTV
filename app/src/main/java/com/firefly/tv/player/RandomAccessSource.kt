package com.firefly.tv.player

import com.firefly.tv.core.Config
import com.firefly.tv.smb.SmbStore
import java.io.Closeable
import java.io.RandomAccessFile

/**
 * 供 ijkplayer 随机读取的字节源。
 *
 * 抽出这一层是为了能测：SMB 版本跑真机真 NAS，本地文件版本可以在模拟器上
 * 跑插桩测试验证 [SmbMediaDataSource] 的 readAt 逻辑，不需要真的搭一台 NAS。
 */
interface RandomAccessSource : Closeable {
    val size: Long

    /** 从 [offset] 读最多 [len] 字节到 [buf] 的 [bufOffset]；返回实际读到的字节数，末尾返回 -1。 */
    fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int): Int
}

/** SMB 上的文件。不在这里做断线重试：由播放器报错、界面走故障页。 */
class SmbRandomAccessSource(cfg: Config.Smb, relativePath: String) : RandomAccessSource {

    private val handle = SmbStore.with(cfg) { it.open(relativePath) }

    override val size: Long get() = handle.size

    override fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int): Int =
        handle.read(offset, buf, bufOffset, len)

    override fun close() {
        runCatching { handle.close() }
    }
}

/**
 * 本地文件。两个用途：
 *  - 模拟器上的插桩测试，验证 readAt 的随机读语义
 *  - 将来若要支持 U 盘播放，直接用这个实现
 */
class FileRandomAccessSource(path: String) : RandomAccessSource {

    private val file = RandomAccessFile(path, "r")

    override val size: Long get() = file.length()

    override fun read(offset: Long, buf: ByteArray, bufOffset: Int, len: Int): Int {
        if (offset >= file.length()) return -1
        file.seek(offset)
        return file.read(buf, bufOffset, len)
    }

    override fun close() {
        runCatching { file.close() }
    }
}

/** 整块在内存里的字节源。插桩测试用来隔离「块缓存逻辑」和「底层读」两类问题。 */
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
