#!/bin/bash
# ijkplayer 只在 macOS 上给了 make -j（do-detect-env.sh 第 91 行），
# Linux 上是空的 → 整个 FFmpeg/OpenSSL 都是**单线程**编，20 核机器上要一小时。
set -euo pipefail
CONTRIB="${1:?usage: patch-ffmpeg-parallel-make.sh <android/contrib>}"
F="$CONTRIB/tools/do-detect-env.sh"
grep -q "FireflyTV: Linux 也要并行" "$F" && { echo "已打过补丁：$F"; exit 0; }
python3 - "$F" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
old = "export IJK_MAKE_FLAG=\n"
new = ("# FireflyTV: Linux 也要并行。上游只给 macOS 设了 -j（见本文件末尾的 darwin 分支），\n"
       "# Linux 上这里是空的 → 整个 FFmpeg/OpenSSL 都是单线程编，20 核机器上要一小时。\n"
       "export IJK_MAKE_FLAG=\"-j$(nproc 2>/dev/null || echo 4)\"\n")
assert old in s, "找不到 IJK_MAKE_FLAG 那一行，上游可能改了"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
print("已写入:", p)
PY
