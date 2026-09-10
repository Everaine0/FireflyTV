# Generates the test fixtures used by instrumentation tests and local SMB testing.
# Requires ffmpeg on PATH (or set -FfmpegPath).
#
# NOTE: kept ASCII-only on purpose. PowerShell 5.1 reads .ps1 files as GBK,
# so Chinese comments/literals in this file get mangled and break parsing.
# The generated asset names and paths are ASCII, so nothing is lost.
param(
    [string]$FfmpegPath = '<ffmpeg>\bin\ffmpeg.exe',
    [string]$MediaRoot = '<本地媒体目录>'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path $FfmpegPath)) {
    Write-Error "ffmpeg not found at $FfmpegPath"
}

# 1) sample videos for instrumentation tests
$assetDir = Join-Path $root 'app\src\androidTest\assets'
New-Item -ItemType Directory -Force -Path $assetDir | Out-Null

# Video codec is mpeg2video, NOT libx264.
# This machine's ffmpeg is built with --disable-autodetect: `-c:v libx264`
# fails with "Unknown decoder 'libx264'" even though it shows up in -encoders.
# The player tests care about AUDIO codec and CONTAINER LAYOUT, not which video
# encoder produced the pixels, so use the built-in one and sidestep the toolchain.
#
# faststart: moov at head -> baseline that plays fine
$testMp4 = Join-Path $assetDir 'test.mp4'
& $FfmpegPath -y -loglevel error -f lavfi -i "testsrc=size=320x240:rate=15:duration=6" `
    -f lavfi -i "sine=frequency=440:duration=6" `
    -c:v mpeg2video -g 15 -pix_fmt yuv420p -c:a aac -shortest -movflags +faststart $testMp4
Write-Host "wrote $testMp4 (faststart)"

# non-faststart: moov at tail -> exercises MoovRelocatingSource
$tailMp4 = Join-Path $assetDir 'test_tail_moov.mp4'
& $FfmpegPath -y -loglevel error -f lavfi -i "testsrc=size=320x240:rate=15:duration=6" `
    -f lavfi -i "sine=frequency=440:duration=6" `
    -c:v mpeg2video -g 15 -pix_fmt yuv420p -c:a aac -shortest $tailMp4
Write-Host "wrote $tailMp4 (tail moov)"

# Video-codec A/B pair: same content, same container, same resolution, both
# faststart -- only the VIDEO codec differs. Used to answer questions like
# "can the emulator decode H.265 at all?" with the variables cleaned up.
# See app/src/androidTest/assets/README.md and VideoCodecMatrixTest.
$videoCases = @(
    @{ name = 'video-h264.mp4'; codec = 'libx264'; extra = @('-preset', 'ultrafast') },
    @{ name = 'video-hevc.mp4'; codec = 'libx265'; extra = @('-preset', 'ultrafast', '-tag:v', 'hvc1', '-x265-params', 'log-level=error') }
)
foreach ($c in $videoCases) {
    $out = Join-Path $assetDir $c.name
    $ffargs = @(
        '-y', '-loglevel', 'error',
        '-f', 'lavfi', '-i', 'testsrc=size=640x360:rate=15:duration=10',
        '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=10',
        '-pix_fmt', 'yuv420p', '-c:v', $c.codec
    ) + $c.extra + @('-c:a', 'aac', '-b:a', '64k', '-movflags', '+faststart', $out)
    & $FfmpegPath @ffargs
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "failed (this ffmpeg may lack the encoder): $($c.name)"
    } else {
        Write-Host "wrote $out ($($c.codec))"
    }
}

# 2) media tree for local SMB testing
#
# All names are ASCII: PowerShell 5.1 reads .ps1 as GBK, so Chinese literals in
# THIS file get mangled and break parsing. The player handles Chinese names fine
# (the real NAS is full of them) -- this is purely a tooling constraint.
# If you need Chinese names locally, add them via a separate UTF-8 data file
# rather than inlining them here.
$dirs = @(
    (Join-Path $MediaRoot 'shows\A'),
    (Join-Path $MediaRoot 'shows\B'),
    (Join-Path $MediaRoot 'movies'),
    (Join-Path $MediaRoot 'live'),
    (Join-Path $MediaRoot 'empty-folder')
)
foreach ($d in $dirs) { New-Item -ItemType Directory -Force -Path $d | Out-Null }

# episode numbers deliberately cross 10, to verify natural ordering
$shows = @{
    'shows\A' = @('ep01.mp4', 'ep02.mp4', 'ep10.mp4', 'ep11.mp4')
    'shows\B' = @('ep01.mp4', 'ep02.mp4')
}
$i = 1
foreach ($show in $shows.Keys) {
    foreach ($name in $shows[$show]) {
        $out = Join-Path (Join-Path $MediaRoot $show) $name
        if (Test-Path $out) { continue }
        $secs = 5 + $i
        & $FfmpegPath -y -loglevel error -f lavfi -i "testsrc=size=640x360:rate=20:duration=$secs" `
            -f lavfi -i "sine=frequency=$((300 + $i * 80)):duration=$secs" `
            -c:v mpeg2video -g 20 -pix_fmt yuv420p -c:a aac -shortest $out
        $i++
    }
}

$m3u = @'
#EXTM3U
#EXTINF:-1 tvg-name="Test Channel 1",Test Channel 1
http://127.0.0.1:9/live1.m3u8
#EXTINF:-1 tvg-name="Test Channel 2",Test Channel 2
http://127.0.0.1:9/live2.m3u8
'@
Set-Content -Path (Join-Path $MediaRoot 'live\channels.m3u') -Value $m3u -Encoding utf8
Set-Content -Path (Join-Path $MediaRoot 'empty-folder\readme.txt') -Value 'not a video' -Encoding utf8

Write-Host "media tree ready at $MediaRoot"
Get-ChildItem $MediaRoot -Recurse -File | Select-Object FullName, Length
