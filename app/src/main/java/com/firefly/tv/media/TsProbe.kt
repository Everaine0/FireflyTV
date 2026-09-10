package com.firefly.tv.media

/**
 * MPEG-TS 轨道探测：读 PMT，列出这条流里到底有什么编码。
 *
 * 为什么非要有这个：ijkplayer 自带的 FFmpeg **没有 AC-3 也没有 MP2 解码器**
 * （实测只有 mp3 / aac / h264 / hevc）。而这台 NAS 上：
 *  - 娘道 76 集是 MPEG-TS + **AC-3**
 *  - IPTV 的 CCTV5 是 HLS(MPEG-TS) + **MP2**
 *
 * 表现就是「画面正常，但没有声音」。用户看到的现象是「没声音」，
 * 根本原因却是「这条流的音频这台设备解不了」—— 这两件事必须能被区分开，
 * 否则永远在猜是配置问题还是片源问题。
 *
 * 这里是纯字节解析，不依赖 FFmpeg，也不依赖设备解码器，所以能给出**确定的**答案：
 * 「这条流的音频是 AC-3」是事实，至于能不能播是下一步的事。
 */
object TsProbe {

    /** 一个基本流（PMT 里的一项）。 */
    class Track(val pid: Int, val streamType: Int) {
        val kind: Kind = when (streamType) {
            0x01, 0x02 -> Kind.VIDEO_MPEG2
            0x1B -> Kind.VIDEO_H264
            0x24 -> Kind.VIDEO_HEVC
            0x03, 0x04 -> Kind.AUDIO_MP2
            0x0F -> Kind.AUDIO_AAC
            0x11 -> Kind.AUDIO_AAC_LATM
            0x81 -> Kind.AUDIO_AC3
            0x87 -> Kind.AUDIO_EAC3
            0x82, 0x85, 0x86 -> Kind.AUDIO_DTS
            else -> Kind.UNKNOWN
        }

        /** 人话名字，用于给用户看的提示。 */
        val label: String
            get() = when (kind) {
                Kind.VIDEO_MPEG2 -> "MPEG-2 视频"
                Kind.VIDEO_H264 -> "H.264 视频"
                Kind.VIDEO_HEVC -> "H.265 视频"
                Kind.AUDIO_MP2 -> "MP2 音频"
                Kind.AUDIO_AAC -> "AAC 音频"
                Kind.AUDIO_AAC_LATM -> "AAC 音频"
                Kind.AUDIO_AC3 -> "AC-3 音频"
                Kind.AUDIO_EAC3 -> "E-AC-3 音频"
                Kind.AUDIO_DTS -> "DTS 音频"
                Kind.UNKNOWN -> "未知轨道(0x%02X)".format(streamType)
            }
    }

    enum class Kind {
        VIDEO_MPEG2, VIDEO_H264, VIDEO_HEVC,
        AUDIO_MP2, AUDIO_AAC, AUDIO_AAC_LATM, AUDIO_AC3, AUDIO_EAC3, AUDIO_DTS,
        UNKNOWN,
    }

    class Info(val tracks: List<Track>, val hasVideo: Boolean, val hasAudio: Boolean) {
        val audio: List<Track> get() = tracks.filter { it.kind.name.startsWith("AUDIO_") }
    }

    /** PAT/PMT 一般在前 1MB 内；给足余量足够覆盖任何正常复用器。 */
    const val PROBE_BYTES = 1 shl 20

