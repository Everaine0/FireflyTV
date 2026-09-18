#!/usr/bin/env bash
# 在 WSL 里编辑、在 Windows 里构建（这台机器的分工）。
#
# 为什么要这个脚本：本机访问不到 services.gradle.org，Gradle 发行版只能用 Windows 缓存里那份，
# 所以真正的构建必须由 Windows 侧的 `build.ps1` 跑；而代码是在 WSL 这边改的。
# 两边是**两棵源码树**，于是必须先把改过的东西同步过去。
#
# ⚠️ 这里同步的**不只是 `app/src`**：一开始只镜像 app/src，结果改了
# `app/build.gradle.kts` / `proguard-rules.pro` 完全不生效 —— 构建用的还是 Windows 侧那份旧的，
# 排查「为什么改了没反应」白花了一轮。四个东西都要同步：
#   app/src（源码）、app/build.gradle.kts（构建配置）、app/proguard-rules.pro（R8 规则）、
#   app/libs/*.aar（播放内核，41MB，用 copy 而不是 robocopy /MIR，避免误删别的）
#
# 用法：
#   scripts/win-build.sh                       # 默认 assembleDebug
#   scripts/win-build.sh assembleRelease
#   scripts/win-build.sh testDebugUnitTest assembleDebug assembleDebugAndroidTest
#   TAIL=80 scripts/win-build.sh assembleRelease     # 多看几行日志
set -euo pipefail

TASKS="${*:-assembleDebug}"
TAIL="${TAIL:-45}"

# 预检：播放内核的 AAR 是本地二进制、不入库。纯源码状态下它不在，
# 这时候 Gradle 会以「Kotlin 找不到 tv.danmaku.ijk.media.player.*」的形式报错，
# 那条报错完全看不出「你只是还没编内核」—— 所以这里先拦一道，把话说明白。
AAR="app/libs/ijkplayer-full-0.8.8.aar"
if [ ! -f "$AAR" ]; then
    cat >&2 <<'MSG'
✗ 缺少 app/libs/ijkplayer-full-0.8.8.aar（播放内核，二进制、不入库）

  纯源码状态下必须先把内核编出来（需 Linux/WSL，会下载 NDK ~1GB 并 clone ijkplayer）：
      scripts/build-ijkplayer.sh     # 交叉编译 FFmpeg + ijkplayer（三个 ABI，耗时较长）
      scripts/collect-ijkplayer.sh   # 收集产物
      scripts/pack-ijkplayer-aar.sh  # 打成 app/libs/ijkplayer-full-0.8.8.aar

  它不在 git 里（二进制不入库），所以除了重编没有别的恢复途径；
  唯一还留着的是已打好的 APK（lib/*/libijk*.so 在里面），那救不了构建。
MSG
    exit 1
fi

WSL_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Windows 侧的源码树路径因机器而异，不写进仓库 —— 必须显式给出。
if [ -z "${WIN_ROOT:-}" ]; then
    cat >&2 <<'MSG'
✗ 没设置 WIN_ROOT（Windows 侧那棵源码树的路径）

  用法：
      WIN_ROOT='<Windows 源码树>' scripts/win-build.sh assembleDebug

  脚本会把 app/src、app/build.gradle.kts、app/proguard-rules.pro 和
  app/libs/*.aar 同步到那棵树，再在 Windows 侧调 Gradle 构建。
MSG
    exit 1
fi

# Windows 侧看不到 /home，走 \\wsl.localhost\<发行版>\... 这条路
DISTRO="${WSL_DISTRO_NAME:-Debian}"
UNC="\\\\wsl.localhost\\${DISTRO}${WSL_ROOT//\//\\}"

BAT="$(mktemp /tmp/ffbuild-XXXXXX.bat)"
trap 'rm -f "$BAT"' EXIT

cat > "$BAT" <<EOF
@echo off
setlocal
set WIN=${WIN_ROOT}
set SRC=${UNC}
set TASKS=${TASKS}

robocopy "%SRC%\\app\\src" "%WIN%\\app\\src" /MIR /NJH /NJS /NDL /NP /NFL >nul
echo robocopy-src rc=%ERRORLEVEL%
copy /Y "%SRC%\\app\\build.gradle.kts" "%WIN%\\app\\build.gradle.kts" >nul
echo copy-gradle rc=%ERRORLEVEL%
copy /Y "%SRC%\\app\\proguard-rules.pro" "%WIN%\\app\\proguard-rules.pro" >nul
echo copy-proguard rc=%ERRORLEVEL%
copy /Y "%SRC%\\app\\libs\\ijkplayer-full-0.8.8.aar" "%WIN%\\app\\libs\\ijkplayer-full-0.8.8.aar" >nul
echo copy-aar rc=%ERRORLEVEL%

cd /d "%WIN%"
rem JDK / SDK / Gradle come from the environment or the usual install locations.
if not defined JAVA_HOME for /d %%j in ("%ProgramFiles%\\Microsoft\\jdk-17*") do set JAVA_HOME=%%~fj
if not defined ANDROID_HOME if defined LOCALAPPDATA set ANDROID_HOME=%LOCALAPPDATA%\\Android\\Sdk
if not defined ANDROID_SDK_ROOT set ANDROID_SDK_ROOT=%ANDROID_HOME%
set GRADLE_BAT=
for /d %%g in ("%USERPROFILE%\\.gradle\\wrapper\\dists\\gradle-8.11.1-bin\\*") do if exist "%%~fg\\gradle-8.11.1\\bin\\gradle.bat" set GRADLE_BAT=%%~fg\\gradle-8.11.1\\bin\\gradle.bat
if not defined GRADLE_BAT echo gradle 8.11.1 not found under %USERPROFILE%\\.gradle\\wrapper\\dists & exit /b 1
call "%GRADLE_BAT%" %TASKS%
set RC=%ERRORLEVEL%

if exist "%WIN%\\app\\build\\outputs\\apk\\debug\\app-debug.apk" copy /Y "%WIN%\\app\\build\\outputs\\apk\\debug\\app-debug.apk" "%SRC%\\build\\apk\\app-debug.apk" >nul
if exist "%WIN%\\app\\build\\outputs\\apk\\release\\app-release.apk" copy /Y "%WIN%\\app\\build\\outputs\\apk\\release\\app-release.apk" "%SRC%\\build\\apk\\app-release.apk" >nul
exit /b %RC%
endlocal
EOF

# .bat 必须是 GBK/ANSI 才能被 cmd 正确解析（路径里有中文）；用 iconv 转一道，没有就原样跑
if command -v iconv >/dev/null 2>&1; then
    iconv -f UTF-8 -t GBK "$BAT" > "$BAT.gbk" 2>/dev/null && mv "$BAT.gbk" "$BAT" || true
fi

cp "$BAT" /tmp/ffbuild.bat
cd /tmp
# ⚠️ 必须先把 cmd.exe 的退出码留下来再交给管道：`cmd | grep | tail` 的退出码是
# **tail 的**，永远是 0 —— 构建失败时脚本照样「成功」返回，CI/自动化里
# 会把一次没编出来的构建当成都通过了。踩过一次，别改回去。
set +e
cmd.exe /c "\\\\wsl.localhost\\${DISTRO}\\tmp\\ffbuild.bat" 2>&1 \
    | grep -v "^robocopy rc=" | tail -"$TAIL"
RC=${PIPESTATUS[0]}
set -e

if [ "$RC" -ne 0 ]; then
    echo >&2
    echo "✗ 构建失败（cmd.exe 退出码 $RC）—— 上面只显示了最后 $TAIL 行，需要更多就调大 TAIL" >&2
fi
exit "$RC"
