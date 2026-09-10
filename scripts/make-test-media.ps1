# Generates the test fixtures used by instrumentation tests and local SMB testing.
# Requires ffmpeg on PATH (or set -FfmpegPath).
param(
    [string]$FfmpegPath = '<ffmpeg>\bin\ffmpeg.exe',
    [string]$MediaRoot = '<本地媒体目录>'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path $FfmpegPath)) {
    Write-Error "ffmpeg not found at $FfmpegPath"
}

# 1) 插桩测试用的示例视频
$assetDir = Join-Path $root 'app\src\androidTest\assets'
New-Item -ItemType Directory -Force -Path $assetDir | Out-Null

# faststart：moov 在文件头，ijkplayer 能正常播（正常场景的基准）
$testMp4 = Join-Path $assetDir 'test.mp4'
& $FfmpegPath -y -loglevel error -f lavfi -i "testsrc=size=320x240:rate=15:duration=6" `
    -f lavfi -i "sine=frequency=440:duration=6" `
    -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest -movflags +faststart $testMp4
Write-Host "wrote $testMp4 (faststart)"

# 非 faststart：moov 在文件尾，用来固定「已知限制」这条用例
$tailMp4 = Join-Path $assetDir 'test_tail_moov.mp4'
& $FfmpegPath -y -loglevel error -f lavfi -i "testsrc=size=320x240:rate=15:duration=6" `
    -f lavfi -i "sine=frequency=440:duration=6" `
    -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest $tailMp4
Write-Host "wrote $tailMp4 (tail moov)"

# 2) 本地 SMB 联调用的媒体库目录树
$dirs = @(
    (Join-Path $MediaRoot '电视剧\水浒传'),
    (Join-Path $MediaRoot '电视剧\西游记'),
    (Join-Path $MediaRoot '电影'),
    (Join-Path $MediaRoot '直播'),
    (Join-Path $MediaRoot '空文件夹')
)
foreach ($d in $dirs) { New-Item -ItemType Directory -Force -Path $d | Out-Null }

# 集号故意跨越 10，用来验证自然排序（第 2 集排在第 10 集前）
$shows = @{
    '电视剧\水浒传' = @('水浒传01.mp4', '水浒传02.mp4', '水浒传10.mp4', '水浒传11.mp4')
    '电视剧\西游记' = @('西游记01.mp4', '西游记02.mp4')
}
$i = 1
foreach ($show in $shows.Keys) {
    foreach ($name in $shows[$show]) {
        $out = Join-Path (Join-Path $MediaRoot $show) $name
        if (Test-Path $out) { continue }
        $secs = 5 + $i
        & $FfmpegPath -y -loglevel error -f lavfi -i "testsrc=size=640x360:rate=20:duration=$secs" `
            -f lavfi -i "sine=frequency=$((300 + $i * 80)):duration=$secs" `
            -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest $out
        $i++
    }
}

$m3u = @'
#EXTM3U
#EXTINF:-1 tvg-name="测试频道一",测试频道一
http://127.0.0.1:9/live1.m3u8
#EXTINF:-1 tvg-name="测试频道二",测试频道二
http://127.0.0.1:9/live2.m3u8
'@
Set-Content -Path (Join-Path $MediaRoot '直播\channels.m3u') -Value $m3u -Encoding utf8
Set-Content -Path (Join-Path $MediaRoot '空文件夹\readme.txt') -Value 'not a video' -Encoding utf8

Write-Host "media tree ready at $MediaRoot"
Get-ChildItem $MediaRoot -Recurse -File | Select-Object FullName, Length
