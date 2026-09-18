#!/usr/bin/env python3
"""
把 moov 里的 stco（chunk 偏移表）读出来，和文件的物理布局对照。

== 要回答的问题 ==
把 moov 从文件尾搬到文件头之后，播放器就解不出画面了，
但 ffmpeg 自己 remux 出来的文件能播。差别只可能在**偏移**上。

stco 存的是 chunk 在文件里的位置。如果它存的是**绝对偏移**，
那么"只搬家不改偏移"应当仍然正确；如果实际行为对不上，
就说明我们的假设哪里错了 —— 必须看真实数字，不能靠推理。

== 为什么重写一版 ==
上一版的 stco 解析少减了一个 4 字节的 version/flags，读出来的是 0 个条目。
工具错了就会得出错误结论（今天已经栽过一次），所以这版每一步都打印原始字节。

用法：
    # 片源路径不入库：--root 指到媒体根目录，后面跟具体文件（不给就自动挑前几个 mp4）
    python3 scripts/dump-stco.py --root <媒体根目录>
    python3 scripts/dump-stco.py --root <媒体根目录> <剧名/第01集.mp4>
"""
import argparse
import os
import struct
import sys
from pathlib import Path


def pick_files(root: Path, names: list, limit: int = 3) -> list:
    """给了文件名就用给的；没给就在 --root 下自动挑前几个 mp4（只扫到够数为止）。"""
    if names:
        return [root / n for n in names]
    out = []
    for p in root.rglob("*.mp4"):
        out.append(p)
        if len(out) >= limit:
            break
    return out


def read_range(f, offset, length):
    f.seek(offset)
    out = bytearray()
    while len(out) < length:
        c = f.read(length - len(out))
        if not c:
            break
        out += c
    return bytes(out)


def find_box(f, start, end, want, depth=0):
    """在 [start,end) 里找第一个类型为 want 的 box，返回 (offset, size, header)。"""
    pos = start
    while pos + 8 <= end:
        head = read_range(f, pos, 16)
        if len(head) < 8:
            return None
        size = struct.unpack(">I", head[0:4])[0]
        btype = head[4:8].decode("ascii", "replace")
        hdr = 8
        if size == 1:
            size = struct.unpack(">Q", head[8:16])[0]
            hdr = 16
        elif size == 0:
            size = end - pos
        if size < hdr or pos + size > end:
            return None
        if btype == want:
            return (pos, size, hdr)
        pos += size
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description="把 moov 里的 stco 摊开，和物理布局对照")
    ap.add_argument("--root", default=os.environ.get("FF_NAS_ROOT", ""),
                    help="媒体根目录（也可用环境变量 FF_NAS_ROOT）")
    ap.add_argument("files", nargs="*", help="相对 --root 的文件路径；不给就自动挑前几个 mp4")
    args = ap.parse_args()
    if not args.root:
        print("缺少 --root（或环境变量 FF_NAS_ROOT）—— 片源路径不入库", file=sys.stderr)
        return 2

    root = Path(args.root)
    for path in pick_files(root, args.files):
        print(f"\n########## {path.name} ##########")
        if not path.exists():
            print("  找不到文件")
            continue
        total = path.stat().st_size
        print(f"  文件总大小 = {total:,}")

        with open(path, "rb") as f:
            # 顶层
            top = []
            pos = 0
            while pos + 8 <= total:
                head = read_range(f, pos, 16)
                size = struct.unpack(">I", head[0:4])[0]
                btype = head[4:8].decode("ascii", "replace")
                if size == 1:
                    size = struct.unpack(">Q", head[8:16])[0]
                elif size == 0:
                    size = total - pos
                if size < 8 or pos + size > total:
                    break
                top.append((btype, pos, size))
                pos += size
                if btype == "moov":
                    break

            print("  顶层 box:")
            for btype, off, size in top:
                print(f"    {btype:<6} @{off:>14,} size={size:>14,}  ends@{off + size:>14,}")

            moov = next((b for b in top if b[0] == "moov"), None)
            mdat = next((b for b in top if b[0] == "mdat"), None)
            if not moov or not mdat:
                print("  缺 moov / mdat")
                continue

            print(f"\n  mdat box @{mdat[1]:,} size={mdat[2]:,}")
            print(f"  mdat 数据区 = [{mdat[1] + 8:,} .. {mdat[1] + mdat[2]:,})")
            print(f"  moov box @{moov[1]:,} size={moov[2]:,}")

            # 在 moov 里找所有 trak -> mdia -> minf -> stbl -> stco
            print("\n  遍历 moov 找 stco:")
            trak_pos = moov[1] + 8
            moov_end = moov[1] + moov[2]
            idx = 0
            while True:
                trak = find_box(f, trak_pos, moov_end, "trak")
                if not trak:
                    break
                idx += 1
                # trak -> mdia
                mdia = find_box(f, trak[0] + 8, trak[0] + trak[1], "mdia")
                if not mdia:
                    trak_pos = trak[0] + trak[1]
                    continue
                minf = find_box(f, mdia[0] + 8, mdia[0] + mdia[1], "minf")
                if not minf:
                    trak_pos = trak[0] + trak[1]
                    continue
                stbl = find_box(f, minf[0] + 8, minf[0] + minf[1], "stbl")
                if not stbl:
                    trak_pos = trak[0] + trak[1]
                    continue

                # 判断轨道类型
                hdlr = find_box(f, mdia[0] + 8, mdia[0] + mdia[1], "hdlr")
                handler = "?"
                if hdlr:
                    hb = read_range(f, hdlr[0] + 16, 4)
                    handler = hb.decode("ascii", "replace")

                stco = find_box(f, stbl[0] + 8, stbl[0] + stbl[1], "stco")
                co64 = find_box(f, stbl[0] + 8, stbl[0] + stbl[1], "co64")
                print(f"\n    trak#{idx} handler={handler}  stbl@{stbl[0]:,}")
                if stco:
                    # find_box 返回的是 box 起点，version/flags 要再跳过 8 字节的 box 头
                    pos, size, hdr = stco
                    body = pos + hdr
                    raw = read_range(f, body, min(size - hdr, 8 + 4 * 6))
                    print(f"      stco @{pos:,} size={size:,}")
                    print(f"        原始字节: {raw.hex()}")
                    version_flags = struct.unpack(">I", raw[0:4])[0]
                    count = struct.unpack(">I", raw[4:8])[0]
                    print(f"        version/flags=0x{version_flags:08x}  entry_count={count}")
                    ents = [struct.unpack(">I", raw[8 + 4 * i:12 + 4 * i])[0]
                            for i in range(min(count, 5))]
                    print(f"        前几个偏移: {[hex(e) for e in ents]}")
                    print(f"        (十进制)   {ents}")
                    inside = sum(1 for i in range(count)
                                 if struct.unpack(">I", read_range(f, body + 8 + 4 * i, 4))[0]
                                 >= mdat[1] + 8)
                    print(f"        落在 mdat 起始之后的: {inside}/{count}")
                if co64:
                    pos, size, hdr = co64
                    raw = read_range(f, pos + hdr, min(size - hdr, 8 + 8 * 4))
                    version_flags = struct.unpack(">I", raw[0:4])[0]
                    count = struct.unpack(">I", raw[4:8])[0]
                    print(f"      co64 @{pos:,} size={size:,} count={count} 原始={raw[:32].hex()}")

                trak_pos = trak[0] + trak[1]

    return 0


if __name__ == "__main__":
    sys.exit(main())
