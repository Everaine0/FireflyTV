#!/usr/bin/env python3
"""
验证「moov 重排」这条路上喂给解码器的字节到底是不是好的。

== 为什么要做这个验证 ==
真实 NAS 上两部**需要 moov 重排**的片源（大宅门 / 猫和老鼠，都是 moov 在尾部）
在播放器里跑不起来，日志里全是：

    Invalid NAL unit size (2016484131 > 98).
    get_buffer() failed

而**不需要重排**的《娘道》（MPEG-TS）完全正常。
这个巧合太整齐了，所以要单独验一下重排出来的字节本身对不对 ——
如果字节是坏的，那就是 `MoovRelocatingSource` 的 bug；
如果字节是好的，才轮到怀疑模拟器解码能力。

== 做法 ==
按 `MoovRelocatingSource` 的逻辑把虚拟视图拼出来（ftyp | moov | mdat 前若干 MB），
写成临时文件，再用 ffmpeg 真解一遍（-xerror：出错即失败）。

== 为什么用 Python 而不是 PowerShell ==
中文路径 + UTF-8 在 PS 5.1 下反复出问题（脚本被按 GBK 解析，字符串直接断掉），
今天已经踩了三次。Python 处理这类事情是可靠的。

用法：
    python3 scripts/verify-moov-relocation.py
"""
import os
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

FFPROBE = r"<ffmpeg>\bin\ffprobe.exe"
FFMPEG = r"<ffmpeg>\bin\ffmpeg.exe"

NAS = Path(r"<NAS 媒体根目录>")
TARGETS = [
    ("大宅门(4K H265)", NAS / "大宅门" / "[大宅门].The.Grand.Mansion.Gate.2001.S01E01.2160p.WEB-DL.H265.AAC-HotWEB.mp4"),
    ("猫和老鼠(H265)", NAS / "猫和老鼠 50周年珍藏版 157集" / "猫和老鼠（001）.mp4"),
]

MDAT_TAKE = 8 * 1024 * 1024  # 拼进样本的 mdat 长度


def read_range(f, offset: int, length: int) -> bytes:
    f.seek(offset)
    out = bytearray()
    while len(out) < length:
        chunk = f.read(length - len(out))
        if not chunk:
            break
        out += chunk
    return bytes(out)


def top_level_boxes(f, total: int, limit: int = 64):
    """列出前若干个顶层 box（只读 box 头，很便宜）。"""
    boxes = []
    pos = 0
    while pos + 8 <= total and len(boxes) < limit:
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
            break
        boxes.append((btype, pos, size))
        if btype == "moov":
            pass
        pos += size
    return boxes


def find_moov_from_tail(f, total: int, window: int = 16 * 1024 * 1024):
    """从尾部往回找 moov 的 box 头（重排前必须先知道它在哪）。"""
    start = max(0, total - window)
    data = read_range(f, start, total - start)
    needle = b"moov"
    idx = data.rfind(needle)
    while idx >= 4:
        size = struct.unpack(">I", data[idx - 4:idx])[0]
        if 8 < size <= len(data):
            return start + idx - 4, size
        idx = data.rfind(needle, 0, idx)
    return None, None


def check(label: str, path: Path) -> bool:
    print(f"\n########## {label} ##########")
    if not path.exists():
        print("  找不到文件")
        return False
    total = path.stat().st_size
    print(f"  总大小: {total:,} 字节")

    with open(path, "rb") as f:
        boxes = top_level_boxes(f, total)
        print("  顶层 box（前几个）:")
        for btype, off, size in boxes[:4]:
            print(f"    {btype} @{off:,} size={size:,}")

        moov_off, moov_size = find_moov_from_tail(f, total)
        if moov_off is None:
            print("  尾部找不到 moov")
            return False
        print(f"  moov: @{moov_off:,} size={moov_size:,}")

        ftyp = next((b for b in boxes if b[0] == "ftyp"), None)
        mdat = next((b for b in boxes if b[0] == "mdat"), None)
        if not ftyp or not mdat:
            print("  缺 ftyp 或 mdat，不是能重排的结构")
            return False

        needs_relocate = moov_off > mdat[1]
        print(f"  需要重排吗: {needs_relocate}（moov {'在' if needs_relocate else '不在'} mdat 之后）")
        if not needs_relocate:
            print("  这个片源本来就不需要重排，换一个样本")
            return True

        # 按 MoovRelocatingSource 的逻辑拼虚拟视图
        ftyp_bytes = read_range(f, ftyp[1], ftyp[2])
        moov_bytes = read_range(f, moov_off, moov_size)
        mdat_take = min(MDAT_TAKE, mdat[2])
        mdat_bytes = read_range(f, mdat[1], mdat_take)

        out = Path(tempfile.gettempdir()) / f"relocated-{label[:6]}.mp4"
        out.write_bytes(ftyp_bytes + moov_bytes + mdat_bytes)
        print(f"  写出重排样本: {out} ({out.stat().st_size:,} 字节)")
        print(f"    结构 = ftyp({len(ftyp_bytes)}) + moov({len(moov_bytes)}) + mdat前{mdat_take:,}")

    print("  --- ffprobe 看流 ---")
    r = subprocess.run(
        [FFPROBE, "-v", "error", "-show_entries",
         "stream=index,codec_type,codec_name,width,height", "-of", "csv=p=0", str(out)],
        capture_output=True, text=True, errors="replace",
    )
    for line in (r.stdout or "").strip().splitlines():
        print("    " + line)
    if r.stderr.strip():
        print("    stderr: " + r.stderr.strip()[:200])

    print("  --- 真解码 2 秒（-xerror：出错即失败）---")
    r2 = subprocess.run(
        [FFMPEG, "-v", "error", "-xerror", "-i", str(out), "-t", "2", "-f", "null", "-"],
        capture_output=True, text=True, errors="replace",
    )
    if r2.stdout.strip():
        print("    stdout: " + r2.stdout.strip()[:400])
    if r2.stderr.strip():
        for line in r2.stderr.strip().splitlines()[:8]:
            print("    " + line)
    ok = r2.returncode == 0
    print(f"    退出码: {r2.returncode}  ->  {'字节是好的' if ok else '解码失败'}")
    return ok


def main():
    results = []
    for label, path in TARGETS:
        results.append((label, check(label, path)))
    print("\n===== 汇总 =====")
    for label, ok in results:
        print(f"  {label:<22} {'OK' if ok else '失败'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
