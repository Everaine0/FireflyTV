"""
Diagnose an SMB media library the way FireflyTV sees it.

Answers the questions that matter for the "moov atom not found" failure:
  - what libraries / shows / episodes are actually there
  - for each video: where is the `moov` box (front = faststart, tail = needs relocation)

Credentials are read from environment variables or a local file that is gitignored:
    set FF_SMB_HOST=192.168.1.100
    set FF_SMB_SHARE=media
    set FF_SMB_USER=...
    set FF_SMB_PASS=...

Usage:
    python scripts/smb-diag.py --root "" --limit 8
"""
import argparse
import os
import struct
import sys

from impacket.smbconnection import SMBConnection


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


def connect(args):
    conn = SMBConnection(args.host, args.host, sess_port=445, timeout=20)
    conn.login(args.user, args.password, args.domain or "")
    return conn


def list_dir(conn, share: str, path: str, limit: int = 200):
    """Return (dirs, files) for one level, sorted naturally-ish."""
    pattern = (path.rstrip("\\") + "\\*") if path else "*"
    dirs, files = [], []
    for e in conn.listPath(share, pattern):
        name = e.get_longname()
        if name in (".", ".."):
            continue
        if e.is_directory():
            dirs.append(name)
        else:
            files.append((name, e.get_filesize()))
    dirs.sort()
    files.sort()
    return dirs[:limit], files[:limit]


def read_at(conn, share: str, path: str, offset: int, length: int) -> bytes:
    fid = conn.openFile(share, path, desiredAccess=0x00120089)  # GENERIC_READ
    try:
        return conn.readFile(fid, offset, length)
    finally:
        conn.closeFile(fid)


def probe_moov(conn, share: str, path: str, size: int, header_scan: int = 64):
    """
    Walk top-level ISO-BMFF boxes and report where moov lives.
    Returns (verdict, detail) where verdict is 'faststart' | 'tail' | 'unknown' | 'no-moov'.
    """
    pos = 0
    boxes = []
    read = 0
    while pos + 8 <= size and read < header_scan and len(boxes) < 24:
        head = read_at(conn, share, path, pos, 16)
        read += 1
        if len(head) < 8:
            break
        bsize = struct.unpack(">I", head[0:4])[0]
        btype = head[4:8].decode("latin-1")
        header = 8
        if bsize == 1:
            if len(head) < 16:
                break
            bsize = struct.unpack(">Q", head[8:16])[0]
            header = 16
        elif bsize == 0:
            bsize = size - pos
        if bsize < header or pos + bsize > size:
            return "unknown", f"box {btype} 长度异常 ({bsize})"
        boxes.append((btype, pos, bsize))
        if btype == "moov" and any(b[0] == "mdat" for b in boxes):
            break
        if btype == "moov" or btype == "mdat":
            if any(b[0] == "moov" for b in boxes) and any(b[0] == "mdat" for b in boxes):
                break
        pos += bsize

    layout = " ".join(f"{t}@{o}" for t, o, _ in boxes)
    types = [b[0] for b in boxes]
    if "moov" not in types:
        return "no-moov", layout
    if "mdat" not in types:
        return "unknown", layout
    moov_off = next(b[1] for b in boxes if b[0] == "moov")
    mdat_off = next(b[1] for b in boxes if b[0] == "mdat")
    if moov_off < mdat_off:
        return "faststart", layout
    return "tail", layout


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=env("FF_SMB_HOST"))
    ap.add_argument("--share", default=env("FF_SMB_SHARE"))
    ap.add_argument("--user", default=env("FF_SMB_USER"))
    ap.add_argument("--password", default=env("FF_SMB_PASS"))
    ap.add_argument("--domain", default=env("FF_SMB_DOMAIN"))
    ap.add_argument("--root", default=env("FF_SMB_ROOT", ""))
    ap.add_argument("--limit", type=int, default=6, help="每层最多看几项")
    ap.add_argument("--probe", type=int, default=6, help="最多探测几个视频的 moov 位置")
    args = ap.parse_args()

    if not (args.host and args.share and args.user):
        print("缺少连接信息：需要 --host/--share/--user（或环境变量 FF_SMB_*）", file=sys.stderr)
        return 2

    conn = connect(args)
    print(f"已连接 {args.host} 共享 {args.share} 根目录 {args.root or '(空)'}")
    print()

    base = args.root
    dirs, files = list_dir(conn, args.share, base, args.limit)
    print(f"顶层目录：{dirs}")
    print(f"顶层文件：{[f[0] for f in files]}")
    print()

    video_ext = {"mp4", "mkv", "avi", "ts", "m2ts", "mov", "wmv", "flv", "rmvb", "rm"}
    probed = 0
    for d in dirs:
        sub = f"{base}\\{d}" if base else d
        sdirs, sfiles = list_dir(conn, args.share, sub, args.limit)
        has_m3u = any(f[0].lower().endswith((".m3u", ".m3u8")) for f in sfiles)
        kind = "直播库(含m3u)" if has_m3u else "视频库"
        print(f"[{kind}] {d}  子目录={sdirs}  文件数={len(sfiles)}")

        # 视频库：再下一层看剧集
        if not has_m3u and sdirs:
            for sd in sdirs[:2]:
                ss = f"{sub}\\{sd}"
                _, eps = list_dir(conn, args.share, ss, 40)
                vids = [f for f in eps if f[0].rsplit(".", 1)[-1].lower() in video_ext]
                print(f"    {sd}: {len(vids)} 个视频  {[v[0] for v in vids[:4]]}")
                for name, size in vids:
                    if probed >= args.probe:
                        break
                    p = f"{ss}\\{name}"
                    try:
                        verdict, layout = probe_moov(conn, args.share, p, size)
                    except Exception as exc:  # noqa: BLE001
                        print(f"      {name}: 探测失败 {exc}")
                        continue
                    probed += 1
                    mark = {"faststart": "✅ 头部", "tail": "⚠️ 尾部", "no-moov": "❔ 无moov",
                            "unknown": "❔ 未知"}[verdict]
                    print(f"      {name}  {size/1024/1024:.1f}MB  moov={mark}  [{layout}]")
    conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
