#!/bin/bash
# 把编好的 ijkplayer .so 重新打包成一个 AAR，覆盖掉从 Maven 拉的官方包。
#
# 为什么要重打包：官方 AAR 里的 libijkffmpeg.so 没有 AC-3 / MP2 / DTS 解码器，
# 导致「有画面没声音」。我们自己编了一份带这些解码器的（见 build-ijkplayer.sh），
# 这里把它塞回 AAR，App 侧不用改一行代码 —— 依赖还是一个 ijkplayer-full-0.8.8.aar。
#
# 产物：app/libs/ijkplayer-full-0.8.8.aar
# 打包时会顺带校验三个 ABI 的解码器都在，缺一个就直接失败。
#
# 用法：
#   bash scripts/pack-ijkplayer-aar.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS="$ROOT/app/libs"
SO_ROOT="${SO_ROOT:-$ROOT/build-out/ijkplayer-full}"
JAVA_AAR="$LIBS/ijkplayer-java-0.8.8.aar"
STAMP_AARS=(
  "$LIBS/ijkplayer-armv7a-0.8.8.aar"
  "$LIBS/ijkplayer-arm64-0.8.8.aar"
  "$LIBS/ijkplayer-x86-0.8.8.aar"
)
OUT_AAR="$LIBS/ijkplayer-full-0.8.8.aar"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

[ -f "$JAVA_AAR" ] || { echo "缺 $JAVA_AAR（先按 app/libs/README.md 补齐官方包）"; exit 1; }
[ -d "$SO_ROOT" ] || { echo "缺 $SO_ROOT（先跑 build-ijkplayer.sh）"; exit 1; }

# 先用官方 AAR 判断目标 ABI 列表，保证和官方结构一致
mapfile -t ABIS < <(for a in "${STAMP_AARS[@]}"; do
  [ -f "$a" ] || continue
  unzip -Z1 "$a" | sed -n 's|^jni/\([^/]*\)/.*|\1|p' | sort -u
done | sort -u)
[ "${#ABIS[@]}" -gt 0 ] || { echo "没能从官方 AAR 里读出 ABI 列表"; exit 1; }
echo "目标 ABI：${ABIS[*]}"

log "整理打包目录"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
# 1) Java 类 + 清单，来自官方的 ijkplayer-java
( cd "$STAGE" && unzip -qo "$JAVA_AAR" )
rm -rf "$STAGE/jni"   # java 包里本来就没有 so，保险起见
# 2) 三个 ABI 的 so，用我们自己编的
for abi in "${ABIS[@]}"; do
  src="$SO_ROOT/$abi"
  [ -d "$src" ] || { echo "缺 $src"; exit 1; }
  mkdir -p "$STAGE/jni/$abi"
  cp -v "$src"/*.so "$STAGE/jni/$abi/"
done

log "打包前校验解码器"
bash "$ROOT/scripts/verify-ijkplayer-decoders.sh" "$SO_ROOT"

log "生成 AAR"
rm -f "$OUT_AAR"
( cd "$STAGE" && zip -qr "$OUT_AAR" . )
ls -la "$OUT_AAR"

log "确认 AAR 内容"
unzip -l "$OUT_AAR" | grep -E '\.so$|classes.jar|AndroidManifest' || true

cat <<EOF

================ 下一步 ================
现在 app/libs 里同时存在官方包和这个 full 包，会出现重复类。
只保留这一个，把三个官方 so 包移走（java 包留着也行，反正内容一样）：

  cd "$LIBS"
  mkdir -p stock-aar && mv ijkplayer-armv7a-0.8.8.aar ijkplayer-arm64-0.8.8.aar ijkplayer-x86-0.8.8.aar stock-aar/
  mv ijkplayer-java-0.8.8.aar stock-aar/ 2>/dev/null || true

（放到 stock-aar/ 子目录里就不会被 fileTree("libs") 扫到，也不会入库。）
EOF
