# FireflyTV · 方案设计

> 老人便捷媒体播放器：连电视、极简物理按键
>
> 定稿 2026-09-11 ｜ 状态：阶段 1–4 已落地，模拟器全流程验证；真机侧未验证项见 §9 风险 21–23 与「尚未实现 / 未在目标设备上确认」表

## 速览

| 项 | 值 |
| :--- | :--- |
| 包名 | `com.firefly.tv` |
| 形态 | Android TV 应用（可兼装手机测试） |
| 目标设备 | 长虹 CHiQ 43Q3T：Android 5.1，Cortex-A53，2GB RAM，4K 面板 |
| minSdk / targetSdk | 21 / 34 |
| 播放内核 | ijkplayer（FFmpeg + MediaCodec） |
| 网络 | 仅局域网（天气接口除外） |
| 界面缩放 | `UiScale`：按「逻辑分辨率 ÷ 系统 density」校正，不直接信 dp/sp（风险 21） |
| 实机排错 | 双击「设置 / 信息」键 → 播放诊断页（解码通路真值 + 帧率 + 读取 + 结论） |

### 落地后的实现选择（与初稿的差异）

| 项 | 初稿 | 实际采用 | 原因 |
| :--- | :--- | :--- | :--- |
| SMB 库 | SmbJ / jcifs-ng 混写 | **`com.hierynomus:smbj:0.15.0`** | 统一为 SmbJ：Apache-2.0，原生 SMB2/3；jcifs-ng 是 LGPL 且与 README 写法冲突 |
| 农历 | `android.icu.util.ChineseCalendar` | **自带 1900–2100 农历表** | `android.icu` 是 API 24+，且 Kotlin 下引用不到；自带表反而少一个兼容风险 |
| 节气 | （未提） | **太阳视黄经迭代计算** | 不用「几月几日」口诀表，那种表会差一天 |
| 播放来源 | 直接 SMB | **`RandomAccessSource` 抽象 + `SmbMediaDataSource`** | 抽出字节源后可在模拟器上用本地文件跑插桩测试 |
| ijkplayer 产物 | 未定 | **自编内核 `app/libs/ijkplayer-full-0.8.8.aar`**（官方 `tv.danmaku.ijk.media:ijkplayer-*:0.8.8` 只用来取 Java 类与清单） | 官方包的 `libijkffmpeg.so` 只编进 23 个解码器：没有 AC-3/E-AC-3/MP2/DTS，也没编 OpenSSL —— 表现就是「有画面没声音」和 `https://` 直播源起不来。重建方式见 `app/libs/README.md` 与 `scripts/build-ijkplayer.sh`；bilibili 版即 §6 所说的参考基线 |

## 1. 目标

为家中老人开发操作极简的电视播放软件，仅局域网内使用。

**成功标准**：老人不用学、不用看说明书、不会误操作退出，打开电视就在放他想看的。

**明确不做**：刮削、海报墙、选集界面、进度条/暂停界面、返回键功能、配置页事后重入。

> 每增加一个界面，老人就多一次迷路的可能。

## 2. 使用流程

```
首次开机 → 电视显示二维码 → 手机扫码打开电视内置的配置网页
        → 填 SMB 地址/账号/密码（天气可选）
        → 保存时逐项实测：失败显示具体原因，成功立刻进入播放
此后每次开机 → 直接播放上次的内容
```

**天气是可选项**（落地时定的）：SMB 必填，天气三项要么都填、要么都留空。
理由：老人看电视不依赖天气，而和风要单独申请 Key —— 为了填天气而卡住「连 NAS 看电视」是反的。
天气没配时，OK 浮层里那一行显示「还没有设置天气」，其余照常。

配置页由**应用内置的极小 HTTP 服务**提供，不依赖外部服务器。配置完成后服务关闭。

**访问控制**（已定）：二维码地址里带一次性随机 token（`http://<电视IP>:<端口>/?t=<token>`），
服务只在「未配置」或用户主动呼出时启动。同网段其他人拿不到 token 就只能看到一句中文提示。
实测：不带 token 或 token 错误一律返回 403。

**重入入口**（已定）：设置键 + OK，或 返回键 + OK。单键不会误触，组合键在遥控器上又很容易按出来。
调试期若懒得按，也可以在电脑上直接触发配置页（见 README）。

## 3. 按键（全部按键，仅此三类）

| 按键 | 行为 | 边界 |
| :--- | :--- | :--- |
| ← / → | 切换媒体库，切换后立即播放 | 循环 |
| ↑ / ↓ | 当前库内换剧 / 换频道，切换后立即播放 | 循环 |
| OK | 全屏浮层：天气+时间+日历+当前剧集名，并语音播报，约 10 秒淡出 | — |

其余按键（返回、主页、菜单）**不响应任何应用逻辑**。音量键交给系统，不拦截。

## 4. 媒体库结构

SMB 共享的**一个根目录**，其下每个子文件夹 = 一个媒体库，库名取文件夹名，不提供重命名或排序设置。

```
\\NAS\media\
├── 电视剧\      ← 视频库：库/剧名/剧集文件
│   └── 水浒传\  → 水浒传01.mkv, 水浒传02.mkv ...
├── 电影\        ← 视频库，**可以平铺**：库/电影.mp4（每个文件算一部「剧」，单集）
└── 直播\        ← 含 .m3u 即自动识别为 IPTV 库
```

- **IPTV 库自动识别**：目录内含 `.m3u` 文件即判定为直播库，↑/↓ 语义变为换频道（平铺列表）
- **自然排序**：数字段按数值比较（第 2 集排在第 10 集前）
- **平铺的视频文件也算一部剧**：`库/电影.mp4` 这种没有剧名子目录的库要能打开 ——
  老版本只认子文件夹，于是整库被判成空库并「唰地跳过去」（实机反馈）。
  路径拼装按「集名 == 剧名 ⇒ 不再多一层目录」处理（`Scanner.isLooseShow`）
- **容错**：无视频文件的文件夹、无有效内容的库一律跳过，但**跳过有上限**：
  转完一整圈还没找到能播的内容就报故障，而不是无限跳（每跳一次都是一轮 SMB 列目录）
- **库列表刷新后按下标前必须按名字重新锚定**：缓存里的库列表可能比 NAS 上少一个
  （新加一个文件夹就是这样），刷新回来时旧下标指向的已经是**另一个库** ——
  实机表现是「按上键没反应、按右键跳到别的库去了」（`Navigator.libraryIndexAfterRefresh`）

## 5. 播放行为

