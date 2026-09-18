#!/bin/bash
# 检查编译出来的 FFmpeg 里，各编解码器的**解析器（parser）**开了哪些。
#
# 为什么单独查 parser：解码器（decoder）和解析器（parser）是两件事。
# 只开 --enable-decoder=hevc 而没开 parser，容器解出来的裸流可能无法被
# 正确切成帧，表现是解码器收到一堆垃圾 NAL：
#   Invalid NAL unit size (...) / get_buffer() failed
# 而且这种缺失在 `nm | grep decoder` 里**看不出来** —— 那一步只查解码器。
# ijkplayer 源码目录：默认用仓库内 build-ijkplayer.sh 的产物位置，可用 IJK= 覆盖
IJK="${IJK:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build/ijkbuild/ijkplayer}"
cd "$IJK" || exit 1

for abi in x86 arm64 armv7a; do
  cfg="android/contrib/build/ffmpeg-$abi/output/config.h"
  [ -f "$cfg" ] || continue
  echo "########## $abi ##########"
  echo "-- 解析器 --"
  grep -E '^#define CONFIG_[A-Z0-9_]+_PARSER 1' "$cfg" | sed 's/#define CONFIG_//;s/ 1//' | sort | tr '\n' ' '
  echo
  echo "-- 视频解码器 --"
  grep -E '^#define CONFIG_(H264|HEVC|MPEG2VIDEO|MPEG4|VP8|VP9|VC1)_DECODER 1' "$cfg" | sed 's/#define CONFIG_//;s/ 1//' | sort | tr '\n' ' '
  echo
  echo
done
