#!/bin/bash
# 在 WSL(Debian) 里编译一份**带 AC-3 / MP2 解码器**的 ijkplayer 原生库。
#
# 为什么需要它：官方 tv.danmaku.ijk.media:ijkplayer-*:0.8.8 的 libijkffmpeg.so
# 只编进了 23 个解码器，没有 ff_ac3_decoder / ff_eac3_decoder / ff_mp2_decoder。
# 而用户的片源恰好是 AC-3（娘道）和 MP2（CCTV5）—— 表现就是「有画面没声音」。
# 指望电视自带解码不可靠（用户明确否掉了这条路），所以自己编一份。
#
# 产出：$OUT/ 下的（每个 ABI 一份）
#   libijkffmpeg.so  libijkplayer.so  libijksdl.so
#
# 用法（在 WSL 里）：
#   bash scripts/build-ijkplayer.sh
#
# 幂等：已经下过的仓库/FFmpeg 不会重复下载；中途失败可以直接重跑。
set -euo pipefail

# ---- 可调项 ----
IJK_REPO="${IJK_REPO:-https://github.com/bilibili/ijkplayer.git}"
IJK_TAG="${IJK_TAG:-k0.8.8}"
# 默认放在仓库内的 build/（已被 .gitignore 排除）：
# 这样在受限沙箱里也能编译，不用往 $HOME 之类的工作区外面写东西。
WORK="${WORK:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build/ijkbuild}"
OUT="${OUT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build-out/ijkplayer-full}"
ABIS="${ABIS:-armv7a arm64 x86}"
NDK_VERSION="${NDK_VERSION:-r10e}"          # ijkplayer k0.8.8 时代配套的 NDK
ANDROID_SDK="${ANDROID_SDK:-/mnt/d/AndroidSDK}"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- 1) 依赖 ----
# 只补缺的那些。没有免密 sudo 时（受限沙箱）也不该直接失败 ——
# 工具齐了照样能编，缺了会在下一步以更清楚的方式报出来。
MISSING=""
for c in g++ yasm nasm cmake pkg-config make git curl unzip python3 bc; do
  command -v "$c" >/dev/null 2>&1 || MISSING="$MISSING $c"
done
if [ -n "$MISSING" ]; then
  log "缺少依赖：$MISSING —— 尝试安装"
  if sudo -n true 2>/dev/null; then
    sudo -n apt-get update -qq
    sudo -n apt-get install -y -qq $MISSING 2>&1 | tail -3
  else
    echo "⚠️  没有免密 sudo，跳过安装；请先自行装好：$MISSING"
  fi
else
  log "编译依赖已齐全，跳过安装"
fi

mkdir -p "$WORK"
cd "$WORK"

# ---- 2) 仓库 ----
if [ ! -d "$WORK/ijkplayer/.git" ]; then
  log "克隆 ijkplayer ($IJK_TAG)"
  git clone --depth 1 --branch "$IJK_TAG" "$IJK_REPO" "$WORK/ijkplayer"
else
  log "ijkplayer 仓库已存在，跳过克隆"
fi
cd "$WORK/ijkplayer"

# ---- 3) NDK ----
NDK_DIR="$WORK/android-ndk-$NDK_VERSION"
if [ ! -d "$NDK_DIR" ]; then
  log "下载 NDK $NDK_VERSION（ijkplayer k0.8.8 配套版本）"
  curl -fL --retry 3 -o "$WORK/ndk.zip" \
    "https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-linux-x86_64.zip"
  unzip -q -o "$WORK/ndk.zip" -d "$WORK"
  rm -f "$WORK/ndk.zip"
else
  log "NDK 已存在，跳过下载"
fi
export ANDROID_NDK="$NDK_DIR"

# ---- 4) 打开缺失的解码器 ----
# module.sh 决定 FFmpeg 的 configure 开关。默认（module-lite.sh / module-default.sh）
# 为了缩小体积关掉了一大批解码器，AC-3 / MP2 就在其中。
log "写 module-firefly.sh（打开 AC-3 / E-AC-3 / MP2 / DTS）"
cat > config/module-firefly.sh <<'MOD'
# 面向「老人的 NAS 老剧」：宁可包大一点，也要什么都能出声。
#
# 关键补充：ac3 / eac3 / mp2 —— 官方包正好缺这三个，导致有画面没声音。
#
# 还要把 avdevice 打开：ijkplayer 的 ff_ffplay.c 会调 avdevice_register_all()，
# 而默认配置里是 --disable-avdevice，链接时直接
#   undefined reference to 'avdevice_register_all'
# 编不过。它体积很小，打开比改 ijkplayer 源码省事。
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-avdevice"