| 场景 | 行为 |
| :--- | :--- |
| 启动 | 恢复上次的库/剧/集/**精确进度**，接着播 |
| 一集播完 | 自动下一集 → 全剧播完自动下一部剧 → 最后一部播完回到该库第一部 |
| ← / → 切库 | 播放目标库的记忆位置 |
| ↑ / ↓ 切剧 | 播放目标剧第 1 集 |
| IPTV 直播 | 直接起播，断流自动重连；不做进度记忆 |

**字幕**：中文字幕优先（内嵌中文轨或同名外挂 `.srt`/`.ass`）；**无中文字幕则不显示任何字幕**。音轨用文件默认。

**续播写盘**：库+剧+集+进度，**5 秒防抖**，避免频繁 IO 卡顿。

## 6. 技术选型

Kotlin + **原生 View**（不用 Compose：API 21 不可用，且 TV 焦点更可控）+ SmbJ（SMB2/3）+ 手写 `ServerSocket` 提供配置页 + `android.icu.util.ChineseCalendar` 算农历 + `SharedPreferences` 存配置（避开 Room/DataStore 的兼容风险）。

### 为什么播放内核用 ijkplayer 而不是 Media3

目标设备是 2016 年的 Android 5.1 电视，系统硬解不可靠：

| | Media3 | ijkplayer |
| :--- | :--- | :--- |
| 软解兜底 | 几乎没有 | **自带完整 FFmpeg 软解** |
| API 22 稳定性 | 有公开崩溃报告 | 老设备久经考验 |
| RMVB / TS / FLV | 不支持 | 支持 |
| 维护 | 活跃 | 停更（FFmpeg n3.4，约 2018） |

对播 NAS 老剧、给老人用的软件，**"什么都能播"比"版本新"重要**。电视原生就支持 H.265/RMVB/DTS，说明片源里真有这些格式。

解码策略是**硬解优先、软解兜底**：默认交给电视的 MediaCodec（4K H.265 只能靠它），
硬解失败或 15 秒不出首帧就自动用软解重播（风险 9）。

播放层抽象为 `PlaybackEngine` 接口，换回 Media3 只需新增一个实现类。基线取 `befovy/ijkplayer`，必要时参考 `bilibili/ijkplayer`。

### 核心技术难点：ijkplayer 不认 SMB

必须自己桥接数据，且 `readAt()` 要实现**随机读**，否则拖进度与续播会失灵：

```
SMB 文件 (smbj) → 实现 readAt() → IMediaDataSource → IjkMediaPlayer
```

### 天气接口：初稿和风 → 实际心知（历史，已不适用）

> **这一段是初稿方案，落地时没有采用**：和风自 2026 年起废弃共享域名，每账号一个专属
> API Host（形如 `<你的子域>.xy.qweatherapi.com`），配置页要多填一栏 —— 老人填不对。
> 实际实现换成了心知天气，只要「私钥 + 地点」两栏，见「风险 20：天气换成心知」。

## 7. 适配与性能

- **界面缩放按「逻辑分辨率 / 系统声明的 density」校正，不直接信 dp/sp**（见风险 21）
- 所有内容留 **5% 安全边距** —— 电视 overscan 会裁掉贴边内容
- 字号：正文 ≥24sp，主要信息 ≥32sp，时间/天气 ≥64sp（设计基准 = 1080p）
- **两级缓存**：内存缓存（按库）→ **落盘缓存**（`LibraryCache`，存「库 / 剧 / 集」三级结构）
- 冷启动**先用落盘缓存出画面**，再在后台静默刷新；刷新失败不打扰用户
- 切库/换台/换剧都先出即时反馈（[SwitchHud]），再做后面可能慢的事
- 单次列目录限 2000 条；SMB 连接同时最多 1–2 条

> **为什么必须有落盘缓存**：这台 NAS 有 3 部剧、274 个文件。第一版每次开机都重问一遍，
> 开机就是几十次 SMB 往返 —— 每次都现查，不做常驻索引。
> 现在冷启动直接用上次的结构，实测画面上屏不再等 NAS。
>
> 缓存只存**结构**，不存播放进度（进度在 `Config.spot` 里，两者独立失效）。
> 换了 NAS / 共享 / 根目录 / 账号，缓存整体作废 —— 否则会拿 A 的剧名去 B 上找。

**按键反馈必须有**：实测按了左键画面还是 IPTV，分不清键有没有生效，只能按 OK 去确认。
现在换库立刻出「《库名》正在打开…」，内容出来后再换成内容名，1.6 秒后收起。
规则由 `SwitchHud` 承担（换库「正在打开…」、起播「正在加载…」两种），14 项单元测试钉住。

> 加载提示的期限是 **60 秒（`SwitchHud.LOADING_MS`）**，**不是**永不超时。
> 早先写的是 `deadline = 0L`（"SMB 慢就一直留着"），但那条提示没有任何出口兜底：
> 后面的链子一旦断在半路，屏幕上就永远挂着这句话 —— 用户报的「刚开机卡住加载不动」
> 正是这个形态。现在它到期会自己让位，同时 40 秒的启动兜底看门狗更早就接手了
> （见「已修复」一节）。

**防泄漏硬要求**：`onDestroy` 中依次释放 `IjkMediaPlayer.release()`、TTS 解绑后 `shutdown()`、`Handler.removeCallbacksAndMessages(null)`、协程 `cancel()`、关闭 SMB 流与配置页 `ServerSocket`。

> 最典型泄漏点：「故障页每 10 秒重试」若不清理 Handler 回调，Activity 销毁后稳定泄漏。

## 8. 故障处理

原则：**老人看得懂、不用管、会自愈**。全屏大字纯中文，不出现技术术语。

| 故障 | 显示 | 自动行为 |
| :--- | :--- | :--- |
| NAS 连不上 / 断网 | 无法连接 NAS，请检查网络 | 每 10 秒重试，恢复后回播放 |
| m3u 拉取失败 | 直播源暂时无法加载 | 每 10 秒重试 |
| 单集文件损坏 | 这个视频无法播放 | 3 秒后跳下一个 |
| NAS 硬盘休眠 / 起播慢（**没出过画面**） | 先出「正在加载…」，**不报故障** | 自动重试（窗口 40 秒、最多 3 次、退避 3/8/15 秒，`StartupGrace`）；超过窗口才把原因摆出来 |
| 天气接口失败 | 天气获取失败 | 静默重试，不阻塞浮层其他内容 |

## 9. 风险清单

> 编号按**追加顺序**保留（18/19/20 与 23/24 是后来补进去的，所以顺序不递增）——
> 正文里的「见风险 N」都按这张表的编号引用，重新编号会打断全文所有引用。

| # | 风险 | 应对 |
| :--- | :--- | :--- |
| 1 | 长虹「虹领金系统」拦截 `CATEGORY_HOME`，抢不到主屏 | 开机自启兜底，双保险；抢主屏效果要在长虹系统上确认 |
| 2 | 电视无中文 TTS 引擎 | **提前验证**；退化为仅文字显示 |
| 3 | ijkplayer 停更、来源不确定 | 以官方 `befovy` 版为基；保留 `PlaybackEngine` 换回 Media3 的退路 |
| 4 | SMB 兼容性（SMB1/2/3、字符编码） | 配置建议填 IP 避免 DNS；SMB 层强制走 smbj 的 Unicode 协商 |
| 5 | ~~和风天气 TLS 握手在 Android 5.1 失败~~（**已作废**：天气已换心知，走标准 HTTPS） | 心知同样是 HTTPS，TLS 握手仍要在 Android 5.1 设备上实测 |
| 6 | 遥控器键值差异 | `logcat` 查实际 keyCode；同时处理 `DPAD_*` 与 `ENTER` |
| 7 | **4K 面板 + density 撒谎**：逻辑分辨率 3840×2160 却仍报 dpi 320 → 浮层只占半屏，「按 OK 只有中间一小块有内容」 | ✅ **已修**：`UiScale` 按「短边 ÷ (6 × densityDpi)」校正。见风险 21 |
| 8 | **非 faststart 的 mp4 播不了**（moov 在文件尾） | ✅ **已修**：`MoovRelocatingSource` 在 Java 侧把 moov 搬到虚拟文件头。见下节 |
| 9 | **硬解没打开 → 4K 片源极慢** | ✅ **已修**：ijkplayer 的 `mediacodec*` 默认全 0，必须显式打开。见本节 |
| 10 | **`readAt` 的零长度读返回 -1 → 每次 seek 都失败** | ✅ **已修**：零长度读是 seek，必须返回 0。见本节 |
| 11 | **模拟器上 4K 依然卡**：x86 内核是 `--disable-asm` 编的（纯 C，无 SIMD） | ✅ **已修**：`patch-ffmpeg-x86-asm.sh`。见风险 11 |
| 12 | **4K 渲染每帧在 CPU 上搬 33MB**（默认 `overlay-format=RV32`） | ✅ **已修**：改 `fcc-_es2` 走 GLES2 着色器。见风险 12 |
| 13 | **切换首帧被后台 SMB 任务拖慢**（同一条连接抢锁） | ✅ **已修**：音频判定挪到首帧后、目录刷新加新鲜度判断。见风险 13 |
| 14 | **电视是否真能硬解 4K H.265 未知**（HEVC 的 UHD 档在 CDD 里只是 SHOULD，且 ijkplayer 不查 profile） | 跑 `CodecCapabilityProbeTest` 看设备声明的能力，见风险 14 |
| 15 | **换到直播就冻住**：ijkplayer 的 DNS 缓存只按主机名做键、却把端口一起缓存，`…:82` 的地址被连到 `…:81` → 403 | ✅ **已修**：关掉那份缓存（`dns_cache_timeout=0` + `dns_cache_clear=1`）。见风险 15 |
| 16 | **直播失败一声不吭**：引擎自己重连，却一次回调都不发，界面永远冻在上一帧 | ✅ **已修**：`Listener.onLiveRetry` + 两句人话。见风险 16 |
| 17 | **`https://` 的直播源全部起不来**：内核没编 OpenSSL（官方包也没有） | ✅ **已修**：`scripts/build-ffmpeg-openssl.sh` 给三个 ABI 补 OpenSSL。见风险 17 |
| 19 | **播放记录只有一份全局记忆**：换剧即覆盖，换回来只能从头看 | ✅ **已修**：`WatchHistory` 按「库 + 剧」各存一条 + 5 秒周期写盘。见风险 19 |
| 20 | **天气换成心知**：和风的专属 Host 老人填不对；地名没权限、频率要省着用 | ✅ **已改**：私钥 + 坐标两栏，30 分钟保鲜 / 5 分钟冷却 / 结果落盘。见风险 20 |
| 18 | **字节源打开失败会把进程带走**：`FileRandomAccessSource` 在构造函数里开句柄，异常直接冒到 Handler/主线程 | ✅ **已修**：`startPlayback` 里统一 `openOrReport` 兜住，出故障页而不是崩。见风险 18 |
| 21 | **硬解失败是「静默回落软解」**：ijkplayer 建 MediaCodec 失败只在 native 打一行日志就换 FFmpeg，Java 层收不到任何回调；界面还以为在硬解 | ✅ **已修**：用 `stat.vdec_type` 真值判定 + 拉黑换候选 + 屏幕上的诊断页。见风险 21 |
| 22 | **首帧超时会误杀硬解**：SMB 打开慢 / 探流 16MB 都会被算进「硬解 15 秒没出首帧」，两次误判就把硬解永久关掉 | ✅ **已修**：拿到数据就宽限（最多 2 次）+ 超时与硬失败分开计数 + 换内容重置。见风险 22 |
| 24 | **4K HEVC 片源送显只有 17~18 帧/秒**（H.264 的 4K 却能满帧） | ✅ **已定案（2026-09-12 更正）**：瓶颈是 HEVC 解码块 ~150 Mpx/秒，与「4K 显示通路」无关；结论与文档已按编码分开重写。见风险 24 |
| 23 | **切显示模式后字号不重排**：`setTextSize` 的像素值缓存在 TextPaint 里，Activity 又声明了 `configChanges` 不重建 → 卡片按新尺寸重排、字还是旧字号 | ✅ **已修**：每次显示都 `Ui.refresh()` 并整棵重建（含重设字号）。见风险 23 |

### 风险 11：模拟器上的 4K —— x86 内核是纯 C 编的

**现象**：《娘道》（1080p H.264）满帧，另外两部 4K H.265 只有 4fps 左右。
第一反应会以为是硬解没打开，其实**模拟器根本没有硬解可用**：
它的 MediaCodec 只有 `OMX.google.hevc.decoder`（AOSP 自带软解，
`media_codecs_google_video.xml` 里声明上限 `2048x2048`），
ijkplayer 给软解实现打 `RANK_SOFTWARE = 200`，低于及格线 `RANK_LAST_CHANCE = 600`，
于是拒掉、回退 FFmpeg 软解。这个行为是对的（用 MediaCodec 的软解器只会更慢），
但也意味着**想让模拟器流畅，只能把软解本身修好**，没有任何开关可以绕。

**根因**：ijkplayer 上游 `android/contrib/tools/do-compile-ffmpeg.sh` 里有一段特例
`if [ "$FF_ARCH" = "x86" ]; then FF_CFG_FLAGS="$FF_CFG_FLAGS --disable-asm"` ——
armv7a / arm64 有 NEON，**32 位 x86 一条 SIMD 都没有**。
证据：`strings libijkffmpeg.so | grep -c sse2` 在 ARM 那边是几百（neon 符号），x86 是 0；
FFmpeg 的 `configure` 自己把无 x86asm 的构建叫 *a crippled build*。

**修法**：`scripts/patch-ffmpeg-x86-asm.sh`（`build-ijkplayer.sh` 会自动调用）把那段改成
`--enable-asm --enable-inline-asm`。只需要重编 x86 的 FFmpeg
（`libijkplayer.so` / `libijksdl.so` 一行没动），用 `scripts/swap-ffmpeg-so.sh` 换进 AAR。
校验看 `config.h` 里的 `HAVE_X86ASM / HAVE_SSE2 / HAVE_SSSE3 / HAVE_SSE4 / HAVE_AVX2`。

**效果**：同一个 4K HEVC 文件的解码 CPU 从 **246ms/帧 → 117ms/帧**。

> 附带代价：x86 汇编会引入**文本重定位**（`readelf -d` 里的 `TEXTREL`），
> Android 6+ 会拒绝加载这种 .so。本项目两个运行环境（API 22 模拟器、Android 5.1 电视）
> 都还没这条限制，arm 两个 ABI 也完全不受影响；但若哪天要在 Android 6+ 的 x86
> 设备上装，必须改用 x86_64。

### 风险 12：4K 渲染每帧在 CPU 上搬 33MB

**现象**：补上解码汇编后 4K 仍只有约 10fps。逐线程量 CPU 发现
**`ff_vout` 单核跑满 92%**，而同一时刻 5 个解码线程加起来才 0.9 个核 —— 瓶颈不在解码。

**根因**：`overlay-format` 默认 `SDL_FCC_RV32`（RGBX8888）。那条路上每帧要做两件事：
libyuv `I420ToABGR` 转换、整帧 `memcpy` 进 ANativeWindow buffer。
3840×2160×4 = **33.2MB/帧**。

**修法**：`VideoDecodePolicy.OVERLAY_GLES2 = 0x3253455F`（fourcc `_ES2`）。
`SDL_VoutFFmpeg_CreateOverlay` 在 `overlay_format == SDL_FCC__GLES2` 时把 8bit YUV420P
标成 `SDL_FCC_YV12`（解码器原生三平面，**不做转换**），`func_display_overlay_l` 因
`vout->overlay_format == SDL_FCC__GLES2` 交给 `IJK_EGL_display`：三平面各上传一张纹理，
YUV→RGB 的矩阵在片元着色器（`gles2/fsh/yuv420p.fsh`）里由 GPU 做。

**硬解不受影响**：MediaCodec 的帧格式是 `IJK_AV_PIX_FMT__ANDROID_MEDIACODEC`，
overlay 由 `SDL_VoutAMediaCodec_CreateOverlay` 创建并把 format 写死成 `SDL_FCC__AMC`，
根本不看 `overlay-format`；显示时只 `releaseOutputBuffer(render=true)` 送回 Surface。

**代价**：GLES2 的 YUV 着色器里色彩矩阵写死 BT.709（`renderer_yuv420p.c`），
标清（BT.601）片源会略有偏色；1080p/4K 片源不受影响。

**效果**（模拟器，`dumpsys SurfaceFlinger --latency SurfaceView`）：

| 片源 | 修前 | 修后 |
| :--- | ---: | ---: |
| 《娘道》1920×1080 H.264 25fps | 24.75 fps | **23.6 fps**（满帧） |
| 《猫和老鼠》2960×2160 HEVC 23.976fps | 4.1 fps | **9.9 fps** |
| 《大宅门》3840×2160 HEVC 25fps | 4.3 fps | **8.4 fps** |

**为什么到不了 24fps**：4K 那条路上还剩两个各约 100ms/帧、互相独立的开销 ——
解码吞吐约 **66 Mpx/s**（两个 4K 片源都是这个数），纹理上传带宽约 **115 MB/s**
（4K 一帧 12.4MB ≈ 107ms）。要满帧得两者再快 2.5~3 倍，而这是 32 位 x86 guest
（4 vCPU、x86-32 汇编最多到 SSE4、GL 走老的 `pipe` 通道）的天花板，应用层改不动。
**结论：模拟器可以验逻辑、验音频、验切换、验 1080p，但不能给 4K H.265 做性能验收。**

### 风险 13：切换首帧被后台 SMB 任务拖慢

`SmbStore` 是**全进程串行**的（一条连接、一把锁）。切换的瞬间有两件后台事在抢这把锁：
起播路径上的音频判定（读 1MB）、以及每次换剧都重列一遍剧集目录。
两件都挪开：音频判定改到 `onFirstFrame()` 之后做（结论一样，代价为零），
目录刷新在缓存 10 分钟内直接跳过（冷启动和过期后照常刷新）。
**切换首帧从 0.7~1.5 秒降到 0.4~0.9 秒。**

### 风险 14：电视能不能硬解 4K H.265，必须实测

目标机型长虹 CHiQ 43Q3T 是联发科 **MT5520**（4×Cortex-A53 + Mali-T860 MP2，Android 5.1），
长虹官方口径是「HEVC/H.265 **4K2K@60** 硬解码」，但**没有逐字写 Main10**。
而 Android 5.1 CDD 只强制两件事：H.264 High Profile L4.2 + 1080p（**MUST**，
剧集那条线安全）、H.265 Main Profile Level 3（SD 档）；
**Main10 L5 + UHD 只是 SHOULD**，厂商可以合法地不做。

更麻烦的是 ijkplayer **对 HEVC 不检查 profile**（HEVC 分支只看开关，
H.264 分支才逐个 profile 判断），Main10 会被无脑交给硬解 ——
硬件不认就是「黑屏但有声音」且不报错（上游 issue #1364）。
本项目现有片源实测都是 **HEVC Main 8bit**（`ffprobe`：`profile=Main`、`pix_fmt=yuv420p`），
暂时踩不到，但换片源就可能踩到。

**应对**：

1. 先跑 `CodecCapabilityProbeTest` —— 打印每个 `video/hevc` 解码器的
   `profileLevels`、`isSizeSupported(3840,2160)`、`areSizeAndRateSupported(3840,2160,60)`、
   以及 `colorFormats` 是否含 `COLOR_FormatSurface`；
2. 播放时把所有候选解码器**连同 ijkplayer 给它们的分数**打进 logcat
   （`adb logcat -s FireflyTV | grep 候选解码器`）。电视 SoC 的解码器在 ijkplayer
   的已知机型表里没有条目，会拿 `RANK_ACCEPTABLE = 700`（含义是「未知、没测过」，
   不是「已验证可用」）—— 真出问题时，那张表就是选用 `mediacodec-default-name`
   钉死某个解码器的依据。

### 风险 15：换到直播就冻住 —— DNS 缓存把端口也缓存了

**现象**：从剧集切回直播时会卡住。
冷启动后第一次换台正常，之后每次换到央视都是 **403 Forbidden**，
画面冻在上一集的最后一帧，按键有反应，**永远不会自己好**。

**排查**：先在宿主机直连同一个地址 —— **一直是正常的 302 → 200**，排除源的问题。
然后在模拟器网卡上抓包（`adb shell tcpdump -i any -A 'host 198.51.100.10'`）：

| 时机 | 报文 | 结果 |
| :--- | :--- | :--- |
| 冷启动第一次 | `→ :82  GET /live/cctv1hd.m3u8` | 302 |
| （跟重定向） | `→ :81  GET /live/cctv1md.m3u8?tm=…&key=…` | 206 ✔ 出画面 |
| 之后每一次 | `→ :81  GET /live/cctv1hd.m3u8` + `Host: …:82` | 403 ✘ |

**TCP 连的是 `:81`，请求行和 `Host` 却还是 `:82` 那一份** —— 这个错位只有一种解释。

**根因**：ijkplayer 给 FFmpeg 打的 patch（`libavutil/dns_cache.c`）
键是**主机名**，值是**把 `getaddrinfo()` 的 `sockaddr_in` 整个 `memcpy` 走**（含 `sin_port`）。
`libavformat/tcp.c` 的 `tcp_open()` 命中缓存后直接拿它 `ff_listen_connect()`，
**不看 URL 里的端口**（`restart:` 那段只处理 `AF_INET6` 且端口为 0 的情况，IPv4 没有对应逻辑）。
走完一轮 `:82 → :81` 之后，那条记录已经带上 `:81`，之后所有「先连 `:82`」都被改成「连 `:81`」。

**修法**：不信这份缓存（`VideoDecodePolicy.NETWORK_OPTIONS`）：

```kotlin
Opt(Category.FORMAT, "dns_cache_timeout", 0L)   // 缓存代码块整个不执行
Opt(Category.FORMAT, "dns_cache_clear", 1L)     // 双保险：万一超时值在别处被置回正数，每次建连前先清
```

它们是 tcp 协议的 AVOption，**只能从 `format-opts` 传**（写 PLAYER 类别会被静默忽略，
单元测试钉住了）。好处是一套 APK 同时修好模拟器和电视，不必重编内核
（上游的正解是把键改成 `host:port`）。

**实测**：每次换台都是 `:82 → 302 → :81 → 206`，403 归零，CCTV-1 稳定 **25.00 fps**。

### 风险 16：直播失败一声不吭

`PlaybackEngine.handleError()` 的直播分支只做了两件事：安排重连、`return`。
**一次回调都不发** —— 直播的失败因此永远到不了界面层。
用户看到的是「上一集冻住的最后一帧 + 按键有反应 + 永远不变」，
和风险 8 的「卡住」长得一模一样，但根因完全不同。

修法：新增 `Listener.onLiveRetry(attempt, url)`，界面层据此说话
（前 3 次「信号中断，正在重连…」，之后「这个频道暂时看不了，还在重试…」），
换台时立刻撤掉。同时给分支补上 `kind == PlaybackMode.Kind.LIVE` 前提：
`liveReconnect` 只会被置 true、**从来没有被置回 false**，
少了这一半，「先看直播、再回电视剧」之后某一集 SMB 读失败会被误判成直播断流。

两条都用插桩测试钉住（`LiveFailureVisibilityTest`，正反两个方向）。

### 风险 17：`https://` 的直播源全部起不来（内核没编 OpenSSL）

频道表里 `live.264788.xyz` / `live2.example.com` / `myalicdn` / `cgtn.com` 这些源占了一大半，
全是 https。自编的 `libijkffmpeg.so` 没有 openssl：

```
W/IJKMEDIA: https protocol not found, recompile FFmpeg with openssl, gnutls or securetransport enabled.
E/IJKMEDIA: https://…: Protocol not found
```

**官方包同样没有**（三个 ABI 的 `libijkffmpeg.so` 里 openssl 符号数都是 0），
所以这是从第一天就存在的缺口，不是改出来的。

修法：`scripts/build-ffmpeg-openssl.sh` —— 先编 openssl（`compile-openssl.sh`，
OpenSSL_1_0_2n），`do-compile-ffmpeg.sh` 里有
`if [ -f "${FF_DEP_OPENSSL_LIB}/libssl.a" ]` 的自动检测，会自己加上 `--enable-openssl`。

> **坑**：`compile-ffmpeg.sh` 每次都会从 `extra/ffmpeg` **重铺**一份源码到
> `android/contrib/ffmpeg-<abi>/`，之前打在那里的补丁**全部作废**
> （症状：`fatal error: linux/perf_event.h: No such file or directory`）。
> 所以脚本里在编译前重新跑了一遍 `patch-all-ffmpeg.sh`（x86 还要
> `patch-ffmpeg-x86-asm.sh`）。

### 风险 18：字节源打开失败会把整个进程带走

`FileRandomAccessSource` / `SmbRandomAccessSource` 都是**在构造函数里就把句柄打开**的，
所以「文件被删了」「NAS 掉线了」是在 `startPlayback()` 里**同步**炸出来的。
原来没有兜：异常一路冒到 `MainActivity` 的 Handler（或主线程）→
`UncaughtExceptionHandler` **直接杀进程**。
用户看到的不是「打不开这一集」，而是应用整个消失 —— 对给老人用的电视应用来说
这是最糟的失败方式（而且是插桩测试里真的踩到的：测试进程当场死掉）。

修法：`startPlayback()` 里统一走 `openOrReport()`，出故障页重试，不让异常外冒。

### 风险 19：播放记录只有一份，换剧即覆盖

**缺口**：除直播外的其他影视库没有播放记录 —— 启动时无法自动切回
上次使用的影视库的最近观看（直播只记到频道，不记具体时长）；切换到具体影视时无法
自动跳到记录时间。老人不看了会直接关电视，所以记录不能受关电视影响，允许 10s 左右的偏差。

**原来为什么不行**：全局只有一份记忆（`last_lib` / `last_show` / `last_ep` / `last_pos`）：

- `Navigator.vertical` 写死 `PlayShow(next, 0, 0L)` —— 不管这部剧看过没有、看到第几集，
  一律从第 1 集开头起；
- `playEpisode` 每次都用新剧覆盖那份唯一的位置。

**现在**：`WatchHistory` 按「库 + 剧」各存一条（`Record(lib, show, episode, posMs, at)`），
序列化成一行一条的文本存在 `watch_history` 这个键里（写一次是原子的，解析逻辑可单测）。

| 时机 | 行为 |
| :--- | :--- |
| 开机 | 回到上次用的库（含直播），接着看该库 `at` 最新的那部 |
| 换剧（↑↓） | 有记录 → 回到那一集那一分钟；没记录 → 第 1 集开头 |
| 换库（←→） | 离开前先写，然后接着看目标库最近看的那部 |
| 播放中 | 每 5 秒写一次（`positionTick`） |
| 关电视/切后台 | `onPause` 立刻写 |
| 换剧/换集/换库 | 当场写 |

**关于 10 秒偏差**：老人直接关电视，应用不一定拿得到 `onPause`/`onDestroy`，
所以记录是**周期性**写的，不是只在退出时写 —— 最坏丢 5 秒。

**两条「宁可少写，也不写坏」的规矩**（「多次切换或者其他原因会导致播放记录
丢失」就是这两条没守住）。整份记录是**一个整体**落盘的（`serialize` 的一整段文本），
所以任何一个写点都有能力把全部记录写坏：

- **问不到进度就不写**（`WatchHistory.shouldStore` / `usablePosition`）：换剧、换库、
  退到后台都是「立刻写一条」（`force = true`），而这些时机正好落在播放器刚被重建的窗口里
  （`prepareAsync` 还没走完、硬解退回软解重播中、已经 `release`），`positionMs()` 回 `0`。
  **`0` 是「问不出来」，不是「看到了片头」**：老实现把它照收，等于把上一次存下的好进度抹成 0，
  换回来只能从头看。现在 `0`/负数一律不写 —— 宁可少记这 5 秒，也不毁掉上次的记录。
- **只认「真的在播的那一集」**（`MainActivity.Spot`）：`playShow` 会**先**改 `showName`，
  而这部剧的集列表是**异步**去 NAS 列的（前面还排着 `refreshShows`）。那几秒里
  `episodeIndex` 还是上一部剧的集号、播放器也还在放上一部剧 —— 定时写盘于是把上一部剧的
  集号和进度记到新剧头上。现在内容身份只在**真正把这一集交给播放器**时认领
  （`playEpisodeNow`）、换库/换台时清空，`saveRecord` 只认它。
  为什么不在 `playEpisode` 里就认领：Surface 没就绪时那一集只是排队（`pendingEpisode`，
  超时 8 秒放弃），排队期间播放器还在放**上一集**，提前认领就是同一个错的另一面。

另外三处「顺手把记录写坏」的路也一起堵了：

- **启动时的空记录覆盖**：`history` 原来是「先挂一份空的、再去 IO 线程补读，读回来
  `main.post { history = book }` 无条件盖掉内存」。这个窗口里任何一次 force 写都会把
  **整份记录覆盖成空的**。现在 `onCreate` 里**同步**读出来再让任何东西写（读的是同一个
  prefs 文件，多出来的只是一次十几 KB 的解析）。
- **退到第 1 部时抹掉它的记录**：记住的那部剧在 NAS 上被删/改名时，`selectLibrary` /
  `refreshShows` 原来直接给 `(0, 0L)` 起播，而 `playEpisode` 会立刻写一条记录 ——
  第 1 部剧存着的续播点就这么没了。现在走 `anchorFor`：退到第 1 部，但**用第 1 部
  自己的记录**（没记录才是第 1 集开头）。
- **全剧播完换下一部时同理**：`onEpisodeFinished` 原来写死 `(0, 0L)`，等于看完一部剧
  就把下一部剧的续播点抹了。现在也接着下一部剧**自己的**记录看
  （和「换到哪部剧就接着哪部看」是同一条规矩）。
- **「重播同一集」不拿 0 当进度**：音频焦点拿回来（`focusListener` → `replayCurrent`）和
  播放器卡死后重建（`recoverFromDeadPlayer`）这两条路，播放器都已经 stop/释放，
  `positionMs()` 回 0；老实现把 0 交给 `playEpisode`，既把画面拉回片头，又把记录里的
  续播点写成 0。现在改用记录里最后存下的位置（`MainActivity.lastKnownPos`）——
  这也是 `recoverFromDeadPlayer` 里「只能从头续播同一集」那条老注释的失效之处。

**两个边界**：
- 位置 < 15 秒不算「看过」（`MIN_RESUME_MS`），否则点开看一眼再换台，下次会从第 8 秒开始；
- 旧版那份全局记忆会被迁移一次（`Config.takeLegacySpot`），升级后不从零开始。

**为什么直播只记频道**：`currentPosition` 对直播是从开播算起的假进度（会涨到几十小时），
写进记录下次拿去 seek 就是长时间音画不同步 —— 这条在 `PlaybackMode` 里有更详细的说明。

### 风险 20：天气换成心知（seniverse）

和风 2026 起每账号一个专属 API Host，配置页多一栏、老人填不对。心知只有两栏：私钥 + 地点。

实测出来的两件事（都写进配置页提示了）：

| 请求 | 结果 |
| :--- | :--- |
| `key=<公钥>&location=<某地名>` | `AP010003 API 密钥 key 错误` —— **公钥不能用** |
| `key=<私钥>&location=<某地名>` | `AP010006 没有权限访问这个地点` |
| `key=<私钥>&location=<纬度:经度>` | ✅ 正常返回该地点 |

所以配置页两种写法都收，**不留默认地点**：写死一个坐标既泄露隐私，
也等于替用户决定看哪儿的天气。两项要么都填、要么都留空（留空 = 电视上不显示天气）。

**频率**：免费套餐按次计费，超了回 `AP010014`。
`WeatherClient.shouldFetch` 是纯函数（有单测）：成功 30 分钟保鲜、失败 5 分钟冷却、
结果落盘（重开电视不算新的一次查询）。一天最多几十次。

> 这里踩过一个自己写的坑：判据原来写成 `now - lastOkAt < ttl`，
> 而 `lastOkAt = 0` 表示「从没成功过」—— 那个式子会把 0 当成「刚刚成功过」，
> 于是**第一次永远查不出去**。现在两条规则都用 `> 0` 把「没有」和「刚刚」分开，
> `WeatherPolicyTest` 里有一条专门钉它。

**顺手修的 bug**：`WeatherClient.test()` 注释写「null = 成功」，实现却在成功时返回
「连接成功，当前晴 17 度」这句字符串，调用方只认 null → **配置页把试连成功显示成了失败**。
现在返回明确的 `Verdict(ok, message)`，成功时还会把「查到的是哪儿、现在多少度」带回去，
便于确认默认坐标查对了地方。

### 风险 8：moov 在文件尾 —— 修了两次才对

**现象**：ijkplayer 0.8.8 在 `IMediaDataSource` 通道下**只顺序读、从不回尾读 moov**，
读满第一个 32KB 块后直接报 `moov atom not found`。
读取轨迹（logcat）：`readAt` 只被顺序调用 4 次（0/32768/65536/98304），全程没有跳读。
试过格式选项 `seekable=1`，**无效**。

**影响面（在真实 NAS 上量的，不是推测）**：

| 剧 | 集数 | moov 位置 |
| :--- | :--- | :--- |
| 大宅门（4K H265） | 40 | 全部在**尾部** |
| 猫和老鼠 50 周年 | 157 | 全部在**尾部** |
| 娘道 | 76 | 文件头不是 `ftyp`，实际是 **MPEG-TS**（后缀骗人），不需要重排 |

即：**197 集里需要重排的那 197 集一开始全都播不了**。这不是边角情况，是全部内容。

**修法**：[MoovRelocatingSource] 在 Java 侧解析顶层 box，把字节流重新排布成
`ftyp + moov + 其余按原顺序`，只改动 view 层，不改一个字节的源文件、不做转码、不落临时文件。
`readAt` 里按段映射虚拟偏移到物理偏移，段数只有个位数，代价可忽略。
本来 moov 就在头部的文件原样透传。

#### ⚠️ 第一次修得不完整：搬了 moov 却**没改 chunk 偏移**（这是"卡住"的真根因）

这一条值得单独记，因为它骗过了我好几轮。

`stco` / `co64` 里存的是 chunk 的**绝对文件偏移**。把 moov 从文件尾搬到头部之后，
mdat 整体后移了，**这些偏移却还是老值** —— 播放器按老偏移去读，
读到的其实是 moov 自己的字节，于是报一堆
`Invalid NAL unit size` / `Error splitting the input into NAL units`，
**永远出不了首帧**。

现场实测（猫和老鼠，245,453,247 字节）：

```
原始布局  ftyp(28) free(8) mdat(245,040,408) moov(412,803)
重排之后  ftyp(28) moov(412,803) free(8) mdat(...)

第一个视频样本：stco 说在 44
  原文件 44 处        = 0000001940010c01...  ← 真正的样本数据
  重排后 44 处        = moov 内部的字节
  正确的新位置        = 44 + 28(ftyp) = 72
```

**修法**：搬运时把所有 `stco`/`co64` 条目整体加上一个常量增量。
增量对所有 chunk 相同 —— moov 原本前面是全部数据，搬到头之后前面只剩 ftyp，
于是它后面的内容整体平移了 `ftyp.size`。
实现上把 moov 整段读进内存改写（实测 412 KB ~ 2.5 MB），
避免「某个偏移项正好跨在块缓冲边界上」这类边界情况。

**这个 bug 为什么长期没被发现**：原来的单元测试用**人造的极简 box**，
`stco` 里的偏移是 0 或很小，改不改都"看起来对"。
这是「测试范围没覆盖到 bug 所在位置」的典型 —— 测试全绿，功能是坏的。

#### 附带：跨段短读

重排后的虚拟文件由物理上不连续的段拼成，`read` 很容易跨段。
旧实现遇到边界只返回当前段剩下的字节，上层会把它当成「文件到这儿就没了」。
同样是只有需要重排的片源才复现。
旧的回归测试用 7 字节零碎读，**永远不跨段**，所以也发现不了。

**验证（可复跑）**：
- `EveryShowPlaysTest` —— 全库扫描，用**严格首帧判据**（`MEDIA_INFO_VIDEO_RENDERING_START`，
  不是 `VIDEO_SIZE_CHANGED`）断言每一部剧都能出画面
- `SmbRelocationIntegrityTest` —— mdat 数据区逐字节比对，且 moov 里**确实发生了**
  偏移改写（差异必须 > 0）
- 运行日志可直接看到：`chunk 偏移增量 = 412803` / `已改写 22788 个 chunk 偏移项`
  （当时记下来的 **22792** 其实是那个 bug 的指纹：11394 × 2 = 22788，多出来的 4 就是被误改的表头字段）

修前 / 修后（真实 NAS，模拟器）：

| 剧 | 修前 | 修后 |
| :--- | :--- | :--- |
| 娘道（MPEG-TS，不需重排） | ✅ 首帧 0.7s | ✅ 首帧 0.8s |
| 大宅门（4K H265） | ❌ **永远出不了首帧** | ✅ 首帧 ~36s（模拟器纯软解） |
| 猫和老鼠（2960×2160 H265） | ❌ **永远出不了首帧** | ✅ 首帧 ~27s（同上） |

> 那 27~36 秒是**模拟器纯软件解码**的代价，不是代码问题：
> 目标电视有硬件 H.265 解码器。实机观感：「有画面了，就是卡，估计性能不够」。
>
> ⚠️ 上面这句「电视有硬解」当时漏了一半：**电视有硬解，但代码从来没让 ijkplayer 用它**。
> 电视上同样是纯软解 —— 后来在真机上复现的「极慢、还没声音」就是它（见「风险 9」）。


#### ⚠️ 第二次修得不完整：搬 moov 时把 `stco` 的 `entry_count` 也改掉了（「有画面没声音」的真根因）

搬 moov 时给 chunk 偏移表加增量，**字段位置算错了 4 个字节**：把 `entry_count`
当成在 `off+8`、偏移项当成从 `off+8` 开始（漏掉了 `type` 那 4 个字节）：

```
[ size 4 ][ type 4 ][ version+flags 4 ][ entry_count 4 ][ 偏移 × N ]
  off       off+4      off+8             off+12           off+16
                       ↑ 被当成第 1 条偏移  ↑ 被当成第 2 条偏移
```

偏移项本身**都改对了**（整体挪后两位），但 `version/flags` 被写成增量、
**`entry_count` 被写成 `N + delta`**。现场实测（大宅门 S01E01）：

```
真实值   stco 有 70505 项，视频+音频共 141010 项 → 日志却说「已改写 141014 个」
改坏之后 entry_count = 70505 + 2535446 = 2605951
解复用器去读 260 万条 → overread end of atom 'stco' by 10141784 bytes
```

**影响面**：解析位置整个错位，**该 trak 的音轨当场废掉**。判据用
`TrackInfoProbeTest`（直接问播放器「看到几条轨」）：

| 片源 | 修前 | 修后 |
| :--- | :--- | :--- |
| 娘道（MPEG-TS，不重排） | 2 条轨（视频 + 音频） | 2 条轨 |
| 猫和老鼠（MP4，moov 在尾） | **1 条轨（只剩视频）** | 2 条轨 |
| 大宅门（MP4，moov 在尾） | **1 条轨（只剩视频）** | 2 条轨 |

即：**只有需要重排的那两部剧没声音**，而它们恰好是「极慢 + 没声音」的那两部。
视频之所以还活着，是因为 `stco` 正好是那个 trak 的最后一个 box。

**为什么以前没被发现**（三条都值得记）：

1. `MoovRelocationRealLayoutTest` 里那个会造 `stco` 的辅助函数 `moovWithStco`
   **从来没被任何测试调用过** —— 偏移改写其实一直没人验证；
2. 另一路验证（按偏移读到的字节是不是样本数据）只能证明**偏移项改对了**，
   证明不了**表头没被改坏**；日志里 `141014` 这个数字看着也很正常；
3. 音频断言用的是 `MEDIA_INFO_AUDIO_RENDERING_START`，而它只在**音频组件真的打开**时才会触发 ——
   修前那两部剧的音频组件压根没打开，所以旧结论「AAC 有声」是错的
   （当时只测了不需要重排的片源，把结论外推了）。

**修法**：`patchStco` / `patchCo64` 按真实布局取 `entry_count`（`off+12`）与
偏移项起点（`off+16`），并新增 `MoovChunkOffsetPatchTest`：
表头一个字节都不许动、改动只允许落在偏移项区域、所有偏移项加同一个常量、
新偏移必须仍指向同一段样本数据。这组测试对着旧实现会红、对着新实现才绿。



### 风险 9：硬解从来没被打开 —— 真机「极慢」的真根因

**现象（真机实测）**：《猫和老鼠》《大宅门》能出画面，但**极慢**。
（「顺带没声音」另有根因，见风险 8 的第二段与风险 10 —— 定这条时曾被它带偏过。）
这两部剧恰好都是 4K H.265（见上一节）。

**根因**：ijkplayer 的 `mediacodec*` 系列选项**默认全部是 0**
（`ijkmedia/ijkplayer/ff_ffplay_options.h`，k0.8.8 里逐条写着 `OPTION_INT(0, 0, 1)`）。
不显式写这些选项，ijkplayer 就**一定走 FFmpeg 软解** —— 和设备有没有硬解能力无关。
代码里一次都没设过，所以电视上一直是纯软解：4K H.265 一帧要几百毫秒，画面自然极慢。

**「没声音」不是另一个毛病**：软解线程把 CPU 占满，音频线程拿不到时间片，
AudioTrack 一直欠载 → 输出静音。注意 `MEDIA_INFO_AUDIO_RENDERING_START`
只表示音频回调**已经开始跑**（回调里全是静音也算），所以它过了不代表真的出声 ——
这也解释了为什么之前的音频断言全绿、真机却没声音。

**为什么模拟器上查不出来**：模拟器只有 `OMX.google.*` 这类**软件实现**的 MediaCodec 解码器，
ijkplayer 的 `IjkMediaCodecInfo` 给它们 `RANK_SOFTWARE`，`DefaultMediaCodecSelector`
会拒掉排名低于 `RANK_LAST_CHANCE` 的候选 → 仍然走 FFmpeg 软解。
**改不改这些选项，模拟器上的行为完全一样**，只有真机的硬解解码器才会被选中。

**修法**（`VideoDecodePolicy` + `IjkPlaybackEngine`）：

| 项 | 值 | 依据 |
| :--- | :--- | :--- |
| `mediacodec-all-videos` | 1 | `ffpipeline_android.c` 的判定条件认 all-videos / avc / hevc / mpeg2 四个；`mediacodec-mpeg4` **不在**其中，`mediacodec` 又是 `mediacodec-avc` 的别名。只写 all-videos 最不容易踩坑（其余编码在管线内部就被挡回软解） |
| `mediacodec-handle-resolution-change` | 1 | 直播中途换分辨率要重建解码器（ijkplayer 只对 H.264 做了这段） |
| `opensles` | 0 | 固定 AudioTrack（默认值也是 0，显式写是为了不让内核默认值漂移把整条音频通路换掉） |
| `max-fps` | 61（硬解）/ 31（软解） | 默认 31 会在流打开时把 `skip_frame` / `skip_loop_filter` / `skip_idct` 抬到 NONREF —— **丢非参考帧、且不做去块滤波**。《娘道》是 1080p50，正好中招 |

硬解通路**不需要重编内核**：`ffpipeline_create_from_android`、
`ffpipenode_create_video_decoder_from_android_mediacodec`（在 `libijkplayer.so`）和
51 个 `J4AC_android_media_MediaCodec__*` + `SDL_AMediaCodec_configure_surface`
（在 `libijksdl.so`）一直都在随包的 AAR 里。它也和 FFmpeg 的 `h264_mediacodec`
解码器无关（那一族官方包和自编包都没有）。

**兜底**（老人用：宁可慢，也不要黑屏）：

- 硬解 **15 秒**不出首帧，或解码器报错 → 自动**改用软解重播**同一份内容；
  界面层收到 `onDecoderFallback` 后把起播看门狗重新起算；
- 连续失败 2 次 → 本次运行不再试硬解（否则每集都要先白等一个超时）；
- 为什么需要它：有些电视的 MediaCodec **建得出来、却一帧都不吐**，而且不报错。
  这时软解虽然慢，至少能看，不该把故障页甩给用户。

**验证**（`HardwareDecodeTest`，判据是 MediaCodec 选码回调有没有被调用）：

```powershell
.\build.ps1 connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.firefly.tv.player.HardwareDecodeTest
adb logcat -s FireflyDecode FireflyTV
```

- 真机：`解码通路=MEDIACODEC`、`硬解选码器 mime=video/hevc ... -> OMX.xxx`（电视的解码器名）；
- 模拟器：`NO_CODEC`（没有可用硬解 → 退回软解），同样算通过。

⚠️ 真机上还多了一条以前没有的日志：`onSelectCodec` 那行只在**真的要建 MediaCodec
解码器**时才出现。以后有人再报「还是很慢」，先看有没有这行。

### 风险 10：`IMediaDataSource` 的**零长度读**就是 seek —— 返回 -1 等于每次 seek 都失败

`SmbMediaDataSource.readAt` 最早写的是 `if (len <= 0 || ...) return -1`，
看着像「无效参数防御」，其实是把**整条随机读通路**废掉了。

依据 `ijkmedia/ijkplayer/ijkavformat/ijkmediadatasource.c` 的 `ijkmds_seek`：

```c
ret = J4AC_IMediaDataSource__readAt(env, c->media_data_source, new_logical_pos, jbuffer, 0, 0);
if (J4A_ExceptionCheck__catchAll(env)) return AVERROR(EIO);
else if (ret < 0)                       return AVERROR_EOF;   // ← 返回负数 = seek 失败
```

**每一次 `avio_seek` 都返回 EOF**，现场症状（大宅门，模拟器）：

| 指标 | 修前 | 修后 |
| :--- | :--- | :--- |
| `stream 0/1, offset 0x...: partial file` | **4418 条 / 一次播放** | 0 |
| `Could not find ref with POC`（解码器拿不到参考帧） | 满屏 | 0 |
| 首帧（4K H.265，模拟器纯软解） | ~10 秒 | **0.9 秒** |
| 拖进度 / 续播 seek | 永远失灵 | 正常 |

mov 解复用器拿不到 seek，只能退化成顺序读 —— 1 GB 的 4K 片源慢到没法看，
而这条通路上「续播」功能其实是靠 seek 实现的，所以它一直没真正生效过。

**修法**（`SmbMediaDataSource.readAt`）：

```
len == 0  → 合法 seek，位置没越过末尾就返回 0
len > 0 且位置已在末尾 → 返回 -1（这才是 EOF；ijkmds_read 里 0 是 EAGAIN，会引发重试）
实际读到的字节数可以短于请求（文件末尾），但不能是 0
```

`SmbMediaDataSourceTest` 把这三条约定连同块缓存（跨块填满、随机跳读）一起钉住。

### 格式兼容性：逐类实测结论（2026-09-11 更新）

> ⚠️ 上面两行的「声音」曾经标成 ✅ —— **那是错的**：这两部剧的解复用器其实只认出
> 一条视频轨（音轨被 `stco` 越界读带崩了，见风险 8 第二段），
> 当时的「✅」是从不需要重排的片源外推出来的。修好 `stco` 之后才真的出声。

这一节是**实测清单**，不是推测。测法：模拟器直连真实 NAS 与真实直播源，
逐类起播并观察「起播 / 出首帧 / 出声」三个信号（`FormatMatrixTest`、`FormatProbeTest`）。
「出声」用的是 ijkplayer 的 `MEDIA_INFO_AUDIO_RENDERING_START`，不是靠人耳听。

| 片源 | 容器 | 视频 | 音频 | 起播 | 画面 | 声音 |
| :--- | :--- | :--- | :--- | :---: | :---: | :---: |
| 《猫和老鼠》157 集 | MP4（moov 在尾部） | H.265 | AAC | ✅ | ✅ | ⚠️→✅ |
| 《大宅门》40 集 | MP4（moov 在尾部） | H.265 4K | AAC | ✅ | ✅ | ⚠️→✅ |
| CCTV1 / CCTV3 等 18 个频道 | HLS（10 秒 TS 分片） | H.264 1080p25 | AAC | ✅ | ✅ | ✅ |
| **《娘道》76 集** | **MPEG-TS**（后缀却叫 `.mp4`） | H.264 High 1080p50 | **AC-3** | ✅ | ✅ | ✅ |
| **CCTV5** | HLS（10 秒 TS 分片） | H.264 1080p25 | **MP2** | ✅ | ✅ | ✅ |
| CCTV17 等 4 个频道 | UDP 组播 / 直链 mp4 | — | — | ⚠️ | — | — |

#### 直播源换过了：上面那两行 HLS 是**历史记录**，现在是 RTSP 单播

直播源已经从 `http://…/live/cctv1hd.m3u8` 那批 HLS 换成了运营商 IPTV：

- **RTSP 单播** —— `rtsp://192.0.2.21/PLTV/…/…_0.smil`（19 个频道），
  服务端先回 `302` 跳到 `192.0.2.x:554` 并带一次性 `online=<unix秒>` 参数；
- **UDP 组播** —— `udp://239.0.0.x:4120`。

> 两份播放列表（含运营商真实地址与地区）**跟源码无关**，既不入库、也不留在工作区 ——
> 上面这些地址是脱敏后的写法（`192.0.2.x` / `239.0.0.x`），要复现直播得自备 m3u。

下面这张表是 2026-09-12 在模拟器上直连真实源重测的（含 `assembleRelease`
出来的**正式包**，不只是 debug 包）：

| 片源 | 容器/协议 | 视频 | 音频 | 起播 | 画面 | 声音 |
| :--- | :--- | :--- | :--- | :---: | :---: | :---: |
| CCTV-1 / CCTV-6 / CCTV-8 / CCTV-11（RTSP 单播） | MPEG-TS over RTSP | H.264 High 1080p25 | AAC-LC 或 MP2 | ✅ | ✅ | ✅ |
| 其余单播频道（共 19 个） | 同上 | 同上 | 同上 | ✅ | ✅ | ✅ |
| 组播 `udp://239.0.0.x` | MPEG-TS over UDP | — | — | ⚠️ | — | — |

实测数据（CCTV-6，7.66 Mbit/s = 18094 KiB 视频 + 632 KiB 音频 / 20.03 秒）：
`302` 之后 **约 1.2 秒出首帧**，连续三分钟零错误、零重连。
「起播」按 `FFP_MSG_VIDEO_RENDERING_START` 判，不看人眼。

组播那行仍然没验：模拟器的 SLIRP NAT 出不去组播，这一条要在真实网络里测
——**别把 ⚠️ 当成「已支持」**。

#### RTSP 直播「播不出来 / 播一段就卡住」的两个根因（2026-09-12 修）

现象：iptv 的 cctv6/8 等播放一段就会卡住，而同一地址在电脑上
`ffprobe`/手写 RTSP 握手都正常。根因全在**选项**上，跟源和服务端无关：

| 协议 | `timeout` 的单位 | 该用哪个 | 备注 |
| :--- | :--- | :--- | :--- |
| HTTP/HLS | **微秒** | `timeout` | 改动前就对了 |
| RTSP | **秒** | `stimeout`（微秒） | `timeout` 在 RTSP 里是「等入向连接」的秒数 |
| UDP 组播 | 秒（且无实质作用） | 都不顶用 | 无连接，只能靠存活看门狗 |

**根因 1：`timeout` 的单位认错。** ffmpeg 3.4 的 RTSP 选项目录原文是
`timeout` = "maximum timeout **(in seconds)** to wait for incoming connections"、
`stimeout` = "timeout **(in microseconds)** of socket TCP I/O operations"。
旧代码给 `timeout` 塞的是 `IO_TIMEOUT_US`(15000000)，被当成 **15000000 秒 ≈ 173 天**；
而它同时是保活间隔的来源（`rtspdec.c`：`>= rt->timeout / 2` 才发 `GET_PARAMETER`），
于是保活等于关闭 —— 服务端认为客户端早死了，中途掐流。这就是「播一段就卡住」。

**根因 2：没有 `rtsp_transport`，默认走 UDP。** 这套 IPTV 拒绝 UDP 的 `SETUP`
（回 `405 Method Not Allowed`），报错链是：

```
Status 302: Redirecting to rtsp://192.0.2.x:554/…?online=…
method SETUP failed: 405 Method Not Allowed
…smil: could not find codec parameters   →   Error (-10000,0)
```

ffmpeg 3.4 本有一条回退（`rtspdec.c`：`ret == AVERROR(ETIMEDOUT) && !rt->packets`
→ 打 `"UDP timeout, retrying with TCP"` → `resetup_tcp()`；这句话确实编进了我们的
内核，`strings libijkffmpeg.so` 搜得到），但在这条通路上触发不了。
指定 `rtsp_transport=tcp` 直接绕开，换台也不必先白等一次 UDP 超时。

**顺带修的两处：**

- **直播调参按传输协议分开。** 原来只有「直播/点播」一档，注释还写着「直播是 HLS」，
  于是 RTSP/组播也用 1 MB 探测窗口 + 512 KB 缓冲。这条流实测 7.66 Mbit/s，
  512 KB 只有 0.5 秒的量，抖一下就断。RTSP/组播改 4 MB / 4 MB，
  **HLS 那档原值不动**（免得弄坏能播的源）。
- **新增播放中存活看门狗。** 超时选项只覆盖「socket 报错」和「对端关闭」；
  连接好着、对端就是不再发数据时**没有任何信号**，画面冻在最后一帧，
  而界面层那个 25 秒看门狗只管起播（首帧出来就撤了）。判据用**送显帧率**
  （连续 15 秒贴近 0 就重连），**刻意不用 `Liveness.differsFrom`** ——
  它拿 `outputFps` 做 `!=` 比较，而那是个速率、卡死时也在 0 附近抖动，
  「有动静」永远成立，看门狗会一辈子不触发。组播尤其需要它。
  ⚠️ **后来实测证明「送显帧率」这个判据本身也不可用**（管线停住后它停在最后一个值上，
  不归零），已于 2026-09-21 换成「播放器自报持续缓冲」，见下面那条
  「**直播断流后画面永远冻着（本次修）**」。

回归防线：`PlaybackModeTest` 钉住协议识别、认不出时退回 HTTP 老行为、
以及各档参数的大小关系（单测 259 项全过，含 `LiveStallTest` 的断流判据）。

#### 「没声音」有两个完全不同的根因，现象一模一样

这一节值钱的地方不是结论，而是**排查过程中踩过的三个假象**。
两个根因都会表现成「画面完全正常，就是没声音」，不区分开就只能瞎猜：

**根因 A：这条流的音频编码，内核解不了。**
官方 ijkplayer 0.8.8 的 `libijkffmpeg.so` 里注册的解码器标志符只有 **23 个**：

```
视频: ff_h264 ff_hevc ff_mpeg4 ff_h263 ff_h263i ff_h263p ff_flv ff_vp6 ff_vp6a ff_vp6f ff_vp8 ff_vp9
音频: ff_aac ff_aac_latm ff_mp3 ff_mp3float ff_mp3adu ff_mp3adufloat ff_mp3on4 ff_mp3on4float ff_flac
```

**没有 `ff_ac3_decoder`，没有 `ff_eac3_decoder`，也没有 `ff_mp2_decoder`。**
这一类要靠换内核解决（下面「重新编译内核」一节）。

**根因 B：音频流的参数没被探测出来。**
FFmpeg 认得出「这是 AC-3」，但采样率和声道数是 0，
于是 `AVCodecContext` 打不开，音频组件**静默失败**（不打任何错误日志）。
设备日志里只有一句很不显眼的：

```
W/IJKMEDIA: Could not find codec parameters for stream 1
            (Audio: ac3 ([129][0][0][0] / 0x0081), 0 channels, fltp): unspecified sample rate
W/IJKMEDIA: Consider increasing the value for the 'analyzeduration' and 'probesize' options
```

**《娘道》真正的根因是 B，不是 A。** 这一点绕了很久才认清。

实测数据：`娘道` 的第一条音频包在文件的 **2,340,788 字节（2.34 MB）**处
（视频包很大，单包 291 KB，把音频压在后面），而当时的探测窗口只有 **2 MB** ——
差了 340 KB，于是播放器把窗口读完了也没碰到一个音频包。
二分实测（`ffprobe -probesize N`）：

```
  2.0 MB -> 0 channels          （旧值，音频组件打不开）
  3.0 MB -> 48000 Hz, 2 ch      （刚够）
```

修法：点播的 `probesize` 提到 **16 MB**（`PlaybackMode.MIN_PROBESIZE_BYTES`），
`PlaybackModeTest` 把下限钉住，防止以后有人为了「起播快一点」调回去。

**验证（可复跑）**：`Ac3AudioTest.niangdaoViaSmbProducesAudio`
直接播 NAS 上的整集（2.4 GB，走真实 SMB 路径），断言拿到
`FFP_MSG_AUDIO_RENDERING_START`。修复前失败、修复后通过，设备侧能看到
`CHANNEL_OUT_STEREO` + `ff_aout_android` 线程起动。

#### 排查中踩到的三个假象（比结论更值得记）

| 假象 | 真相 |
| :--- | :--- |
| 「娘道的 PAT/PMT 是坏的：`section_length=176`、`PMT_PID=0x0AC3`、CRC 全错」 | 我自己的工具错了。**PSI 包的 payload 第一个字节是 `pointer_field`**，没跳过它就从错误偏移读 section。垃圾里恰好出现 `0x0AC3`，看着特别像「AC-3 标记」，把方向带偏很久。真实值是 `section_length=13`、`PMT_PID=0x109A`、CRC 正确 |
| 「用 CRC 判断表是否损坏」 | **MPEG-TS 的 PSI 不用 zlib 那套 CRC**，用 zlib 校验会把**所有正确的表判成损坏**。识破办法：拿一份已知正确的 TS 校验工具本身 —— 连 ffmpeg 亲手生成的样本都「全部 CRC 错误」，那错的必然是校验器 |
| 「音频包在 13 KB 处（找到了 AC-3 的 `0x0B77`）」 | 假阳性。`0x0B77` 只有两个字节，在视频数据里会撞车。真实偏移是 2.34 MB |

共同点：**每一个都给出了「看起来很有道理」的错误答案**。
所以现在凡是靠工具得出的结论，都要求先在**已知正确的样本**上验证工具本身
（`scripts/ts-psi.py` 就是这么写出来的，可以拿两份文件互相印证）。

#### 附带修好的：时长算不出来

`娘道` 一集实际 2560 秒，播放器报 1700ms。这和根因 B 同源 ——
音频参数解不出来，时长估算跟着崩。**注意「同源」不等于「可互换的指标」**：
修好探测窗口后音频已经正常出声，但 `player.duration` **仍然是 1700ms**。
所以验证音频问题要用音频参数本身，不能拿时长当代理指标。

#### 解决方式：重新编译内核（已落地）

不指望电视自带解码：没有就自己加，不赌设备环境，
所以走的是**自己编一份内核**：

```
scripts/build-ijkplayer.sh     # 拉源码 → 编 FFmpeg（打开 ac3/eac3/mp2/dca）→ 编 ijkplayer
scripts/collect-ijkplayer.sh   # 收集 .so，用 nm 逐 ABI 校验解码器
scripts/pack-ijkplayer-aar.sh  # 打包成 app/libs/ijkplayer-full-0.8.8.aar
```

关键是 `config/module-firefly.sh`（ijkplayer 用 `module.sh` 决定 FFmpeg 开关）里把
`--enable-decoder=ac3/eac3/mp2/mp1/dca/truehd/mlp` 和对应的 parser/demuxer 打开。
内核体积从 3 MB 涨到 8.7 / 12 / 13 MB（armv7a / arm64 / x86），换来「什么都能出声」。

**实测结果（换内核后，模拟器直连真实 NAS 与真实直播源）：**

| 片源 | 音频 | 换内核前 | 换内核后 |
| :--- | :--- | :--- | :--- |
| 自生成的 AC-3 样本 | AC-3 | — | ✅ 画面 + 声音 |
| 自生成的 E-AC-3 / MP2 样本 | E-AC-3 / MP2 | — | ✅ 画面 + 声音 |
| 《大宅门》《猫和老鼠》 | AAC | ✅ | ✅ |
| CCTV1 / CCTV3 等 | AAC | ✅ | ✅ |
| 《娘道》76 集（真实 NAS，2.4 GB 走 SMB） | AC-3 | ❌ 无声 | ✅ 画面 + 声音 |

判据用的是 ijkplayer 的 `MEDIA_INFO_AUDIO_RENDERING_START` 回调，不靠人耳听；
设备侧也确认了 `SDL_Android_AudioTrack` 起播（`CHANNEL_OUT_STEREO`）、
`ff_aout_android` 线程在跑。

音频编码矩阵（`AudioCodecMatrixTest`，同一段视频只换音频编码）：
**AAC / AC-3 / E-AC-3 / MP2 在 TS 与 MP4 两种容器下全部出声。**

> ⚠️ 换内核**只是必要条件，不是充分条件**。它解决了根因 A；
> 《娘道》还额外撞上了根因 B（探测窗口不够），那个要靠 `probesize` 解决。
> 两个都修好之后娘道才有声音 —— 这也是为什么「换了内核还是没声音」曾经成立。

#### 编这一版踩到的三个坑（都写成了补丁脚本）

都是「2018 年的代码遇上 2026 年的系统」：

| 补丁 | 挡住的错误 |
| :--- | :--- |
| `patch-ffmpeg-for-modern-linux.sh` | FFmpeg n3.4 无条件 `include <linux/perf_event.h>`，而 Debian 13 的内核头不再提供它 |
| `patch-ijkplayer-no-avdevice.sh` | ijkplayer 调 `avdevice_register_all()`，但它默认 `--disable-avdevice`，链接必失败。（加 `--enable-avdevice` 不管用：disable 在后面，configure 以最后一个为准） |
| `wsl-fix-apt.sh` | WSL 镜像里过期的 NVIDIA 源用 SHA1 签名，Debian 13 的 sqv 拒收，导致 `apt update` 整体失败 |

另外 `TsProbe` + `AudioSupport` 这套「起播前认出音频编码」的能力保留着 ——
现在它不再报警，但它仍然是**换内核后验证有没有生效**的手段，
也是以后遇到新编码时第一个能给出确定答案的地方。

#### 还没解决的部分（写清楚，别当成已支持）

- **字幕规则**（§5）仍未实现。
- **`AudioSupport.BUILT_IN` 必须跟着内核一起维护**：换了内核却忘了改这张表，
  就会误报「这台电视不支持」。单元测试会遍历所有编码把两个方向都钉住。
- **UDP 组播频道（`udp://239.0.0.x`）仍未验证**。不是源的问题，是**模拟器**的问题：
  它走 SLIRP NAT，组播本身就通不出去，所以这条路要在真实网络里测。
  代码侧已按组播的特性给了独立的超时处理与存活看门狗（见上面「两个根因」），
  但**没有实测背书**，别当成已支持。百度网盘直链那类同理。
- H.265 4K 在**模拟器**上会把模拟器整个搞崩（软解扛不住），测试里按 500MB 体积阈值跳过；
  真机（armv7/arm64）现在走 MediaCodec 硬解（风险 9），模拟器上仍然是软解 —— 两边行为**故意不同**。

### 关键实现参数

| 项 | 值 |
| :--- | :--- |
| 重入配置页 | 设置键 + OK，或 返回键 + OK |
| 续播写盘 | 库 + 剧 + 集 + 进度，**5 秒防抖** |
| 定时任务 | 浮层 10 秒淡出、故障页 10 秒重试、进度 5 秒采样 |
| 配置页端口 | 绑 `0.0.0.0` 的**随机端口**（不写死 8080，避免与电视上其它应用抢） |
| 只读块大小 | 256KB × 8 块 ≈ 2MB 缓存 |
| 单次列目录上限 | 2000 条 |
| 天气刷新 | 30 分钟一次，失败静默 |

### 风险 21：4K 电视上「按下 OK 只有中间一小块有内容」

**现象**：电视上按下 OK 大概只有中心那一块有内容，像是没有缩放。
同一份 APK 在 1080p 模拟器上排版正常。

**根因**：`dp`/`sp` 只有在**系统 density 与逻辑分辨率自洽**时才等于「占屏比例」。
这台电视是 4K 面板 + Android 5.1，而 Android 5.1 上**没有任何框架机制**保证界面按 1080p 渲染
（把 UI 卡在 1920 的 `config_maxUiWidth` 是 Android 8.0 才有的，电视端要 Android 11 的 overlay
才会设成 1920）。所以完全可能是**逻辑分辨率 3840×2160 而 `densityDpi` 仍是 320**：
这时 64sp 只画出 128px，在 2160 高的屏上占 **5.9%**，而同一行字在 1080p 上占 **11.9%** ——
看起来就是「内容缩在中间一小块」。

**修法**（`UiScale`）：一切尺寸以「1080p 上的像素」为设计基准，再乘一个
**由分辨率与 density 一起算出来的**倍数：

```
缩放 = min(宽, 高 × 16/9) / (6 × densityDpi)          // 6 = 1920 / 320
```

| 逻辑分辨率 | densityDpi | 缩放 | 说明 |
| :--- | :--- | :--- | :--- |
| 1920×1080 | 320 | 1.00 | 正常 1080p 电视：不动 |
| 1280×720 | 213 | 1.00 | 正常 720p 电视：不动 |
| 3840×2160 | 640 | 1.00 | 真 4K 界面（Chromecast 4K 实测就是 640）：**不能**再放大 |
| **3840×2160** | **320** | **2.00** | 这台电视的可疑组合：放大 2 倍才回到正确比例 |

注意**不能**简单用 `短边/1080`：那会在真 4K 界面（3840@640）上把字放大两倍。
其余细节：
- 字号仍走 `setTextSize(SP, 设计值 × 缩放)`，这样电视系统里的「字体大小」设置继续生效；
- 取 `min(宽, 高×16/9)` 而不是 `min(宽, 高)`，21:9 之类的宽屏不会算歪。

**验证方式（模拟器可复现）**：`adb shell wm size 3840x2160` 把逻辑分辨率改成 4K、
density 留在 320 —— 这就是电视的可疑组合。修复前浮层的时钟测出来 52px 高（半屏），
修复后 104px（与 1080p 基线的 105px 一致）。修复前后都截图量了字高，不靠肉眼。

> 4K 界面（dpi 640）那一条**在 1080p 模拟器上测不出来**，所以缩放公式是纯函数 + 8 项单元测试
> （`UiScaleTest`）钉住的，模拟器只负责验「两倍逻辑分辨率」这一半。

### 风险 22：硬解失败是**静默**的，而首帧超时又会误杀硬解

两件事叠在一起，表面现象就是「电视的硬解没开」。

**第一件：静默回落。** ijkplayer 的 `func_open_video_decoder`
（`ffpipeline_android.c:73-77`）是这么写的：先试建 MediaCodec 管线，
**任何一步失败都只在 native 层打一行 `ALOGE`，然后直接 `node = ffpipenode_create_video_decoder_from_ffplay(ffp)`**
（= 换 FFmpeg 软解）。Java 层**一个回调都没有** ——
所以「选码回调被调用过」「我设了 `mediacodec-all-videos=1`」都不能证明硬解建成。

真值只有一个：`IjkMediaPlayer.getVideoDecoder()`（= `ffp->stat.vdec_type`）。
`ffpipenode_android_mediacodec_vdec.c:2081` 只在 **MediaCodec 管线真的建成**时写
`FFP_PROPV_DECODER_MEDIACODEC(2)`，软解通路在 `ffpipenode_ffplay_vdec.c:57` 写 `AVCODEC(1)`。
现在起播 5 秒后会去问一次（`hardwareTruthCheck`）：如果选码器给过名字、实际却是软解，
就**把那个解码器拉黑**（下次自动换下一个候选）并记一笔。

**第二件：超时误杀。** 原来的策略是「硬解 15 秒没出首帧 → 判定硬解不行」，
连续 2 次就**本次运行再也不试硬解**。问题在于这 15 秒里可能根本没数据：
SMB 打开 + 探流（`probesize` 16MB）+ 搬 moov 都可能占掉大半。一次网络抖动就能把硬解判死，
而硬解是这台电视上看 4K 的唯一活路。现在：
- 看门狗到点时如果**解码器还没拿到数据**（`videoCachedBytes/Duration` 为 0）就**宽限**一次，最多 2 次；
- **超时**与**硬失败**（解码器报错 / 界面判定卡死）分开计数：超时只对当前内容生效，
  换内容（下一集 / 换剧 / 换台）就归零重新给机会；硬失败才是累计 2 次关掉硬解。

**排查入口**：双击遥控器的「设置 / 信息」键打开诊断页，「解码」那一行直接写着
`硬解 OMX.xxx` / `软解（硬解没建成！）` / `这台设备没有可用的硬解解码器`。

### 风险 23：切了显示模式，字号还是旧的

`setTextSize` 把 sp 换算成像素后**缓存在 `TextPaint` 里**；而 Activity 声明了
`configChanges="…|density|screenSize"` **不会重建**，`TextView` 也不会在配置变化时重算。
于是「卡片、内边距都按新缩放重排了，唯独字还是旧字号」——
在 4K 电视上就是「卡片变大了、字缩在卡片中间」。

修法：浮层/HUD/故障页/诊断页每次显示都问一次 `Ui.refresh()`，变了就**整棵重建**，
并且**重设每一个字号**（`OverlayScreen.build()` 里那 8 行 `ui.text(...)` 就是干这个的，
注释里写清了原因，别当冗余删掉）。实测：`wm size 3840x2160` 之后浮层时钟从 52px 回到 104px。

### 播放诊断页：这台电视没有 adb，只能把证据放到屏幕上

电视上**不方便连 adb**，而「帧率不高」至少有四种完全不同的原因。
所以加了一页**播放诊断**：**双击遥控器上的「设置 / 信息」键**
（`KEYCODE_INFO` / `KEYCODE_MENU` / `KEYCODE_SETTINGS=176`，800ms 内两下），
**OK 键关闭**，30 秒后也会自动关闭。

> 为什么是双击：目标机型的「信息」键实际就是**设置键**，
> 单击就弹一屏字太重；双击误触概率极低，也不用记组合键。

一行一个事实，全部来自播放器真值（不做推算）：

| 行 | 内容 | 它能回答什么 |
| :--- | :--- | :--- |
| 屏幕 | `3840×2160 · dpi 320 · density 2.00 · 缩放 ×2.00 @60Hz` | 界面缩放的输入是否自洽（风险 21） |
| 画面 | `3840×2160 · 片源 25.0 帧/秒 → 输出 1920×1080` | 片源**本来**是多少帧、Surface 多大 |
| 解码 | `硬解 OMX.MTK.VIDEO.DECODER.HEVC` / `软解（硬解没建成！）` | 「硬解好像没开」的直接答案 |
| 帧率 | `解码 25.8 · 送显 9.8 · 丢帧 0.2` | 谁跟不上：解码、送显，还是都不差 |
| 像素率 | `送显 151.2 Mpx/秒（片源要 207.4）` | 电视视频通路的「吞吐尺子」（见风险 24） |
| 缓冲 | `30399 毫秒 / 14.5 MB` | 数据是不是供得上 |
| 读取 | `0.4 MB/秒（要 0.5）` | SMB 够不够这条流的码率 |
| 音频 | `avcodec aac · 已出声` | 「有画面没声音」 |
| 结论 | 一句话（见 `PlaybackVerdict`） | **该动哪里** |

「结论」那一行由 `PlaybackVerdict`（纯函数 + 18 项单元测试）给出，判据按优先级：
硬解静默回落 → 读取跟不上 → 缓存见底 → 送显低于片源 → 解码跟不上。
它**不猜**：说「送显低于片源」时必须同时知道片源帧率和实际送显帧率。

两个容易踩的坑，代码里都写了注释：
- `片源帧率` 来自 ijkplayer 的 media meta（`ijkmeta.c:245` 写的 `avg_frame_rate`），
  取不到时是 **0 = 不知道**，不能当「0 帧」；
- 「送显」在硬解通路上**是有值的**（曾经注释里写成「恒为 0」，是错的）：
  MediaCodec 的帧走 `SDL_FCC__AMC` overlay，`vp->bmp` 非空，
  `stat.vfps` 每次 `releaseOutputBuffer(render=true)` 都会刷新（见风险 24 的源码核对）。

### 实测：SMB 读取吞吐（`SmbThroughputTest`）

「帧率不高」还有一个候选根因是「读数据供不上」。这台 NAS 上实测（模拟器直连，
`adb logcat -s FireflyProbe`）：

| 读法 | 吞吐 |
| :--- | :--- |
| 原始 `read(32KB)`（≈ FFmpeg 的 `AVIOContext` 默认读法） | 7.37 MB/s |
| 原始 `read(64KB)` | 9.33 MB/s |
| 原始 `read(256KB)` | 14.19 MB/s |
| 经 `SmbMediaDataSource`（256KB 块缓存 + 32KB `readAt`） | **14.53 MB/s** |

结论：**块缓存已经把「小读放大成大读」这件事做完了**（14.5 是 32KB 的两倍），
片源要的码率（实测 0.5 MB/s，4K HEVC 一集 1GB）离吞吐上限差着 20 倍，
**读取不是瓶颈**，所以没有再加 read-ahead。这条数据以后换 NAS / 换网络时可以重跑。

### 播放内核调研结论：几个「改了等于没改」的选项

为了避免以后再花时间试错，把逐行核对过的结论记在这里（详细报告见 `docs/research/`）：

- `overlay-format='_ES2'` **只对软解有效**。MediaCodec 解码出来的帧 overlay 是
  `SDL_FCC__AMC`，显示时直接 `releaseOutputBuffer(idx, true)`，根本不看这个选项。
- `mediacodec-handle-resolution-change` **只对 H.264 生效**（`vdec.c:514` 写死
  `codec_id == AV_CODEC_ID_H264`），对 HEVC 无意义。
- `skip_frame` / `skip_loop_filter` 对 **HEVC 是空操作**（FFmpeg 的 hevc 解码器不读
  `skip_frame`，`skip_loop_filter` 只在 `AVDISCARD_ALL` 时才关去块滤波，SAO 不可跳）。
  也就是说 `max-fps` 那套启发式对 4K HEVC 帮不上忙，我们保留 `max-fps=61`（硬解不干预）
  的理由仍然是「别白丢帧」，而不是「丢帧能救 4K」。
- `max-buffer-size` **上限是 15MB**（`MAX_QUEUE_SIZE`）。超过不会报错，但
  `av_opt_set_dict` 会因为越界**提前返回、剩余选项一个都不应用**，而 ijkplayer 不检查返回值 ——
  也就是说「调大缓冲」反而可能把 `mediacodec-*`、`opensles`、`subtitle` 全部弄失效。
- HEVC 分支**没有任何 profile 检查**，且构造 MediaFormat 时连 profile 都不写：
  Main10（10bit）交给只支持 8bit 的解码器 `configure` **不会报错**，症状是黑屏或极慢。
  现有片源实测都是 Main 8bit；应用层加了防线（`MediaCodecChoice.profileRejection`）。

### 风险 24：4K 片源跟不上 —— **已定案（2026-09-12 更正）：锅在编码，不在「4K」**

#### 结论（2026-09-12 实机重测后定案）

**这台电视放得动 4K，放不动的是 4K HEVC。** 同一条诊断口、同一台电视、前后几分钟，
每个片源按**片名过滤**取样（`/series` 的每个样本都带 `title`，只统计对得上的）后的读数：

| 片源 | 编码 | 解码（均值/中位） | 送显（均值/中位） | 丢帧 | 送显像素率 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1920×1080@60 | H.264 | 59.3 / 59.3 | **59.1 / 59.3** | 0% | 122 Mpx/秒 |
| 3840×2160@50 | H.264 | 56.0 / 55.2 | **46.0 / 48.5** | 9% | **381 Mpx/秒** |
| 2960×2160@23.98 | HEVC | 23.8 / 23.9 | **23.8 / 23.9** | 0% | 152 Mpx/秒 |
| 3840×2160@25 | HEVC | 18.2 / 18.4 | **17.1 / 17.4** | 14% | 141 Mpx/秒 |

- 真正的墙是**这颗芯片的 HEVC 解码块，约 150 Mpx/秒**。4K 一帧 8.3 Mpx ⇒
  4K HEVC 顶多 ~18 帧/秒，**与片源帧率无关**；2960×2160@24（152 Mpx/秒）刚好在线上，满帧。
- **H.264 的 4K 能到 ~380 Mpx/秒**（4K50 送显中位 48.5/50）。
  所以出路是「**换编码**」：4K 优先 H.264，HEVC 就退到 1080p 或准 4K。
- 12 档应用侧预设对 4K HEVC 依然**全无差别**（默认 17.1/17.4，
  深队列那档 21.2/18.2）—— 应用侧确实没有余地，但**原因不是原先写的那个**。

<details>
<summary>被推翻的旧结论（保留，作为教训）</summary>

当天早些时候这里写着「**已定案：这颗 SoC 的 4K 显示通路吃不下**」，依据是：

1. 12 种应用侧组合全都没有差别（送显一律 15.8~17.4 帧/秒、131~144 Mpx/秒）；
2. 深帧队列 + 不丢帧时「解码回到 23.9 帧/秒、送显仍是 16.8」⇒ 判为**反压假象**；
3. CPU 只有 11~14%；
4. 电视自带播放器放同一个文件同样掉帧。

这四条**全都是真的**，但第 4 条只证明了「不是 Android 应用这条路没走好」，
第 1、2 条只说明了「应用侧改不动」——它们**都不能推出「4K 一律不行」**。
所有实验用的 4K 片源都是 **HEVC**（大宅门 4K25 HEVC），
于是把「HEVC 解码块 ~150 Mpx/秒」错读成了「4K 显示通路 ~137 Mpx/秒」。
换成 4K **H.264** 一比，显示通路立刻跑到 381 Mpx/秒 —— 结论当场翻掉。

两条方法上的教训，都写进了代码注释：

- **判据要按「解码够不够片源」**，不能按「解码是否低于送显」。送显卡住会反压解码、
  把两者一起拖低；旧判据在这种时候会把锅判反（`PlaybackVerdict` 里
  `decodeIsRealBottleneck()` 已删除，改为 `decodeShortfall()`）。
- **取样必须核对片名**：第一版测量脚本只等「缓冲够了」就开始采样，
  结果把上一个片源的样本混进了均值（4K25 HEVC 被算成 44.4 帧/秒）。
  `/series` 的每个样本都带 `title`，按它过滤之后数字才可信。

</details>

> 还是没能做的一件事：这台电视 `adbd` 是停的、5555 关闭、`ro.adb.secure=1`，
> 所以拿不到 `dumpsys SurfaceFlinger` 看视频层是 `HWC` 还是 `GLES` 合成。
> 不过现在也不需要了 —— HEVC 与 H.264 走的是同一条显示通路，差异只可能在解码器。

**现象（实机诊断页读数）**：《大宅门》（3840×2160 HEVC 25 帧）明显不够流畅，
而同一台电视上《猫和老鼠》（2960×2160 HEVC 23.976 帧）、《娘道》（1080p50 H.264）、
新增的测试片（**3840×2160 H.264 50 帧**，中位 48.5 帧）都正常。

| 片源 | 分辨率 | 帧率 | 需要像素率 | 实机结果 |
| :--- | :--- | :--- | :--- | :--- |
| 大宅门（HEVC） | 3840×2160 | 25 | **207 Mpx/秒** | 送显 17.1/17.4 帧/秒（=141 Mpx/秒） ❌ **HEVC 解码块到顶** |
| 猫和老鼠（HEVC） | 2960×2160 | 23.976 | 153 Mpx/秒 | 23.8/23.9 帧/秒 ✅（刚好在 HEVC 上限内） |
| 测试片（H.264） | 3840×2160 | 50 | 415 Mpx/秒 | 46.0/48.5 帧/秒 ✅ **同一个 4K，H.264 就行** |
| 娘道（H.264） | 1920×1080 | 50 | 103 Mpx/秒 | 正常 |

**这台电视自己的数字**（诊断页）：`屏幕 1920×1080 · dpi 240 · density 1.50 · 缩放 ×1.33 @50Hz`、
`解码 硬解 OMX.MTK.VIDEO.DECODER.HEVC`、`解码 24.6 · 送显 18.2 · 丢帧 0%`、
`缓冲 41240 毫秒 / 13.9 MB`、`读取 0.0 MB/秒（要 0.4）`、`音频 已出声`。

三条已经**排除**的可能（都有证据，不是猜）：
- **不是硬解没开**：`stat.vdec_type = 2`，`OMX.MTK.VIDEO.DECODER.HEVC` 真的建成了；
- **不是读取/网络**：缓冲 41 秒、码率只要 0.4 MB/秒，而实测 SMB 能到 14.5 MB/秒；
- **不是片源时间戳有问题**：宿主机 ffprobe 直读 NAS 上的文件 ——
  `avg_frame_rate = r_frame_rate = 25/1`、`nb_frames = 70509 / 2820.36 秒 = 25.000`、
  视频与音频 `start_time` 都是 0、`profile = Main`（8bit）、码率 2.78 Mbps。**完全规整。**

#### 源码核对：解码→显示这一段已经是零拷贝，瓶颈在**消费者**

把 ijkplayer 0.8.8 的这条路读了一遍（`build/ijkbuild/ijkplayer`）：

- MediaCodec 的帧被包成 `SDL_VoutOverlay`，格式写死 `SDL_FCC__AMC`
  （`ijksdl_vout_overlay_android_mediacodec.c` 的 `func_fill_frame`），
  所以 `ff_ffplay.c:880` 的 `if (vp->bmp)` **成立**、`stat.vfps` **有值**
  （这一点以前注释里写反了，已在代码里改正）；
- 显示只有一步：`SDL_VoutOverlayAMediaCodec_releaseFrame_l(overlay, NULL, true)`
  → `MediaCodec.releaseOutputBuffer(idx, render=true)`
  （`ijksdl_vout_android_nativewindow.c:167-171`）。**没有 CPU 拷贝、没有 GL 上传**；
- 送显帧率 = `releaseOutputBuffer(render=true)` 的成功次数，也就是
  **Surface 的 BufferQueue 消费者（SurfaceFlinger → HWC/显示）每秒收下几帧**；
- `解码 24.6 / 送显 18.2 / 丢帧 0%` 里那 6.4 帧的差额，是被
  `video_refresh()` 里那条**不计数**的迟到帧跳过吃掉的（`ff_ffplay.c:1373`，
  只有 `framedrop>0` 时才走）—— 所以「丢帧 0%」并不代表一帧没丢。

推论（**2026-09-12 更正**）：这条通路本身没问题，**问题在 HEVC 解码器**。
原先这里写的是「解码侧没问题（24.6 帧说明 VPU 有余量），卡在显示通路」——
那句只在「解码 24.6」是同一颗 HEVC 解码器在深队列下的读数时才成立，
而它同时也是**送显的上限来源**：HEVC 这一档整条链（解码 → 输出）都停在 ~150 Mpx/秒。
H.264 的 4K 跑到 381 Mpx/秒，说明显示通路和 BufferQueue 消费者都不止这点能耐。

（下面这张表是当时列的三个猜想，方向都被同一个错误前提带偏了 —— 保留作为记录。）

#### 实验台：局域网 HTTP + 旋钮（当时的交付，**已从主线移除**）

> **这套工装已经不在主线里**：`DiagHub`、`com.firefly.tv.diag.Knobs`、`scripts/tvprobe.py`
> 和 12 档预设都已在「清理版」里删掉（结论已经拿到，见本节末的 A/B/A 定案）。
> 完整实现留在 git 分支 **`diag-experiment-snapshot`**，下次要「换一个变量量一次数字」时
> 从那个分支捡回来即可；下面这一节是当时的用法记录，照着跑要先切回那个分支。

电视在用户家里、没有 adb，而这件事必须「换一个变量、量一次数字」。
所以应用在 **debug 包里**开了一个局域网 HTTP 口（`DiagHub`，端口 **8642**，无鉴权 —— 怎么简单
怎么来，里面也不经手任何凭据）：

| 接口 | 用途 |
| :--- | :--- |
| `GET /state` | 一屏 JSON：屏幕/视频层/内容/解码/帧率/像素率/缓冲/音频/结论 **+ 面板那几行原文** + 系统 CPU/温度/核频/DDR 频 |
| `GET /series?n=60` | 1 Hz 历史样本（解码/送显/丢帧/像素率/读取/CPU/位置），用来算窗口均值 |
| `GET /threads` | 逐线程 CPU 占用与 nice —— 回答「是哪个线程在满、有没有被饿死」 |
| `GET /knobs` `/presets` | 列出旋钮与预设方案 |
| `GET /preset?n=4` | 切到第 4 档预设（**会重播**） |
| `GET /set?player.framedrop=0` | 改任意旋钮；按需要自动重播（只改建面前的变量才重播） |
| `GET /cmd?a=…` | `ls` / `find` / `open` / `pause` / `resume` / `seek` / `key` / `panel` / `replay` / `stop` |
| `GET /log?n=200` | 应用自己的日志 |
| `GET /codecs` | 解码器清单 + **声明的最大尺寸** + `tunneled`（隧道播放）支持与否 |
| `GET /props?q=` | 系统属性（`getprop`）—— `debug.sf.hw`、`sys.display-size`、`service.adb.tcp.port`，不用 adb |

操作端是 `scripts/tvprobe.py`（只用标准库）：
`state` / `watch 30` / `threads` / `knobs` / `presets` / `preset N` / `set k=v` /
`sweep --presets 1,3,4,8 --sec 20`（逐档切、逐档采样，最后给对照表）/ `ls` / `find` / `open` /
**`measure 路径...`**（逐个片源「打开 → 等它真的在走 → **按片名过滤**取样」，
输出可比的一行数字 + 诊断页会写的那句结论 —— 片源之间对比必须用它，
别自己撸脚本：第一版脚本没过滤片名，把上一个片源的样本混进均值，量出过「44.4 帧/秒」这种假数）。

**旋钮分三类**（`com.firefly.tv.diag.Knobs`，共 18 个，面板上单击「设置键」循环 12 档预设）：

| 类别 | 旋钮 | 想排除的可能 |
| :--- | :--- | :--- |
| 视频层（建面前一锤子买卖） | `surface.format`（opaque / rgba8888 / rgbx8888 / translucent / **unknown=不设**）、`surface.zorder`（default/media/top）、`surface.fixed`（none/**hd1080**/screen）、`window.background`（black/transparent） | 视频层被挤出硬件叠加通路、被窗口拖着走 GPU 合成；以及「固定 1080p 面」能不能让解码器少搬 4K |
| 播放器选项（起播前读） | `player.framedrop`、`mediacodec-sync`、`video-pictq-size`、`max-fps`、`packet-buffering`、`max-buffer-mb`、`opensles`、`mediacodec-handle-resolution-change`、`sync`（主时钟 audio/video/ext）、`mediacodec-default-name`、`infbuf`、`start-on-prepared` | ②③：缓冲回收节奏、帧队列深度、追帧逻辑、音频线程抢占、主时钟选择 |
| 进程调度 | `thread.boost`（给 `ff_vout`/`ff_read`/`ff_video_dec`/音频线程提 nice）、`thread.boost.extra`（连 `ACodec`/`CodecLooper`/`OMXCallbackDisp` 一起提） | ①：线程被饿死/优先级不对 |

