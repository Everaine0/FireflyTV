# 一键拉起「用模拟器试流程」所需的两件事：
#   1) 宿主机上的 SMB 测试服务（impacket，纯 Python，不需要管理员）
#   2) Android TV 5.1 模拟器（与目标电视同版本，自带遥控器面板）
# 并把配置页从宿主机端口转发出来，手机/电脑都能直接打开。
#
# 用法：
#   .\scripts\dev-env.ps1              # 全量：装依赖 + 起服务 + 起模拟器 + 转发
#   .\scripts\dev-env.ps1 -SkipInstall # 依赖已装好时跳过 pip
param(
    [switch]$SkipInstall,
    [int]$SmbPort = 4450,
    [int]$WebPort = 18080,
    [string]$SmbUser = 'firefly',
    [string]$SmbPass = 'firefly',
    [string]$MediaRoot = '<本地媒体目录>'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$adb = '<Android SDK>\platform-tools\adb.exe'
$emulator = '<Android SDK>\emulator\emulator.exe'
$pylibs = Join-Path (Split-Path $MediaRoot -Parent) 'pylibs'

$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot' }
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { '<Android SDK>' }

# ---- 1) 依赖与测试素材 ----
if (-not $SkipInstall) {
    Write-Host "[1/5] 安装 impacket 到 $pylibs ..."
    python -m pip install --quiet --no-warn-script-location --target $pylibs impacket
}
$env:PYTHONPATH = $pylibs

if (-not (Test-Path (Join-Path $MediaRoot '电视剧'))) {
    Write-Host "[2/5] 生成测试素材 ..."
    & (Join-Path $PSScriptRoot 'make-test-media.ps1') -MediaRoot $MediaRoot
} else {
    Write-Host "[2/5] 测试素材已存在，跳过"
}

# ---- 2) SMB 服务 ----
$smbListening = Get-NetTCPConnection -LocalPort $SmbPort -State Listen -ErrorAction SilentlyContinue
if ($smbListening) {
    Write-Host "[3/5] SMB 服务已在 $SmbPort 监听，跳过"
} else {
    Write-Host "[3/5] 启动 SMB 测试服务 ..."
    Start-Process -FilePath 'python' -ArgumentList @(
        (Join-Path $PSScriptRoot 'dev-smb-server.py'),
        '--root', $MediaRoot,
        '--share', 'media',
        '--port', "$SmbPort",
        '--user', $SmbUser,
        '--password', $SmbPass
    ) -WindowStyle Minimized
    Start-Sleep -Seconds 3
}

# ---- 3) 模拟器 ----
$running = (& $adb devices) -match 'emulator-\d+\s+device'
if ($running) {
    Write-Host "[4/5] 模拟器已在运行，跳过"
} else {
    Write-Host "[4/5] 启动 Android TV 模拟器（首次开机约 1–2 分钟）..."
    Start-Process -FilePath $emulator -ArgumentList @(
        '-avd', 'firefly_tv', '-no-snapshot', '-no-boot-anim',
        '-gpu', 'swiftshader_indirect', '-no-audio', '-port', '5554'
    )
    & $adb -s emulator-5554 wait-for-device
    for ($i = 0; $i -lt 60; $i++) {
        if ((& $adb -s emulator-5554 shell getprop sys.boot_completed 2>$null).Trim() -eq '1') { break }
        Start-Sleep -Seconds 3
    }
}

# ---- 4) 装应用并转发配置页 ----
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { & (Join-Path $root 'build.ps1') assembleDebug }
Write-Host "[5/5] 安装并启动应用 ..."
& $adb -s emulator-5554 install -r $apk | Out-Null
& $adb -s emulator-5554 shell am force-stop com.firefly.tv
& $adb -s emulator-5554 shell am start -n com.firefly.tv/.ui.MainActivity | Out-Null
Start-Sleep -Seconds 6

# 找出应用这次绑的随机端口，再转发出来
$line = (& $adb -s emulator-5554 shell 'cat /proc/net/tcp6') |
    Select-String -Pattern '00000000000000000000000000000000:([0-9A-F]{4})' | Select-Object -First 1
$guestPort = [Convert]::ToInt32([regex]::Match($line.ToString(), ':([0-9A-F]{4})').Groups[1].Value, 16)
& $adb -s emulator-5554 forward --remove-all | Out-Null
& $adb -s emulator-5554 forward "tcp:$WebPort" "tcp:$guestPort" | Out-Null

$hostIp = (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.InterfaceAlias -notlike 'VMware*' } |
    Select-Object -First 1).IPAddress

Write-Host ''
Write-Host '================ 可以开始试了 ================' -ForegroundColor Green
Write-Host "模拟器窗口里应该显示二维码和一行地址。"
Write-Host ''
Write-Host "配置页（宿主机浏览器）： http://127.0.0.1:$WebPort/?t=<token>"
Write-Host "配置页（手机同一 WiFi）： http://${hostIp}:$WebPort/?t=<token>"
Write-Host "  token 就是模拟器屏幕上地址栏 ?t= 后面那串，照抄即可"
Write-Host ''
Write-Host "在这一页填："
Write-Host "  NAS 地址   10.0.2.2      （模拟器里宿主机的地址，不是 127.0.0.1）"
Write-Host "  共享文件夹 media"
Write-Host "  账号/密码  $SmbUser / $SmbPass"
Write-Host "  天气三项   随便填会让保存失败；只想试播放就留空也没用 —— 保存要求两项都通过"
Write-Host ''
Write-Host "注意：配置页的「保存」要求 SMB 与天气**都**通过。"
Write-Host "没有和风 Key 时，可以先点「先测试一下」看 SMB 单项结果，"
Write-Host "或临时用 scripts\dev-weather.py 起一个假天气接口（可选）。"
Write-Host ''
Write-Host "按键：模拟器右侧遥控器面板，或键盘方向键 + 回车（回车 = OK）。"
Write-Host '  左右 = 换媒体库   上下 = 换剧/换频道   OK = 天气浮层'
Write-Host '==============================================' -ForegroundColor Green
