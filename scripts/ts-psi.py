#!/usr/bin/env python3
"""
MPEG-TS 的 PAT/PMT 解析与校验。

== 这个脚本存在的理由（一个很贵的教训）==
排查「娘道没声音」时，我写工具去读这条流的 PAT/PMT，得到的结论是
「PAT 的 section_length=176（正常是 13）、PMT_PID=0x0AC3（流里根本不存在）、
CRC 全错」—— 于是判断「片源的表是坏的」。**这个结论是错的，而且错得很隐蔽。**

两个原因叠在一起：

1. **每个 PSI 包的 payload 第一个字节是 `pointer_field`**，section 从它之后才开始。
   没跳过这一个字节，就会从错误偏移读 section，`section_length` 变成 176，
   再往后读就全是垃圾 —— 而垃圾里恰好出现了 `0x0AC3` 这个数，
   看着特别像「AC-3 的标记」，把排查方向彻底带偏。

2. **MPEG-TS 的 CRC 不是 zlib 那套。** 用 `zlib.crc32` 去校验，
   会把**所有正确的表都判成损坏**。识破办法：拿一份已知正确的 TS 校验工具本身，
   如果连 ffmpeg 亲手生成的样本都「全部 CRC 错误」，那错的必然是校验器。

所以这个脚本做两件事：正确解析（跳过 pointer_field），
并且在 CRC 对不上时**明确说明这可能是校验器的问题而不是文件的问题**。

用法：
    python3 scripts/ts-psi.py <file.ts> [更多文件...] [扫描字节数]
"""
import sys
from collections import Counter

PACKET = 188
SYNC = 0x47

STREAM_TYPES = {
    0x01: "MPEG-1 Video", 0x02: "MPEG-2 Video", 0x03: "MPEG-1 Audio",
    0x04: "MPEG-2 Audio", 0x0F: "AAC (ADTS)", 0x10: "MPEG-4 Video",
    0x11: "AAC (LATM)", 0x1B: "H.264", 0x24: "HEVC",
    0x81: "AC-3 (ATSC)", 0x82: "DTS", 0x83: "TrueHD", 0x84: "E-AC-3",
    0x87: "E-AC-3 (ATSC)", 0x06: "PES 私有(看 descriptor)",
    0x90: "PGS 字幕", 0x15: "ID3 元数据",
}
DESCRIPTORS = {
    0x0A: "ISO_639_language", 0x2A: "DVB AC-3", 0x7A: "DVB E-AC-3",
    0x7B: "DVB DTS", 0x7C: "AAC", 0x56: "teletext", 0x59: "DVB 字幕",
    0x6A: "AC-3 (ETSI)", 0x7F: "extension", 0x52: "stream_identifier",
}

# 音频 stream_type：判定「这条流有没有音频」只看这个，不看 CRC
AUDIO_TYPES = {0x03, 0x04, 0x0F, 0x11, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87}
VIDEO_TYPES = {0x01, 0x02, 0x10, 0x1B, 0x24}


def crc32_mpeg2(data: bytes) -> int:
    """CRC-32/MPEG-2：多项式 0x04C11DB7，初值全 1，不反射、不取反。"""
    crc = 0xFFFFFFFF
    for b in data:
        crc ^= b << 24
        for _ in range(8):
            if crc & 0x80000000:
                crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF
            else:
                crc = (crc << 1) & 0xFFFFFFFF
    return crc


def payload_of(pkt: bytes):
    """取 TS 包负载，处理自适应域。"""
    afc = (pkt[3] >> 4) & 0x03
    if not (afc & 0x01):
        return None
    off = 4
    if afc & 0x02:
        off += 1 + pkt[4]
    return pkt[off:] if off < PACKET else None


def sections(data: bytes):
    """
    产出 (pos, pid, table_id, section)。

    **关键：跳过 pointer_field。** 这是整个脚本最容易写错的地方，
    写错了不会报错，只会安静地给出错误答案。
    """
    for pos in range(0, len(data) - PACKET + 1, PACKET):
        pkt = data[pos:pos + PACKET]
        if pkt[0] != SYNC:
            continue
        pid = ((pkt[1] & 0x1F) << 8) | pkt[2]
        if pid == 0x1FFF or not (pkt[1] & 0x40):
            continue
        pl = payload_of(pkt)
        if not pl:
            continue
        ptr = pl[0]                      # <<< pointer_field
        sec = pl[1 + ptr:]
        if not sec or sec[0] == 0xFF:
            continue
        slen = ((sec[1] & 0x0F) << 8) | sec[2]
        if slen < 4 or len(sec) < 3 + slen:
            continue
        yield pos, pid, sec[0], sec[:3 + slen]


