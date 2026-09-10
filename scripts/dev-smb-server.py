"""
Runs a tiny SMB2 server on the Windows host so the Android TV emulator can
exercise the real SMB path (the emulator reaches the host loopback at 10.0.2.2).

Needs impacket:
    python -m pip install --target <本地媒体目录> impacket
    set PYTHONPATH=<本地媒体目录>

Usage:
    python scripts/dev-smb-server.py --root <本地媒体目录> --port 4450
"""
import argparse
import logging
import sys
import threading

from impacket import smbserver
from impacket.ntlm import compute_lmhash, compute_nthash


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=r"<本地媒体目录>", help="directory to share")
    ap.add_argument("--share", default="media", help="share name")
    ap.add_argument("--port", type=int, default=4450)
    ap.add_argument("--user", default="firefly")
    ap.add_argument("--password", default="firefly")
    ap.add_argument("--guest", action="store_true", help="accept any credentials instead of a fixed account")
    args = ap.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    server = smbserver.SimpleSMBServer(listenAddress="0.0.0.0", listenPort=args.port)
    server.setSMB2Support(True)
    server.setSMBChallenge("")
    server.addShare(args.share.upper(), args.root, "FireflyTV test media", readOnly="yes")

    if args.guest:
        # 未认证：直接当成 guest，方便不关心凭据的场景
        def auth_callback(conn_id, sess_id, user_name, domain, challenge, lm_hash, nt_hash, error):
            if user_name and user_name.lower() not in ("guest", "anonymous", ""):
                return smbserver.SMBSERVER_STATUS_LOGON_FAILURE

        server.setAuthCallback(auth_callback)
        print("auth: guest (any credentials accepted)", flush=True)
    else:
        server.addCredential(args.user, 0, compute_lmhash(args.password), compute_nthash(args.password))
        print(f"auth: {args.user} / {args.password}", flush=True)

    print(f"share : //<host>:{args.port}/{args.share.upper()}  ->  {args.root}", flush=True)
    print("from the emulator use host 10.0.2.2", flush=True)

    server.start()
    # start() 内部起线程后立刻返回，主线程必须自己挂住，否则进程直接退出
    stop = threading.Event()
    try:
        while not stop.wait(3600):
            pass
    except KeyboardInterrupt:
        pass
    finally:
        server.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
