#!/usr/bin/env python3
"""
把 NAS 配置通过配置页的 HTTP 接口写进电视，省掉手填一遍。

**只在本机联调时用。** 账号密码从 `local.properties` 读（该文件不入库），
脚本本身不含任何凭据。

用法：
    python3 scripts/push-config.py <base_url>

其中 base_url 形如 http://127.0.0.1:18080/?t=<token>，token 在电视二维码页上。
"""
import sys
import urllib.parse
import urllib.request
from pathlib import Path


def read_local_properties() -> dict:
    """读 gitignore 掉的 local.properties。找不到就报错退出，不要瞎猜默认值。"""
    p = Path(__file__).resolve().parent.parent / "local.properties"
    if not p.exists():
        sys.exit("找不到 local.properties（模板见 local.properties.example）")
    out = {}
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    base = sys.argv[1]
    cfg = read_local_properties()

    form = {
        "host": cfg.get("ff.smb.host", ""),
        "share": cfg.get("ff.smb.share", ""),
        "root": cfg.get("ff.smb.root", ""),
        "user": cfg.get("ff.smb.user", ""),
        "pass": cfg.get("ff.smb.pass", ""),
        "domain": cfg.get("ff.smb.domain", ""),
        # 天气留空 = 跳过（和风要单独申请 Key，不该卡住看电视）
        "wkey": "",
        "whost": "",
        "wloc": "",
    }
    if not form["host"] or not form["share"]:
        sys.exit("local.properties 里 ff.smb.host / ff.smb.share 是空的")

    # token 已经在 base 的 query 里，POST body 只放表单字段
    body = urllib.parse.urlencode(form).encode()
    req = urllib.request.Request(
        base, data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded; charset=utf-8"},
        method="POST",
    )
    # /api/save 是 POST 到根路径之外的；这里按配置页实际用的路由拼
    save_url = base.split("?")[0] + "api/save?" + base.split("?", 1)[1]
    req = urllib.request.Request(
        save_url, data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded; charset=utf-8"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            print("HTTP", r.status)
            print(r.read().decode("utf-8", "replace"))
    except Exception as e:
        sys.exit("保存失败: %s" % e)
    return 0


if __name__ == "__main__":
    sys.exit(main())
