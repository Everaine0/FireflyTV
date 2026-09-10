#!/bin/bash
# 让 WSL(Debian) 里的 apt 恢复可用。做两件事：
#
#  1) 禁用那个 NVIDIA CUDA 源。它会挡住 apt-get update：Debian 13 的 sqv
#     从 2026-02 起不再接受 SHA1 签名，而这个第三方源还在用 SHA1，
#     于是整个 update 失败，g++ / yasm / nasm 这些编译必需的包都装不上。
#
#  2) 清掉上一次被中断的编译留下的 apt/dpkg 锁。构建脚本被中途杀掉时，
#     apt-get 可能还活着（或锁文件残留），下一次运行就会撞
#     "Could not get lock /var/lib/dpkg/lock-frontend"。
set -u
shopt -s nullglob

echo "================ 1) 禁用 NVIDIA/CUDA 源 ================"
found=0
for f in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
  [ -e "$f" ] || continue
  if grep -qi 'nvidia\|cuda' "$f" 2>/dev/null; then
    echo "禁用: $f"
    sudo -n cp "$f" "$f.firefly-disabled"
    sudo -n rm -f "$f"
    found=1
  fi
done
[ "$found" -eq 0 ] && echo "没有 nvidia/cuda 源需要处理"

echo
echo "================ 2) 清理 apt/dpkg 锁 ================"
holders=$(pgrep -a apt-get; pgrep -a dpkg; pgrep -a '^apt$')
if [ -n "$holders" ]; then
  echo "占用锁的进程："
  echo "$holders"
  echo "结束它们…"
  sudo -n pkill -9 -f 'apt-get|dpkg' 2>/dev/null || true
  sleep 2
else
  echo "没有进程占用锁"
fi
sudo -n rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/cache/apt/archives/lock
sudo -n dpkg --configure -a 2>&1 | tail -3 || true

echo
echo "================ 验证 ================"
if sudo -n apt-get update -qq >/dev/null 2>&1; then
  echo "apt update 可用 ✅"
else
  echo "apt update 仍失败 ❌ —— 把上面的报错发我"
fi