> 纪律：**每个旋钮都要能退回默认**（第 1 档预设 = 全默认），
> 旋钮值存在独立的 `firefly_diag` prefs 里，不会污染 NAS/天气那套配置。
> `KnobsTest` 钉住了「键名不重复 / 默认值合法 / 每档预设都合法且非空 /
> 第 1 档必须是纯默认 / `max-buffer-size` 不许超过 ijkplayer 的 15MB 上限」。

#### 实测结果（2026-09-12 定稿，全部经局域网诊断口取得）

1. 先看 `/state` 的 `live`：解码跟片源齐平而**总 CPU 很低、核频也没跑满** ⇒ 不是算力问题，
   是解码器/显示侧；反之若某几个核 100% 且核频贴顶 ⇒ 是①。
2. **先分清编码**（`/state` 的 `decoder.impl`，面板「画面」那一行也写了）：
   - `hevc` + 4K：解码只能出 ~18 帧/秒，**到顶了**，换 H.264 或降分辨率；
   - `h264` + 4K：能到 ~380 Mpx/秒，4K50 也能到 46~48 帧/秒。
3. `/threads`：`ff_vout` 是否单核满？（模拟器软解时它 57%，是正常现象；电视硬解时不该高）
4. 扫预设：**每档的像素率**。
   - 全都在同一个水平抖、互相没有差别 ⇒ App 侧无解（4K HEVC 就是这样：默认 17.1/17.4、
     深队列 21.2/18.2）；
   - 某一档明显更高 ⇒ 按那一档定案，然后删掉实验台（**已经这么做了**：12 档里没有一档
     在送显帧率上稳定优于默认）；
   - **唯一量到的一次差异**：`pictq=8 + framedrop=0` 把「丢帧比例」压下去，
     但**送显帧率一点没变**。清理版因此保留 ijkplayer 默认参数，证据见下表。
