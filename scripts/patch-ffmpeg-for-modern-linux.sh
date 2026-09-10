#!/bin/bash
# 给 ijkplayer 的 FFmpeg（n3.4，2018 年）打上「在现代 Linux 上编译得动」的补丁。
#
# 问题：libavutil/timer.h 里这一段的 guard 是 CONFIG_LINUX_PERF，
# 一旦为真就会 #include <linux/perf_event.h>：
#
#     #if CONFIG_LINUX_PERF
#     ...
#     # include <linux/perf_event.h>     ← 编译到这里直接断
#     #endif
#
# 而 Debian 13 的内核头已经不默认提供那个头文件了，于是：
#     libavutil/timer.h:38:31: fatal error: linux/perf_event.h: No such file or directory
#
# 这段代码只是给开发者的性能计时（START_TIMER / STOP_TIMER），
# Android 上根本用不到，所以直接把它关掉最省事，也不影响任何功能。
#
# 幂等：重复跑不会重复插入。
set -u

FF="${1:?用法: patch-ffmpeg-for-modern-linux.sh <ffmpeg 源码目录>}"
TIMER="$FF/libavutil/timer.h"

[ -f "$TIMER" ] || { echo "跳过（没有 $TIMER）"; exit 0; }

if grep -q 'FireflyTV: 关掉 linux_perf' "$TIMER"; then
  echo "已打过补丁：$FF"
  exit 0
fi

python3 - "$TIMER" <<'PY'
import io, sys

path = sys.argv[1]
src = io.open(path, encoding='utf-8').read()

marker = 'FireflyTV: 关掉 linux_perf'
if marker in src:
    print('已打过补丁'); sys.exit(0)

anchor = '#include "config.h"'
if anchor not in src:
    print('没找到 #include "config.h"，文件结构可能不同'); sys.exit(2)

patch = (anchor + '\n\n'
         '/* FireflyTV: 关掉 linux_perf。\n'
         '   Debian 13 的内核头不再提供 <linux/perf_event.h>，\n'
         '   而这段（只用于 START_TIMER 计时）会无条件 include 它，导致编译失败。\n'
         '   Android 上用不到，关掉即可。 */\n'
         '#ifdef CONFIG_LINUX_PERF\n'
         '#undef CONFIG_LINUX_PERF\n'
         '#endif\n'
         '#define CONFIG_LINUX_PERF 0')

src = src.replace(anchor, patch, 1)
io.open(path, 'w', encoding='utf-8').write(src)
print('补丁已写入')
PY

echo "--- 确认 ---"
grep -n 'FireflyTV: 关掉 linux_perf' -A6 "$TIMER" | head -10
