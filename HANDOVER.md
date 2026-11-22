# HANDOVER：构建、发布与维护记录

最后更新：2026-11-22 ｜ 当前版本 1.1.3（versionCode 7）｜ code commit `f3a6288` ｜ tag `v1.1.3`

## 仓库与发布

- Gitea：`http://8.146.238.150:10200/everaine/firefly-tv`，分支 `main`。
- Release：`v1.1.3`，资产 `app-release.apk`（24,758,131 B，
  SHA-256 `81217249916e92f335072cc039eb17d29d70aa4972d11a1a6a81453402c54668`）；
  本地留档 `firefly-apk/firefly-tv-1.1.3.apk`（`firefly-apk/` 不入库）。
- 回滚资产：`firefly-tv-1.1.2.apk`（24,757,641 B，
  SHA-256 `4dbe6d1ee39229ce2e95e15be19450e5ff758f0e724796b025cf0940efdbd7a9`）。
- 包名 `com.firefly.tv`；签名复用 Windows debug keystore
  （证书 SHA-256 `da14fe1d035dff9284bbbbbd91715d98c88aa10064b63fa36245bf67b4186272`）。
  同签名覆盖安装保留配置和观看记录。
- `app/libs/ijkplayer-full-0.8.8.aar` 是本地二进制、不入库；Release APK 内含三个 ABI 的
  `libijk*.so`，本身就是这份内核的可用恢复源。

## 构建（WSL 编辑，Windows 构建）

本机访问不到 services.gradle.org，Gradle 只能用 Windows 缓存里的 8.11.1，所以真正的构建由
Windows 侧跑：

```bash
ANDROID_HOME='D:\AndroidSDK' WIN_ROOT='E:\firefly-build' \
  scripts/win-build.sh testDebugUnitTest assembleRelease
```

脚本会同步 `app/src`、`app/build.gradle.kts`、`app/proguard-rules.pro` 与
`app/libs/ijkplayer-full-0.8.8.aar`，产物回拷到 `build/apk/`。

- Windows 源码树：`E:\firefly-build`
- JDK：`C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot`
- SDK：`D:\AndroidSDK`（build-tools 36.1.0 / platforms 34）
- Gradle：`%USERPROFILE%\.gradle\wrapper\dists\gradle-8.11.1-bin\...`
- 验证结果：290 项单测 0 失败；release 构建正常（缓存热、R8 正常）。
- `scripts/win-build.sh` 在 WSL 侧先建 `build/apk`，否则 `.bat` 的产物回拷会静默失败；
  该脚本有可执行位（755），其余脚本按 README 用 `bash scripts/xxx.sh` 调用。

## 播放内核（自编 AAR）

自编 `libijkffmpeg.so` 的目标：AC-3 / E-AC-3 / MP2 / DTS 解码器 + OpenSSL（https 直播源），
三个 ABI（armeabi-v7a / arm64-v8a / x86），AAR 约 20 MB。

```bash
bash scripts/build-ijkplayer.sh          # 编 FFmpeg + ijkplayer
bash scripts/build-ffmpeg-openssl.sh     # 补 OpenSSL 并重链 libijkffmpeg.so
bash scripts/collect-ijkplayer.sh        # 收集到 build-out/ijkplayer-full
bash scripts/pack-ijkplayer-aar.sh       # 打成 app/libs/ijkplayer-full-0.8.8.aar
```

- 打 AAR 前要把官方四个 AAR 放回 `app/libs/` 根，打完移回 `app/libs/stock-aar/`，
  否则 `fileTree("libs")` 会扫到重复类。
- `build-ffmpeg-openssl.sh` 必须先删旧 `config.h`，否则 `do-compile-ffmpeg.sh` 复用旧配置，
  `--enable-openssl` 永远不生效（症状：编译秒过、https 报 Protocol not found）。脚本已修。
- 检查 OpenSSL 要看 `android/contrib/ffmpeg-<abi>/config.h` 的 `CONFIG_OPENSSL 1`，
  不要用 `find build/ffmpeg-<abi> -name config.h`：会先撞上 toolchain 的 sysroot config。
