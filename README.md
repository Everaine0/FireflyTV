# FireflyTV（萤火照夜）

> 老人便捷媒体播放器：连电视、极简物理按键
>
> 萤火虫不照明整片天，只在夜里静静亮着一小块地方。

为家中老人做的电视播放软件，仅局域网使用。**只有三个按键**：左右换媒体库，上下换剧/换频道，OK 看天气。

## 按键

```
   ← →   切换媒体库（循环）
   ↑ ↓   换剧 / 换频道（循环）
   OK    天气 + 时间 + 日历 + 语音播报
```

切换后**立即播放**。没有列表、没有海报墙、没有选集页。其余按键一律不响应。

> 每次按键都会在画面下方出一条名字（换库时是「《库名》正在打开…」），
> 让老人知道键收到了、正在换什么 —— 切库要等 NAS，没有这条提示看起来就像卡死。
>
> 重入配置页：**设置键 + OK**，或 **返回键 + OK**（不在正常操作路径上，不会误触）

## 已知限制：部分片源没有声音

**这不是配置问题，也不是电视坏了。** 播放内核 ijkplayer 0.8.8 自带的 FFmpeg
只编进了 23 个解码器，**没有 AC-3、E-AC-3、DTS，也没有 MP2**：

| 片源 | 音频 | 声音 |
| :--- | :--- | :--- |
| 《猫和老鼠》《大宅门》等 AAC 片源 | AAC | ✅ |
| 直播频道（18 个） | AAC | ✅ |
| 《娘道》76 集 | **AC-3** | ❌ 画面正常，无声 |
| CCTV5 | **MP2** | ❌ 画面正常，无声 |

应用会**认出来并明确告知**（OK 浮层里显示「这个片子画面能看，声音放不出来（AC-3 音频，这台电视不支持）」），
而不是留一个哑巴画面。详见 [方案设计 · 格式兼容性](docs/DESIGN.md)。

> 有些电视（Amlogic 方案）系统自带 AC-3 解码，判据走设备能力查询，所以真机上结论可能不同。

## 项目信息

| 项 | 值 |
| :--- | :--- |
| 目录 | `FireflyTV` |
| 包名 | `com.firefly.tv` |
| 目标设备 | 长虹 CHiQ 43Q3T（Android 5.1，Cortex-A53，2GB RAM，4K 面板） |
| 技术栈 | Kotlin + 原生 View + ijkplayer + smbj |

## 构建

```powershell
# 本机（访问不到 services.gradle.org，脚本直接用缓存的 Gradle 8.11.1）
.\build.ps1                      # 打 debug APK
.\build.ps1 testDebugUnitTest    # 单元测试（90 项：农历/节气/缓存/导航/按键反馈/格式判定）
.\build.ps1 connectedDebugAndroidTest   # 插桩测试（20 项：播放桥接/配置页/真 NAS 联调/格式实测）
```

联网机器上标准的 `.\gradlew assembleDebug` 同样可用。

首次构建前需要补齐 `app/libs/` 下的 ijkplayer AAR —— 见 [`app/libs/README.md`](app/libs/README.md)。

产物：

| 命令 | 产物 |
| :--- | :--- |
| `.\build.ps1` | `app\build\outputs\apk\debug\app-debug.apk` |
| `.\build.ps1 assembleRelease` | `app\build\outputs\apk\release\app-release-unsigned.apk`（装机前需签名） |

## 测试与联调

```powershell
# 生成测试素材（插桩测试的示例视频）
.\scripts\make-test-media.ps1

# 一键拉起模拟器：装包 + 启动 + 把内置配置页转发出来
.\scripts\dev-env.ps1
```

想用**自己的真实 NAS** 跑插桩联调，把连接信息填进 `local.properties`
（模板见 `local.properties.example`，该文件已 gitignore，不会入库）。
没填就自动跳过相关测试，不会失败。

### 出问题时先跑这两个诊断

```powershell
# 1) 网络到底通不通（模拟器里 ping 不通是正常的，NAT 不回 ICMP，只有 TCP 能说明问题）
.\build.ps1 connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.firefly.tv.diag.NetworkDiagTest
adb logcat -s FireflyDiag

# 2) 每种片源到底能不能播、有没有声音
.\build.ps1 connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.firefly.tv.player.FormatMatrixTest
adb logcat -s FireflyFormat
```

想单独看某一份片源的容器与编码（会打印大小、容器、moov 位置、轨道清单）：

```powershell
.\build.ps1 connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.firefly.tv.player.FormatProbeTest
adb logcat -s FireflyProbe
```

排查 NAS 上的片源结构（尤其是 mp4 的 `moov` 在头还是尾）：

```powershell
$env:PYTHONPATH = '<impacket 安装目录>'
$env:FF_SMB_HOST='192.168.1.100'; $env:FF_SMB_SHARE='media'
$env:FF_SMB_USER='...'; $env:FF_SMB_PASS='...'
python .\scripts\smb-diag.py --limit 10 --probe 8
```

## 文档

- [方案设计](docs/DESIGN.md) —— 按键、媒体库结构、技术选型、**格式兼容性实测**、风险、实施阶段、验证结果

## 状态

🚧 阶段 1–2 已完成并通过真实 NAS 联调；阶段 3–5 部分待真机验收。
**格式兼容性已逐类实测**（见上「已知限制」）。

## 明确不做

刮削、海报墙、选集界面、进度条/暂停界面、返回键功能。

> 每增加一个界面，老人就多一次迷路的可能。