#### A/B/A 定案：默认 vs 深队列 + 不丢帧（2026-09-12，逐轮 40 秒窗口，样本按片名过滤）

同一台电视、同一个包，轮流切「第 1 档（全默认）」与「第 9 档（`pictq=8` + `framedrop=0`）」：

| 片源 | 档位 | 送显均值 | 送显中位 | 丢帧比例 | 解码 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 3840×2160@25 HEVC（3 轮） | 默认 | **16.7** | 17.6 | 4.1% | 20.2 |
| | 深队列 | **16.7** | 17.3 | 2.0% | 20.4 |
| 1920×1080@60 H.264（2 轮） | 默认 | 60.1 | 59.3 | 0% | 60.8 |
| | 深队列 | 60.3 | 59.3 | 0% | 61.3 |
| 3840×2160@50 H.264（40 秒窗口） | 默认 | 46.0 | **48.5** | 9.2% | 56.0 |
| | 深队列 | 47.7 | **48.5** | 0% | 49.9 |

**结论：送显帧率三处全部持平**（差异都在噪声里），唯一系统性变化是「丢帧比例」这一栏 ——
而 `framedrop=0` 的语义就是**不再把迟到帧记成丢弃**（改成晚一点显示），
计数变小不等于画面变流畅。所以正式版保持 ijkplayer 默认值。

