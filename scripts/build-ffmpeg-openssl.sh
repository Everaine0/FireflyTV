#!/bin/bash
# 给 libijkffmpeg.so 补上 OpenSSL（https 直播源）。
#
# 背景：自编的 libijkffmpeg.so 没有 openssl，`https://` 的直播源全部起不来 ——
# ijkplayer 只会打一行 "https protocol not found, recompile FFmpeg with openssl..."，
# 用户看到的是「这个频道暂时看不了」。频道表里 264788.xyz / live2.example.com / myalicdn
# / cgtn.com 这些 http**s** 源占了一大半，所以这条必须补。
#
# 步骤：
#   1) init-android-openssl.sh  克隆 Bilibili/openssl（OpenSSL_1_0_2n）到 android/contrib/openssl-<abi>
#   2) compile-openssl.sh <abi> 每个 ABI 编出 libssl.a / libcrypto.a
#   3) compile-ffmpeg.sh <abi>  do-compile-ffmpeg.sh 里有检测：
#                              `if [ -f "${FF_DEP_OPENSSL_LIB}/libssl.a" ]` → 自动加 --enable-openssl
#
# 用法：bash scripts/build-ffmpeg-openssl.sh [abi...]（默认 armv7a arm64 x86）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${WORK:-$ROOT/build/ijkbuild}"
IJP="$WORK/ijkplayer"
ABIS="${*:-armv7a arm64 x86}"

export ANDROID_NDK="$WORK/android-ndk-r10e"
export ANDROID_SDK="${ANDROID_SDK:-/mnt/d/AndroidSDK}"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

[ -d "$IJP" ] || { echo "找不到 ijkplayer 源码：$IJP"; exit 1; }
[ -d "$ANDROID_NDK" ] || { echo "找不到 NDK：$ANDROID_NDK"; exit 1; }

cd "$IJP"

# ---- 1) openssl 源码 ----
if [ ! -d "$IJP/extra/openssl/.git" ]; then
  log "克隆 openssl 源码"
  ./init-android-openssl.sh
else
  log "openssl 源码已存在，跳过克隆"
fi

# ---- 2) 编 openssl ----
cd "$IJP/android/contrib"
for abi in $ABIS; do
  if [ -f "build/openssl-$abi/output/lib/libssl.a" ]; then
    log "openssl $abi 已编译，跳过"
    continue
  fi
  log "编译 openssl $abi"
  bash ./compile-openssl.sh "$abi"
done

# ---- 3) 重编 FFmpeg（会自动带上 --enable-openssl）----
#
# ⚠️ 每次 compile-ffmpeg.sh 都会从 extra/ffmpeg 重新铺一份源码，
# **之前打在 android/contrib/ffmpeg-<abi>/ 上的补丁会没**。
# 所以这里必须重新打一遍「现代 Linux」补丁（否则第一刀就死在
# `fatal error: linux/perf_event.h: No such file or directory`），
# x86 还要另外打开汇编。
log "重新打「现代 Linux」补丁（compile-ffmpeg.sh 会重铺源码）"
bash "$ROOT/scripts/patch-all-ffmpeg.sh" "$IJP/android/contrib"

# 上游只在 macOS 上给了 `make -j`，Linux 上是空的 —— 不补这一下，
# 三个 ABI 的 FFmpeg 会是**单线程**编，20 核机器上要一个多小时。
# 这个补丁改的是 do-detect-env.sh，不在会被重铺的范围内，重复执行是幂等的。
log "让 make 并行（上游 Linux 下是单线程）"
bash "$ROOT/scripts/patch-ffmpeg-parallel-make.sh" "$IJP/android/contrib"

if printf '%s\n' $ABIS | grep -qx x86; then
  log "给 x86 打开 FFmpeg 汇编（SSE/AVX）"
  bash "$ROOT/scripts/patch-ffmpeg-x86-asm.sh" "$IJP/android/contrib"
fi

for abi in $ABIS; do
  log "重编 FFmpeg $abi（含 openssl）"
  bash ./compile-ffmpeg.sh "$abi" 2>&1 | tail -20
  echo "-------- 检查 $abi 的https开关 --------"
  CFG=$(dirname "$(find "build/ffmpeg-$abi" -name config.h -print -quit)")
  grep -E "CONFIG_OPENSSL 1|CONFIG_HTTPS_PROTOCOL 1" "$CFG/config.h" || echo "⚠️  $abi 没有打开 openssl！"
done

log "完成。产物："
ls -la "$IJP/android/contrib/build/ffmpeg-"*/output/lib/libavformat.a 2>/dev/null || true

# ---- 4) 重新链接出 libijkffmpeg.so ----
#
# ⚠️ FFmpeg 那一步只产出 `libav*.a`（静态库），`libijkffmpeg.so` 是
# compile-ijk.sh 把它们链起来的产物 —— 少了这一步，AAR 里换进去的还是旧内核。
log "重新链接 ijkplayer（产出 libijkffmpeg.so）"
cd "$IJP/android"
for abi in $ABIS; do
  log "compile-ijk.sh $abi"
  bash ./compile-ijk.sh "$abi" 2>&1 | tail -12
done

log "产物（每个 .so 都要有）："
for abi in $ABIS; do
  case "$abi" in
    armv7a) dir=armeabi-v7a ;;
    arm64)  dir=arm64-v8a ;;
    x86)    dir=x86 ;;
    *)      dir="$abi" ;;
  esac
  ls -la "$IJP/android/ijkplayer/ijkplayer-$abi/src/main/libs/$dir/" 2>/dev/null || echo "  ⚠️  $abi 没有产物"
done

