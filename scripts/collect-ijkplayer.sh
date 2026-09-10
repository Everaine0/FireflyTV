#!/bin/bash
# 把编好的 ijkplayer .so 收集到仓库外的产物目录，并校验解码器。
#
# 单独拆出来是为了「编了一次、收集多次」：编译很慢，
# 收集/校验这一步经常要反复调，不该每次都重编。
#
# 用法：
#   bash scripts/collect-ijkplayer.sh            # 收集 + 校验
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${WORK:-$HOME/ijkbuild}"
OUT="${OUT:-$ROOT/build-out/ijkplayer-full}"
ABIS="${ABIS:-armv7a arm64 x86}"

abi_dir() {
  case "$1" in
    armv7a) echo armeabi-v7a ;;
    arm64)  echo arm64-v8a ;;
    x86)    echo x86 ;;
    *)      echo "$1" ;;
  esac
}

rm -rf "$OUT"
for abi in $ABIS; do
  dir="$(abi_dir "$abi")"
  src="$WORK/ijkplayer/android/ijkplayer/ijkplayer-$abi/src/main/libs/$dir"
  mkdir -p "$OUT/$dir"
  if [ -d "$src" ]; then
    cp -v "$src"/*.so "$OUT/$dir/"
  else
    echo "⚠️  找不到 $abi 的产物目录：$src"
  fi
  for need in libijkplayer.so libijksdl.so libijkffmpeg.so; do
    [ -f "$OUT/$dir/$need" ] || echo "❌ $dir 缺 $need"
  done
done

echo
bash "$ROOT/scripts/verify-ijkplayer-decoders.sh" "$OUT"