> 4K50 H.264 那一行是更早一次同等条件的窗口（每档 40 个样本）；这次重测时
> 它没量成 —— 每次切档都会从**当前进度**重播，而那个测试片只有 113 秒，
> 重播时已经接近片尾，播完自动跳到下一部剧，采样窗口就废了（脚本判据是「片名没变」）。
> 记在这里免得下次再踩：**短测试片不能用「切档重播」的方式做 A/B**。

5. `surface.fixed=hd1080` —— **已核实无效，别在它身上花时间**。
   AOSP 5.1 的 `ACodec` 分配输出缓冲时自己调 `native_window_set_buffers_geometry(win, nFrameWidth, nFrameHeight, ...)`
   （用 OMX 输出端口回报的尺寸），而 `Surface::dequeueBuffer` 的取值优先级是
   `reqW = mReqWidth ? mReqWidth : mUserWidth` —— **解码器设的覆盖 app 设的**；
   而且 5.1 的 `SurfaceHolder.setFixedSize()` 走的是 SurfaceView 子窗口 relayout，
   根本没碰 native window 的 buffer 尺寸 API。所以这一档从预设里撤掉了，
   清理版连旋钮一起移除。

#### 还没用、但可能是唯一能绕开合成瓶颈的一招：Tunneled playback（HWC_SIDEBAND）

