#!/bin/bash
# 给 ijkplayer 的 FFmpeg **x86** 打开汇编（SSE/AVX）。
#
# ## 为什么必须打这一下
#
# ijkplayer 的 `android/contrib/tools/do-compile-ffmpeg.sh` 里写着一段很显眼的特例：
#
#     if [ "$FF_ARCH" = "x86" ]; then
#         FF_CFG_FLAGS="$FF_CFG_FLAGS --disable-asm"
#     else
#         FF_CFG_FLAGS="$FF_CFG_FLAGS --enable-asm"
#         FF_CFG_FLAGS="$FF_CFG_FLAGS --enable-inline-asm"
#     fi
#
# 也就是说：**官方 ijkplayer 的 x86 内核是纯 C 编译的，一条 SIMD 都没有**
# （armv7a / arm64 反过来是开着的 —— 它们的 .so 里有几百个 neon 符号）。
# 拿 `strings libijkffmpeg.so | grep -c sse2` 一比就很清楚：x86 是 0，arm 是几百。
#
# 后果全部落在**模拟器**上（真机是 ARM，不受影响）：
# 4K H.265 的软解 + YUV→RGB32 转换在纯 C 下慢到离谱 —— 实测《猫和老鼠》
# (2960x2160 HEVC) 只有 **4.1 fps**（ff_vout 线程 92% 单核、5 个解码线程各 21%，
# 进程总共吃掉 2 个核还是出不来帧）。同一台机器上，同一条命令行的 ffmpeg
# 打开汇编后能跑几十 fps。所以「模拟器上另外两部剧卡顿」跟电视无关，
# 是模拟器这份 x86 内核自己瘸了一条腿。
#
# 硬件解码救不了模拟器：模拟器的 MediaCodec 只有 `OMX.google.*` 这类
# **软件实现**，ijkplayer 按排名把它们拒掉（设计如此，见 VideoDecodePolicy）。
# 想在模拟器上看到接近真机的效果，只能把软件解码本身修好。
#
# ## 为什么上游当初要关掉
#
# 2015 年前后用 NDK r10 编 x86 汇编时，yasm 产出的目标文件带**文本重定位**
# （TEXTREL），Android 6 (API 23) 之后会拒绝加载这种 .so。
# 现在的 yasm/nasm 配合 `--enable-pic` 已经没有这个问题（打完包可以自检：
# `readelf -d libijkffmpeg.so | grep TEXTREL` 应当没有输出）。
#
# 目标设备是 Android 5.1（API 22），本来也还没开始卡 TEXTREL；
# 而且这条只影响 x86，ARM 两个 ABI 一行都不动。
#
# 幂等：重复跑不会重复改。
set -u

CONTRIB="${1:-${WORK:-$HOME/ijkbuild}/ijkplayer/android/contrib}"
FILE="$CONTRIB/tools/do-compile-ffmpeg.sh"

[ -f "$FILE" ] || { echo "跳过（没有 $FILE）"; exit 0; }

if grep -q 'FireflyTV: x86 打开汇编' "$FILE"; then
  echo "已打过补丁：$FILE"
else
  python3 - "$FILE" <<'PY'
import io, re, sys

path = sys.argv[1]
src = io.open(path, encoding='utf-8').read()
if 'FireflyTV: x86 打开汇编' in src:
    print('已打过补丁'); sys.exit(0)

old = '''if [ "$FF_ARCH" = "x86" ]; then
    FF_CFG_FLAGS="$FF_CFG_FLAGS --disable-asm"
else'''
new = '''# FireflyTV: x86 打开汇编（原来是 --disable-asm，纯 C 跑 4K H.265 只有 4fps）
if [ "$FF_ARCH" = "x86" ]; then
    FF_CFG_FLAGS="$FF_CFG_FLAGS --enable-asm"
    FF_CFG_FLAGS="$FF_CFG_FLAGS --enable-inline-asm"
else'''

if old not in src:
    print('没找到 x86 --disable-asm 那一段，上游结构可能变了', file=sys.stderr)
    sys.exit(2)
io.open(path, 'w', encoding='utf-8').write(src.replace(old, new, 1))
print('补丁已写入')
PY
fi

echo "--- 确认 ---"
grep -n 'FireflyTV: x86 打开汇编' -A5 "$FILE" | head -8
