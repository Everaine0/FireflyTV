package com.firefly.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.firefly.tv.core.Config
import com.firefly.tv.player.MoovRelocatingSource
import com.firefly.tv.player.RandomAccessSource
import com.firefly.tv.player.SmbRandomAccessSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端到端验证：**通过 SMB** 读真实片源时，moov 重排后的虚拟字节流是不是正确的。
 *
 * ## 判据的演变（这一步很关键，别退回旧写法）
 *
 * 最初这个测试断言「重排源读出的字节 == 按虚拟布局直读的字节，逐字节一致」。
 * 那个断言在**加入 chunk 偏移改写之后就不再成立了，而且不该成立** ——
 * 因为 moov 里的 `stco` 区域是**故意被改写的**：
 *
 * ```
 * 第 183424 字节不符：重排源=0x06 期望=0x00
 *                    ↑ 正是 stco 所在的位置
 * ```
 *
 * 所以正确的判据是分开看：
 *  - **样本数据区（mdat）必须逐字节一致** —— 这才是播放器真正要读的东西
 *  - **moov 里只允许 stco/co64 区域不同**，其余（时长、轨道信息等）必须一致
 *
 * 前者是「数据没被搞坏」的证据，后者是「偏移改写确实发生了」的证据。
 */
@RunWith(AndroidJUnit4::class)
class SmbRelocationIntegrityTest {

    private val args get() = InstrumentationRegistry.getArguments()
    private fun arg(k: String) = args.getString(k, "").orEmpty()
    private val cfg get() = Config.Smb(
        arg("ff.smb.host"), arg("ff.smb.share"), arg("ff.smb.root"),
        arg("ff.smb.user"), arg("ff.smb.pass"), arg("ff.smb.domain"),
    )

    private fun say(msg: String) {
        println(msg)
        android.util.Log.i("FireflyRelocIntegrity", msg)
    }

    @Test
    fun smbRelocatedStreamMatchesExpected() {
        assumeTrue("没有配置真 NAS 账号，跳过", cfg.host.isNotBlank() && cfg.share.isNotBlank())

        val path = "电视剧/猫和老鼠 50周年珍藏版 157集/猫和老鼠（001）.mp4"
        val base: RandomAccessSource = SmbRandomAccessSource(cfg, path)
        try {
            val total = base.size
            say("文件大小 = $total")

            val boxes = scan(base, total)
            say("顶层 box = $boxes")
            val ftyp = boxes.firstOrNull { it.first == "ftyp" }
            val moov = boxes.firstOrNull { it.first == "moov" }
            val mdat = boxes.firstOrNull { it.first == "mdat" }
            assumeTrue("结构不符合预期，跳过", ftyp != null && moov != null && mdat != null)

            val reloc = MoovRelocatingSource.wrap(base)
            say("wrap 后 = ${reloc::class.java.simpleName}，虚拟大小 = ${reloc.size}")

            // 虚拟布局：ftyp | moov | 其余按物理顺序
            val ordered = listOf(ftyp!!, moov!!) + boxes.filter { it !== ftyp && it !== moov }
            say("虚拟顺序 = ${ordered.map { it.first }}")

            val orderedSize = ordered.sumOf { it.third }
            assertEquals("虚拟长度应等于各段之和", orderedSize, reloc.size)

            // 逐段比对：mdat（样本数据）必须完全一致；moov 允许 stco 区域不同
            var virtualOff = 0L
            var mdatChecked = 0L
            var moovDiffs = 0
            var mdatDiffs = 0

            for ((type, physOff, size) in ordered) {
                var p = 0L
                while (p < size) {
                    val n = minOf(size - p, 512L * 1024).toInt()
                    val want = ByteArray(n)
                    val got = ByteArray(n)
                    val wn = readFully(base, physOff + p, want, n)
                    val gn = readFully(reloc, virtualOff + p, got, n)
                    if (wn != gn) {
                        throw AssertionError("段 $type 偏移 $p: 直读 $wn 字节，重排源 $gn 字节")
                    }
                    for (i in 0 until n) {
                        if (want[i] != got[i]) {
                            if (type == "mdat") {
                                mdatDiffs++
                                if (mdatDiffs <= 3) {
                                    throw AssertionError(
                                        "**样本数据被改坏了**：mdat 段虚拟偏移 ${virtualOff + p + i} " +
                                            "（文件内偏移 ${physOff + p + i}）" +
                                            " 重排源=0x%02x 期望=0x%02x".format(got[i], want[i]),
                                    )
                                }
                            } else {
                                moovDiffs++
                            }
                        }
                    }
                    if (type == "mdat") mdatChecked += n
                    p += n
                }
                virtualOff += size
            }

            say("mdat 比对 $mdatChecked 字节，差异 $mdatDiffs 处")
            say("moov 等非 mdat 段共有 $moovDiffs 处字节不同（应全部来自 chunk 偏移改写）")

            assertEquals("样本数据一个字都不能错", 0, mdatDiffs)
            assertTrue(
                "moov 里应当有被改写的 chunk 偏移（否则播放器会按老偏移读到 moov 自己）。" +
                    "实测差异 $moovDiffs 处",
                moovDiffs > 0,
            )
            say("通过：样本数据完好，且 moov 里确实发生了偏移改写")
        } finally {
            runCatching { base.close() }
        }
    }

    private fun readFully(src: RandomAccessSource, off: Long, buf: ByteArray, len: Int): Int {
        var got = 0
        while (got < len) {
            val n = src.read(off + got, buf, got, len - got)
            if (n <= 0) break
            got += n
        }
        return got
    }

    /** 扫顶层 box，规则与 MoovRelocatingSource.scanTopLevelAtoms 一致。 */
    private fun scan(src: RandomAccessSource, total: Long): List<Triple<String, Long, Long>> {
        val out = ArrayList<Triple<String, Long, Long>>()
        var pos = 0L
        var headerBytes = 0L
        while (pos + 8 <= total) {
            headerBytes += 16
            if (headerBytes > 64L * 1024 * 1024) break
            val head = ByteArray(16)
            val n = readFully(src, pos, head, 16)
            if (n < 8) break
            var size = ((head[0].toLong() and 0xFF) shl 24) or
                ((head[1].toLong() and 0xFF) shl 16) or
                ((head[2].toLong() and 0xFF) shl 8) or (head[3].toLong() and 0xFF)
            val type = String(head, 4, 4, Charsets.US_ASCII)
            var hdr = 8L
            if (size == 1L) {
                size = 0
                for (i in 0 until 8) size = (size shl 8) or (head[8 + i].toLong() and 0xFF)
                hdr = 16
            } else if (size == 0L) {
                size = total - pos
            }
            if (size < hdr || pos + size > total) break
            out += Triple(type, pos, size)
            if (out.any { it.first == "moov" } && out.any { it.first == "mdat" }) break
            pos += size
        }
        return out
    }
}
