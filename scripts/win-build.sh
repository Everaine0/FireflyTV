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

WSL_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WIN_ROOT="${WIN_ROOT:-<Windows 源码树>}"

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
set JAVA_HOME=C:\\Program Files\\Microsoft\\jdk-17.0.18.8-hotspot
set ANDROID_HOME=<Android SDK>
set ANDROID_SDK_ROOT=<Android SDK>
call C:\\Users\\<用户>\\.gradle\\wrapper\\dists\\gradle-8.11.1-bin\\bpt9gzteqjrbo1mjrsomdt32c\\gradle-8.11.1\\bin\\gradle.bat %TASKS%
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
cmd.exe /c "\\\\wsl.localhost\\${DISTRO}\\tmp\\ffbuild.bat" 2>&1 | grep -v "^robocopy rc=" | tail -"$TAIL"
