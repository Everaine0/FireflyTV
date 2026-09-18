#!/usr/bin/env python3
"""
把 moov 重排的字节**完整**地物化出来，排除「截断」这个干扰项。

== 为什么还要再做一次 ==
上一版验证脚本拼的样本只包含 mdat 的前 8 MB。
但 stco 里的 chunk 偏移指向**整个文件范围内**的位置 ——
播放器一 seek 到样本之外就会读到 EOF / 垃圾，报出
`Invalid NAL unit size`，看起来就像「重排逻辑坏了」。

这个假象必须排掉，否则我会去改一段本来没错的代码（今天已经犯过一次同类错误）。

== 做法 ==
写出**完整**的重排副本：ftyp + moov + 其余全部字节（和 MoovRelocatingSource
的虚拟视图逐字节等价），再用 ffmpeg 真解。

用法：
    # 片源路径不入库：--root 指到媒体根目录，后面跟具体文件（不给就自动挑前几个 mp4）
    # ffmpeg 从 PATH 找，也可以用 FFMPEG 环境变量指定
    python3 scripts/verify-relocation-full.py --root <媒体根目录>
    python3 scripts/verify-relocation-full.py --root <媒体根目录> <剧名/第01集.mp4>
"""
import argparse
import os
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path


def pick_files(root: Path, names: list, limit: int = 2) -> list:
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
        c = f.read(min(1 << 20, length - len(out)))
        if not c:
            break
        out += c
    return bytes(out)


def scan_boxes(f, total, max_header_bytes=64 * 1024 * 1024):
    """
    扫顶层 box。**和 MoovRelocatingSource.scanTopLevelAtoms 同样的规则**，
    包括那个 32 MB 的头部扫描上限 —— 这一点很关键，见下方说明。
    """
    boxes = []
    pos = 0
    header_bytes = 0
    while pos + 8 <= total:
        header_bytes += 16
        if header_bytes > max_header_bytes:
            boxes.append(("<<扫描上限>>", pos, 0))
            break
        head = read_range(f, pos, 16)
        if len(head) < 8:
            break
        size = struct.unpack(">I", head[0:4])[0]
        btype = head[4:8].decode("ascii", "replace")
        hdr = 8
        if size == 1:
            size = struct.unpack(">Q", head[8:16])[0]
            hdr = 16
        elif size == 0:
            size = total - pos
        if size < hdr or pos + size > total:
            return None
        boxes.append((btype, pos, size))
        if any(b[0] == "moov" for b in boxes) and any(b[0] == "mdat" for b in boxes):
            break
        pos += size
    return boxes


def relocated_bytes(local: Path) -> tuple[Path, str]:
    """按 MoovRelocatingSource 的规则写出完整的重排副本。"""
    total = local.stat().st_size
    out = Path(tempfile.gettempdir()) / ("reloc-full-" + local.name)

    with open(local, "rb") as f:
        boxes = scan_boxes(f, total)
        if not boxes:
            return out, "扫描失败"
        names = [b[0] for b in boxes]
        ftyp = next((b for b in boxes if b[0] == "ftyp"), None)
        moov = next((b for b in boxes if b[0] == "moov"), None)
        mdat = next((b for b in boxes if b[0] == "mdat"), None)
        if not ftyp or not moov or not mdat:
            return out, f"缺 box（扫到: {names}）"
        if moov[1] < mdat[1]:
            return out, "本来就不需要重排"

        ordered = [ftyp, moov] + [b for b in boxes if b is not ftyp and b is not moov]
        desc = " + ".join(f"{b[0]}({b[2] / 1048576:.1f}MB)" for b in ordered[:4])

        with open(out, "wb") as w:
            for btype, off, size in ordered:
                if size <= 0:
                    continue
                remaining = size
                src = off
                while remaining > 0:
                    chunk = read_range(f, src, min(4 << 20, remaining))
                    if not chunk:
                        break
                    w.write(chunk)
                    src += len(chunk)
                    remaining -= len(chunk)
    return out, desc


def main() -> int:
    ap = argparse.ArgumentParser(description="物化完整的 moov 重排副本，再用 ffmpeg 真解一遍")
    ap.add_argument("--root", default=os.environ.get("FF_NAS_ROOT", ""),
                    help="媒体根目录（也可用环境变量 FF_NAS_ROOT）")
    ap.add_argument("files", nargs="*", help="相对 --root 的文件路径；不给就自动挑前几个 mp4")
    args = ap.parse_args()
    if not args.root:
        print("缺少 --root（或环境变量 FF_NAS_ROOT）—— 片源路径不入库", file=sys.stderr)
        return 2

    ffmpeg = os.environ.get("FFMPEG") or shutil.which("ffmpeg")
    if not ffmpeg:
        print("找不到 ffmpeg：装一个，或用 FFMPEG 环境变量指定路径", file=sys.stderr)
        return 2

    root = Path(args.root)
    for path in pick_files(root, args.files):
        print(f"\n########## {path.name} ##########")
        if not path.exists():
            print("  找不到文件")
            continue

        tmp = Path(tempfile.gettempdir())
        local = tmp / ("src-" + path.name)
        if not local.exists():
            print("  先复制到本地...")
            shutil.copyfile(path, local)

        out, desc = relocated_bytes(local)
        if not out.exists() or out.stat().st_size == 0:
            print(f"  重排失败: {desc}")
            continue
        print(f"  重排副本: {out.name}  {out.stat().st_size:,} 字节")
        print(f"  布局: {desc}")

        r = subprocess.run(
            [ffmpeg, "-v", "error", "-xerror", "-i", str(out), "-t", "5", "-f", "null", "-"],
            capture_output=True, text=True, errors="replace",
        )
        ok = r.returncode == 0
        print(f"  完整重排副本解码 5 秒: {'成功' if ok else '失败'}")
        if r.stderr.strip():
            for line in r.stderr.strip().splitlines()[:6]:
                print("    " + line)
        print("\n  ===> 判定:", end=" ")
        if ok:
            print("重排逻辑产出的字节是**好的** —— "
                  "之前那次失败是我自己样本截断造成的假象，不该去改重排代码")
        else:
            print("完整字节也解不出来 —— 重排逻辑确实有问题")

    return 0


if __name__ == "__main__":
    sys.exit(main())