- 自检：三个 ABI 的 `libijkffmpeg.so` 里 `SSL_connect|OPENSSL_init` 各 3 个；
  `ff_ac3_decoder`、`ff_mp2_decoder`、`ff_dca_decoder` 都在。release APK 的
  `lib/x86/libijkffmpeg.so` 同。

### 源码下载慢时的做法（2026-11-21 实际用了这条）

GitHub 全量 clone `Bilibili/FFmpeg` 太慢（十几分钟还没完），改用 codeload tarball：

1. 下载 tag tarball：`https://codeload.github.com/Bilibili/FFmpeg/tar.gz/refs/tags/ff3.4--ijk0.8.7--20180103--001`
   （libyuv/soundtouch 用 `refs/heads/ijk-r0.2.1-dev` / `ijk-r0.1.2-dev`；
   OpenSSL 用 `refs/tags/OpenSSL_1_0_2n`）。
2. 解压到 `build/ijkbuild/ijkplayer/extra/ffmpeg`（或 openssl/libyuv/soundtouch），
   `git init` + commit + 打同名 tag。
3. 临时改 `build/ijkbuild/ijkplayer/tools/pull-repo-base.sh`（目录已存在就跳过全量 fetch）、
   `tools/pull-repo-ref.sh`（从本地 base clone 而不是远端）、
   `init-android.sh` / `init-android-openssl.sh`（只保留三个 ABI）。
   这些是 build tree 里的临时改动，不在仓库；网络正常时不必改。

### AAR 丢了怎么恢复

1. **从 Release APK 取**（最快）：`unzip -o app-release.apk 'lib/*'`，得到三个 ABI 的
   `libijk*.so`，按 `build-out/ijkplayer-full/<abi>/` 放好，再跑 `pack-ijkplayer-aar.sh`。
2. **全量重编**：上面的四步。
3. 不要从 AVD 的 userdata 里取：之前那个 UI-only 包 `lib/` 是空的，
   先 `unzip -l base.apk | grep lib/` 确认再谈恢复。

## 测试与验证

- 单测：`testDebugUnitTest` 290 项，0 失败（2026-11-22）。
- Release APK：`versionCode 7 / versionName 1.1.3`，minSdk 21 / targetSdk 34，
  native-code 三个 ABI，debug 签名；安装到 AVD API 22/x86 验证过开机页行为。
- 模拟器实测（AVD，NAS 指向不可达地址）：
  - 1.1.2（基线）：开机页约 8 秒后被故障页顶掉，随后「故障页 → 纯黑 10 秒 → 故障页」循环；
  - 1.1.3：开机页一直留着，状态行写「连接超时，检查地址和网络」，连续 3 次重试失败
    （约 50 秒）后才交给故障页；故障页在后续重试期间**保持可见**，不再黑屏；
  - 断网（`ifconfig eth0 down`，等 ConnectivityService 掉网）后重启：开机页显示
    「正在等待网络连接…」，不产生「连接超时」假故障；`ifconfig eth0 up` 后约 2 秒
    自动变成「网络已连接，正在打开 NAS…」并继续起播。
- 截图：`docs/images/boot.png`（1.1.2 版式，1.1.3 视觉未变）、`docs/images/switch.png`（换库过渡页）。
  AVD 1920×1080/density 320；配置用 `scripts/push-prefs.sh` + `build/tmp/fake-prefs.xml`
  （gitignored）。过渡页截图是在 debug 预览里隐藏 SurfaceView 后拍的：模拟器软件渲染下
  独立 surface 会盖住普通 View；**release 包没有预览代码**，真实电视上视频层和过渡层正常叠加。
- 未验证：插桩测试（需要真实 NAS + `local.properties`）、真机 4K 帧率、中文 TTS、
  长虹设置键 keyCode、主屏抢占、https 直播源真机网络。相关结论见 `docs/DESIGN.md` §10 与风险清单。

## 模拟器

```bash
cd /mnt/d/AndroidSDK/emulator
./emulator.exe -avd firefly_tv -no-window -no-audio -no-snapshot-save -gpu swiftshader_indirect
/mnt/d/AndroidSDK/platform-tools/adb.exe devices
```