export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=ac3"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=eac3"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=mp2"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=mp1"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=dca"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=truehd"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=mlp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-parser=ac3"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-parser=dca"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=ac3"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=eac3"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=pcm_s16le"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=pcm_s16be"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=pcm_u8"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=pcm_alaw"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=pcm_mulaw"
# 容器：NAS 上老剧什么后缀都有；hls 是直播（IPTV）必需
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=mpegts"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=mpegtsraw"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=avi"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=matroska"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=asf"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=rm"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=flv"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=hls"
MOD
ln -sf module-firefly.sh config/module.sh

# ---- 5) 编译 ----
# 三个步骤缺一不可：
#   a) init-android.sh            拉 FFmpeg / libyuv / soundtouch 源码
#   b) android/contrib/compile-ffmpeg.sh   把 FFmpeg 编成 prebuilt 的 libijkffmpeg.so
#      （少了这步，后面 NDK 会报 "LOCAL_SRC_FILES points to a missing file /libijkffmpeg.so"）
#   c) android/compile-ijk.sh      编 ijkplayer 自己的 so，并打包成 AAR
if [ ! -f "$WORK/ijkplayer/extra/ffmpeg/ffmpeg/configure" ]; then
  log "拉取 FFmpeg / libyuv / soundtouch 源码"
  ./init-android.sh
else
  log "源码已存在，重新 configure 以应用 module.sh"
  ./init-android.sh
fi

ls -l config/module.sh

# FFmpeg n3.4 会无条件 include <linux/perf_event.h>，而 Debian 13 的内核头不再提供它。
# 不补这一下，FFmpeg 第一步就编译不过（见补丁脚本里的说明）。
log "给 FFmpeg 打「现代 Linux」补丁"
bash "$ROOT/scripts/patch-all-ffmpeg.sh" "$WORK/ijkplayer/android/contrib"

# x86 必须**打开汇编**，否则模拟器上的软解慢到没法看。
# 见 scripts/patch-ffmpeg-x86-asm.sh 的长注释（这是「模拟器 4K 只有 4fps」的根因）。
log "给 x86 打开 FFmpeg 汇编（SSE/AVX）"
bash "$ROOT/scripts/patch-ffmpeg-x86-asm.sh" "$WORK/ijkplayer/android/contrib"

log "编译 FFmpeg（$ABIS）—— 这步最慢，20 核大概几分钟"
for abi in $ABIS; do
  echo "-------- ffmpeg $abi --------"
  ( cd android/contrib && bash ./compile-ffmpeg.sh "$abi" ) 2>&1 | tail -15
done

log "编译 ijkplayer（$ABIS）"
# ijkplayer 会调 avdevice_register_all()，但它默认 --disable-avdevice，链接必失败。
# 播放用不到输入设备，直接把那次调用去掉（见补丁脚本说明）。
bash "$ROOT/scripts/patch-ijkplayer-no-avdevice.sh" "$WORK/ijkplayer"

# compile-ijk.sh 在 android/ 下，且必须在那个目录里跑（它按相对路径找 ijkplayer/ 各模块）。
# 它还会自己检查 ANDROID_NDK / ANDROID_SDK 两个环境变量，少了就直接退出。
COMPILE="$WORK/ijkplayer/android/compile-ijk.sh"
[ -f "$COMPILE" ] || { echo "找不到 $COMPILE"; exit 1; }
[ -d "$ANDROID_NDK" ] || { echo "ANDROID_NDK 不存在：$ANDROID_NDK"; exit 1; }
export ANDROID_NDK
export ANDROID_SDK
echo "ANDROID_NDK=$ANDROID_NDK"
echo "ANDROID_SDK=$ANDROID_SDK"
for abi in $ABIS; do
  echo "-------- ijk $abi --------"
  ( cd "$WORK/ijkplayer/android" && bash ./compile-ijk.sh "$abi" ) 2>&1 | tail -25
done

# ---- 6) 汇总产物 ----
log "收集 .so"
rm -rf "$OUT"
for abi in $ABIS; do
  case "$abi" in
    armv7a) dir=armeabi-v7a ;;
    arm64)  dir=arm64-v8a ;;
    x86)    dir=x86 ;;
    *)      dir="$abi" ;;
  esac
  mkdir -p "$OUT/$dir"
  # 只认 src/main/libs/<abi>/ 下的成品：obj/ 里的是中间产物（还没链接完）
  src="$WORK/ijkplayer/android/ijkplayer/ijkplayer-$abi/src/main/libs/$dir"
  if [ -d "$src" ]; then
    cp -v "$src"/*.so "$OUT/$dir/"
  else
    echo "  ⚠️  找不到 $abi 的产物目录：$src"
  fi
  # 三个 so 一个都不能少，少一个装到电视上就是起不来
  for need in libijkplayer.so libijksdl.so libijkffmpeg.so; do
    [ -f "$OUT/$dir/$need" ] || echo "  ❌ $dir 缺 $need"
  done
done

log "校验解码器（用 nm 读符号表，不用 strings）"
bash "$ROOT/scripts/verify-ijkplayer-decoders.sh" "$OUT" || true

log "完成，产物在 $OUT"
ls -la "$OUT"/*/
