param(
    [string]$Ffmpeg = "<ffmpeg>\bin\ffmpeg.exe",
    [string]$OutDir = "app\src\androidTest\assets"
)

# 生成一组音频编码对照样本，用来判断「内核的 AC-3 到底能不能出声」。
#
# 为什么需要：真实片源（娘道）有太多变量（SMB、容器、时间戳偏移、moov 重排…），
# 一旦测不出声音，就分不清是「内核不支持 AC-3」还是「这条流有别的问题」。
# 用自己生成的、**只换音频编码**的同一段视频做对照，就能把
# 「内核能力」和「片源问题」彻底分开。
#
# 视频刻意压到 320x240@15fps：保证模拟器也能实时软解，
# 这样「没声音」就不会被误判成「解不动画面」。
#
# 用法：& scripts\make-audio-fixtures.ps1

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

if (-not (Test-Path $Ffmpeg)) { throw "找不到 ffmpeg: $Ffmpeg" }

$V = "testsrc=size=320x240:rate=15:duration=12"
$A = "sine=frequency=440:sample_rate=48000:duration=12"

# 这个 ffmpeg 是 --disable-autodetect 编的，通过 @ffargs 传参时 libx264 会被解析成
# "Unknown decoder"（同一份 exe 手工敲同样的参数却能过，见脚本末尾的说明）。
# 播放器要测的是**音频编码**，视频编码是什么并不重要，
# 所以直接用一个内置编码器，绕开这个纯工具链问题。
$VideoCodec = "mpeg2video"

$cases = @(
    @{ name = "audio-ac3.ts";  codec = "ac3";  bitrate = "192k"; container = "mpegts" },
    @{ name = "audio-aac.ts";  codec = "aac";  bitrate = "128k"; container = "mpegts" },
    @{ name = "audio-mp2.ts";  codec = "mp2";  bitrate = "192k"; container = "mpegts" },
    @{ name = "audio-eac3.ts"; codec = "eac3"; bitrate = "192k"; container = "mpegts" },
    @{ name = "audio-ac3.mp4"; codec = "ac3";  bitrate = "192k"; container = "mp4" },
    @{ name = "audio-aac.mp4"; codec = "aac";  bitrate = "128k"; container = "mp4" }
)

foreach ($c in $cases) {
    $out = Join-Path $OutDir $c.name
    Write-Host "==> $($c.name)   H.264 + $($c.codec) / $($c.container)"
    # 用数组 + 调用运算符传参。
    # 注意两点（都已踩过）：
    #  1) 不要用行尾反引号续行再直接写 -f：PS 会把 -f 当成命令名报 "not recognized"
    #  2) 注释里不要出现反引号，它是 PS 的转义字符，会让整个脚本解析失败
    $ffargs = @(
        "-hide_banner", "-loglevel", "error",
        "-f", "lavfi", "-i", $V,
        "-f", "lavfi", "-i", $A,
        # 视频输出选项必须放在**输出侧**（-i 之后）。
        # 放在两个 -i 中间会被当成第二个输入的选项，ffmpeg 会报
        # "Codec AVOption g ... is not a decoding option"（已踩）。
        "-pix_fmt", "yuv420p", "-c:v", $VideoCodec, "-g", "15",
        "-c:a", $c.codec, "-b:a", $c.bitrate,
        "-f", $c.container, "-shortest", "-y", $out
    )
    & $Ffmpeg @ffargs
    if ($LASTEXITCODE -ne 0) { Write-Warning "  生成失败: $($c.name)" }
}

Write-Host ""
Write-Host "=== 生成结果 ==="
Get-ChildItem $OutDir -Filter "audio-*" | ForEach-Object {
    "{0,-18} {1,8:N0} 字节" -f $_.Name, $_.Length
}

Write-Host ""
Write-Host "=== 逐个确认实际编码（防止 ffmpeg 悄悄换了编码器）==="
$ffprobe = $Ffmpeg -replace "ffmpeg\.exe$", "ffprobe.exe"
Get-ChildItem $OutDir -Filter "audio-*" | ForEach-Object {
    $v = (& $ffprobe -v error -select_streams v:0 -show_entries stream=codec_name -of csv=p=0 $_.FullName) -join ""
    $au = (& $ffprobe -v error -select_streams a:0 -show_entries stream=codec_name -of csv=p=0 $_.FullName) -join ""
    "{0,-18} 视频={1,-6} 音频={2}" -f $_.Name, $v, $au
}
