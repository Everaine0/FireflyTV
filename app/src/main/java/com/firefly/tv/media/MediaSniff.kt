package com.firefly.tv.media

/**
 * 按**内容**判断文件是不是能播的媒体。
 *
 * 为什么需要：后缀名经常是错的。实测遇到过整季《娘道》76 集全叫 `.mp4`，
 * 实际是 MPEG-TS（头 4 字节 `47 40 00 10`，没有 `ftyp`）。
 * 只看后缀的话，这种目录会被当成「没有视频」直接跳过 —— 老人会发现整部剧不见了。
 *
 * 只在「按后缀一个视频都没找到」时才回退到内容探测，避免给正常目录增加 SMB 往返。
 */
object MediaSniff {

    /** 探测需要的字节数：够看 MPEG-TS 的包间隔和常见 magic。 */
    const val HEAD_BYTES = 1024

    /**
     * 只带后缀的快速判断（不产生 IO）。命中就是命中，没命中不代表不是视频。
     */
    fun looksLikeVideoByName(name: String): Boolean = MediaExt.isVideo(name)

    /**
     * 按内容判断。只认几种最可靠的容器 magic：
     *  - MPEG-TS：0x47 开头且每 188 字节重复一次
     *  - ISO BMFF（mp4/mov/3gp）：偏移 4 处是 `ftyp`
     *  - Matroska/WebM：EBML magic `1A 45 DF A3`
     *  - AVI：`RIFF` + 偏移 8 处 `AVI `
     *  - ASF/WMV：GUID 头 `30 26 B2 75 8E 66 CF 11`
     *
     * 认不出来就返回 false（宁可不显示，也不要把图片/字幕当视频去播）。
     */
    fun looksLikeVideoByContent(head: ByteArray): Boolean {
        if (head.size < 16) return false
        return isMpegTs(head) || isIsoBmff(head) || isMatroska(head) || isAvi(head) || isAsf(head)
    }

    private fun isMpegTs(b: ByteArray): Boolean {
        if (b[0] != 0x47.toByte()) return false
        // 标准 TS 每 188 字节一个包；有些是 192（带 4 字节时间戳前缀），两种都认
        if (b.size >= 189 && b[188] == 0x47.toByte()) return true
        if (b.size >= 193 && b[192] == 0x47.toByte()) return true
        // 包更小的情况（例如只有几十字节的头）只能靠首字节，风险偏高，不放行
        return false
    }

    private fun isIsoBmff(b: ByteArray): Boolean =
        b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
            b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()

    private fun isMatroska(b: ByteArray): Boolean =
        b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() &&
            b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()

    private fun isAvi(b: ByteArray): Boolean =
        b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'A'.code.toByte() && b[9] == 'V'.code.toByte() &&
            b[10] == 'I'.code.toByte() && b[11] == 0x20.toByte()

    private fun isAsf(b: ByteArray): Boolean {
        val guid = byteArrayOf(
            0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66,
            0xCF.toByte(), 0x11, 0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(),
            0x00, 0x62, 0xCE.toByte(), 0x6C,
        )
        for (i in guid.indices) if (b[i] != guid[i]) return false
        return true
    }
}