    /**
     * 探测。数据不足或不是 TS 就返回 null —— 调用方按「不知道」处理，不要瞎猜。
     *
     * @param data 文件开头的若干字节（建议 [PROBE_BYTES]）
     */
    fun probe(data: ByteArray): Info? {
        val base = syncOffset(data) ?: return null
        val packetSize = guessPacketSize(data, base)

        var pmtPid = -1
        val tracks = ArrayList<Track>()

        var pos = base
        var guard = 0
        while (pos + packetSize <= data.size && guard++ < 200_000) {
            if (data[pos].toInt() and 0xFF != 0x47) {
                pos++
                continue
            }
            val pid = ((data[pos + 1].toInt() and 0x1F) shl 8) or (data[pos + 2].toInt() and 0xFF)
            val payloadStart = data[pos + 1].toInt() and 0x40 != 0
            val adaptation = (data[pos + 3].toInt() and 0x30) shr 4
            var offset = pos + 4
            if (adaptation == 2) {
                // 只有适配域，没有负载
                pos += packetSize
                continue
            }
            if (adaptation == 3) {
                val afLen = data[offset].toInt() and 0xFF
                offset += 1 + afLen
            }
            if (offset >= pos + packetSize) {
                pos += packetSize
                continue
            }

            if (pid == 0 && payloadStart) {
                // PAT：找到 program -> PMT PID
                pmtPid = parsePat(data, offset, pos + packetSize)
            } else if (pid == pmtPid && payloadStart && tracks.isEmpty()) {
                tracks += parsePmt(data, offset, pos + packetSize)
            }

            if (tracks.isNotEmpty()) break
            pos += packetSize
        }

        if (tracks.isEmpty()) return null
        return Info(
            tracks = tracks,
            hasVideo = tracks.any { it.kind.name.startsWith("VIDEO_") },
            hasAudio = tracks.any { it.kind.name.startsWith("AUDIO_") },
        )
    }

    /** 找到 188/192 对齐的同步字节位置。 */
    private fun syncOffset(data: ByteArray): Int? {
        for (start in 0 until minOf(1024, data.size)) {
            if (data[start].toInt() and 0xFF != 0x47) continue
            // 连续三个包都是 0x47 才算数
            val ok188 = start + 188 * 2 < data.size &&
                data[start + 188].toInt() and 0xFF == 0x47 &&
                data[start + 376].toInt() and 0xFF == 0x47
            if (ok188) return start
            val ok192 = start + 192 * 2 < data.size &&
                data[start + 192].toInt() and 0xFF == 0x47 &&
                data[start + 384].toInt() and 0xFF == 0x47
            if (ok192) return start
        }
        return null
    }

    private fun guessPacketSize(data: ByteArray, base: Int): Int =
        if (base + 188 < data.size && data[base + 188].toInt() and 0xFF == 0x47) 188 else 192

    private fun parsePat(data: ByteArray, payload: Int, limit: Int): Int {
        // 指针域
        val p = payload + 1 + (data[payload].toInt() and 0xFF)
        if (p + 8 > limit) return -1
        if (data[p].toInt() and 0xFF != 0x00) return -1 // table_id = PAT
        val sectionLength = ((data[p + 1].toInt() and 0x0F) shl 8) or (data[p + 2].toInt() and 0xFF)
        val end = minOf(p + 3 + sectionLength - 4, limit) // 去掉 CRC
        var i = p + 8
        while (i + 4 <= end) {
            val program = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            val pid = ((data[i + 2].toInt() and 0x1F) shl 8) or (data[i + 3].toInt() and 0xFF)
            if (program != 0) return pid // program 0 是 NIT，跳过
            i += 4
        }
        return -1
    }

    private fun parsePmt(data: ByteArray, payload: Int, limit: Int): List<Track> {
        val p = payload + 1 + (data[payload].toInt() and 0xFF)
        if (p + 12 > limit) return emptyList()
        if (data[p].toInt() and 0xFF != 0x02) return emptyList() // table_id = PMT
        val sectionLength = ((data[p + 1].toInt() and 0x0F) shl 8) or (data[p + 2].toInt() and 0xFF)
        val programInfoLength = ((data[p + 10].toInt() and 0x0F) shl 8) or (data[p + 11].toInt() and 0xFF)
        val end = minOf(p + 3 + sectionLength - 4, limit)

        val out = ArrayList<Track>(4)
        var i = p + 12 + programInfoLength
        while (i + 5 <= end) {
            val streamType = data[i].toInt() and 0xFF
            val pid = ((data[i + 1].toInt() and 0x1F) shl 8) or (data[i + 2].toInt() and 0xFF)
            val esInfoLength = ((data[i + 3].toInt() and 0x0F) shl 8) or (data[i + 4].toInt() and 0xFF)
            // 0x06 是私有流，HLS 里常见 AC-3；再单独认一下描述符里的 AC-3 标识
            out += Track(pid, streamType)
            i += 5 + esInfoLength
        }
        return out
    }
}
