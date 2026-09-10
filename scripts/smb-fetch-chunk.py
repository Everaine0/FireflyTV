"""
Pull a small chunk of a file off an SMB share so it can be inspected locally.

Used to identify the real container format of files whose extension lies
(e.g. a ".mp4" that has no `ftyp` box).

    set FF_SMB_HOST=... FF_SMB_SHARE=... FF_SMB_USER=... FF_SMB_PASS=...
    python scripts/smb-fetch-chunk.py --path "电视剧/xxx/01.mp4" --out chunk.bin --mb 4
"""
import argparse
import os
import sys

from impacket.smbconnection import SMBConnection


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=os.environ.get("FF_SMB_HOST", ""))
    ap.add_argument("--share", default=os.environ.get("FF_SMB_SHARE", ""))
    ap.add_argument("--user", default=os.environ.get("FF_SMB_USER", ""))
    ap.add_argument("--password", default=os.environ.get("FF_SMB_PASS", ""))
    ap.add_argument("--domain", default=os.environ.get("FF_SMB_DOMAIN", ""))
    # SMB 路径用反斜杠，正好对应 App 里的 root + 相对路径
    ap.add_argument("--path", required=True, help=r"shared 内的路径，例如 电视剧/xxx/01.mp4")
    ap.add_argument("--out", required=True)
    ap.add_argument("--mb", type=int, default=4)
    args = ap.parse_args()

    if not (args.host and args.share and args.user):
        print("缺少连接信息 --host/--share/--user", file=sys.stderr)
        return 2

    path = args.path.replace("/", "\\")
    want = args.mb * 1024 * 1024

    conn = SMBConnection(args.host, args.host, sess_port=445, timeout=30)
    conn.login(args.user, args.password, args.domain or "")
    fid = conn.openFile(args.share, path, desiredAccess=0x00120089)  # GENERIC_READ
    try:
        data = bytearray()
        while len(data) < want:
            block = conn.readFile(fid, len(data), min(1024 * 1024, want - len(data)))
            if not block:
                break
            data.extend(block)
    finally:
        conn.closeFile(fid)
        conn.close()

    with open(args.out, "wb") as fh:
        fh.write(data)

    print(f"path     : {path}")
    print(f"got      : {len(data)} bytes -> {args.out}")
    head = bytes(data[:32])
    print("head hex :", head.hex())
    print("head txt :", "".join(chr(b) if 32 <= b <= 126 else "." for b in head))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
