# 一键拉起「用模拟器试流程」所需的东西：
#   - Android TV 5.1 模拟器（与目标电视同版本，自带遥控器面板）
#   - 装上并启动调试包
#   - 把内置配置页从宿主机端口转发出来，电脑/手机都能直接打开
#
# SMB 侧请用**你自己的真实 NAS**：把连接信息填进 local.properties，
# 二维码配置页里直接填 NAS 的地址即可（模拟器默认能路由到局域网）。
#
# 用法：
#   .\scripts\dev-env.ps1
#   .\scripts\dev-env.ps1 -WebPort 18080
param(
    [int]$WebPort = 18080,
    [string]$Avd = 'firefly_tv'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# Android SDK / JDK：环境变量优先，其次常见安装位置（路径因机器而异，不入库）
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = @("$env:LOCALAPPDATA\Android\Sdk") |
        Where-Object { Test-Path (Join-Path $_ 'platform-tools') } |
        Select-Object -First 1
}
if (-not $env:ANDROID_HOME) { throw '找不到 Android SDK：设置 ANDROID_HOME，或装一个 Android Studio' }
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "$env:ProgramFiles\Microsoft\jdk-17*" -Directory -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'

# ---- 1) 模拟器 ----
$running = (& $adb devices) -match 'emulator-\d+\s+device'
if ($running) {
    Write-Host "[1/3] 模拟器已在运行，跳过"
} else {
    Write-Host "[1/3] 启动 Android TV 模拟器（首次开机约 1-2 分钟）..."
    Start-Process -FilePath $emulator -ArgumentList @(
        '-avd', $Avd, '-no-snapshot', '-no-boot-anim',
        '-gpu', 'swiftshader_indirect', '-port', '5554'
    )
    & $adb -s emulator-5554 wait-for-device
    for ($i = 0; $i -lt 60; $i++) {
        if ((& $adb -s emulator-5554 shell getprop sys.boot_completed 2>$null).Trim() -eq '1') { break }
        Start-Sleep -Seconds 3
    }
}

# ---- 2) 装包并启动 ----
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) {
    Write-Host "[2/3] 还没有 APK，先构建 ..."
    & (Join-Path $root 'build.ps1') assembleDebug
}
Write-Host "[2/3] 安装并启动应用 ..."
& $adb -s emulator-5554 install -r $apk | Out-Null
& $adb -s emulator-5554 shell am force-stop com.firefly.tv
& $adb -s emulator-5554 shell am start -n com.firefly.tv/.ui.MainActivity | Out-Null
Start-Sleep -Seconds 6

# ---- 3) 转发配置页 ----
# 应用绑的是随机端口，先找出它这次用的是哪个
$line = (& $adb -s emulator-5554 shell 'cat /proc/net/tcp6') |
    Select-String -Pattern '00000000000000000000000000000000:([0-9A-F]{4})' | Select-Object -First 1
if (-not $line) {
    Write-Warning "没找到应用监听的端口 —— 可能它已经配置完成、配置服务已关闭。"
    Write-Warning "想重新配置：在模拟器里按 设置键+OK 或 返回键+OK 呼出二维码。"
    return
}
$guestPort = [Convert]::ToInt32([regex]::Match($line.ToString(), ':([0-9A-F]{4})').Groups[1].Value, 16)
& $adb -s emulator-5554 forward --remove-all | Out-Null
& $adb -s emulator-5554 forward "tcp:$WebPort" "tcp:$guestPort" | Out-Null

$hostIp = (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object {
        $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and
        $_.InterfaceAlias -notlike 'VMware*' -and $_.InterfaceAlias -notlike 'vEthernet*'
    } | Select-Object -First 1).IPAddress

Write-Host ''
Write-Host '================ 可以开始试了 ================' -ForegroundColor Green
Write-Host '模拟器窗口里显示二维码和一行地址。'
Write-Host ''
Write-Host "配置页（宿主机浏览器）： http://127.0.0.1:$WebPort/?t=<token>"
Write-Host "配置页（手机同一 WiFi）： http://${hostIp}:$WebPort/?t=<token>"
Write-Host '  token 就是模拟器屏幕上地址里 ?t= 后面那串，照抄即可'
Write-Host ''
Write-Host '在配置页填你自己 NAS 的地址/共享名/账号密码（天气可留空）。'
Write-Host '天气三项要么都填、要么都留空；SMB 必须连通才能保存。'
Write-Host ''
Write-Host '按键：模拟器右侧遥控器面板，或键盘方向键 + 回车（回车 = OK）'
Write-Host '  左右 = 换媒体库   上下 = 换剧/换频道   OK = 天气浮层'
Write-Host '==============================================' -ForegroundColor Green
