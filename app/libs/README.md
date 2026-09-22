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

另外官方包**没有编 OpenSSL**（三个 ABI 的 openssl 符号数都是 0），
所以 `https://` 的直播源一律起不来（`https protocol not found`）——
频道表里 `live.264788.xyz` / `live2.example.com` / `myalicdn` / `cgtn.com` 这些占了一大半。

所以本仓库用的是一份**重新编译、把 AC-3/E-AC-3/MP2/DTS 都打开、并且带 OpenSSL** 的内核：
`app/libs/ijkplayer-full-0.8.8.aar`（约 20 MB，含 armeabi-v7a / arm64-v8a / x86）。

自检（每个 ABI 都要有数）：

```bash
for abi in x86 armeabi-v7a arm64-v8a; do
  echo "$abi openssl=$(strings app/libs/.../jni/$abi/libijkffmpeg.so | grep -c 'SSL_connect\|OPENSSL_init')"
done
```

### 怎么产出这份内核

```bash
# 需要一台 Linux 机器（本仓库用 WSL2 Debian，20 核约 10 分钟）
bash scripts/build-ijkplayer.sh          # 拉源码 → 编 FFmpeg → 编 ijkplayer
bash scripts/collect-ijkplayer.sh        # 收集 .so 并用 nm 校验解码器
bash scripts/pack-ijkplayer-aar.sh       # 打包成 app/libs/ijkplayer-full-0.8.8.aar
```

三个脚本都可以重复跑：源码和 NDK 已经下过就不会再下。
默认把源码放在仓库内的 `build/ijkbuild/`（已被 gitignore 排除），可用 `WORK=` 覆盖。

### x86 必须打开汇编，否则模拟器上的 4K 只有 4fps

`build-ijkplayer.sh` 会自动调 `scripts/patch-ffmpeg-x86-asm.sh`，把上游那段
`if [ "$FF_ARCH" = "x86" ]; then --disable-asm` 改成打开汇编。
上游这么写有历史原因（2015 年的 yasm + NDK r10 会产出带文本重定位的 .so），
但代价是**模拟器拿到的是一份纯 C 的 libavcodec** —— 4K HEVC 软解只有 4fps。
改完之后同一个文件的解码 CPU 从 246ms/帧降到 117ms/帧。详见 [docs/DEVLOG.md](../../docs/DEVLOG.md#模拟器上-4k-还是卡) 的「根因 D」。

> 只编 FFmpeg（不跑 `compile-ijk.sh`）时产物是**没 strip** 的（x86 约 55MB）。
> 用 NDK 自带的 `i686-linux-android-strip --strip-unneeded` 处理到 14MB 再打包。

### 补 OpenSSL（https 直播源）

```bash
bash scripts/build-ffmpeg-openssl.sh armv7a arm64 x86   # 编 openssl → 重编 FFmpeg → 重新链接
bash scripts/swap-ffmpeg-so.sh armeabi-v7a <...>/ijkplayer-armv7a/src/main/libs/armeabi-v7a/libijkffmpeg.so
```

两个坑都写在脚本注释里，这里再记一遍：

1. `compile-ffmpeg.sh` **每次都会从 `extra/ffmpeg` 重铺源码**，之前打在
   `android/contrib/ffmpeg-<abi>/` 上的补丁全部作废 —— 脚本会在编译前重打一遍
   （否则第一刀就死在 `fatal error: linux/perf_event.h: No such file or directory`）。
2. 目录里已经有 `config.h` 时，`do-compile-ffmpeg.sh` **直接复用配置**，
   新加的 `--enable-openssl` 不会生效（症状：编译秒过、`https` 依旧报 Protocol not found）。
   必须删掉 `config.h` 逼它重新 configure。

> 顺带修了上游一个磨人的地方：`do-detect-env.sh` 只在 **macOS** 上给了 `make -j`，
> Linux 下是空的 —— 三个 ABI 全量重编要一个多小时。
> `scripts/patch-ffmpeg-parallel-make.sh` 把它改成 `-j$(nproc)`，同样 20 核只要几分钟。

### 只改 FFmpeg、不想重编整个内核时

`libijkplayer.so` / `libijksdl.so` 一行都没动的话，没必要跑十几分钟的完整编译。
单独编出 FFmpeg 之后，用这个脚本把某一个 ABI 的 `libijkffmpeg.so` 换进现有 AAR：

```bash
bash scripts/swap-ffmpeg-so.sh x86 \
  build/ijkbuild/ijkplayer/android/contrib/build/ffmpeg-x86/output/libijkffmpeg.so
```

它会先校验 ELF 架构（防止把 arm64 的产物塞进 x86 目录），
并检查 x86 那份确实带 SIMD 符号 —— 装错了要到电视上才炸，所以在这里拦一道。
原 AAR 会先备份成 `.bak`。

**改内核必须同步改 `AudioSupport.BUILT_IN`**（`app/src/main/java/com/firefly/tv/media/AudioSupport.kt`），
否则要么明明能出声却提示「这台电视不支持」，要么反过来。单元测试会遍历所有编码
把两个方向都钉住。

### 补齐官方包（只在需要重新打包时用）

`pack-ijkplayer-aar.sh` 需要从官方 `ijkplayer-java-0.8.8.aar` 里取 Java 类和清单；
**`app/libs/stock-aar/` 不在仓库里**（AAR 已被 gitignore），第一次用先下到这里：

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
| `patch-ffmpeg-parallel-make.sh` | 上游只给 macOS 设了 `make -j`，Linux 下是单线程，全量重编要一个多小时 |
| `patch-ffmpeg-x86-asm.sh` | 上游对 x86 写死 `--disable-asm`，模拟器拿到的是一份纯 C 的 libavcodec |
| `patch-ijkplayer-no-avdevice.sh` | ijkplayer 调 `avdevice_register_all()`，但它自己又 `--disable-avdevice`，链接必失败 |
| `wsl-fix-apt.sh` | WSL 镜像里过期的 NVIDIA 源用 SHA1 签名，Debian 13 拒收，导致 `apt update` 整体失败 |

## 已知限制

`IMediaDataSource` 通道下 ijkplayer 不会回尾读 moov，
**非 faststart 的 mp4 播不了** —— 由 `MoovRelocatingSource` 在 Java 侧解决。详见 `docs/DESIGN.md` 风险 8。
