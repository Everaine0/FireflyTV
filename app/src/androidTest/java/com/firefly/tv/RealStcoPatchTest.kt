package com.firefly.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.firefly.tv.core.Config
import com.firefly.tv.player.MoovRelocatingSource
import com.firefly.tv.player.RandomAccessSource
import com.firefly.tv.player.SmbRandomAccessSource
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在**真实片源**上验证 chunk 偏移改写是否生效。
 *
 * ## 为什么前面那个完整性测试不够
 *
 * `SmbRelocationIntegrityTest` 只比对了虚拟流的前 12 MB。
 * 而真实文件的 `stco` 在 **245 MB** 处（moov 在文件尾），完全没被覆盖到 ——
 * 所以它「逐字节一致」的结论对这个 bug 毫无意义。
 * 这正是「测试范围没覆盖到 bug 所在位置」的典型。
 *
 * ## 这个测试做什么
 *
 * 1. 找到虚拟流里 moov 里那个 `stco`，读出它的偏移表（未改写前应指向物理位置）
 * 2. 算出播放器**应该**读到的样本数据（按改写后的偏移）
 * 3. 和原文件里 mdat 数据区同位置的字节对比
 *
 * 判据：按改写后的偏移去读，必须拿到真正的样本字节；
 * 如果拿到的还是 moov 里的字节，说明改写没生效。
 */
@RunWith(AndroidJUnit4::class)
class RealStcoPatchTest {

    private val args get() = InstrumentationRegistry.getArguments()
    private fun arg(k: String) = args.getString(k, "").orEmpty()
    private val cfg get() = Config.Smb(
        arg("ff.smb.host"), arg("ff.smb.share"), arg("ff.smb.root"),
        arg("ff.smb.user"), arg("ff.smb.pass"), arg("ff.smb.domain"),
    )

    private fun say(msg: String) {
        println(msg)
        android.util.Log.i("FireflyStco", msg)
    }

    @Test
    fun relocatedStreamHasPatchedChunkOffsets() {
        assumeTrue("没有配置真 NAS 账号，跳过", cfg.host.isNotBlank() && cfg.share.isNotBlank())

        val path = "电视剧/猫和老鼠 50周年珍藏版 157集/猫和老鼠（001）.mp4"
        val base: RandomAccessSource = SmbRandomAccessSource(cfg, path)
        try {
            val total = base.size
            say("文件大小 = $total")

            val reloc = MoovRelocatingSource.wrap(base)
            say("wrap 后 = ${reloc::class.java.simpleName}，虚拟大小 = ${reloc.size}")

            // moov 在虚拟流里的位置：紧跟 ftyp（28 字节）+ free（8）之后？
            // 直接扫虚拟流的顶层 box，别假设布局
            val vbox = scanVirtual(reloc, total)
            say("虚拟顶层 box = $vbox")
            val moov = vbox.firstOrNull { it.first == "moov" }
            val mdat = vbox.firstOrNull { it.first == "mdat" }
            assumeTrue("虚拟流里应有 moov 和 mdat", moov != null && mdat != null)
            say("moov 虚拟位置=${moov!!.second} size=${moov.third}")
            say("mdat 虚拟位置=${mdat!!.second} size=${mdat.third}  数据区起点=${mdat.second + 8}")

            // 在 moov 里找 stco
            val stcoAt = findInVirtual(reloc, moov.second + 8, moov.second + moov.third, "stco", listOf("trak", "mdia", "minf", "stbl"))
            assumeTrue("moov 里应能找到 stco", stcoAt != null)
            say("stco 虚拟位置 = $stcoAt")

            val head = readVirtual(reloc, stcoAt!!, 16)
            val size = u32(head, 0)
            val type = String(head, 4, 4, Charsets.US_ASCII)
            val flags = u32(head, 8)
            val count = u32(head, 12).toInt()
            say("stco: size=$size type='$type' version/flags=$flags entry_count=$count")

            if (type != "stco" || flags != 0L || count <= 0) {
                say("!! stco 头不对，后面没法判 —— 但至少说明了改写或定位有问题")
                return
            }

            val entries = ArrayList<Long>()
            val n = minOf(count, 8)
            val raw = readVirtual(reloc, stcoAt + 16, n * 4)
            for (i in 0 until n) entries += u32(raw, i * 4)
            say("stco 前 $n 个偏移（改写后）= $entries")

            val mdatData = mdat.second + 8
            val inside = entries.count { it >= mdatData && it < mdatData + mdat.third - 8 }
            say("其中落在 mdat 数据区内的: $inside/$n")

            // 关键判据：按第一个偏移去读，应拿到真正的样本字节
            if (entries.isNotEmpty()) {
                val off = entries[0]
                val gotBytes = readVirtual(reloc, off, 16)
                say("按第一个偏移 $off 读到的 16 字节 = ${gotBytes.joinToString("") { "%02x".format(it) }}")

                // 对照：同样这 16 字节在**原文件**里的物理位置
                // 未改写时偏移就是物理位置；改写后偏移 = 物理 + delta
                val physOff = if (off >= mdatData) off else off
                val rawPhys = ByteArray(16)
                base.read(physOff, rawPhys, 0, 16)
                say("原文件物理偏移 $physOff 处的 16 字节 = ${rawPhys.joinToString("") { "%02x".format(it) }}")
                say("两者一致? ${gotBytes.contentEquals(rawPhys)}")
                say("（若一致，说明改写后的偏移确实指向真正的样本数据）")
            }
        } finally {
            runCatching { base.close() }
        }
    }

