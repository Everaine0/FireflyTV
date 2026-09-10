#!/bin/bash
# 校验编出来的 libijkffmpeg.so 里到底有没有我们要的解码器。
#
# 为什么不能用 `strings | grep`：那个符号是 C 符号，不一定作为裸字符串出现，
# 拿它当判据会得出「没有」的错误结论（上一轮就误报了三次）。
# 正确做法是用 nm 读符号表。
set -u

SO_DIR="${1:?用法: verify-ijkplayer-decoders.sh <so 目录>}"
NDK="${ANDROID_NDK:-$HOME/ijkbuild/android-ndk-r10e}"

# 按 ABI 选对应的 nm。
# 上一轮就栽在这里：拿 arm 的 nm 去读 aarch64 的 so，一个符号都读不出来，
# 于是把编好的 arm64 误报成「解码器没编进去」。
nm_for() {
  case "$1" in
    arm64-v8a)
      echo "$NDK/toolchains/aarch64-linux-android-4.9/prebuilt/linux-x86_64/bin/aarch64-linux-android-nm" ;;
    *)
      echo "$NDK/toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64/bin/arm-linux-androideabi-nm" ;;
  esac
}

WANT="ff_ac3_decoder ff_eac3_decoder ff_mp2_decoder ff_aac_decoder ff_h264_decoder ff_hevc_decoder ff_dca_decoder"
fail=0
checked=0

for so in "$SO_DIR"/*/libijkffmpeg.so; do
  [ -f "$so" ] || continue
  checked=$((checked + 1))
  abi="$(basename "$(dirname "$so")")"
  NM="$(nm_for "$abi")"
  [ -x "$NM" ] || NM="$(command -v nm || true)"
  echo
  echo "======== $abi  ($(du -h "$so" | cut -f1)) ========"
  echo "  nm = $(basename "${NM:-无}")"
  if [ -n "$NM" ]; then
    # x86 的 so 用 arm 的 nm 也读不出来，所以统一退回宿主 nm 兜底
    syms="$("$NM" -D --defined-only "$so" 2>/dev/null || true)"
    if [ -z "$syms" ]; then
      syms="$("$NM" "$so" 2>/dev/null || true)"
    fi
    if [ -z "$syms" ]; then
      hostnm="$(command -v nm || true)"
      [ -n "$hostnm" ] && syms="$("$hostnm" -D --defined-only "$so" 2>/dev/null || "$hostnm" "$so" 2>/dev/null || true)"
    fi
  fi
  [ -z "${syms:-}" ] && syms="$(strings "$so")"
  for w in $WANT; do
    if printf '%s\n' "$syms" | grep -q "\b$w\b"; then
      echo "  ✅ $w"
    else
      echo "  ❌ $w"
      case "$w" in
        ff_ac3_decoder|ff_mp2_decoder) fail=1 ;;
      esac
    fi
  done
done

echo
if [ "$checked" -eq 0 ]; then
  echo "==== 结论：一个 libijkffmpeg.so 都没找到，校验没意义 ❌ ===="
  echo "     （目录：$SO_DIR）"
  exit 2
fi
if [ "$fail" -eq 0 ]; then
  echo "==== 结论：检查了 $checked 个 ABI，AC-3 / MP2 解码器都在 ✅ ===="
else
  echo "==== 结论：仍缺关键解码器，编译开关没生效 ❌ ===="
fi
exit "$fail"