def parse_pat(sec):
    """PAT -> [(program, pmt_pid)]。程序号 0 是 NIT，跳过。"""
    out = []
    body = sec[8:-4]
    for i in range(0, len(body) - 3, 4):
        prog = (body[i] << 8) | body[i + 1]
        pid = ((body[i + 2] & 0x1F) << 8) | body[i + 3]
        if prog == 0 or pid == 0x1FFF:
            continue
        out.append((prog, pid))
    return out


def parse_pmt(sec):
    """PMT -> (pcr_pid, [(stream_type, pid, descriptors)])。"""
    pcr = ((sec[8] & 0x1F) << 8) | sec[9]
    pil = ((sec[10] & 0x0F) << 8) | sec[11]
    i, end = 12 + pil, len(sec) - 4
    streams = []
    while i + 5 <= end:
        st = sec[i]
        pid = ((sec[i + 1] & 0x1F) << 8) | sec[i + 2]
        ln = ((sec[i + 3] & 0x0F) << 8) | sec[i + 4]
        streams.append((st, pid, sec[i + 5:i + 5 + ln]))
        i += 5 + ln
    return pcr, streams


def describe(desc):
    out, i = [], 0
    while i + 2 <= len(desc):
        tag, ln = desc[i], desc[i + 1]
        body = desc[i + 2:i + 2 + ln]
        out.append("desc tag=0x%02X(%s) raw=%s"
                   % (tag, DESCRIPTORS.get(tag, "?"), body.hex()))
        i += 2 + ln
    return out


def check(path, limit):
    with open(path, "rb") as f:
        data = f.read(limit)
    print("\n===== %s (读了 %d 字节) =====" % (path, len(data)))

    counts = Counter()
    for pos in range(0, len(data) - PACKET + 1, PACKET):
        pkt = data[pos:pos + PACKET]
        if pkt[0] == SYNC:
            counts[((pkt[1] & 0x1F) << 8) | pkt[2]] += 1

    print("  真实出现的 PID:")
    for pid, c in sorted(counts.items(), key=lambda kv: -kv[1])[:8]:
        print("    PID=0x%04X(%5d) %6d 包" % (pid, pid, c))

    pats, pmts = {}, {}
    for pos, pid, tid, sec in sections(data):
        ok = crc32_mpeg2(sec[:-4]) == int.from_bytes(sec[-4:], "big")
        if tid == 0x00:
            pats.setdefault(ok, (pos, pid, sec))
        elif tid == 0x02:
            pmts.setdefault(ok, (pos, pid, sec))

    if not pats and not pmts:
        print("\n  [!] 没解析出任何 PAT/PMT。先怀疑解析，再怀疑文件。")
        return

    for src, name, parser in ((pats, "PAT", parse_pat), (pmts, "PMT", parse_pmt)):
        # CRC 不对也照样解析 —— 内容对不对和 CRC 对不对是两件事，
        # 而 CRC 校验本身可能是错的（见文件头说明）
        for ok in (True, False):
            if ok not in src:
                continue
            pos, pid, sec = src[ok]
            tag = "CRC 校验通过" if ok else "CRC 对不上（可能是校验器的问题，见文件头说明）"
            print("\n  == %s  包@%d  PID=0x%04X  section_length=%d  [%s]"
                  % (name, pos, pid, ((sec[1] & 0x0F) << 8) | sec[2], tag))
            print("     raw: %s" % sec.hex())
            if name == "PAT":
                for prog, p in parser(sec):
                    mark = "" if p in counts else "   <-- 这个 PID 在流里不存在"
                    print("     program=%d PMT_PID=0x%04X(%d)%s" % (prog, p, p, mark))
            else:
                pcr, streams = parser(sec)
                print("     PCR_PID=0x%04X(%d)" % (pcr, pcr))
                n_audio = n_video = 0
                for st, spid, desc in streams:
                    present = "在流中" if spid in counts else "!! 流里没这个 PID"
                    print("     stream_type=0x%02X (%-18s) PID=0x%04X(%d)  %s"
                          % (st, STREAM_TYPES.get(st, "未知"), spid, spid, present))
                    for d in describe(desc):
                        print("        %s" % d)
                    if st in AUDIO_TYPES:
                        n_audio += 1
                    if st in VIDEO_TYPES:
                        n_video += 1
                print("     >>> 视频轨 %d 条，音频轨 %d 条" % (n_video, n_audio))
                if n_audio == 0:
                    print("     >>> 这条流没有音频轨")


def main():
    argv = [a for a in sys.argv[1:] if not a.startswith("-")]
    limit = 16 * 1024 * 1024
    files = []
    for a in argv:
        if a.isdigit():
            limit = int(a)
        else:
            files.append(a)
    if not files:
        print(__doc__)
        return 1
    for p in files:
        check(p, limit)
    return 0


if __name__ == "__main__":
    sys.exit(main())