AVD：`firefly_tv`（API 22 / x86 / 1920×1080 / density 320）。安装：
`adb install -r build/apk/app-release.apk`。真实 NAS 不可达时，fake prefs 用
192.0.2.1 可稳定触发故障页；开机页和换库过渡页不依赖 NAS。

## 发布流程

1. `git push origin main`；`git tag -a v1.1.2 -m ... && git push origin v1.1.2`。
2. Gitea API：`POST /api/v1/repos/everaine/firefly-tv/releases`（token 只从
   `~/.git-credentials` 读，不写进仓库/不打印），body 含安装、覆盖、回滚、未验证项。
3. 上传资产 `app-release.apk`，记录 SHA-256。
4. Release 页面：`http://8.146.238.150:10200/everaine/firefly-tv/releases/tag/v1.1.3`。

## 回滚

- 回滚资产：`firefly-apk/firefly-tv-1.1.2.apk`（1.1.2 / versionCode 6，SHA-256 `4dbe6d1ee39229ce2e95e15be19450e5ff758f0e724796b025cf0940efdbd7a9`）；Release 侧同版本资产可从 `v1.1.2` 标签重新取。
- 从 tag `v1.1.2` 重建：同一份 AAR、同一把 debug keystore，`versionCode 6`。
- 1.1.3 覆盖 1.1.2 是升级；回滚到 1.1.2 是降级，电视 U 盘安装需要先卸载
  （会清配置和观看记录），或用 adb `install -d`。所以回滚前先保留 1.1.2 的 APK，再决定。
- 不要换签名：换签名会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，卸载会丢配置和记录。

## 已知坑（按重要性）

- 旧 UI-only 包没有 native 库，不能当内核来源。
- `win-build.sh` 只同步 `app/src`、`app/build.gradle.kts`、`proguard-rules.pro`、AAR；
  改 settings/root gradle 时要手动同步过去。
- FFmpeg 全量 clone 慢，见「源码下载慢」。
- `pack-ijkplayer-aar.sh` 固定从 `app/libs/` 根找官方 AAR；打完后移回 `stock-aar/`。
- R8 会报 `MediaCodecChoice.kt` 的 unused param warning，无影响。
- 过渡页在模拟器截图时会被 SurfaceView 盖住；只是预览环境问题，真实电视上视频层与过渡层正常叠加。
- **开机页最短 5 秒必须从首次 `dispatchDraw` 算**，不能从 `show()` 算：onCreate 到窗口出画
  可能差几秒，按调用时刻计会让用户看到的动画短一截（1.1.2 及以前的实测反馈「2 秒就没了」）。
- **开机自启时网络往往比应用晚就绪**：`startPlayback` 先查 `activeNetworkInfo`，没网就
  500ms 轮询等待、不去连 NAS；否则第一次连接必然失败，会被当成故障。
- **开机页显示期间，会自动重试的故障不要 `notifyFault()`**：先写状态行、最多挡 3 次；
  否则用户看到「动画闪一下 → 旧版那句打不开」。不可重试的故障仍然立刻让位。
- **重试期间不要用 `showFault(null)` 清屏**：它会把原因和提示条一起收掉，重试的十几秒
  屏幕纯黑（模拟器实测过）。让原因留在屏幕上，成功时 `onPrepared`/`onFirstFrame` 自己收。
- 模拟器模拟断网：`adb shell "ifconfig eth0 down"` 后要等约 4 秒 ConnectivityService 才认为
  没有默认网络；直接看 `dumpsys connectivity` 的 `Active default network`。

## 待确认

- 真机 4K 帧率、中文 TTS、设置键 keyCode、长虹主屏抢占。
- https 直播源在真实网络下的起播。
- 老人实际观感：开机页 1.1.2 左侧遥控器 + 右侧三行提示、开机页 5 秒、过渡页 0.4 秒、400ms 防抖是否需要调参。
- 真机开机自启时网络就绪要多久、开机页实际露脸时长（1.1.3 起诊断页有一行
  「开机页 X 秒后为什么退场」，用户复现时直接读这一行）。
