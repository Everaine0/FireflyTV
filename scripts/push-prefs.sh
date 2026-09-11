#!/bin/bash
# 把一份 firefly.xml 写回应用私有目录。
#
# 为什么要单独一个脚本：`adb shell ... < file` 会被 adb 的伪终端把 LF 换成 CRLF，
# 写进去的字符串值（比如天气私钥）就会多出一堆 \r。
# 所以改走 `adb push` + `run-as cat`：push 是二进制传输，不经过终端转换。
# （/data/local/tmp 是 0771 shell:shell，chmod 644 之后 app uid 才读得到。）
set -euo pipefail
SRC="${1:?用法: push-prefs.sh <firefly.xml>}"
PKG=com.firefly.tv
DST=/data/data/$PKG/shared_prefs/firefly.xml

adb push "$SRC" /data/local/tmp/firefly.xml > /dev/null
adb shell "chmod 644 /data/local/tmp/firefly.xml"
adb shell "run-as $PKG mkdir -p /data/data/$PKG/shared_prefs"
adb shell "run-as $PKG sh -c 'cat /data/local/tmp/firefly.xml > $DST'"
adb shell "rm -f /data/local/tmp/firefly.xml"

# 校验：设备上的内容和本地文件必须逐字节一致（去掉 adb 输出带回的 \r）
want=$(tr -d '\r' < "$SRC" | md5sum | awk '{print $1}')
got=$(adb shell "run-as $PKG sh -c 'cat $DST'" 2>/dev/null | tr -d '\r' | md5sum | awk '{print $1}')
echo "本地 md5=$want"
echo "设备 md5=$got"
[ "$want" = "$got" ] && echo "✅ 一致" || { echo "❌ 不一致"; exit 1; }
