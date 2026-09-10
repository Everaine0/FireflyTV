package com.firefly.tv.media

import com.firefly.tv.core.Config
import com.firefly.tv.smb.SmbStore

/**
 * m3u 解析：`#EXTINF` 行给频道名，紧随其后的非注释行是地址。
 * 按用户确认：地址是 HTTP/HTTPS 链接，直接交给 ijkplayer 拉流，不走 SMB 桥接。
 */
object M3uParser {

    fun parse(text: String): List<Library.Channel> {
        val out = ArrayList<Library.Channel>()
        var pendingName: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> pendingName = nameOf(line)
                line.startsWith("#") -> Unit // 其他指令忽略
                else -> {
                    // #EXTGRP 或裸 URL
                    if (isStreamUrl(line)) {
                        out += Library.Channel(pendingName ?: "频道 ${out.size + 1}", line)
                    }
                    pendingName = null
                }
            }
        }
        return out
    }

    private fun isStreamUrl(s: String): Boolean {
        val l = s.lowercase()
        return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("rtsp://") ||
            l.startsWith("rtmp://") || l.startsWith("udp://") || l.startsWith("rtp://")
    }

    /** `#EXTINF:-1 tvg-name="CCTV1" group-title="央视",CCTV-1 综合` → 逗号后的显示名优先。 */
    private fun nameOf(line: String): String? {
        val comma = line.lastIndexOf(',')
        val afterComma = if (comma >= 0) line.substring(comma + 1).trim() else ""
        if (afterComma.isNotEmpty()) return afterComma

        val tvg = Regex("""tvg-name="([^"]*)"""").find(line)?.groupValues?.get(1)
        return tvg?.takeIf { it.isNotBlank() }
    }
}

/** 直播库读取：拉 m3u 文本 → 平铺频道列表。 */
object LiveSource {

    private const val MAX_BYTES = 8 * 1024 * 1024

    fun channels(cfg: Config.Smb, lib: Library.Live): List<Library.Channel> {
        val text = SmbStore.with(cfg) { smb ->
            smb.open("${lib.name}/${lib.m3u}").use { h ->
                val size = minOf(h.size, MAX_BYTES.toLong()).toInt()
                val buf = ByteArray(size)
                var got = 0
                while (got < size) {
                    val n = h.read(got.toLong(), buf, got, size - got)
                    if (n <= 0) break
                    got += n
                }
                String(buf, 0, got, Charsets.UTF_8)
            }
        }
        return M3uParser.parse(text)
    }
}
