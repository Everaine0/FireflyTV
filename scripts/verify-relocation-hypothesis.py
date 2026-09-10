#!/usr/bin/env python3
"""
判定「需要 moov 重排的片源解不出来」到底怪谁。

== 两个候选 ==
  A. 源文件本身的数据是坏的（下载不完整 / 编码就有问题）
  B. 数据是好的，是 `MoovRelocatingSource` 重排后喂错了字节

== 怎么一锤定音 ==
拿 ffmpeg 把文件**重新封装**一份（`-c copy` 不重新编码，只重建 moov 并 faststart 化）。
- 如果重封装后能正常解码 → 源数据没问题，责任在重排逻辑（B）
- 如果重封装后也解不出来 → 源数据本身就是坏的（A）

这个判据不依赖我读 box / 读 stco 的正确性，所以不会自己出错。

用法：
    python3 scripts/verify-relocation-hypothesis.py
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

FFMPEG = r"<ffmpeg>\bin\ffmpeg.exe"
FFPROBE = r"<ffmpeg>\bin\ffprobe.exe"

NAS = Path(r"<NAS 媒体根目录>")
TARGETS = [
    ("猫和老鼠(H265 2960x2160)", NAS / "猫和老鼠 50周年珍藏版 157集" / "猫和老鼠（001）.mp4"),
    ("大宅门(H265 3840x2160)", NAS / "大宅门" / "[大宅门].The.Grand.Mansion.Gate.2001.S01E01.2160p.WEB-DL.H265.AAC-HotWEB.mp4"),
]


def decode_ok(path: Path, seconds: int = 3) -> tuple[bool, str]:
    """真解码若干秒。返回 (是否成功, 摘要)。"""
    r = subprocess.run(
        [FFMPEG, "-v", "error", "-xerror", "-i", str(path),
         "-t", str(seconds), "-f", "null", "-"],
        capture_output=True, text=True, errors="replace",
    )
    tail = ""
    if r.stderr.strip():
        lines = [l for l in r.stderr.strip().splitlines() if l.strip()]
        tail = " | ".join(lines[:3])[:300]
    return r.returncode == 0, tail


def main():
    print("先复制到本地临时目录：网络路径上反复读大文件太慢，而且 -c copy 需要稳定 IO")
    for label, path in TARGETS:
        print(f"\n########## {label} ##########")
        if not path.exists():
            print("  找不到文件")
            continue

        tmpdir = Path(tempfile.gettempdir())
        local = tmpdir / ("src-" + path.name)
        if not local.exists():
            print(f"  复制中（{path.stat().st_size / 1048576:.0f} MB）...")
            shutil.copyfile(path, local)
        print(f"  本地副本: {local} ({local.stat().st_size:,} 字节)")

        # 1) 直接解原文件（moov 在尾部）—— ffmpeg 能自己找到 moov，所以这一步应当成功
        ok_raw, msg_raw = decode_ok(local)
        print(f"  [1] 直接解原文件（ffmpeg 自己找尾部 moov）: {'成功' if ok_raw else '失败'}")
        if msg_raw:
            print(f"      {msg_raw}")

        # 2) 重封装成 faststart（重建 moov 并搬到头部，chunk 偏移由 ffmpeg 重写）
        remux = tmpdir / ("remux-" + path.name)
        if not remux.exists():
            print("  重封装中（-c copy，只重建 moov）...")
            r = subprocess.run(
                [FFMPEG, "-v", "error", "-y", "-i", str(local),
                 "-c", "copy", "-movflags", "+faststart", str(remux)],
                capture_output=True, text=True, errors="replace",
            )
            if r.returncode != 0:
                print(f"  重封装失败: {r.stderr.strip()[:300]}")
                continue
        print(f"  重封装产物: {remux} ({remux.stat().st_size:,} 字节)")

        ok_remux, msg_remux = decode_ok(remux)
        print(f"  [2] 解重封装后的文件（moov 在头部，偏移已重写）: "
              f"{'成功' if ok_remux else '失败'}")
        if msg_remux:
            print(f"      {msg_remux}")

        # 3) 结论
        print("\n  ===> 判定:", end=" ")
        if ok_raw and ok_remux:
            print("源数据是好的，且 moov 提前后能播 —— "
                  "所以问题在**我们自己的 moov 重排逻辑**（MoovRelocatingSource）")
        elif ok_raw and not ok_remux:
            print("原文件能解、重封装反而不能 —— 少见，需要看 ffmpeg 的具体报错")
        elif not ok_raw:
            print("原文件本身就解不出来 —— 源数据有问题，不是重排的锅")

    print("\n（临时文件留在 %TEMP%，需要时自行清理）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