如果量出来「视频层确实是被 GPU 合成拖累」，那 app 侧**唯一**的真出路是
**隧道播放**：给 MediaFormat 塞 `feature-tunneled-playback=1`（+ `audio-hw-sync`=AudioTrack 会话 id），
ACodec 走 `configureTunneledVideoPlayback()` → `native_window_set_sideband_stream()`，
**解码器直接交给显示控制器**，SurfaceFlinger/GPU 都不碰这一层（SF 侧该层类型变成 `HWC_SIDEBAND`）。
Android 5.1 的 ACodec 完整支持这条路（`ACodec.cpp#1315/#2127`，此时它**跳过 buffer 分配**）。

代价与前提：
- ijkplayer 0.8.8 没实现它，要在 `recreate_format_l()` 里加那两个键，并把
  `releaseOutputBuffer(render=true)` 那条路改成不渲染（节奏由隧道组件驱动），
  还要把音频会话 id 从 aout 传下来 —— 属于**改内核**，本仓库有完整的重编流程
  （`scripts/build-ijkplayer.sh` / `pack-ijkplayer-aar.sh`）；
- 先要探测 `MediaCodecInfo.CodecCapabilities.isFeatureSupported("tunneled-playback")`
  与厂商 `media_codecs.xml` 里的 `<Feature name="tunneled-playback"/>`；
- 风险：若该机 HWC 不支持 sideband 缩放，HWC1 规范允许把它降级成 `HWC_FRAMEBUFFER`，
  那时**画面会是纯色块**（不是黑屏）—— 必须小步验证。

**2026-09-12 更新：这一招先搁置。** 瓶颈已经量实为 **HEVC 解码块的吞吐**
（~150 Mpx/秒），而不是合成/显示通路 —— 同一条 Surface 通路上 H.264 的 4K 能到 381 Mpx/秒。
隧道播放省掉的是「解码器 → SurfaceFlinger → 合成」这一段，**不会让解码器本身变快**，
所以对 4K HEVC 的 18 帧没有帮助。只有将来量到「解码有余量、卡在合成」的片源，
才值得回来投这一改。

#### 两条能一锤定音的现场检查（需要 adb 或等价手段）

电视上「不能/不方便」连 adb，所以这两条要挑能做的时候做；
应用内已经用 `/codecs` 把第一条的**等价信息**拿到了（`MediaCodecInfo` 声明的
`max_w/max_h/supports_4k`，不需要 adb）：

1. `ls -l /system/etc/media_codecs*.xml` —— 看是否软链到 `media_codecs_2k.xml`。
   MTK 电视平台有 2K/4K 两套能力声明；2015 年的 Sony MT5595 上 2K 版把尺寸限成
   `1920x1088`，Kodi 因此对 4K 回退软解。若这台也是 2K 声明，4K 硬解本身就走在一条
   「没保证能跑」的路上。
2. `dumpsys SurfaceFlinger` —— 看 "Hardware Composer state" 里视频层那一行的类型列：
   `HWC`（硬件叠加）/ `GLES`（GPU 合成）/ `SIDEBAND` / `FB TARGET`。
   **这一条直接决定后面往哪走**：已经是 `HWC` 就说明卡顿不是 GPU 合成造成的，
   方向要转向显示控制器的像素率上限（那 App 侧基本无解）。
   另外应用启动时会打印自己的 IP，`adb connect <电视IP>:5555` 若通就不用碰电视。

**顺手修掉的两个读数错误**（都会把人带偏）：
- 「丢帧」原来是 `drop_frame_rate`，它是 **`drop_count / decode_count` 的比例（0~1）**，
  不是每秒帧数 —— 现在按百分比显示，判据也改成比例；
