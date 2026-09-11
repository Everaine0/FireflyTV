#!/bin/bash
# 只把**某一个 ABI 的 libijkffmpeg.so** 换进现有的 AAR，不动其它两个 ABI，
# 也不动 libijkplayer.so / libijksdl.so。
#
# 为什么需要它：重编整个内核要十几分钟、还要 NDK 全套；而很多改动
# （比如给 x86 打开汇编，见 patch-ffmpeg-x86-asm.sh）**只影响 libijkffmpeg.so**。
# 这种情况下没必要连 ijkplayer 自己的 so 一起重编 —— 那几个 so 一行都没变。
#
# 用法：
#   bash scripts/swap-ffmpeg-so.sh <abi> <新的 libijkffmpeg.so 路径>
#   # 例：bash scripts/swap-ffmpeg-so.sh x86 \
#   #       build/ijkbuild/ijkplayer/android/contrib/build/ffmpeg-x86/output/libijkffmpeg.so
#
# 产物：原地更新 app/libs/ijkplayer-full-0.8.8.aar（先备份成 .bak）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AAR="${AAR:-$ROOT/app/libs/ijkplayer-full-0.8.8.aar}"

ABI="${1:?用法: swap-ffmpeg-so.sh <abi:x86|armeabi-v7a|arm64-v8a> <libijkffmpeg.so>}"
SO="${2:?缺少 libijkffmpeg.so 路径}"

[ -f "$AAR" ] || { echo "❌ 找不到 AAR：$AAR"; exit 1; }
[ -f "$SO" ]  || { echo "❌ 找不到 .so：$SO"; exit 1; }

echo "==> 校验新 so 的身份"
file "$SO" | sed 's/^/    /'
# 三个 ABI 的 .so 长得一样，装错了要到电视上才炸，所以这里按 ELF 头先拦一道
case "$ABI" in
  # `file` 对 32 位 x86 的输出在不同版本里叫法不一（"Intel 80386" / "Intel i386"），
  # 所以两个都认。
  x86)          want='Intel 80386|Intel i386' ;;
  armeabi-v7a)  want='ARM' ;;
  arm64-v8a)    want='aarch64' ;;
  *) echo "❌ 不认识的 ABI：$ABI"; exit 1 ;;
esac
if ! file "$SO" | grep -qEi "$want"; then
  echo "❌ $SO 看起来不是 $ABI 的产物（期望含 '$want'）"; exit 1
fi
# 顺带挡住「把 64 位产物塞进 32 位 ABI」这类错误
if [ "$ABI" = "arm64-v8a" ] && file "$SO" | grep -q "32-bit"; then
  echo "❌ arm64-v8a 要的是 64 位产物"; exit 1
fi
# x86 必须是开过汇编的：纯 C 的构建会让模拟器 4K 只剩几帧
if [ "$ABI" = "x86" ]; then
  n=$(strings -n 3 "$SO" | grep -ciE 'sse2|ssse3|sse4|avx2' || true)
  echo "    x86 SIMD 相关字符串：$n"
  if [ "$n" -lt 3 ]; then
    echo "⚠️  这个 x86 so 几乎没有 SIMD 符号 —— 可能又是 --disable-asm 编出来的，"
    echo "    装上去模拟器还是会卡。确认跑过 scripts/patch-ffmpeg-x86-asm.sh。"
    exit 1
  fi
fi

echo "==> 备份 AAR"
cp -f "$AAR" "$AAR.bak"
ls -la "$AAR.bak"

echo "==> 换入 jni/$ABI/libijkffmpeg.so"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$AAR" -d "$TMP/aar"
cp -f "$SO" "$TMP/aar/jni/$ABI/libijkffmpeg.so"
# zip 打回：先删旧的再压，避免同名条目留着两份
rm -f "$AAR"
( cd "$TMP/aar" && zip -qr "$AAR" . )

echo "==> 确认"
unzip -l "$AAR" | grep -E "jni/.*\.so" | sed 's/^/    /'
ls -la "$AAR"
