#!/bin/bash
# 直接查每个 ABI 构建出来的 FFmpeg config.h，确认 AC-3 相关的开关。
# ijkplayer 源码目录：默认用仓库内 build-ijkplayer.sh 的产物位置，可用 IJK= 覆盖
IJK="${IJK:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build/ijkbuild/ijkplayer}"
cd "$IJK" || exit 1

for abi in x86 arm64-v8a armeabi-v7a; do
  d="android/contrib/build/ffmpeg-$abi"
  [ -d "$d" ] || continue
  cfg="$d/output/config.h"
  echo "########## $abi  ($cfg) ##########"
  [ -f "$cfg" ] || { echo "  没有 config.h"; continue; }
  echo "-- 解码器 --"
  grep -E '^#define CONFIG_(AC3|EAC3|MP2|MP1|DCA|TRUEHD|MLP|AAC|H264|HEVC)_DECODER ' "$cfg"
  echo "-- parser --"
  grep -E '^#define CONFIG_(AC3|DCA|AAC|MPEG4VIDEO|H264)_PARSER ' "$cfg"
  echo "-- demuxer --"
  grep -E '^#define CONFIG_(MPEGTS|AC3|EAC3|HLS|MOV)_DEMUXER ' "$cfg"
  echo "-- 解码器总数 --"
  grep -cE '^#define CONFIG_[A-Z0-9_]+_DECODER 1' "$cfg"
  echo
done

echo "=== 构建目录里的 include（打包用的那份 config.h）==="
for abi in x86 arm64-v8a armeabi-v7a; do
  f="android/contrib/build/ffmpeg-$abi/output/include/libffmpeg/config.h"
  echo "--- $abi ---"
  [ -f "$f" ] && grep -E 'CONFIG_(AC3|EAC3|MP2|DCA)_DECODER ' "$f"
done