- 帧率计数器在第一个采样点会给出 `Infinity`（`SDL_SpeedSamplerAdd` 除以 0 间隔），
  现在统一当 0（= 还不知道）处理。

## 10. 实施阶段

| 阶段 | 内容 | 状态 |
| :--- | :--- | :--- |
| **1** | 骨架、二维码配置页、内置 HTTP 服务、**SMB 流播放打通** | ✅ 已完成并验证 |
| **2** | SMB 惰性扫描、库/剧/集索引、按键导航与循环 | ✅ 已联调；**扫描结果落盘缓存**，冷启动不再等 NAS |
| **3** | 连播、精确续播、字幕规则、直播重连 | 连播与续播已联调；**字幕规则未实现**；直播起播已验证，断流自愈未造过真实断流 |
| 4 | OK 键浮层：天气 + 农历 + TTS | 农历/节气已用单元测试验证；天气与 TTS 要在目标机上确认 |
| 5 | 故障页与重试、开机自启、`CATEGORY_HOME`、正式 APK | 部分已写，**主屏抢占要在长虹系统上试** |
| **6** | **格式兼容性实测 → 发现内核缺 AC-3/MP2 解码器** | ✅ 已定性，会提示用户；**声音本身仍未解决** |
| **7** | **真机复现「极慢 + 没声音」→ 打开 MediaCodec 硬解 + 软解兜底** | ✅ 已改完（风险 9）；效果看目标机诊断页的「解码」行 |

### 阶段 1 的验证结果（可复现）

| 验证项 | 方式 | 结果 |
| :--- | :--- | :--- |
| ijkplayer 能否解码 `IMediaDataSource` 桥接的数据 | 插桩测试（本地文件字节源 + 内存字节源） | ✅ 起播、出首帧、时长正常 |
| `readAt` 随机读是否真的生效 | 插桩测试：seek 到 3 秒后位置落在 2–6 秒区间 | ✅ |
| 配置页 token 防护 | 插桩测试 + 真机运行验证：无 token / 错 token → 403 | ✅ |
| 配置页「逐项实测」报错 | 插桩测试：SMB 与天气分别给出中文原因 | ✅ |
| 农历表正确性 | 单元测试：1984–2043 共 60 个春节锚点 + 春节前一天必须是腊月末 | ✅ 11 项全绿 |
| 节气正确性 | 单元测试：对照公开万年历逐日校验 | ✅ |
| 中文 TTS | 运行时探测，无引擎则退化为纯文字 | 模拟器无中文 TTS，看目标机的探测结果 |

> 农历表曾有一位写错（1996 年 `0x05ac0` 应为 `0x055c0`），会让整张表偏 9 天，
> 而肉眼看日历根本发现不了 —— 那 60 个春节锚点就是为了兜住这类错误。

### 尚未实现 / 未在目标设备上确认（不要当成已完成）

| 项 | 现状 | 说明 |
| :--- | :--- | :--- |
| **字幕规则**（§5） | ❌ 未实现 | 当前只开了 `subtitle=1`，会把文件里的**任意**字幕轨显示出来，违反「无中文字幕则不显示任何字幕」。要做对需要：枚举字幕轨、判断语言（`IjkMediaMeta` 的轨语言/轨名），再决定开关或外挂同名 `.srt`/`.ass`。需要带多语言字幕轨的测试片源才能验证 |
| **AC-3 / MP2 片源没有声音** | ✅ 已解决 | **两个根因都修了才有效**：①自己编了带 AC-3/E-AC-3/MP2/DTS 的内核；②点播探测窗口从 2 MB 提到 16 MB（见「格式兼容性」）。`Ac3AudioTest` 直连真实 NAS 播整集娘道断言出声。实测娘道有声音 |
| **切换剧集卡在当前页面** | ✅ 已解决 | 三个根因：①`surfaceUsable()` 改成按状态查询 + 轮询兜底（原来等一个可能永不再来的回调）；②**moov 重排没改 `stco` 偏移**（真根因，见风险 8）；③首帧判据假绿导致看门狗被提前撤销。`EveryShowPlaysTest` 全库扫描断言严格首帧，3/3 通过；**实测大宅门 / 猫和老鼠「有画面了」** |
| **播放中「播放器悄悄死亡」没有自救** | 🟡 **直播已解决，点播仍未解决** | **直播这一半已修**（2026-09-21）：判据换成「播放器自报持续缓冲 10 秒」，实测丢包后 0 毫秒收到 `BUFFERING_START`、21 秒内自动重连、画面自动回来，约 3 分钟里连救两次真实断流（见下面「**直播断流后画面永远冻着（本次修）**」）。**点播（SMB）上的「悄悄死掉」仍然没有判据**：SMB 通路上 `outputFps` 恒为 0、`trafficStatisticByteCount` 在 RTSP 通路上恒为 0，两个候选量都不可信，`LIVENESS_ENABLED = false` 继续关着，**不要只把开关改成 true**。已排除的方向写在 `MainActivity.livenessWatchdog` 注释里 |
| **电视上 4K 的帧率还是不够** | 模拟器测不出，看诊断页的「解码 / 帧率 / 结论」三行 | 实机观感：还算流畅、声音跟得上，但帧率明显偏低（25 甚至更低）。三种根因（解码不够快 / 送显来不及 / 读取供不上）在电视上没法用 adb 区分，所以加了**播放诊断页**：**双击「设置 / 信息」键**，看「解码」「帧率」「结论」三行 —— `硬解 OMX.xxx` + `解码 ≈ 片源帧率` 说明通路没问题，`软解（硬解没建成！）` 就是风险 22。需要日志时同时抓 `adb logcat -s FireflyDecode FireflyTV`（如果方便接 adb） |
| 硬解真值 | 模拟器测不出，看诊断页的「解码」行 | 模拟器上只有 `OMX.google.*`（`RANK_SOFTWARE=200`，被 ijkplayer 拒掉），所以**「选中硬解 → 真的建成」这条路在模拟器上跑不到**。真机要看诊断页的「解码」行或日志 `解码通路确认：硬解 OMX.xxx（vdec_type=2）`。若出现 `硬解没建成：选中的 … 实际没接上`，说明这台电视的 MediaCodec 建得出来却接不上，代码会自动拉黑它换下一个候选 |
| 键值差异（风险 6） | 用 logcat 查实际 keyCode | 代码同时处理 `DPAD_*` 与 `ENTER`，但**设置键的 keyCode 尚未在真机上确认**；模拟器遥控器没有设置键 |
| 中文 TTS（风险 2） | 看目标电视运行时探测的结果 | 运行时探测、失败退化为纯文字，逻辑已写；模拟器没有中文 TTS 引擎，**要在目标电视上实测** |
| 天气接口（风险 5） | 在 Android 5.1 设备上实测 TLS | 接口形态已按**心知 v3** 确认（`https://api.seniverse.com/v3/weather/…?key=<私钥>&location=<地点>`，见「风险 20：天气换成心知」）；Android 5.1 的 TLS 握手只能在目标设备上实测 |
| `CATEGORY_HOME`（风险 1） | 在长虹系统上试抢主屏 | 开机自启已写；抢主屏需在长虹系统上试 |
| 「这台电视不支持 AC-3」这句话 | 看设备能力查询结果 | 判据走设备能力查询，模拟器（纯软件解码器）上没有 AC-3；目标电视是 Amlogic 方案，**系统可能自带**，要在目标机上确认 |
| 直播重连 | ✅ **已造过真实断流并验证自愈** | 用包过滤（`iptables -I INPUT -s <边缘服务器IP> -j DROP`）造出与真实故障同形的断流：**TCP 连着、对端不再发数据**。旧判据（送显帧率）在画面冻住 3 分钟里一次都没触发（帧率停在 25.00）；新判据 21 秒内完成「判定 → 重连 → 画面回来」，且换台/静置期间不误判。另观察到该网络**本身就会频繁断流**（同一轮测试里 4 分钟内自然断了 3 次，落在不同边缘服务器上），所以这条自愈不是纸面功能 |
| 音画不同步（只在下/刚启动时） | 模拟器上复现不出来 | 已排除一个确定成因（直播写续播进度导致拿假进度去 seek，已修）。剩下的**没能在模拟器上可靠复现**：模拟器是纯软解，1080p25 H.264 本来就跑不满帧率，画面落后很可能是模拟器性能所致，而不是代码问题。已把直播缓冲压到 512 KB。**要在目标电视上实测**才能定性 |

> 说明：早先记录过「`娘道` 的容器格式未知」，现在已确认为 **MPEG-TS + H.264 + AC-3**（见「格式兼容性」）。

### 已修复（真实 NAS 联调与实测暴露的硬伤）

