#!/usr/bin/env python3
"""TV 远程诊断客户端（配合应用里的 DiagHub，端口 8642）。

用途：电视上「4K 送显只有 18 帧」这类问题必须**换一个变量、量一次数字**。
应用在 debug 包里开了一个无鉴权的局域网 HTTP 口（见 app/src/main/java/com/firefly/tv/diag/），
这个脚本是它的操作端：读状态、切旋钮、扫一遍实验矩阵并给出对照表。

只用标准库，Windows / WSL / Linux 都能跑：

    python3 scripts/tvprobe.py --url http://192.0.2.34:8642 state
    python3 scripts/tvprobe.py watch 30
    python3 scripts/tvprobe.py preset 4
    python3 scripts/tvprobe.py set player.framedrop=0 player.video-pictq-size=8
    python3 scripts/tvprobe.py sweep --presets 1,3,4,7 --sec 20
    python3 scripts/tvprobe.py measure "测试/4k.mp4" "测试/1080p.mp4"
    python3 scripts/tvprobe.py ls 电视剧
    python3 scripts/tvprobe.py find 大宅门
    python3 scripts/tvprobe.py open "电视剧/大宅门/01.mkv"

地址也可以走环境变量：TV_URL=http://192.0.2.34:8642
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_URL = os.environ.get("TV_URL", "http://127.0.0.1:8642")

# 直连局域网，**绝不走代理**。
# 这台机器上配着 http_proxy（127.0.0.1:7897），而 urllib 默认会读它 ——
# 结果发往电视 192.0.2.248 的请求被代理转手，代理回了 502，
# 现象是「同一个接口有时通、有时 Bad Gateway」，白白浪费一轮排查。
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(base, path, params=None, timeout=60):
    url = base.rstrip("/") + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    try:
        with _OPENER.open(url, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
    except urllib.error.URLError as e:
        sys.exit(f"连不上 {url}：{e}\n（确认电视上开着应用、端口是 8642、和这台机器在同一局域网）")
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return raw


def fmt_row(label, value):
    return f"  {label:<12}{value}"


def cmd_state(a):
    d = call(a.url, "/state")
    app, sc, su, ct = d["app"], d["screen"], d["surface"], d["content"]
    v, dec, au, ve = d["video"], d["decoder"], d["audio"], d["verdict"]
    print(f"地址   {app['url']}  端口 {app['port']}  运行 {app['uptime_s']}s")
    print(fmt_row("屏幕", f"{sc['w']}×{sc['h']} dpi {sc['dpi']} density {sc['density']} "
                        f"缩放 ×{sc['ui_scale']} @{sc['refresh_hz']}Hz"))
    print(fmt_row("视频层", f"{su['w']}×{su['h']} 面 {su['frame']} 格式 {su['format']} "
                          f"层级 {su['zorder']} 固定 {su['fixed']}"))
    print(fmt_row("内容", f"{ct['title']} ({ct['kind']}) {ct['pos_ms']}ms/{ct['dur_ms']}ms 起播={ct['prepared']}"))
    print(fmt_row("画面", f"{v['w']}×{v['h']} 片源 {v['source_fps']} 帧/秒"))
    print(fmt_row("帧率", f"解码 {v['decode_fps']} · 送显 {v['present_fps']} · 丢帧 {v['drop_ratio']*100:.0f}%"))
    if "pixel_present_mpx" in v:
        print(fmt_row("像素率", f"送显 {v['pixel_present_mpx']} Mpx/秒（片源要 {v['pixel_source_mpx']}）"))
    print(fmt_row("缓冲", f"{v['cached_ms']}ms / {v['cached_bytes']/1048576:.1f}MB "
                          f"码率 {v['bitrate_bps']/8/1048576:.2f}MB/s"))
    print(fmt_row("解码", f"{dec['in_use']} {dec['name']} {dec['impl']} "
                          f"(请求硬解={dec['requested_hw']} 给了码={dec['codec_offered']})"))
    print(fmt_row("音频", f"{au['codec']} 出声={au['started']}"))
    live = d.get("live")
    if live:
        print(fmt_row("系统", f"总CPU {live['cpu_busy_pct']}% 本进程 {live['proc_cpu_pct']}% "
                              f"温度 {live['temp_c']} 核频 {live['core_mhz']} DDR {live['ddr_mhz']} "
                              f"读取 {live['read_bps']/1048576:.2f}MB/s"))
    print(fmt_row("结论", ve["text"]))
    print(fmt_row("预设", f"{d['preset']['index']+1}/{d['preset']['count']}"))
    for r in d.get("extra", []):
        print(fmt_row(r["label"], r["value"]))


def series_window(base, sec, settle=0):
    """先等 settle 秒（重播后要重新起播），再在 sec 秒窗口里采样。"""
    if settle:
        time.sleep(settle)
    t0 = time.time()
    call(base, "/series", {"n": 1})  # 探活
    while time.time() - t0 < sec:
        time.sleep(1)
    arr = call(base, "/series", {"n": sec + 5})
    now = int(time.time() * 1000)
    return [s for s in arr if now - s["at"] <= (sec + 3) * 1000]


def avg(samples, key):
    vals = [s[key] for s in samples if isinstance(s.get(key), (int, float))]
    return sum(vals) / len(vals) if vals else 0.0


def summarize(samples):
    return {
        "n": len(samples),
        "dec": avg(samples, "dec"),
        "pres": avg(samples, "pres"),
        "pres_min": min([s["pres"] for s in samples], default=0),
        "pres_max": max([s["pres"] for s in samples], default=0),
        "drop": avg(samples, "drop"),
        "mpx": avg(samples, "mpx"),
        "cpu": avg(samples, "cpu"),
        "proc": avg(samples, "proc_cpu"),
        "read_mb": avg(samples, "read_bps") / 1048576,
        "cached": avg(samples, "cached_ms"),
    }


def cmd_watch(a):
    samples = series_window(a.url, a.sec)
    s = summarize(samples)
    st = call(a.url, "/state")
    print(f"{st['content']['title']}  {st['video']['w']}×{st['video']['h']}  "
          f"{st['decoder']['in_use']} {st['decoder']['name']}")
    print(f"窗口 {a.sec}s（{s['n']} 个样本）")
    print(f"  解码 {s['dec']:.2f}  送显 {s['pres']:.2f}（{s['pres_min']:.1f}~{s['pres_max']:.1f}）"
          f"  丢帧 {s['drop']*100:.1f}%")
    print(f"  像素率 {s['mpx']:.1f} Mpx/秒   总CPU {s['cpu']:.0f}%  本进程 {s['proc']:.0f}%")
    print(f"  读取 {s['read_mb']:.2f} MB/秒  缓冲 {s['cached']:.0f} ms")
    print(f"  结论 {st['verdict']['text']}")


def cmd_measure(a):
    """逐个片源「打开 → 等它真的在走 → 按片名过滤取样」，输出可比的一行数字。

    这个子命令存在的唯一理由是**踩过一次坑**：第一版测量脚本只等「缓冲够了」就采样，
    结果上一个片源的样本混进了均值（4K25 HEVC 被算成 44.4 帧/秒，比片源帧率还高）。
    `/series` 的每个样本都带 `title`，现在只统计片名对得上的样本 ——
    片源之间要对比，这一步不能省。
    """
    for path in a.paths:
        want = path.rsplit("/", 1)[-1]
        call(a.url, "/cmd", {"a": "open", "path": path, "ms": 0}, timeout=120)
        t0, seen, ready = time.time(), None, False
        while time.time() - t0 < a.timeout:
            time.sleep(3)
            d = call(a.url, "/state")
            v, c = d["video"], d["content"]
            if c["title"] != want or v["w"] == 0:
                seen = None
                continue
            if seen is None:
                seen = (time.time(), c["pos_ms"])
            elif c["pos_ms"] > seen[1] + 3000 and time.time() - seen[0] >= a.settle and v["cached_ms"] > a.buffer:
                ready = True
                break
        if not ready:
            print(f"{want}: 等了 {a.timeout}s 还没稳定开播（跳过）")
            continue

        arr = call(a.url, "/series", {"n": a.window})
        s = [x for x in arr if x.get("title") == want and x.get("mpx", 0) > 0]
        if len(s) < 10:
            print(f"{want}: 只有 {len(s)} 个有效样本 —— 不采信")
            continue
        st = call(a.url, "/state")
        med = sorted(x["pres"] for x in s)[len(s) // 2]
        print(f"{want}")
        print(f"  {st['video']['w']}×{st['video']['h']}@{st['video']['source_fps']:.2f} "
              f"{st['decoder']['in_use']} {st['decoder']['impl']}   （{len(s)} 个样本 / {a.window}s）")
        print(f"  解码 均值 {avg(s, 'dec'):5.1f} | 送显 均值 {avg(s, 'pres'):5.1f} 中位 {med:5.1f} "
              f"最低 {min(x['pres'] for x in s):5.1f} | 丢帧 {avg(s, 'drop') * 100:4.1f}% | "
              f"像素率 {avg(s, 'mpx'):6.1f} Mpx/秒")
        print(f"  结论 {st['verdict']['text']}")


def cmd_threads(a):
    rows = call(a.url, "/threads")
    print(f"{'tid':>7} {'nice':>5} {'cpu%':>6}  {'提权目标':<8} 名字")
    for t in rows:
        print(f"{t['tid']:>7} {t['nice']:>5} {t['cpu_pct']:>6.1f}  "
              f"{'是' if t['boost_target'] else '':<8} {t['name']}")


def cmd_knobs(a):
    ks = call(a.url, "/knobs")
    group = None
    for k in ks:
        if k["group"] != group:
            group = k["group"]
            print(f"[{group}]")
        mark = " *" if k["value"] != k["default"] else "  "
        print(f"{mark} {k['key']:<45} = {k['value']!r:<20} 默认 {k['default']!r:<20} {k['desc']}")


def cmd_presets(a):
    d = call(a.url, "/presets")
    for i, p in enumerate(d["presets"], 1):
        mark = "->" if i - 1 == d["current"] else "  "
        kv = " ".join(f"{k}={v}" for k, v in p["values"].items())
        print(f"{mark} {i:>2}. {p['label']}   {kv}")


def cmd_preset(a):
    r = call(a.url, "/preset", {"n": a.n})
    print(r)


def cmd_set(a):
    for kv in a.kv:
        if "=" not in kv:
            sys.exit(f"旋钮要写成 key=value：{kv}")
    r = call(a.url, "/set", dict(kv.split("=", 1) for kv in a.kv), timeout=120)
    print(r)


def cmd_cmd(a):
    r = call(a.url, "/cmd", {"a": a.what}, timeout=120)
    print(r)


def cmd_key(a):
    r = call(a.url, "/cmd", {"a": "key", "n": a.name})
    print(r)


def cmd_open(a):
    r = call(a.url, "/cmd", {"a": "open", "path": a.path, "ms": a.ms}, timeout=120)
    print(r)


def cmd_ls(a):
    r = call(a.url, "/cmd", {"a": "ls", "dir": a.dir}, timeout=120)
    if not r.get("ok"):
        sys.exit(r)
    for e in r["entries"]:
        kind = "目录" if e["dir"] else f"{e['size']/1048576:>8.1f}MB"
        print(f"  {kind}  {e['name']}")


def cmd_find(a):
    r = call(a.url, "/cmd", {"a": "find", "q": a.q, "depth": a.depth}, timeout=180)
    if not r.get("ok"):
        sys.exit(r)
    print(f"{r['count']} 项：")
    for h in r["hits"]:
        print(f"  {h}")


def cmd_sweep(a):
    """一轮对照实验：逐档切预设、每档采样一段时间，最后给一张对照表。"""
    presets = [int(x) for x in a.presets.split(",") if x.strip()]
    url = a.url
    st0 = call(url, "/state")
    print(f"对象：{st0['content']['title']}  {st0['video']['w']}×{st0['video']['h']}  "
          f"片源 {st0['video']['source_fps']} 帧/秒")
    print(f"每档 {a.sec}s + 起播等待 {a.settle}s\n")
    results = []
    for p in presets:
        label = call(url, "/presets")["presets"][p - 1]["label"]
        call(url, "/preset", {"n": p})
        if a.open_path:
            # 切档会重播「当前媒体库」的内容（可能根本不是被测片源），所以再点一次名。
            # 等 2 秒让上一次起播落地，免得两个起播互相打架。
            time.sleep(2)
            call(url, "/cmd", {"a": "open", "path": a.open_path}, timeout=120)
        samples = series_window(url, a.sec, settle=a.settle)
        s = summarize(samples)
        st = call(url, "/state")
        s["preset"] = p
        s["label"] = label
        s["verdict"] = st["verdict"]["text"]
        results.append(s)
        print(f"  {p:>2}. {label:<34} 送显 {s['pres']:>5.2f}  解码 {s['dec']:>5.2f}  "
              f"{s['mpx']:>6.1f} Mpx/s  CPU {s['cpu']:>3.0f}%")
    if a.restore:
        call(url, "/preset", {"n": a.restore})
        if a.open_path:
            time.sleep(2)
            call(url, "/cmd", {"a": "open", "path": a.open_path}, timeout=120)
        print(f"\n已恢复到第 {a.restore} 档" + ("（并重新打开片源）" if a.open_path else ""))
    print("\n档位  送显(均/最小/最大)      解码    像素率     丢帧   总CPU  本进程  读取MB/s  结论")
    for s in results:
        print(f"{s['preset']:>3}.  {s['pres']:>5.2f} /{s['pres_min']:>5.2f} /{s['pres_max']:>5.2f}   "
              f"{s['dec']:>5.2f}  {s['mpx']:>7.1f}  {s['drop']*100:>5.1f}%  {s['cpu']:>5.0f}% "
              f"{s['proc']:>5.0f}%  {s['read_mb']:>7.2f}   {s['verdict']}")
    if a.json:
        print(json.dumps(results, ensure_ascii=False, indent=2))


def cmd_scan(a):
    """在本地 /24 里找开着诊断口的设备（省得用户念 IP），顺便看有没有网络 adb。"""
    import concurrent.futures as cf
    import socket

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 53))
        me = s.getsockname()[0]
    finally:
        s.close()
    prefix = me.rsplit(".", 1)[0]
    print(f"本机 {me}，扫描 {prefix}.1-254 的 {a.port} 端口" + ("（+ 5555 网络 adb）" if a.adb else ""))

    def probe(job):
        host, port = job
        try:
            with socket.create_connection((host, port), timeout=a.timeout):
                return host, port
        except OSError:
            return None

    jobs = [(f"{prefix}.{i}", a.port) for i in range(1, 255)]
    if a.adb:
        jobs += [(f"{prefix}.{i}", 5555) for i in range(1, 255)]
    hits = []
    with cf.ThreadPoolExecutor(max_workers=64) as ex:
        for r in ex.map(probe, jobs):
            if r:
                hits.append(r)
    if not hits:
        print("没找到。确认：应用已启动、电视和这台机器在同一局域网、防火墙没挡。")
        return
    for host, port in sorted(hits):
        if port == 5555:
            print(f"  {host}:5555  ← 网络 adb 开着！`adb connect {host}:5555` 之后就能拿 dumpsys")
            continue
        try:
            d = call(f"http://{host}:{port}", "/state", timeout=5)
            v = d["video"]
            live = d.get("live") or {}
            print(f"  {host}:{port}  {d['content']['title']}  {v['w']}×{v['h']}@{v['source_fps']}  "
                  f"解码 {v['decode_fps']} 送显 {v['present_fps']}  {d['decoder']['in_use']} "
                  f"{d['decoder']['name']}  CPU {live.get('cpu_busy_pct')}%")
        except Exception as e:  # noqa: BLE001 - 扫描就是要容错
            print(f"  {host}:{port}  （连着但读不出状态：{e}）")


def cmd_props(a):
    d = call(a.url, "/props", {"q": a.q} if a.q else None)
    if not d.get("ok", True):
        sys.exit(d)
    for k, v in sorted(d.items()):
        print(f"  {k:<40} {v}")


def cmd_codecs(a):
    rows = call(a.url, "/codecs")
    print(f"{'mime':<16}{'解码器':<40}{'排名':>5}{'面':>4}{'最大':>12}{'4K':>4}{'隧道':>6}")
    for c in rows:
        size = f"{c['max_w']}x{c['max_h']}"
        print(f"{c['mime']:<16}{c['name']:<40}{c['rank']:>5}{'Y' if c['surface'] else '-':>4}"
              f"{size:>12}{'Y' if c['supports_4k'] else '-':>4}{'Y' if c.get('tunneled') else '-':>6}")


def cmd_log(a):
    print(call(a.url, "/log", {"n": a.n}))


def main():
    ap = argparse.ArgumentParser(description="FireflyTV 远程诊断客户端")
    ap.add_argument("--url", default=DEFAULT_URL, help=f"电视上诊断服务的地址（默认 {DEFAULT_URL}）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("state", help="打印一屏现场快照").set_defaults(fn=cmd_state)

    p = sub.add_parser("watch", help="采样一段时间并打印均值")
    p.add_argument("sec", nargs="?", type=int, default=20)
    p.set_defaults(fn=cmd_watch)

    p = sub.add_parser("measure", help="逐个片源量一遍，输出可比的一行数字（按片名过滤样本）")
    p.add_argument("paths", nargs="+", help="NAS 上相对共享根的路径，可给多个")
    p.add_argument("--window", type=int, default=40, help="每个片源的取样窗口秒数（默认 40）")
    p.add_argument("--settle", type=int, default=20, help="开播后稳定多久才开始取样（默认 20）")
    p.add_argument("--buffer", type=int, default=25000, help="缓冲至少这么多毫秒才算稳定（默认 25000）")
    p.add_argument("--timeout", type=int, default=120, help="等一个片源稳定开播的上限秒数")
    p.set_defaults(fn=cmd_measure)

    sub.add_parser("threads", help="逐线程 CPU 与 nice").set_defaults(fn=cmd_threads)
    sub.add_parser("knobs", help="列出全部旋钮").set_defaults(fn=cmd_knobs)
    sub.add_parser("presets", help="列出全部预设方案").set_defaults(fn=cmd_presets)

    p = sub.add_parser("preset", help="切到第 N 档预设（会重播）")
    p.add_argument("n", type=int)
    p.set_defaults(fn=cmd_preset)

    p = sub.add_parser("set", help="改旋钮，例如 set player.framedrop=0")
    p.add_argument("kv", nargs="+")
    p.set_defaults(fn=cmd_set)

    p = sub.add_parser("do", help="任意动作：do preset_next / do pause / do resume / do panel …")
    p.add_argument("what")
    p.set_defaults(fn=cmd_cmd)

    p = sub.add_parser("key", help="模拟遥控器按键：ok/left/right/up/down/settings")
    p.add_argument("name")
    p.set_defaults(fn=cmd_key)

    p = sub.add_parser("open", help="直接打开 NAS 上的文件")
    p.add_argument("path")
    p.add_argument("--ms", type=int, default=0)
    p.set_defaults(fn=cmd_open)

    p = sub.add_parser("ls", help="列 NAS 目录")
    p.add_argument("dir", nargs="?", default="")
    p.set_defaults(fn=cmd_ls)

    p = sub.add_parser("find", help="在 NAS 上按名字找（广度优先）")
    p.add_argument("q")
    p.add_argument("--depth", type=int, default=3)
    p.set_defaults(fn=cmd_find)

    p = sub.add_parser("sweep", help="逐档预设做对照实验")
    p.add_argument("--presets", default="1,3,4,8")
    p.add_argument("--open", dest="open_path", default="",
                   help="每档切换后重新 open 这个 NAS 路径（切档会重播「当前库」的内容，"
                        "直接 open 的文件会被冲掉，所以对比固定片源时必须带上它）")
    p.add_argument("--sec", type=int, default=20)
    p.add_argument("--settle", type=int, default=8)
    p.add_argument("--restore", type=int, default=1)
    p.add_argument("--json", action="store_true")
    p.set_defaults(fn=cmd_sweep)

    p = sub.add_parser("props", help="读系统属性（adb getprop 的替代）")
    p.add_argument("q", nargs="?", default="", help="只显示键名里含这段的（留空=默认关注项）")
    p.set_defaults(fn=cmd_props)

    sub.add_parser("codecs", help="本机解码器清单 + 声明的最大尺寸 + 隧道支持").set_defaults(fn=cmd_codecs)

    p = sub.add_parser("scan", help="在局域网里自动找电视（不用念 IP）")
    p.add_argument("--port", type=int, default=8642)
    p.add_argument("--timeout", type=float, default=0.6)
    p.add_argument("--no-adb", dest="adb", action="store_false", default=True,
                   help="顺便看看有没有开网络 adb（5555）")
    p.set_defaults(fn=cmd_scan)

    p = sub.add_parser("log", help="看应用自己的日志")
    p.add_argument("n", nargs="?", type=int, default=80)
    p.set_defaults(fn=cmd_log)

    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