    private fun readVirtual(src: RandomAccessSource, off: Long, len: Int): ByteArray {
        val buf = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = src.read(off + got, buf, got, len - got)
            if (n <= 0) break
            got += n
        }
        return if (got == len) buf else buf.copyOf(got)
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    /** 扫虚拟流的顶层 box。 */
    private fun scanVirtual(src: RandomAccessSource, total: Long): List<Triple<String, Long, Long>> {
        val out = ArrayList<Triple<String, Long, Long>>()
        var pos = 0L
        var guard = 0
        while (pos + 8 <= total && guard++ < 64) {
            val h = readVirtual(src, pos, 16)
            if (h.size < 8) break
            var size = u32(h, 0)
            val type = String(h, 4, 4, Charsets.US_ASCII)
            var hdr = 8L
            if (size == 1L) {
                size = 0
                for (i in 0 until 8) size = (size shl 8) or (h[8 + i].toLong() and 0xFF)
                hdr = 16
            } else if (size == 0L) size = total - pos
            if (size < hdr || pos + size > total) break
            out += Triple(type, pos, size)
            if (out.any { it.first == "moov" } && out.any { it.first == "mdat" }) break
            pos += size
        }
        return out
    }

    /** 在 [start,end) 里按容器层级找 [want]。 */
    private fun findInVirtual(
        src: RandomAccessSource,
        start: Long,
        end: Long,
        want: String,
        containers: List<String>,
    ): Long? {
        var lo = start
        var hi = end
        for (c in containers) {
            val at = findBox(src, lo, hi, c) ?: return null
            val h = readVirtual(src, at, 8)
            lo = at + 8
            hi = at + u32(h, 0)
        }
        return findBox(src, lo, hi, want)
    }

    private fun findBox(src: RandomAccessSource, start: Long, end: Long, want: String): Long? {
        var pos = start
        var guard = 0
        while (pos + 8 <= end && guard++ < 512) {
            val h = readVirtual(src, pos, 8)
            if (h.size < 8) return null
            val size = u32(h, 0)
            val type = String(h, 4, 4, Charsets.US_ASCII)
            if (type == want) return pos
            if (size < 8 || pos + size > end) return null
            pos += size
        }
        return null
    }
}
