# app/libs — 本地播放内核依赖

这里的 AAR 是 ijkplayer 的 Android 产物，**不提交进仓库**（`.gitignore` 已排除）。
换机器或重新克隆后，按下面任一方式补齐，否则 `assembleDebug` 会因为找不到
`tv.danmaku.ijk.media.player.*` 而编译失败。

## 为什么不用 Maven 坐标直接拉

- Maven Central 上没有 `com.github.befovy:ijkplayer`（404）
- JitPack 上 `befovy/ijkplayer` 的所有 tag 都是 `Error`
- GitHub Release `f0.7.16` 的 `IJKMediaPlayer.tar.gz` 里**只有 iOS 的 .framework**

所以走阿里云镜像上的 bilibili 版，也就是 DESIGN §6 里说的「必要时参考 `bilibili/ijkplayer`」。

## 补齐方式

```powershell
$libs = 'app\libs'
New-Item -ItemType Directory -Force -Path $libs | Out-Null
$base = 'https://maven.aliyun.com/repository/public/tv/danmaku/ijk/media'
$arts = @{
  'ijkplayer-java-0.8.8.aar'   = "$base/ijkplayer-java/0.8.8/ijkplayer-java-0.8.8.aar"
  'ijkplayer-armv7a-0.8.8.aar' = "$base/ijkplayer-armv7a/0.8.8/ijkplayer-armv7a-0.8.8.aar"
  'ijkplayer-arm64-0.8.8.aar'  = "$base/ijkplayer-arm64/0.8.8/ijkplayer-arm64-0.8.8.aar"
  'ijkplayer-x86-0.8.8.aar'    = "$base/ijkplayer-x86/0.8.8/ijkplayer-x86-0.8.8.aar"
}
foreach ($k in $arts.Keys) {
  Invoke-WebRequest -Uri $arts[$k] -OutFile (Join-Path $libs $k) -UseBasicParsing
}
```

四个包的分工：

| 包 | 内容 | 为什么需要 |
| :--- | :--- | :--- |
| `ijkplayer-java` | `IjkMediaPlayer`、`IMediaDataSource` 等 Java API | 桥接 SMB 必需 |
| `ijkplayer-armv7a` | `armeabi-v7a` 的 3 个 .so | 目标电视 |
| `ijkplayer-arm64` | `arm64-v8a` 的 3 个 .so | 目标电视 |
| `ijkplayer-x86` | `x86` 的 3 个 .so | 模拟器；**缺了会直接崩** |

## 已知限制

`0.8.8`（FFmpeg n3.1）在 `IMediaDataSource` 通道下不会回尾读 moov，
**非 faststart 的 mp4 播不了**。详见 `docs/DESIGN.md` 风险 8。
