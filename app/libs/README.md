# app/libs — 本地播放内核依赖

这里的 AAR 是 ijkplayer 的 Android 产物，**不提交进仓库**（`.gitignore` 已排除）。
换机器或重新克隆后需要补齐，否则 `assembleDebug` 会因为找不到
`tv.danmaku.ijk.media.player.*` 而编译失败。

## 现在用的是**自己编的**内核，不是官方包

官方 `tv.danmaku.ijk.media:ijkplayer-*:0.8.8` 里的 `libijkffmpeg.so` 只编进了
**23 个解码器，没有 AC-3、没有 MP2、没有 DTS**。而实际片源恰好是：

| 片源 | 音频 | 官方包 |
| :--- | :--- | :--- |
| 《娘道》76 集 | AC-3 | ❌ 有画面没声音 |
| CCTV5 等直播频道 | MP2 | ❌ 有画面没声音 |

所以本仓库用的是一份**重新编译、把 AC-3/E-AC-3/MP2/DTS 都打开**的内核：
`app/libs/ijkplayer-full-0.8.8.aar`（约 17 MB，含 armeabi-v7a / arm64-v8a / x86）。

### 怎么产出这份内核

```bash
# 需要一台 Linux 机器（本仓库用 WSL2 Debian，20 核约 10 分钟）
bash scripts/build-ijkplayer.sh          # 拉源码 → 编 FFmpeg → 编 ijkplayer
bash scripts/collect-ijkplayer.sh        # 收集 .so 并用 nm 校验解码器
bash scripts/pack-ijkplayer-aar.sh       # 打包成 app/libs/ijkplayer-full-0.8.8.aar
```

三个脚本都可以重复跑：源码和 NDK 已经下过就不会再下。

**改内核必须同步改 `AudioSupport.BUILT_IN`**（`app/src/main/java/com/firefly/tv/media/AudioSupport.kt`），
否则要么明明能出声却提示「这台电视不支持」，要么反过来。单元测试会遍历所有编码
把两个方向都钉住。

### 补齐官方包（只在需要重新打包时用）

`app/libs/stock-aar/` 下放着官方包，`pack-ijkplayer-aar.sh` 需要从
`ijkplayer-java-0.8.8.aar` 里取 Java 类和清单。重新下载：

```powershell
$base = 'https://maven.aliyun.com/repository/public/tv/danmaku/ijk/media'
$arts = @{
  'ijkplayer-java-0.8.8.aar'   = "$base/ijkplayer-java/0.8.8/ijkplayer-java-0.8.8.aar"
  'ijkplayer-armv7a-0.8.8.aar' = "$base/ijkplayer-armv7a/0.8.8/ijkplayer-armv7a-0.8.8.aar"
  'ijkplayer-arm64-0.8.8.aar'  = "$base/ijkplayer-arm64/0.8.8/ijkplayer-arm64-0.8.8.aar"
  'ijkplayer-x86-0.8.8.aar'    = "$base/ijkplayer-x86/0.8.8/ijkplayer-x86-0.8.8.aar"
}
foreach ($k in $arts.Keys) {
  Invoke-WebRequest -Uri $arts[$k] -OutFile (Join-Path 'app\libs\stock-aar' $k) -UseBasicParsing
}
```

为什么当初走阿里云镜像：Maven Central 上没有 `com.github.befovy:ijkplayer`（404），
JitPack 上 `befovy/ijkplayer` 的所有 tag 都是 `Error`，
GitHub Release `f0.7.16` 的包里**只有 iOS 的 .framework**。

## 为什么编的时候要打补丁

`scripts/` 下三个补丁脚本，都是「2018 年的代码遇上 2026 年的系统」被迫加的：

| 补丁 | 挡住的错误 |
| :--- | :--- |
| `patch-ffmpeg-for-modern-linux.sh` | `linux/perf_event.h` 在 Debian 13 上没了，FFmpeg n3.4 无条件 include 它 |
| `patch-ijkplayer-no-avdevice.sh` | ijkplayer 调 `avdevice_register_all()`，但它自己又 `--disable-avdevice`，链接必失败 |
| `wsl-fix-apt.sh` | WSL 镜像里过期的 NVIDIA 源用 SHA1 签名，Debian 13 拒收，导致 `apt update` 整体失败 |

## 已知限制

`IMediaDataSource` 通道下 ijkplayer 不会回尾读 moov，
**非 faststart 的 mp4 播不了** —— 由 `MoovRelocatingSource` 在 Java 侧解决。详见 `docs/DESIGN.md` 风险 8。
