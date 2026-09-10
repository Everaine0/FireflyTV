#!/bin/bash
# 对每个 ABI 的 FFmpeg 源码目录打「现代 Linux」补丁。
#
# 为什么单独一个脚本：必须在 init-android.sh（拉源码）之后、
# compile-ffmpeg.sh（编译）之前执行。放在 build-ijkplayer.sh 里用内联循环写，
# 从 PowerShell 调过来时引号会被层层吃掉，所以拆成独立脚本。
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PATCH="$ROOT/scripts/patch-ffmpeg-for-modern-linux.sh"
CONTRIB="${1:-$HOME/ijkbuild/ijkplayer/android/contrib}"

for abi in armv7a arm64 x86; do
  d="$CONTRIB/ffmpeg-$abi"
  if [ -f "$d/libavutil/timer.h" ]; then
    bash "$PATCH" "$d"
  else
    echo "跳过 $abi（源码还没拉下来）"
  fi
done
