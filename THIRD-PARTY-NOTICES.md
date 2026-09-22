# 第三方组件与许可证

本仓库**自己的源码**按根目录 [LICENSE](LICENSE)（Apache License 2.0）授权。
下面这些第三方组件**各自按自己的协议授权**，与 Apache-2.0 无关；随 APK 一起分发时要一并遵守。

> 本仓库**不包含**这些组件的源码或二进制（`app/libs/**/*.aar` 已在 `.gitignore` 里排除），
> 构建时会从各自的官方来源获取，或由 `scripts/` 下的脚本重新编译。

## 随 APK 一起分发的组件

| 组件 | 版本 | 许可证 | 用在哪 / 怎么确认的 |
| :--- | :--- | :--- | :--- |
| [ijkplayer](https://github.com/bilibili/ijkplayer) | k0.8.8 | **LGPL-2.1-or-later** | 播放内核（`libijkplayer.so` / `libijksdl.so` / `libijkffmpeg.so`）。上游 README 明写 "ijkplayer is licensed under LGPLv2.1 or later" |
| [FFmpeg](https://ffmpeg.org/) | n3.4（随 ijkplayer，重新编译） | **LGPL-2.1-or-later** | 实测 `libijkffmpeg.so` 自报 `libavcodec license: LGPL version 2.1 or later`；并且 .so 里嵌的 configure 行**没有** `--enable-gpl` / `--enable-nonfree`（有 `--enable-openssl` 与 AC-3/E-AC-3/MP2/DTS 解码器） |
| [OpenSSL](https://www.openssl.org/) | 1.0.2n（`Bilibili/openssl`，tag `OpenSSL_1_0_2n`） | OpenSSL License + SSLeay License（BSD 风格，带署名条款） | 让 `https://` 直播源能起播；由 `scripts/build-ffmpeg-openssl.sh` 编入 FFmpeg |
| [libyuv](https://chromium.googlesource.com/libyuv/libyuv/) | 随 ijkplayer | BSD-3-Clause | `libijksdl.so` 里有 `I420Copy` / `ARGBToI420` 等符号（协议按 ijkplayer README 声明） |
| [SDL](https://www.libsdl.org/) | 随 ijkplayer（SDL 分支） | zlib | `libijksdl.so`，ijkplayer 的显示/音频层 |
| [SoundTouch](https://www.surina.net/soundtouch/) | 随 ijkplayer | LGPL-2.1-or-later | `libijkplayer.so` 里有 soundtouch 符号；上游 README 列为 LGPL |
| [zxing](https://github.com/zxing/zxing) | core 3.5.3 | Apache-2.0 | 电视端配置页二维码 |
| [smbj](https://github.com/hierynomus/smbj) | 0.15.0 | Apache-2.0 | SMB2/3 客户端 |
| [SLF4J](https://www.slf4j.org/) | 2.0.13（`slf4j-nop`） | MIT | smbj 的日志门面 |
| [AndroidX](https://developer.android.com/jetpack/androidx)（core-ktx 1.12.0 / appcompat 1.6.1） | — | Apache-2.0 | |
| [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.7.3 | Apache-2.0 | |
| [desugar_jdk_libs](https://github.com/google/desugar_jdk_libs) | 2.0.4 | **GPL-2.0 WITH Classpath-exception-2.0** | API 21 上的 `java.*` 脱糖回填，随 APK 分发；Classpath 例外允许它与任意协议的模块链接 |

> ijkplayer 的 README 另外声明它的部分代码派生自 LGPL 的 libVLC / kxmovie —— 这里一并致谢。

## LGPL 合规说明（ijkplayer / FFmpeg / SoundTouch）

这几个库是 **LGPL-2.1-or-later**，本项目以**动态链接**方式使用（APK 里是独立的 `.so`，可以单独替换），
因此你自己的代码不受其传染，但分发时请一并满足：

- **保留声明**：分发 APK 时附上本文件（或等价的第三方声明）。
- **能替换该库**：LGPL 要求使用者有权替换这几个库。本项目满足这一点 ——
  源码与构建脚本都在仓库里：
  - ijkplayer k0.8.8：<https://github.com/bilibili/ijkplayer>（tag `k0.8.8`）
  - FFmpeg n3.4：ijkplayer 的 `extra/ffmpeg`
  - 编译步骤：[app/libs/README.md](app/libs/README.md)、[scripts/build-ijkplayer.sh](scripts/build-ijkplayer.sh)、
    [scripts/build-ffmpeg-openssl.sh](scripts/build-ffmpeg-openssl.sh)、[scripts/pack-ijkplayer-aar.sh](scripts/pack-ijkplayer-aar.sh)
  - 重新编译后用 `scripts/swap-ffmpeg-so.sh` 换掉 AAR 里的 `.so` 再打包即可。
- **不要只发二进制**：如果发布 APK，请同时提供上面这两个库的源码获取方式（本仓库的构建脚本已经指向官方仓库）。

## 其他

- 应用图标（`app/src/main/res/mipmap-*` 与 `drawable/ic_launcher_foreground.xml`）是自绘的，随本项目一起授权。
- 文档里引用的第三方文章、issue、源码位置，版权归各自作者，仅作引用与出处标注。
