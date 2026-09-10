param(
    [string]$Ffprobe = "<ffmpeg>\bin\ffprobe.exe",
    [string]$Ffmpeg  = "<ffmpeg>\bin\ffmpeg.exe",
    [string]$File    = "build-out\verify\sample.ts"
)

# Can the audio in this stream actually be decoded?
#
# Context: the same kernel plays AC-3 fine (self-made fixture produces sound),
# so the problem must be this particular stream. "The audio cannot be decoded"
# is the most likely candidate. -xerror makes ffmpeg fail on the first decode
# error instead of silently skipping, so the exit code is a binary answer.
#
# NOTE: kept ASCII-only on purpose. PowerShell 5.1 reads UTF-8 .ps1 files as GBK
# and mangles Chinese punctuation, which breaks string literals (hit repeatedly).

$ErrorActionPreference = "Continue"

if (-not (Test-Path $File)) { throw "file not found: $File" }

Write-Host "=== 1) stream info ==="
& $Ffprobe -v error -show_entries "stream=index,codec_type,codec_name" -of csv=p=0 $File 2>&1 |
    ForEach-Object { "  $_" }

Write-Host ""
Write-Host "=== 2) decode AC-3 audio only, 10s (-xerror = fail on error) ==="
& $Ffmpeg -v warning -xerror -i $File -t 10 -vn -f null - 2>&1 | Select-Object -First 25
Write-Host "exit code: $LASTEXITCODE"

Write-Host ""
Write-Host "=== 3) count decoded audio frames over 30s (0 = cannot decode) ==="
& $Ffmpeg -v error -i $File -t 30 -vn -f null - -progress - 2>&1 |
    Select-String -Pattern "^frame=" | Select-Object -Last 1 |
    ForEach-Object { "  $_" }

Write-Host ""
Write-Host "=== 4) full decode (video + audio), 10s ==="
& $Ffmpeg -v error -i $File -t 10 -f null - 2>&1 | Select-Object -First 15
Write-Host "exit code: $LASTEXITCODE"

Write-Host ""
Write-Host "=== 5) first AC-3 sync word (0x0B77) in the first 4MB ==="
$bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $File))
$limit = [Math]::Min($bytes.Length - 1, 4MB)
$found = -1
for ($i = 0; $i -lt $limit; $i++) {
    if ($bytes[$i] -eq 0x0B -and $bytes[$i + 1] -eq 0x77) { $found = $i; break }
}
if ($found -ge 0) {
    Write-Host "  first 0x0B77 at offset $found"
    $hex = ($bytes[$found..($found + 15)] | ForEach-Object { $_.ToString("X2") }) -join " "
    Write-Host "  header: $hex"
} else {
    Write-Host "  no AC-3 sync word in first 4MB"
}
