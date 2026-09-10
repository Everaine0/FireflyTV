#!/usr/bin/env python3
"""
把 moov 里的关键表摊开，回答一个问题：**重排后 chunk 偏移还对得上吗？**

== 背景 ==
`MoovRelocatingSource` 把 moov 搬到文件头后，播放器仍然解不出画面
（在电脑上用 ffmpeg -xerror 同样失败，所以不是模拟器的问题）。
最可能的原因是 **moov 里的 chunk 偏移表（stco/co64）和重排后的虚拟布局不匹配**。

stco 里存的是**绝对文件偏移**。重排只是改变了字节在虚拟文件里的位置，
物理位置没变，所以理论上 stco 应该仍然有效 —— 但"理论上"必须验。

== 顺便核对我自己的扫描 ==
上一版脚本用 `rfind(b"moov")` 从尾部找 moov，可能命中 mdat 数据里的巧合字节。
这里改用**真实的 box 链**逐个走，两者对不上就说明扫描不可靠。

用法：
    python3 scripts/inspect-mp4-moov.py
"""
import struct
import sys
from pathlib import Path

NAS = Path(r"<NAS 媒体根目录>")
TARGETS = [
    ("大宅门", NAS / "大宅门" / "[大宅门].The.Grand.Mansion.Gate.2001.S01E01.2160p.WEB-DL.H265.AAC-HotWEB.mp4"),
    ("猫和老鼠", NAS / "猫和老鼠 50周年珍藏版 157集" / "猫和老鼠（001）.mp4"),
]

CONTAINERS = {
    b"moov": [b"mvhd", b"trak", b"mvex", b"udta", b"iods"],
    b"trak": [b"tkhd", b"mdia", b"edts", b"tref"],
    b"mdia": [b"mdhd", b"hdlr", b"minf", b"elng"],
    b"minf": [b"vmhd", b"smhd", b"dinf", b"stbl", b"hmhd"],
    b"stbl": [b"stsd", b"stts", b"stsc", b"stsz", b"stco", b"co64", b"stss", b"ctts", b"sgpd", b"sbgp"],
    b"edts": [b"elst"],
    b"dinf": [b"dref"],
}


def read_range(f, offset, length):
    f.seek(offset)
    out = bytearray()
    while len(out) < length:
        c = f.read(length - len(out))
        if not c:
            break
        out += c
    return bytes(out)


def walk(f, start, end, depth=0, out=None):
    """递归走 box 树（只走容器 box，不读数据体）。"""
    if out is None:
        out = []
    pos = start
    while pos + 8 <= end:
        head = read_range(f, pos, 16)
        if len(head) < 8:
            break
        size = struct.unpack(">I", head[0:4])[0]
        btype = head[4:8]
        hdr = 8
        if size == 1:
            if len(head) < 16:
                break
            size = struct.unpack(">Q", head[8:16])[0]
            hdr = 16
        elif size == 0:
            size = end - pos
        if size < hdr or pos + size > end:
            out.append((depth, btype.decode("ascii", "replace"), pos, size, "长度越界"))
            break
        out.append((depth, btype.decode("ascii", "replace"), pos, size, ""))
        kids = CONTAINERS.get(btype)
        if kids:
            walk(f, pos + hdr, pos + size, depth + 1, out)
        pos += size
    return out


def parse_stco(f, off, size):
    """stco: 4 字节 version/flags + 4 字节 entry_count + N*4 字节绝对偏移。"""
    body = read_range(f, off + 8, size - 8)
    n = struct.unpack(">I", body[0:4])[0]
    entries = [struct.unpack(">I", body[4 + 4 * i:8 + 4 * i])[0] for i in range(min(n, 8))]
    return n, entries


def main():
    for label, path in TARGETS:
        print(f"\n########## {label} ##########")
        if not path.exists():
            print("  找不到文件")
            continue
        total = path.stat().st_size
        print(f"  总大小: {total:,}")

        with open(path, "rb") as f:
            # 1) 用真实 box 链走顶层
            boxes = []
            pos = 0
            while pos + 8 <= total and len(boxes) < 12:
                head = read_range(f, pos, 16)
                size = struct.unpack(">I", head[0:4])[0]
                btype = head[4:8].decode("ascii", "replace")
                if size == 1:
                    size = struct.unpack(">Q", head[8:16])[0]
                elif size == 0:
                    size = total - pos
                boxes.append((btype, pos, size))
                pos += size
            print("  顶层 box（真实链）:")
            for btype, off, size in boxes:
                print(f"    {btype:<6} @{off:>14,}  size={size:>14,}")

            moov = next((b for b in boxes if b[0] == "moov"), None)
            mdat = next((b for b in boxes if b[0] == "mdat"), None)
            if not moov or not mdat:
                print("  缺 moov 或 mdat")
                continue

            mdat_data_start = mdat[1] + 8
            mdat_data_end = mdat[1] + mdat[2]
            print(f"\n  mdat 数据区: [{mdat_data_start:,} .. {mdat_data_end:,})")

            # 2) 走 moov 内部，找所有 stco / co64
            print("\n  moov 内部结构:")
            for depth, btype, off, size, note in walk(f, moov[1] + 8, moov[1] + moov[2]):
                pad = "    " + "  " * depth
                extra = f"  {note}" if note else ""
                print(f"{pad}{btype:<6} @{off:>14,}  size={size:>12,}{extra}")

            print("\n  chunk 偏移表检查:")
            for depth, btype, off, size, note in walk(f, moov[1] + 8, moov[1] + moov[2]):
                if btype == "stco":
                    n, entries = parse_stco(f, off, size)
                    print(f"    stco: {n} 个条目，前几个 = {entries}")
                    in_range = [e for e in entries if mdat_data_start <= e < mdat_data_end]
                    print(f"      落在 mdat 数据区内的: {len(in_range)}/{len(entries)}")
                    if entries and not in_range:
                        print("      [!] 偏移**不在** mdat 数据区内 —— 重排后必然解不出来")
                elif btype == "co64":
                    body = read_range(f, off + 8, min(size - 8, 4 + 8 * 4))
                    n = struct.unpack(">I", body[0:4])[0]
                    entries = [struct.unpack(">Q", body[4 + 8 * i:12 + 8 * i])[0] for i in range(min(n, 8))]
                    print(f"    co64: {n} 个条目，前几个 = {entries}")

            # 3) 直接把 mdat 开头当成裸 HEVC 试解，确认数据本身是好的
            print("\n  对照：把 mdat 数据区开头单独抽出来当裸流试解")
            raw = read_range(f, mdat_data_start, 2 * 1024 * 1024)
            tmp = Path("build-out/verify/mdat-raw.bin")
            tmp.parent.mkdir(parents=True, exist_ok=True)
            tmp.write_bytes(raw)
            print(f"    写出 {tmp} ({len(raw):,} 字节)，前 16 字节 = {raw[:16].hex()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
