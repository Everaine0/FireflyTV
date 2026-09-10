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

> 重入配置页：**设置键 + OK**，或 **返回键 + OK**（不在正常操作路径上，不会误触）

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
.\build.ps1 testDebugUnitTest    # 单元测试（农历/节气，11 项）
.\build.ps1 connectedDebugAndroidTest   # 插桩测试（播放桥接/配置页，8 项）
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

排查 NAS 上的片源结构（尤其是 mp4 的 `moov` 在头还是尾）：

```powershell
$env:PYTHONPATH = '<impacket 安装目录>'
$env:FF_SMB_HOST='192.0.2.3'; $env:FF_SMB_SHARE='media'
$env:FF_SMB_USER='...'; $env:FF_SMB_PASS='...'
python .\scripts\smb-diag.py --limit 10 --probe 8
```

## 文档

- [方案设计](docs/DESIGN.md) —— 按键、媒体库结构、技术选型、风险、实施阶段、验证结果

## 状态

🚧 阶段 1（骨架 + 二维码配置页 + SMB 播放打通）已完成并通过验证；阶段 2–5 待真机联调

## 明确不做

刮削、海报墙、选集界面、进度条/暂停界面、返回键功能。

> 每增加一个界面，老人就多一次迷路的可能。