| 项 | 说明 |
| :--- | :--- |
| **起播慢就报「这个视频无法播放」（本次修）** | 用户报「偶尔打开会显示这个视频无法播放，等一段时间正常」，并猜是 NAS 硬盘休眠 —— 猜对了。链路：硬盘休眠 → 播放器第一次读撞上 SMB 的 20 秒请求超时 → `TimeoutException` → ijkplayer 报成播放错误 → 界面层**立刻**弹故障页，而十几秒后盘转起来同一集明明能播。修法两件：① 起播先挂「正在加载…」（`SwitchHud.onStarting`），首帧上屏才换成内容名 —— 这段时间原来屏幕上一个字都没有；② 没出过画面的失败不再立刻报故障，先自动重试（纯函数 `StartupGrace`：窗口 40 秒 / 最多 3 次 / 退避 3、8、15 秒），超窗口才把原因摆出来。已在放的内容中途断了不走这条路（用户需要立刻知道原因）。顺手封掉 `onCompletion` 里「时长不可信」那条**只写日志就 return** 的死路（此刻播放器停在文件结尾、屏幕全黑，起播看门狗早撤了）|
| **上一集播完，下一集直接黑屏（本次修）** | 用户报「铁梨花播放偶尔会出现上一集播完，下一集直接黑屏。原因未知」—— 不是原因未知，是**状态串了**：`liveReconnect` 是换到直播时打开的，而全工程没有一处把它置回 false。于是「先看直播、再回电视剧」以后，点播的一集播完被当成直播断流：`scheduleLiveRetry()` 先 `releaseInternal()`（画面当场变黑），两秒后去取频道地址重连，而 `channels` 在切库时已被清空、provider 返回 null → 黑屏永远停在那儿（无报错、无看门狗，起播看门狗在上一集首帧时就撤了）。修法：① 判据加另一半 `PlaybackMode.onCompletion(kind, liveReconnect)`（纯函数 + 3 项单测，这条规则错了不报错只黑屏）；② 引擎切到点播内容时自己 `stopLiveReconnect()`，并清掉 provider（顺带让已排队的直播重连 Runnable 拿不到地址、不会抢画面）。同类死路 `advanceToNextShow` 未回写 `shows` 字段也一并封掉 |
| **崩溃闪退** | ijkplayer 的 `onPrepared/onError/onCompletion/onInfo` 回调**不在它自己的线程上**：`IjkMediaPlayer.initPlayer` 是 `Looper.myLooper()` 优先、`getMainLooper()` 兜底（核对了 AAR 字节码），即「谁 new 的播放器就排谁的队列」。之前直接透传给界面层，界面在非 UI 线程碰 View，抛 `CalledFromWrongThreadException` 当场崩；Activity 重建后又立刻报同样的错，于是「闪退之后再也打不开」。现在 `IjkPlaybackEngine` 用 `onMain{}` 统一把回调切回主线程 |
| **直播断流后画面永远冻着（本次修）** | 用户报的「看着看着卡住不动，得重进软件才好」。模拟器上抓到现场：直播流断掉（TCP 连着、对端不再发数据）后画面冻在最后一帧，`ff_read` 之类的原生线程还在、`isPlaying` 仍是 true、**既无 `onError` 也无 `onCompletion`**，于是永远冻着 —— 界面层的起播看门狗首帧时就撤了，引擎那道存活看门狗的判据（送显帧率）实测**根本不会掉**（画面冻住 3 分钟它一直显示 25.00 帧/秒）。修法：判据换成播放器**自己报的** `BUFFERING_START` —— 实测丢包后 **0 毫秒**就到，「持续缓冲 10 秒」即判断流，3 次巡检后走既有的直播重连。**实测（`iptables` 丢包造断流）**：21 秒完成「判定 → 重连 → 画面回来」，同一轮里连救两次真实断流；随后 3 分多钟正常播放零误判。判据抽成纯函数 `LiveStall`（8 项单测钉住「真断流必判」与「正常播放/暂停不判」）。⚠️ 被否掉的两个候选量记在 `LiveStall` 类注释里（`outputFps` 停住不归零、`trafficStatisticByteCount` 在 RTSP 上恒为 0），别再试 |
| **刚开机卡住加载不动（本次修）** | 播放器原来在 `firefly-io` 上构造，而那条队列上同时排着 NAS 扫描 / 剧集列目录 / 音频探测 —— 于是**画面早就上屏了，撤掉「正在打开…」的那条回调还堵在后面**，屏幕永远停在加载条上；而那条加载提示当时还是"永不超时"。四处一起改：① 播放器独立到 `firefly-player` 线程；② `SwitchHud` 的加载提示有 60 秒期限；③ 新增 40 秒**启动兜底看门狗**（覆盖"决定要播 → 列库 → 列剧 → 列集"这段原本无人看管的窗口，到点自动重启，额度 3 次 + 5 分钟冷却）；④ 报故障时顺手收掉加载条（它原来画在故障页**上面**） |
| **等 Surface 超时就永远不出画面（本次修）** | 老实现 8 秒等不到 Surface 就**丢掉排队的那一集**并报 `retry=false` 的故障，提示还写着「请按返回键再试」—— 而返回键在本应用里是被明确吞掉的。现在请求留着继续等（退避重问 3/6/10/15/20 秒），Surface 一好立刻起播，彻底等不到由启动兜底看门狗接手；直播也补上了同样的 Surface 排队（原来直播压根不查 Surface，会在没有 Surface 时建出"收数据不出画面"的解码器） |
| **列目录失败被当成"这个库是空的"（本次修）** | `Scanner.classify` 原来把每个库的列目录异常吞成 `Kind.Empty`，开机那几秒 NAS 没就绪时会扫出一个**空库表却不抛异常** → 报「NAS 上还没有可以播放的内容」且 `retry=false`，而空库表下左右上下键全都不响应 = 只能重开应用的死页面。更糟的是那一轮的空 `libraries` 会被写进落盘缓存，下次开机走缓存路径得到空表 → `restoreSpot` 里 `libs[0]` 主线程越界崩。现在：列目录异常照原样上抛（整轮判失败 → 保留缓存 + 报故障重试）、"没有内容"改为带重试、`restoreSpot` 加空表防护 |
| **时长不可信时清空屏幕（本次修）** | `onError(fatal=true)` 显示 3 秒后 `showFault(null)`，而 `canAutoAdvance` 需要时长 ≥10 秒、出错时是 0 → 既不跳集也不报错，只剩一片黑。现在这个分支改成「留着原因 + 10 秒重试」 |
| **无限加载：重试自己把原因盖掉（模拟器实测发现，本次修）** | 模拟器上把 NAS 指向不可达地址、清掉缓存冷启动，实测到真实现象：每 10 秒重试一轮，而 `startPlayback()` 是**先清故障页、再挂「正在连接 NAS」**，列库又要 5 秒（SocketTimeout）—— 于是屏幕在「看不懂的加载条」和「看得懂的原因」之间来回切，**每次切都在用户刚读出原因之后**，120 秒采样只看得到加载条。修法：上一次失败在 60 秒内时，重试只藏故障页（`showFault(null, keepHud = true)`）并把原因继续留在屏幕上，不再重挂"正在连接"。修后同场景截图确认屏幕上写的是「连接超时，检查地址和网络」 |
| **`VIDEO_RENDERING_START` 缺失时误判卡死（模拟器实测发现，本次修）** | 起播超时只认 `onFirstFrame`（= `VIDEO_RENDERING_START`），而实测该事件在**模拟器纯软解 + GLES2** 通路上一次都不来 —— 画面明明正常在放（截图确认），25 秒一到 watchdog 却把播放打断并报「这个视频打不开，正在换下一个」。A/B 对照确认这是**既有的误判**（改动前的代码同样收不到该事件，只是没有一道恰好 25 秒的闸门去打断它）。修法：超时时先问一句 `engine.decodedAnyFrame()`（解码/送显帧率非 0 即视为已出画面并撤哨）。真机上该事件照常会来，这条路只是兜底 |
| **正式包里一行日志都不留（本次改）** | R8 只保留 `w/e`，而排查用的 `trace()` 走的是 `Log.i` —— 用户在电视上复现「卡住」时手里没有任何证据，只能让对方换 debug 包重装再等一次复现（本次排查就卡在这儿）。改成 `Log.w` 之后正式包也留得下来，消息量很小（一次启动十几行），且只记真值、不记密码 |
| **音频焦点被抢走后可能永远不回来（本次修）** | `PauseTransient` 只调 `engine.pause()`，而 `pause()` 会撤掉引擎的两道看门狗（硬解首帧 + 播放中存活），界面层的起播看门狗在首帧时也已撤掉 —— 万一焦点再也不回来（实测有些电视固件抢走之后不发 GAIN），画面就永远冻在那一帧，没有任何东西会报故障。`engine.resume()` 在整个工程里**一次都没被调用过**。修法：暂停后挂一道 45 秒的"等焦点"兜底（`focusWait`），到点没回来就自己接着播；拿到焦点或彻底丢失时撤销它 |
| **SMB 层承诺的"自动重连一次"基本没生效（本次修）** | 三处都有问题：① `isSessionGone` 只匹配几个英文字符串，而 smbj 0.15.0 实际抛的 `TransportException("... transport is disconnected")`、`STATUS_FILE_CLOSED`、`TimeoutException`、`Connection reset by peer` **一个都没匹配到** → 改成按类型 + `NtStatus` 判定（权限/路径类错误故意不重连）；② `isUsable()` 反射取 `DiskShare.isStale()`，而 0.15.0 上**根本没有这个方法** → 不再假装能判，自愈交给按类型判定那条路；③ `withSoTimeout(30s)` 会被 smbj 当成**致命传输错误并直接关连接**（暂停 / 停在故障页超过 30 秒就踩），而播放器手里的 `File` 句柄跟着失效 → 去掉它，每次请求的超时仍由 `withTimeout(20s)` 保证 |
| **主线程上的 SMB 调用（本次修）** | `onEpisodeFinished()`（从 ijkplayer 的 onCompletion 经 onMain 回主线程）会走 `loadShows()` → `Scanner.shows()` 真去问 NAS，慢 NAS 上就是网络调用占着 UI 线程；`onDestroy()` 里的 `SmbStore.drop()` 会 close share/session（TREE_DISCONNECT + LOGOFF 各是一个 20 秒超时的请求）并攥着全局锁，最坏能让退出卡几十秒。修法：拆出只查缓存的 `showsFromCache()`（缓存没有再交给 IO 线程），`drop()` 也排到 IO 线程 |
| **自动连播把下一集的续播点抹成 0（本次修）** | 一集播完自动跳下一集时走的是 `playEpisode(0L)`，而 `playEpisode` 一进去就按 `startMs` 写记录 —— 等于把**下一集已经存下的**续播点覆盖成 0，用户第二天打开从那一集开头重看。换剧/换库那条路早就改用那一集自己的记录了，只有"自动连播"漏了。修法：新增纯函数 `WatchHistory.autoAdvanceResumeMs(记录, 集号)`（记录集号必须对得上、进度必须可信），`WatchHistoryTest` 4 项钉住 |
| **`singleTask` 下再次打开是空操作（本次修）** | `onNewIntent` 原来什么都不做 —— 于是"进程活着但界面卡住"时，用户点图标**毫无反应**，只能去系统设置强停或重启电视（而"重进软件就好了"恰恰是用户报这条故障时唯一的自救手段）。修法：判据交给纯函数 `Navigator.needsRecovery`（有单测钉住，5 项），**只在真的什么都没在播时**才重跑一遍启动链路，并加 60 秒节流。模拟器验证：正常播放中点图标，画面继续走（三张截图字节数各不相同）、日志里没有 `startPlayback` |
| **moov 在尾部播不了** | 真实 NAS 上需要重排的片源原来一集都播不了。`MoovRelocatingSource` 把 moov 搬到虚拟文件头后正常起播（风险 8） |
| **搬 moov 时把 `stco` 的 `entry_count` 一起改坏** | 偏移表的字段位置算错 4 字节，`entry_count` 被写成 `N+delta`（大宅门：70505+2535446），解复用器越界 10,141,784 字节去读 260 万条 —— **音轨当场废掉**：猫和老鼠/大宅门解出来只剩一条视频轨，表现就是「有画面没声音」。已按真实布局改写（`entry_count` 在 `off+12`、偏移项从 `off+16`），并由 `MoovChunkOffsetPatchTest` 逐字节钉住（对着旧实现会红） |
| **`readAt` 的零长度读返回 -1** | ijkplayer 把 seek 实现成「零长度读」，返回负数即 `AVERROR_EOF`：每一次 `avio_seek` 都失败，mov 只能退化成顺序读。现场一次播放刷 4418 条 `partial file`、解码器满屏「缺参考帧」，首帧 ~10 秒。改成零长度返回 0 之后，首帧 **0.9 秒**、`partial file` 0 条、续播 seek 才真正生效 |
| **moov 重排但没改 `stco` 偏移** | 搬了 moov 却没改 chunk 的绝对偏移，播放器按老偏移读到的其实是 moov 自己的字节 —— 表现为**大宅门 / 猫和老鼠永远出不了首帧**（「切换其他电视剧卡死」的真根因）。已补偏移改写，风险 8 有完整说明 |
| **首帧判据假绿导致看门狗被提前撤销** | `onFirstFrame` 同时挂在 `VIDEO_RENDERING_START` 和 **`VIDEO_SIZE_CHANGED`** 上，而后者在**准备阶段**就触发（解码器刚拿到分辨率就报，一帧都没解出来）。`onFirstFrame` 的第一件事是撤掉起播看门狗，于是**看门狗在画面真正出来之前就被撤了**，解码器随后卡住就再也没人报故障。现在首帧只由 `VIDEO_RENDERING_START` 触发并去重。这条在**所有设备**上都成立 |
| **`MoovRelocatingSource` 跨段短读** | 重排后的虚拟文件由物理上不连续的段拼成，`read` 很容易跨段。旧实现遇到边界就只返回当前段剩下的字节 —— 上层会把这个短读当成「文件到这儿就没了」。**只有 moov 在尾部、需要重排的片源才会复现**，表现为播到一半「播完」或画面花掉。已补循环填满，并加了**用大块读**的回归测试（旧的测试用 7 字节零碎读，永远不跨段，所以一直没发现） |
| **按上键跳回 CCTV5** | 切库时没清掉上一个库的缓存，于是拿 IPTV 遗留的频道列表去换台。现在切库整体清空，并把决策抽成 `Navigator`（类型 + 缓存归属），11 项单元测试钉住 |
| **切库看起来像卡死** | 切库要等 SMB 列目录，这段时间屏幕上什么都不变。现在任何一次按键都立刻出反馈条（`SwitchHud`，14 项测试） |
| **切换很慢** | 每按一次上下键都要重新列一遍剧和集。现在剧列表按库缓存、集列表落盘缓存，实测冷启动不再等 NAS |
| **切剧后永远卡住不出画面** | 起播时若 Surface 还没建好就排进等待队列，代码假设 `surfaceCreated` 会再来一次 —— 实测不成立（从后台回前台时按键能触发，但 Surface 早建好了）。那一集就永远躺在队列里。改成按状态查询 `surface.isValid` + 500ms 轮询 + 8 秒超时 |
| **直播也在写续播进度** | IPTV 的 `currentPosition` 是从开播算起的毫秒数，被当成进度存下来，下次拿它去 seek 一条直播流 —— 这就是「IPTV 音画不同步」的确定成因。现在 `PlaybackMode` 写死「只有点播配拥有进度」，6 项测试钉住 |
| **SMB 会话废掉后再也连不上** | 底层断过一次后，单例里缓存的 `DiskShare` 一直在报 "already been closed"，每次调用都失败，只能重启应用。现在 `SmbStore` 每次调用前检查连接可用性，坏了就重建 |
| **换剧回来只能从头看** | 全局只有一份观看记录，换一部剧就把它覆盖掉，`Navigator.vertical` 还写死从第 1 集 0 秒起。现在按「库 + 剧」各存一条，5 秒一次周期写盘（老人直接关电视也最多丢 5 秒），换剧/换库/开机都接着上次看（风险 19） |
| **天气换成心知天气** | 和风要填专属 Host，老人填不对；心知只有私钥 + 地点两栏。实测公钥会被拒（AP010003）、地名可能没权限（AP010006）；地点不留默认值（写死坐标既泄露隐私也替用户做决定），两项要么都填、要么都留空。频率做了 30 分钟保鲜 + 5 分钟冷却 + 结果落盘（风险 20） |
| **换回直播就冻住（403）** | ijkplayer 的 DNS 缓存只按主机名做键、却把端口一起缓存：央视 `…:82` 的地址被连到缓存里的 `…:81`，请求行/`Host` 却还是 `:82` 那份 → openresty 403，缓存活到进程结束所以永远不好。改为不信那份缓存（风险 15），实测每次换台都是 `:82→302→:81→206`，CCTV-1 稳定 25.00 fps |
| **直播失败一声不吭** | 引擎的直播分支自己重连、却一次回调都不发，界面永远冻在上一帧、按键还有反应 —— 看起来就是死机。新增 `Listener.onLiveRetry` + 两句人话（风险 16） |
| **`https://` 直播源全部起不来** | 内核没编 OpenSSL（官方三个 AAR 也没有），https 源一律 `Protocol not found`。已给三个 ABI 补上（风险 17） |
| **字节源打开失败会把进程带走** | `startPlayback()` 里同步 `open()` 抛的异常原来没人接，会冒到 Handler/主线程 → 进程被杀。现在统一走 `openOrReport()`，出故障页（风险 18） |
| **配置页说「检查通过」但其实没保存** | 实测报出的假反馈。现在保存成功有明确文案与等待动画，手机侧有转圈反馈 |
| **配置页把「跳过」说成「连接成功」** | 天气留空会跳过检测，却回「天气 连接成功」，用户会以为天气已经配好了。现在区分「连接成功」和「没填，已跳过（电视上不显示天气）」 |
| **配置页底部文字被裁切** | 写死的 420dp 二维码把整列撑出 1080p 屏幕，**最下面那行地址被切掉一半** —— 而那是唯一能手动输入的入口。改成按屏幕短边比例算（34%），并补了窄屏媒体查询与 safe-area 留白 |
| SMB 端到端 | 对真实 NAS（SMB 3.1.1）跑通：列库 → 跳过空目录 → 识别直播库 → 列剧列集 → 逐集探 moov → m3u 解析 → 随机读 → 播放出首帧 |
| 格式兼容性 | 逐类实测，结论见「格式兼容性」一节：AAC / AC-3 / E-AC-3 / MP2 在 TS 与 MP4 下全部出声；真实 NAS 上 3 部剧全部出首帧（实测大宅门 / 猫和老鼠「有画面了」） |

## 11. 构建与部署要点

| 项 | 值 |
| :--- | :--- |
| 构建 | 联网机器：`.\gradlew assembleDebug`；**本机：`.\build.ps1`**（本机访问不到 services.gradle.org，脚本直接用缓存里的 Gradle 8.11.1）。WSL 里改代码、Windows 里构建时用 `scripts/win-build.sh`（**连 build.gradle.kts / proguard / AAR 一起同步**，只镜像 app/src 会「改了构建脚本却不生效」） |
| 环境 | Android SDK + JDK 17 + Gradle 8.11.1（本机路径不入库，构建脚本会自己找） |
| **ABI** | 必须含 `armeabi-v7a` + `arm64-v8a`（电视）+ **`x86`**（模拟器，缺了会直接崩） |
| 正式包 | `.\build.ps1 assembleRelease` → `app-release.apk`（~25MB，debug 包 ~30MB）：R8 压缩 + 资源裁剪 + 去掉 `v/d/i` 日志（规则见 `app/proguard-rules.pro`）。**签名复用 debug keystore**，否则装不上电视上现有的包（换签名 = `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，卸载会清配置） |
| **版本号** | `app/build.gradle.kts` 的 `versionCode` / `versionName`：**每次发版 versionCode +1**（安卓靠它判断能否覆盖安装），`versionName` 走 `x.y.z`（修 bug 动 z、加功能动 y、不兼容动 x）。当前 `2` / `1.0.1`。电视上没有 adb，「用户手上是哪个包」只能靠这两个数：**诊断页标题**（双击「设置 / 信息」）与**配置页底部**都写着版本号；GitHub Release 的资产就叫 `app-release.apk`（不为每个版本另起一个文件名，版本看 Release 的 tag 即可）。一直写死 `1` / `1.0` 的话，出了问题连「是不是已经修过的那版」都判断不了 |
| R8 两个坑 | ①`net.engio.mbassy` 被写成 `net.engio.mbassador`（keep 规则等于没写，debug 包不压缩所以一直没症状）；②`javax.el.**` / `org.ietf.jgss.**` 是 Java SE 专有依赖，必须 `-dontwarn`，否则 R8 直接失败。**开 R8 的价值一半在这里** —— 它把「装上能开、一连 NAS 就崩」这类问题提到了构建期 |
| 依赖 | **ijkplayer 用自己编的内核**（`app/libs/ijkplayer-full-0.8.8.aar`，含 AC-3/MP2/DTS）；AAR 不入库，重建见 `app/libs/README.md` 与 `scripts/build-ijkplayer.sh` |
| 模拟器 | AVD `firefly_tv` = `system-images;android-22;android-tv;x86`（Android TV 5.1.1，与目标电视同版本，自带遥控器面板） |
| 测试 | `.\build.ps1 testDebugUnitTest`（**259 项**）/ `.\build.ps1 connectedDebugAndroidTest`（**60 项**，含对真实 NAS、真实直播源与天气接口的联调；未配 `local.properties` 时自动跳过） |
| 内核重建 | `scripts/build-ijkplayer.sh` → `collect-ijkplayer.sh` → `pack-ijkplayer-aar.sh`（需 Linux/WSL，见 `app/libs/README.md`） |
| 解码器校验 | `scripts/verify-ijkplayer-decoders.sh <so 目录>`：逐 ABI 用 `nm` 读符号表。**别用 `strings`**，那个符号不一定以裸字符串出现，会误报「没有」 |
| 格式实测 | `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.firefly.tv.player.FormatMatrixTest`，结果看 `adb logcat -s FireflyFormat` |
| 全库起播扫描 | `…class=com.firefly.tv.EveryShowPlaysTest`，结果看 `adb logcat -s FireflyEveryShow`。**这是判断「能不能播」最可信的一条** —— 每部剧真起播一次，断言严格首帧 |
| 音频编码矩阵 | `…class=com.firefly.tv.AudioCodecMatrixTest`，结果看 `adb logcat -s FireflyAudioMatrix`。同一段视频只换音频编码，把「内核缺解码器」和「流参数探测失败」分开 |
| **硬解通路** | `…class=com.firefly.tv.player.HardwareDecodeTest`，结果看 `adb logcat -s FireflyDecode`。判据是 MediaCodec 选码回调**有没有被调用**：真机应看到 `MEDIACODEC` + 解码器名，模拟器是 `NO_CODEC`（没有可用硬解→软解） |
| **诊断页的数字是不是真的** | `…class=com.firefly.tv.player.DecoderTruthTest`：钉住「解码通路真值 / 片源帧率（样本是 15 帧）/ 视频尺寸 / 音频解码器名」四条。诊断页要贴到用户眼前，读数错了比没有更糟 |
| **SMB 吞吐** | `…class=com.firefly.tv.player.SmbThroughputTest`：四种读法的 MB/s（换 NAS / 换网络后重跑它，别靠猜）。结果看 `adb logcat -s FireflyProbe` |
| 网络诊断 | `…class=com.firefly.tv.diag.NetworkDiagTest`，结果看 `adb logcat -s FireflyDiag`（模拟器里 `ping` 不通是正常的，NAT 不回 ICMP，**只有 TCP 能说明问题**） |
| 测试素材 | `.\scripts\make-test-media.ps1`（moov 位置对照）+ `.\scripts\make-audio-fixtures.ps1`（音频编码对照）。生成的媒体**都不入库**，见 `app/src/androidTest/assets/README.md`；`app/src/test/resources/real-ac3.ts` 是入库的 TS 语料，用来验证轨道探测 |
| 诊断脚本 | `scripts/ts-psi.py`（正确的 PAT/PMT 解析，文件头写了三个踩过的假象）、`scripts/push-config.py`（本机联调直接写配置，账号从 `local.properties` 读）、`scripts/check-ac3-decode.ps1` |
| moov 重排取证 | `scripts/verify-relocation-full.py`（把重排后的**完整**字节物化出来，用 ffmpeg 验；截断样本会给出假结论）、`scripts/dump-stco.py` / `inspect-mp4-moov.py`（读 moov 结构与 chunk 偏移表） |
| WSL 侧配置核查 | `scripts/wsl-check-ffmpeg-config.sh`（逐 ABI 查 FFmpeg 编进去的解码器/解析器/解复用器）、`wsl-check-parsers.sh`（**解码器和解析器是两回事**，只看 `nm \| grep decoder` 会漏）、`wsl-fix-apt.sh`（清 apt 锁 + 禁用挡住 `apt-get update` 的 NVIDIA 源） |
| 装到电视 | U 盘拷 APK → 电视文件管理安装；或手机电视助手局域网推送 |
| 电视设置 | 开「允许未知来源应用」；设置开机自启；尝试设为默认桌面 |

## 12. 目录与命名

- 目录名 `FireflyTV`、包名 `com.firefly.tv`、文档文件名一律 ASCII（避免中文路径在 Git / 命令行工具中的编码问题）
- 中文名「萤火照夜」仅用于展示
