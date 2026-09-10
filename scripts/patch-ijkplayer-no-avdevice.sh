#!/bin/bash
# 去掉 ijkplayer 里对 avdevice 的依赖。
#
# 背景：ijkplayer 的 ff_ffplay.c 在 ffp_global_init() 里调 avdevice_register_all()，
# 而它自己的 module-default.sh / module-lite.sh 里是 `--disable-avdevice` ——
# 于是链接直接失败：
#     ff_ffplay.c:3874: undefined reference to 'avdevice_register_all'
#
# 尝试过在 module-firefly.sh 里加 --enable-avdevice，但不管用：
# module.sh 是先被 source 的，之后 module-default.sh 才把 --disable-avdevice
# 追加进 COMMON_FF_CFG_FLAGS，顺序上 disable 在后，configure 以最后一个为准。
#
# 而 avdevice 是**输入设备**（摄像头/采集卡）那一套，播放 NAS 上的片子完全用不到。
# 所以直接把这一行调用去掉，既不用把整个 avdevice 编进来，也不用改 FFmpeg 开关。
#
# 幂等：重复跑不会重复改。
set -u

IJK_ROOT="${1:-$HOME/ijkbuild/ijkplayer}"
FILE="$IJK_ROOT/android/ijkplayer/ijkplayer-armv7a/src/main/jni/ijkmedia/ijkplayer/ff_ffplay.c"

# 4 个 ABI 目录各有一份源码副本（armv5/armv7a/arm64/x86/x86_64），都要改
patched=0
for f in "$IJK_ROOT"/android/ijkplayer/ijkplayer-*/src/main/jni/ijkmedia/ijkplayer/ff_ffplay.c; do
  [ -f "$f" ] || continue
  abi="$(echo "$f" | sed -n 's|.*/ijkplayer-\([^/]*\)/.*|\1|p')"
  if grep -q 'FireflyTV: 去掉 avdevice' "$f"; then
    echo "  已打过补丁：$abi"
    continue
  fi
  python3 - "$f" <<'PY'
import io, sys, re
path = sys.argv[1]
src = io.open(path, encoding='utf-8').read()
if 'FireflyTV: 去掉 avdevice' in src:
    print('  已打过补丁'); sys.exit(0)

# 匹配 avdevice_register_all(); 那一行，连同它的缩进一起注释掉
pat = re.compile(r'^([ \t]*)avdevice_register_all\(\);[ \t]*$', re.M)
new, n = pat.subn(
    r'\1/* FireflyTV: 去掉 avdevice —— 播放不需要输入设备，\n'
    r'\1   而且 --disable-avdevice 会导致这一行链接失败 */',
    src)
if n == 0:
    print('  没找到 avdevice_register_all() 调用'); sys.exit(0)
io.open(path, 'w', encoding='utf-8').write(new)
print('  已注释掉 avdevice_register_all()')
PY
  patched=1
done

[ "$patched" -eq 0 ] && echo "  没找到 ff_ffplay.c（源码还没拉下来？）"
echo "--- 确认 ---"
grep -c 'FireflyTV: 去掉 avdevice' "$IJK_ROOT"/android/ijkplayer/ijkplayer-*/src/main/jni/ijkmedia/ijkplayer/ff_ffplay.c 2>/dev/null || true
