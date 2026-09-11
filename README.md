# FireflyTV（萤火照夜）

> 老人便捷媒体播放器：连电视、极简物理按键
>
> 萤火虫不照明整片天，只在夜里静静亮着一小块地方。

为家中老人做的电视播放软件，仅局域网使用。**只有三个按键**：左右换媒体库，上下换剧/换频道，OK 看天气。
（**双击设置键**是给维护者看的播放诊断页，老人不会误触 —— 见下文。）

## 按键

```
   ← →   切换媒体库（循环）
   ↑ ↓   换剧 / 换频道（循环）
   OK    天气 + 时间 + 日历 + 语音播报
   双击「设置 / 信息」键   播放诊断页（OK 关闭，30 秒后也会自动关）
   面板开着时，单击同一颗键  切换到下一档实验方案（会重播，debug 包才有）
```

切换后**立即播放**。没有列表、没有海报墙、没有选集页。其余按键一律不响应。

> 每次按键都会在画面左下角出一条名字（换库时是「《库名》正在打开…」），
> 让老人知道键收到了、正在换什么 —— 切库要等 NAS，没有这条提示看起来就像卡死。
>
> 诊断页为什么是「双击」：用户实测这台电视的「信息」键其实就是**设置键**，
> 单击弹一屏字太重，双击既不会误触、也不用记组合键；打开后 **OK 关闭**。
> 切换实验方案也复用同一颗键，因为用户的遥控器**没有数字键**（老人机遥控器的常态）。
> （配置页目前只能在首次未配置时自动进入，没有按键重入路径。）

## 播放诊断页（电视上没有 adb 时的唯一证据）

用户的原话是「明显感觉帧率不高，25 甚至更低」，而电视上不方便连 adb。
所以把证据直接放到屏幕上 —— **双击遥控器上的「设置 / 信息」键**：

```
屏幕    1920×1080 · dpi 240 · density 1.50 · 缩放 ×1.33 @50.0Hz
画面    3840×2160 · 片源 25.0 帧/秒 → 输出 1920×1080
解码    硬解 OMX.MTK.VIDEO.DECODER.HEVC
帧率    解码 24.6 · 送显 18.2 · 丢帧 0%
像素率  送显 151.2 Mpx/秒（片源要 207.4）
缓冲    41240 毫秒 / 13.9 MB       读取 0.0 MB/秒（要 0.4）
音频    avcodec aac · 已出声
结论    送显 18.2 帧/秒，低于片源的 25.0 帧/秒：解码够快，画面却跟不上，瓶颈在送显/合成（送显 151.2 Mpx/秒）
内容    大宅门
```

「结论」那一行是**算出来的**（`PlaybackVerdict`，16 项单元测试），判据按优先级：
硬解静默回落 → 读取跟不上 → 缓存见底 → 送显低于片源 → 解码跟不上。
每一行都来自播放器真值，不做推算；取不到的（例如某些流没有 `avg_frame_rate`）显示 0 而不是瞎猜。

## 远程诊断口（debug 包；电视在别人家里时用它量数字）

「4K 送显只有 18 帧」这类问题必须**换一个变量、量一次数字**，而在电视上改一行代码
要重装一次 APK。所以 debug 包里开了一个**无鉴权**的局域网 HTTP 口（`DiagHub`，端口 **8642**），
面板上「远程」那一行就是它的地址：

```
远程    http://192.0.2.34:8642
```

| 接口 | 用途 |
| :--- | :--- |
| `/state` | 一屏 JSON：屏幕 / 视频层 / 内容 / 解码 / 帧率 / 像素率 / 缓冲 / 音频 / 结论，**外加面板那几行的原文**，以及系统 CPU、温度、核频、DDR 频 |
| `/series?n=60` | 1 Hz 历史样本，用来算窗口均值 |
| `/threads` | 逐线程 CPU 与 nice（回答「哪个线程在满 / 有没有被饿死」） |
| `/knobs`、`/presets` | 列出 18 个旋钮与 12 档预设 |
| `/preset?n=4` | 切到第 4 档（**会重播**，面板上单击「设置键」是同一件事） |
| `/set?player.framedrop=0` | 改任意旋钮；只有「建面前的」和「起播前的」那些才需要重播，会自动重播 |
| `/codecs` | 本机解码器清单 + **声明的最大尺寸**（`max_w/max_h/supports_4k`）+ 是否支持隧道播放（`tunneled`） |
| `/props?q=` | 系统属性（`getprop` 的替代）：`debug.sf.hw`、`sys.display-size`、`service.adb.tcp.port` 这些不用 adb 也能看到 |
| `/cmd?a=…` | `ls` / `find` / `open` / `pause` / `resume` / `seek` / `key` / `panel` / `replay` / `stop` |
| `/log?n=200` | 应用自己的日志 |
| `/state` 的 `io` 段 | 读路径计数：moov 搬运次数、读请求次数、**平均每次读多少字节**（读放大就看它） |
| `/state` 的 `library` 段 | 当前库名与下标 + **内存里的库列表**（顺序就是按键取库的顺序）+ 剧数/集数。「按键跑到别的库去了」这类问题看它 |
| `/state` 的 `fault` 段 | 电视屏幕上此刻显示的故障原文（没显示就是空串）。「它自己跳走了」这类问题靠它取证 |

操作端是 `scripts/tvprobe.py`（只用 Python 标准库，WSL / Windows 都能跑）：

```bash
TV_URL=http://192.0.2.34:8642 python3 scripts/tvprobe.py state
python3 scripts/tvprobe.py watch 30          # 采 30 秒，打印均值
python3 scripts/tvprobe.py measure "测试/4k.mp4" "测试/1080p.mp4"   # 逐个片源量一遍（按片名过滤样本）
python3 scripts/tvprobe.py threads           # 逐线程 CPU
python3 scripts/tvprobe.py preset 4          # 换第 4 档（会重播）
python3 scripts/tvprobe.py set player.framedrop=0 player.video-pictq-size=8
python3 scripts/tvprobe.py sweep --presets 1,3,4,7 --sec 20   # 逐档对照，出表
python3 scripts/tvprobe.py find 大宅门        # 在 NAS 上找文件（例如找 1080p 版本）
python3 scripts/tvprobe.py open "电视剧/大宅门/01.mkv"
python3 scripts/tvprobe.py scan             # 在局域网里自动找电视（不用念 IP），顺便看 5555 有没有网络 adb
python3 scripts/tvprobe.py props sys.display   # 系统属性（adb getprop 的替代）
python3 scripts/tvprobe.py codecs             # 解码器能力 + 隧道播放支持
```

设计取舍：**常开、无 token、只在 debug 包**。用户明确要求「怎么简单怎么来」，
而这个口不经手任何凭据（NAS 密码在配置页那套里，和它无关）；
release 包里 `EXPERIMENTS = BuildConfig.DEBUG` 为 false，服务和面板实验行都不存在。
旋钮值存在独立的 `firefly_diag` prefs 文件里，不会污染 `firefly` 那份配置。

## NAS 上怎么放（平铺的文件也能播）

一个子文件夹 = 一个媒体库，库里的内容两种都认：

```
影视\
├── 电视剧\              ← 一个库
│   ├── 娘道\            ← 一部剧（子文件夹）
│   │   └── 01.mp4 ...   ← 剧集
│   └── 大宅门\
└── 测试\                ← 一个库
    ├── BV1cP34zRE33.mp4 ← **平铺的视频文件 = 一部剧（单集）**
    └── BV1TuGE6AES8.mp4
```

2026-09-12 实机反馈「新建了个测试文件夹放两段片子，**电视打不开这个媒体库，会快速跳过**」，
查出两个真实缺陷，都已修并在模拟器上对着真实 NAS 复现过：

| 缺陷 | 现象 | 修法 |
| :--- | :--- | :--- |
| **只把子文件夹当剧** | 平铺的库被判成空库 → 立刻跳下一个库；而「跳过空库」原来**没有上限**，几个库一轮一轮地跳，屏幕上就是唰唰唰跳个不停，也不说为什么 | `Scanner.showNames` 把**散装视频文件也当一部剧**（单集，路径不拼多余的那一层）；跳过改为**转完一圈就停并报故障** |
| **库列表刷新后下标没重新锚定** | 缓存里的库列表比 NAS 上少一个（新加的文件夹还没扫到）时，刷新回来的新列表会让旧下标指向**另一个库** —— 实测「在《娘道》上按 ↓ 毫无反应（下标越界，库为 null）、按 → 却跳到了别的库」 | 列表一换就按**名字**把当前库找回来（`Navigator.libraryIndexAfterRefresh`） |

顺带：`/state` 新增 `library` 段（当前库名/下标 + 内存里的库列表 + 剧数/集数），
这类「按键跑到别的库去了」的问题以后一眼就能看出来。

## 4K 片源的定论（2026-09-12 实机，**已更正过一次**）

**结论：这台电视放得动 4K，放不动的是 4K HEVC。** 锅在**编码**上，不在「4K」上。

同一条命令、同一台电视、前后几分钟，逐个片源量出来的（`/series` 只统计片名对得上的样本）：

| 片源 | 编码 | 解码（均值/中位） | 送显（均值/中位） | 丢帧 | 送显像素率 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1920×1080@60 | H.264 | 59.3 / 59.3 | **59.1 / 59.3** | 0% | 122 Mpx/秒 |
| 3840×2160@50 | H.264 | 56.0 / 55.2 | **46.0 / 48.5** | 9% | **381 Mpx/秒** |
| 2960×2160@23.98 | HEVC | 23.8 / 23.9 | **23.8 / 23.9** | 0% | 152 Mpx/秒 |
| 3840×2160@25 | HEVC | 18.2 / 18.4 | **17.1 / 17.4** | 14% | 141 Mpx/秒 |

- 真正的墙是**这颗芯片的 HEVC 解码块，约 150 Mpx/秒**：4K 一帧 8.3 Mpx，
  所以 4K HEVC 顶多 ~18 帧/秒，**跟片源帧率无关**；2960×2160@24（152 Mpx/秒）刚好在线上，满帧。
- **H.264 的 4K 能跑到 ~380 Mpx/秒**（4K50 送显中位 48.5/50 帧），
  深队列那档预设还能把丢帧从 9% 压到 0%。所以「4K 一律放不动」是**错的**。
- 老结论（当天早些时候写下的「4K 缩到 1080p 的显示通路吃不下、只能维持 ~137 Mpx/秒」）
  是**只测过 4K HEVC** 得出的，已被上面这条 4K H.264 的读数推翻。
  当时的误解还有一层：把「送显卡住 → 反压解码」当成了「显示通路的锅」，
  于是判据写成「解码低于送显 ⇒ 怪解码」。现在的判据只问一句：
  **解码自己够不够片源**，然后按编码给出路（见 [PlaybackVerdict] 的 `uhdHint`）。
- 实用建议：**4K 优先选 H.264 版本；HEVC 就选 1080p（或 2960×2160 那种准 4K）。**
  诊断页遇到跟不上时会直接说清是哪一档，不再笼统地说「换 1080p」。
- 完整的实测数据、源码核对与实验台用法见 `docs/DESIGN.md` 风险 24，以及
  `docs/research/ijkplayer-0.8.8-mediacodec-mtk-report.md`。

## 播放内核是自己编的

ijkplayer 官方包自带的 FFmpeg **只编进了 23 个解码器，没有 AC-3、没有 MP2、没有 DTS**。
而实际片源恰好命中这两个缺口 —— 娘道 76 集是 **AC-3**、CCTV5 是 **MP2**，
表现就是「画面好好的，就是没声音」。

所以本仓库用的是一份**重新编译、把 AC-3 / E-AC-3 / MP2 / DTS 都打开**的内核：
`app/libs/ijkplayer-full-0.8.8.aar`（不含在仓库里，重建方式见
[`app/libs/README.md`](app/libs/README.md)）。

实测结果（模拟器直连真实 NAS 与真实直播源）：

| 片源 | 音频 | 结果 |
| :--- | :--- | :--- |
| 《娘道》76 集 | AC-3 | ✅ 画面 + 声音 |
| CCTV5 | MP2 | ✅ 画面 + 声音 |
| 《大宅门》《猫和老鼠》 | AAC | ✅ 画面 + 声音 |
| CCTV1 / CCTV3 等 | AAC | ✅ 画面 + 声音 |

判据是 ijkplayer 的 `MEDIA_INFO_AUDIO_RENDERING_START` 回调，不是靠人耳听。

> 内核换了以后要同步改 `AudioSupport.BUILT_IN`，否则会误报「这台电视不支持」。
> 单元测试会遍历所有编码把两个方向都钉住。

## 真机上「极慢 + 没声音」的三个根因

一次真机复现（《猫和老鼠》《大宅门》有画面但极慢、且没有声音）查出**三个独立的问题**，
都在数据通路上，跟电视本身没关系：

| # | 根因 | 症状 | 修在哪 |
| :--- | :--- | :--- | :--- |
| A | ijkplayer 的 `mediacodec*` 选项**默认全是 0**，不写就永远软解 | 4K H.265 极慢 | `VideoDecodePolicy` |
| B | 搬 moov 时把 `stco` 的 `entry_count` 一起改坏 | **音轨消失 → 没声音** | `MoovRelocatingSource` |
| C | `readAt` 的零长度读返回 -1，而它是 ijkplayer 的 seek | 慢到没法看、续播失灵 | `SmbMediaDataSource` |

### A：硬解必须**显式打开**（否则 4K 片源极慢）

ijkplayer 的 `mediacodec*` 选项**默认全是 0**（`ff_ffplay_options.h`，逐条写着
`OPTION_INT(0, 0, 1)`）。不写这些选项就等于**永远走 FFmpeg 软解** ——
和「这台电视有没有硬解能力」无关：4K H.265 一帧要几百毫秒，画面自然极慢。

修法集中在 `VideoDecodePolicy`（选项）与 `IjkPlaybackEngine`（选码、兜底）：

- 打开 `mediacodec-all-videos`，并显式 `mediacodec-handle-resolution-change=1`
  （央视各频道会在标清/高清之间换分辨率）；
- `opensles=0` 钉死 AudioTrack（OpenSL ES 在部分电视固件上是坏的）；
- `max-fps=61`：默认的 31 会把帧率超过 31 的片源按「高帧率」处理，
  丢非参考帧且不做去块滤波 —— 《娘道》是 1080p50，正好中招；
- 硬解 **15 秒不出首帧**、或解码器报错 → **自动改用软解重播同一集**
  （连续失败 2 次后本次运行不再试硬解）。有些电视的 MediaCodec 建得出来、
  却一帧都不吐，这时软解虽然慢但至少能看，不该把故障页甩给老人。

> 硬解通路**不需要重编内核**：它一直在随包的 `libijkplayer.so` / `libijksdl.so` 里
> （`ffpipeline_create_from_android`、`ffpipenode_create_video_decoder_from_android_mediacodec`、
> 51 个 `J4AC_android_media_MediaCodec__*`）。
> 也不是靠 FFmpeg 的 `h264_mediacodec` 解码器 —— 那一族官方包和自编包都没有。

⚠️ **模拟器测不出这个问题**：模拟器上只有 `OMX.google.*` 这类**软件实现**的
MediaCodec 解码器，ijkplayer 按排名把它们拒掉，于是仍然走 FFmpeg 软解。
必须在电视上验证：

```powershell
.\build.ps1 connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.firefly.tv.player.HardwareDecodeTest
adb logcat -s FireflyDecode FireflyTV
# 期望看到：解码通路=MEDIACODEC、硬解选码器 mime=video/hevc ... -> OMX.xxx（电视的解码器名）
# 只看到 NO_CODEC = 这台设备确实没有可用的硬解，会退回软解
```

### B：搬 moov 时把 `stco` 的 `entry_count` 也改了 —— **没声音的真根因**

给 chunk 偏移表加增量时把字段位置算错了 4 个字节：`entry_count` 被当成偏移项改掉，
变成 `N + delta`（《大宅门》里是 `70505 + 2535446 = 2605951`）。
解复用器于是去读 260 万条、越界 10,141,784 字节：

```
overread end of atom 'stco' by 10141784 bytes
wrong sample count
```

**音轨当场废掉** —— 只有需要重排的那两部剧（猫和老鼠 / 大宅门）会这样，
所以「画面有、声音没有」。判据用 `TrackInfoProbeTest`（问播放器「看到几条轨」）：

| 片源 | 修前 | 修后 |
| :--- | :--- | :--- |
| 娘道（MPEG-TS，不重排） | 2 条轨（视频+音频） | 2 条轨 |
| 猫和老鼠 / 大宅门（MP4，moov 在尾） | **1 条轨（只剩视频）** | **2 条轨（音频回来了）** |

新增 `MoovChunkOffsetPatchTest` 逐字节钉住：表头一个字节都不许动、改动只能落在
偏移项上。**这组测试对着旧实现是红的**（已实测），所以它能防回归。

### C：`readAt` 的零长度读就是 seek —— 返回 -1 等于每次 seek 都失败

ijkplayer 的 `ijkmds_seek` 把 seek 实现成一次**零长度读**，返回负数就当 `AVERROR_EOF`。
而 `SmbMediaDataSource` 最早写的是 `if (len <= 0) return -1` —— 于是每一次 `avio_seek`
都失败，mov 只能退化成顺序读（大宅门一次播放刷 **4418 条** `partial file`，
解码器满屏「缺参考帧」，首帧要 10 秒）。改成零长度返回 0 之后：

| 指标（模拟器，4K H.265） | 修前 | 修后 |
| :--- | :--- | :--- |
| `partial file` | 4418 条 | **0** |
| 缺参考帧报错 | 满屏 | **0** |
| 首帧 | ~10 秒 | **0.9 秒** |
| 续播 seek | 从未生效 | 生效 |

> 这三条里只有 A 能在模拟器上「看不出来」（模拟器没有硬解解码器，仍走软解）；
> B、C 都在模拟器上完整复现并验证过。真机上应该三条一起见效。

## 模拟器上 4K 还是卡 —— 又是两个根因，跟电视无关

上面三条修完以后，模拟器上《娘道》满帧了，另外两部 4K 依然只有 4fps 左右。
继续量下去，发现是**模拟器这条路自己**的两个问题：

| # | 根因 | 症状 | 修在哪 |
| :--- | :--- | :--- | :--- |
| D | ijkplayer 的 x86 内核是 `--disable-asm` 编出来的，**一条 SIMD 都没有** | 软解吞吐只有该有的几分之一 | `scripts/patch-ffmpeg-x86-asm.sh` |
| E | 默认渲染通路要 CPU 做 YUV→RGB32 + 整帧 memcpy，4K 下**每帧 33MB** | `ff_vout` 单核跑满 | `VideoDecodePolicy.OVERLAY_GLES2` |

先说清楚为什么必须修软解：**模拟器上没有硬解可用**。它的 MediaCodec 只有
`OMX.google.hevc.decoder`（AOSP 自带的软件实现，`media_codecs_google_video.xml` 里
声明上限 `2048x2048`），而 ijkplayer 给软解实现打的分是 `RANK_SOFTWARE = 200`，
低于它自己的及格线 `RANK_LAST_CHANCE = 600` —— 于是直接拒掉、回退 FFmpeg 软解。
这个行为是对的（用 MediaCodec 的软解器只会更慢），但也意味着
**「让模拟器流畅」这件事只能靠把软解和渲染本身修好**，没有任何开关可以绕。

修之前先量了一遍（模拟器，直连真实 NAS，`dumpsys SurfaceFlinger --latency SurfaceView`）：

| 片源 | 分辨率 / 编码 | 修前 | 修后 |
| :--- | :--- | ---: | ---: |
| 《娘道》 | 1920×1080 H.264 25fps | 24.75 fps | **23.6 fps**（满帧） |
| 《猫和老鼠》 | 2960×2160 HEVC 23.976fps | 4.1 fps | **9.9 fps** |
| 《大宅门》 | 3840×2160 HEVC 25fps | 4.3 fps | **8.4 fps** |

切换延迟（按键到首帧）同时从 **0.7~1.5 秒降到 0.4~0.9 秒**，见下面的 F。

### D：x86 内核是纯 C 编的（`--disable-asm`）

ijkplayer 上游的 `android/contrib/tools/do-compile-ffmpeg.sh` 里有一段特例：

```sh
if [ "$FF_ARCH" = "x86" ]; then
    FF_CFG_FLAGS="$FF_CFG_FLAGS --disable-asm"
else
    FF_CFG_FLAGS="$FF_CFG_FLAGS --enable-asm"
    FF_CFG_FLAGS="$FF_CFG_FLAGS --enable-inline-asm"
fi
```

也就是说 **armv7a / arm64 有 NEON，x86 什么都没有**（拿 `strings libijkffmpeg.so | grep -c sse2`
一比就看得很清楚：ARM 那边几百个 neon 符号，x86 是 0；FFmpeg 自己把这种构建叫
"a crippled build"）。补上以后同一个文件（4K HEVC）的解码 CPU 从 **246ms/帧降到 117ms/帧**。

```bash
bash scripts/patch-ffmpeg-x86-asm.sh <android/contrib 目录>   # 已经在 build-ijkplayer.sh 里自动调用
```

### E：默认渲染通路在 4K 下每帧搬 33MB

`overlay-format` 的默认值是 `RV32`（RGBX8888）。那条路上每帧要做两件纯 CPU 的事：
libyuv 把 YUV420P 转成 ABGR32，再把整帧 `memcpy` 进 ANativeWindow 的 buffer。
3840×2160 一帧就是 **33.2MB**。实测 `ff_vout` 线程**单核跑满 92%**，
而同一时刻 5 个解码线程加起来才 0.9 个核 —— **瓶颈根本不在解码**。

改成 `overlay-format = fcc-_es2` 之后，ijkplayer 会把 8bit YUV420P 直接标成
`SDL_FCC_YV12`（解码器原生的三平面格式，**不做转换**），交给 GLES2 渲染器：
三个平面各上传成一张纹理，YUV→RGB 的矩阵在片元着色器里由 GPU 做。
CPU 侧每帧只剩约 12.4MB。

> 硬解那条路**完全不受影响**：MediaCodec 解出来的帧是
> `IJK_AV_PIX_FMT__ANDROID_MEDIACODEC`，overlay 创建时直接把格式写成 `SDL_FCC__AMC`，
> 压根不看 `overlay-format`，显示时也只是 `releaseOutputBuffer(render=true)` 送回 Surface。
> 所以这个选项只对「软解 + 8bit YUV420P」生效，正好是需要它的那种场景。
>
> 代价：GLES2 的 YUV 着色器里色彩矩阵写死了 BT.709（`renderer_yuv420p.c`），
> 标清（BT.601）片源颜色会略有偏移。1080p/4K 片源不受影响。

### 模拟器到不了 24fps，这是环境的天花板

修完 D 和 E 之后，4K 那条路上还剩两个各约 100ms/帧的开销，而且它们互相独立：

- **解码**：4K HEVC 软解在 32 位 x86 guest 里吞吐约 **66 Mpx/s**（两个 4K 片源都是这个数）；
- **渲染**：往宿主 GPU 传纹理的实测带宽约 **115 MB/s**，4K 一帧 12.4MB ≈ 107ms。

要满帧（24~25fps）需要两者再快 2.5~3 倍。这台模拟器是 **32 位 x86**（`hw.cpu.arch=x86`）、
guest 只能拿到 4 个 vCPU，FFmpeg 的 x86-32 汇编最多用到 SSE4（没有 AVX2 的 16 个 YMM 寄存器），
而且模拟器的 GL 传输走的是老的 `pipe` 通道 —— 这几条都不是应用层能改的。
换成 x86_64 镜像理论上能再快一截，但那要另建 AVD、另编一套内核，
而且会离目标设备（Android 5.1 / ARM）更远，所以没做。

**结论：模拟器可以拿来验逻辑、验音频、验切换、验 1080p，但不能拿来给 4K H.265 做性能验收。**
4K 能不能看，取决于电视的硬解 —— 那是下面 G 要实测的东西。

### F：切换慢——两次 SMB 抢锁

切换首帧里有一部分时间不是花在解码上，而是**后台任务和播放器抢同一条 SMB 连接**
（`SmbStore` 是串行的，全进程一把锁）：

- 起播路径上原来会读 1MB 判音频编码（`checkAudio`）；
- 每次换剧还会重新列一遍剧集目录。

两件事都挪开了：音频判定改到**首帧之后**做（结论一样，代价为零），
目录刷新在缓存还新（10 分钟内）时直接跳过。实测切换首帧从 0.7~1.5 秒降到 **0.4~0.9 秒**。

### G：真机上 4K 能不能放，取决于这个探测

目标机型长虹 CHiQ 43Q3T 是联发科 **MT5520**（4×Cortex-A53 + Mali-T860，Android 5.1），
长虹官方写的是「HEVC/H.265 **4K2K@60** 硬解码」。但 Android 5.1 CDD 只强制
H.264 High@L4.2 + 1080p（剧集那条线安全），**H.265 只强制 Main Profile Level 3（SD 档）**，
Main10/UHD 只是 SHOULD —— 所以只能问设备本身。

更麻烦的是 ijkplayer **对 HEVC 不检查 profile**（源码里 HEVC 分支只看开关，
H.264 分支才逐个 profile 判断），Main10 会被无脑塞给硬解，硬件不认就是
「黑屏但有声音」且不报错。所以上真机第一件事是跑探测：

```powershell
.\build.ps1 connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.firefly.tv.player.CodecCapabilityProbeTest
adb logcat -s FireflyCodec
```

看 `type=video/hevc` 那几行的 `4K=`、`4K@60=`、`Surface可用=`。
另外播放时会把这台设备的**所有候选解码器连同 ijkplayer 给它们的分数**打出来
（`adb logcat -s FireflyDecode | grep 候选解码器`）。注意 `omx.mtk.*` 是**特例**：
ijkplayer 的排名表里给它写死了 `RANK_TESTED = 800`，所以必然入选；
其它没见过的名字才是 `RANK_ACCEPTABLE = 700`（意思是「未知、没测过」）。

### G 补：硬解到底有没有开，程序自己以前也不知道

用户实机反馈「我感觉电视的硬解没开」。查源码发现：ijkplayer 建 MediaCodec 失败时是
**静默回落软解**的 —— `ffpipeline_android.c:73-77` 试建硬解管线，
任何一步失败只打一行 `ALOGE`，然后直接换成 FFmpeg 软解，**Java 层一个回调都没有**。
也就是说「选码回调被调用过」「我设了 `mediacodec-all-videos=1`」都不能证明硬解建成。

真值只有一个：`IjkMediaPlayer.getVideoDecoder()`（= `ffp->stat.vdec_type`），
硬解管线真的建成时才写 2。现在起播 5 秒后会去问一次，发现「选过、却没接上」就
**把那个解码器拉黑**（下次自动换下一个候选）并记一笔，诊断页上直接写
`软解（硬解没建成！）`。

同时修掉一个会**误杀硬解**的策略：原来「硬解 15 秒没出首帧」连续 2 次就本次运行
再也不试硬解 —— 可这 15 秒里可能根本还没数据（SMB 打开 + 16MB 探流 + 搬 moov）。
现在解码器还没拿到数据就先宽限（最多 2 次），并且**超时**与**硬失败**分开计数：
超时只对当前内容生效，换一集/换剧就重新给硬解机会。

## 4K 电视上「按下 OK 只有中间一小块有内容」

用户的原话：「感觉电视上按下 OK 大概就中心那一块有内容，应该是没有缩放」。
同一份 APK 在 1080p 模拟器上排版完全正常。

根因是 `dp`/`sp` 只在**系统 density 与逻辑分辨率自洽**时才等于「占屏比例」，
而这台电视是 4K 面板 + Android 5.1，Android 5.1 上**没有任何框架机制**保证界面按
1080p 渲染（把 UI 卡在 1920 的 `config_maxUiWidth` 是 Android 8.0 才有的东西）。
于是完全可能是「逻辑分辨率 3840×2160，densityDpi 却仍是 320」：
64sp 只画 128px，在 2160 高的屏上占 5.9%，而同一行字在 1080p 上占 11.9%。

修法是**按分辨率与 density 一起算缩放**，而不是信 dp：

```
缩放 = min(宽, 高 × 16/9) / (6 × densityDpi)      // 6 = 1920 / 320
```

| 逻辑分辨率 | densityDpi | 缩放 | |
| :--- | :--- | :--- | :--- |
| 1920×1080 | 320 | 1.00 | 正常 1080p 电视，与改造前逐像素一致 |
| 3840×2160 | **320** | **2.00** | 这台电视的可疑组合 —— 就是它导致「缩在中间」 |
| 3840×2160 | 640 | 1.00 | 真 4K 界面（Chromecast 4K 实测 640）：**不能**再放大 |

> 不能用简单的 `短边/1080`：那会在真 4K 界面上把字放大两倍。
> 4K 界面这一条在 1080p 模拟器上测不出来，所以公式是纯函数 + 8 项单元测试钉住的。

模拟器上可以复现这个故障：`adb shell wm size 3840x2160`（density 留 320）。
修复前浮层时钟量出来 **52px** 高，修复后 **104px**（1080p 基线是 105px）——
两次都截图量了字高，不靠肉眼。

顺带修掉一个同源 bug：`setTextSize` 的像素值缓存在 `TextPaint` 里，而 Activity 声明了
`configChanges="…|density|screenSize"` **不会重建**。所以电视中途切显示模式时，
卡片会按新尺寸重排、**字却还是旧字号**（看起来「卡片变大了，字缩在中间」）。
现在每次显示都会重新对一遍缩放并整棵重建（含重设字号）。

## 直播：换台换不过去 —— 三个根因（2026-09-11）

用户报的：「我尝试从电视[剧]换回直播，又卡住了」。查下来是三个**互相独立**的问题，
每一个都足以让央视那几套频道出不来。

### H：ijkplayer 的 DNS 缓存**只按主机名做键，却把端口一起缓存了**

现象：冷启动后**第一次**换台正常，从第二次开始每次换到央视都是 403 Forbidden，
而且**永远不会自己好** —— 画面冻在上一集/上一个频道的最后一帧。
宿主机直连同一个地址一直是正常的 302 → 200，所以不是源的问题。

频道表里的央视地址是两步的：

```
http://198.51.100.10:82/live/cctv1hd.m3u8        ← 只负责重定向
   ↓ 302 Location
http://198.51.100.10:81/live/cctv1md.m3u8?tm=…&key=…   ← 真正的流（一次性 token）
```

在模拟器网卡上抓包（`adb shell tcpdump -i any -A 'host 198.51.100.10'`），
冷启动那次是**对的**：

```
10.0.2.15 → 198.51.100.10:82  GET /live/cctv1hd.m3u8          → 302
10.0.2.15 → 198.51.100.10:81  GET /live/cctv1md.m3u8?tm=…&key=… → 206 ✔ 出画面
```

之后每一次换台都是**错的**，而且错得很具体：

```
10.0.2.15 → 198.51.100.10:81  GET /live/cctv1hd.m3u8
                             Host: 198.51.100.10:82            → 403 ✘
```

**TCP 连到了 `:81`，请求行和 `Host` 却还是 `:82` 那一份。**

根因在 ijkplayer 给 FFmpeg 打的 patch 里（`libavutil/dns_cache.c`）：

- 键：`av_dict_get(context->dns_dictionary, hostname, …)` —— **只有主机名，没有端口**；
- 值：`new_dns_cache_entry()` 里 `memcpy(new_entry->res->ai_addr, cur_ai->ai_addr, …)`
  —— 把 `getaddrinfo()` 返回的 `sockaddr_in` 整个拷走，**`sin_port` 一起拷**。

命中之后 `libavformat/tcp.c` 的 `tcp_open()` 拿这份缓存直接去
`ff_listen_connect()`，**完全不看 URL 里写的端口**（`restart:` 那段只在
`AF_INET6` 且端口为 0 时补端口，IPv4 没有对应处理）。
第一轮走完 `:82 → :81` 之后，缓存里那条主机名的记录已经带上了 `:81`，
于是后面每一次「先连 `:82`」都被悄悄改成「连 `:81`」，而请求仍然是 `:82` 那份 —— 403。
缓存活到进程结束，所以换多少次台都一样。

修法是**让 tcp 协议别再信这份缓存**（`VideoDecodePolicy.NETWORK_OPTIONS`）：

```kotlin
Opt(Category.FORMAT, "dns_cache_timeout", 0L)   // 整个缓存代码块不再执行
Opt(Category.FORMAT, "dns_cache_clear", 1L)     // 万一超时值在别处被置回正数，每次建连前先清一次
```

两个一起写是双保险。这是 tcp 协议的 AVOption，只能从 `format-opts` 传下去
（写到 PLAYER 类别上会被**静默忽略**，单元测试钉住了这一点）。
修完实测：每次换台都是 `:82 → 302 → :81 → 206`，**403 归零**，CCTV-1 稳定 **25.00 fps**。

> 上游的正解是把键改成 `host:port`，那要重编三个 ABI 的 `libijkffmpeg.so`。
> 用选项修的好处是**一套 APK 同时修好模拟器和电视**，不必再维护一份自编译内核。

### I：直播失败以前**一声不吭**

`PlaybackEngine.handleError()` 里直播分支是这样的：

```kotlin
if (liveReconnect && liveUrlProvider?.invoke() != null) {
    scheduleLiveRetry()
    return            // ← 一次回调都不发
}
```

直播断流由引擎自己退避重连（2 秒起、最多 10 秒一次、永不放弃），这本身是对的；
但它**顺手把界面层也蒙在鼓里** —— 屏幕上就是「上一集冻住的最后一帧 + 按键有反应 +
永远不变」。用户看到的就是死机。

现在多了一个回调 `Listener.onLiveRetry(attempt, url)`，界面层据此说话：

- 前 3 次：「信号中断，正在重连…」
- 之后：「这个频道暂时看不了，还在重试…」（多半不是抖动，是源本身有问题）
- 换台时立刻撤掉（`playChannel()` 里 `showFault(null)`），不会把上一句留在屏幕上

顺带修了同一段里的另一个坑：判据只看 `liveReconnect`，而它**从来没有被置回 false**
（只在换台时置 true）。少了「当前确实在播直播」这一半，用户「先看直播、再回电视剧」
之后，某一集 SMB 读取失败会被误判成直播断流 —— 去重连一个根本没在播的频道，
该出的故障页永远不出来。现在 `kind == LIVE` 是前提之一，插桩测试两个方向都钉住了。

### J：`https://` 的直播源全部起不来（内核没编 OpenSSL）

频道表里 `live.264788.xyz` / `live2.example.com` / `myalicdn` / `cgtn.com` 这些源占了一大半，
它们全是 **https**。而自编的 `libijkffmpeg.so` 没有 openssl，ijkplayer 只会在
logcat 里打一行：

```
W/IJKMEDIA: https protocol not found, recompile FFmpeg with openssl, gnutls or securetransport enabled.
E/IJKMEDIA: https://…: Protocol not found
```

界面上就是一秒不动的黑屏。官方包同样没有 openssl（三个 ABI 都查过，符号数为 0），
所以这是**一直存在**的缺口，不是改出来的。修法是给三个 ABI 补 OpenSSL：

```bash
bash scripts/build-ffmpeg-openssl.sh armv7a arm64 x86
bash scripts/swap-ffmpeg-so.sh <abi> build/ijkbuild/ijkplayer/android/contrib/build/ffmpeg-<abi>/output/libijkffmpeg.so
```

> 这个脚本里有一处必须记住的坑：`compile-ffmpeg.sh` **每次都会从 `extra/ffmpeg`
> 重铺一份源码**，之前打在 `android/contrib/ffmpeg-<abi>/` 上的补丁全部作废。
> 不重打「现代 Linux」补丁，第一刀就死在
> `fatal error: linux/perf_event.h: No such file or directory`。

## 播放记录：每部剧各记各的

用户反馈的原话：「除直播外的其他影视库没有做播放记录功能 —— 无法做到启动时自动切换到
上一次使用的影视库的最近观看（直播则不记录具体时长，只到频道）；无法做到切换到具体影视时
自动跳转到记录时间；考虑到老人不看了会关闭电视，所以要做到关闭不影响，可以有 10s 左右的偏差」。

原来只有**一份全局记忆**（`last_lib` / `last_show` / `last_ep` / `last_pos`），所以：

- 按 ↑↓ 换到别的剧，`Navigator.vertical` 永远返回 `(第 1 集, 0ms)` —— **换回来只能从头看**；
- 换一部剧就把上一部的位置**覆盖掉**。

现在按「**库 + 剧**」各存一条（`WatchHistory`，一行一条存在 `watch_history` 里）：

```
WH1
lib=电视剧
r=电视剧|大宅门|0|286767|1789134750701
r=电视剧|猫和老鼠 50周年珍藏版 157集|0|24940|1789134743020
```

| 时机 | 行为 |
| :--- | :--- |
| 开机 | 回到**上次用的那个库**（直播库也算），并接着看这个库里 `at` 最新的那部剧 |
| 上次用的是直播库 | 直接去直播库，频道号由 `last_ch` 记着（**直播不记时长** —— 那个 position 是从开播算起的假进度，拿它去 seek 会让音画长时间不同步） |
| ↑↓ 换剧 | 换到看过的剧 → 回到上次那一集那一分钟；没看过 → 第 1 集开头 |
| ←→ 换库 | 离开前先写一条，然后接着看目标库里最近看的那部 |
| 播放中 | **每 5 秒**写一次（不是只在退出时写） |
| 关电视/切后台 | `onPause` 立刻再写一次 |
| 换剧/换集/换库 | 当场写一次 |

**为什么要「每 5 秒」**：老人不看了直接关电视，应用不一定拿得到 `onPause`/`onDestroy`。
5 秒一次 → 最坏丢 5 秒，满足「10 秒左右偏差」。写的量很小（整个 prefs 文件十几 KB），
对闪存和流畅度都没有影响。

两个细节：

- **只看了一眼就不续播**（`MIN_RESUME_MS = 15s`）：点开看了 8 秒就换台，
  下次从第 8 秒开始播会让人以为「怎么一打开就跳到中间」。
- **升级会迁移**旧版那份全局记忆（`Config.takeLegacySpot`），用户电视上正存着
  上次看到哪儿，不该从头开始。

## 天气：换成心知天气（seniverse）

原来是和风天气 —— 2026 起每账号一个专属 API Host，配置页要多填一栏，老人自己填不对。
心知只有两栏：**私钥** + **地点**。

### 两个实测出来的坑

1. **要用「私钥」，不是「公钥」。** 控制台给的是一对：公钥拿去请求回
   `AP010003 API 密钥 key 错误`，私钥才对。配置页的提示里写死了这一点。
2. **城市名可能没权限，坐标反而可以。**
   - `location=北京` → `AP010006 没有权限访问这个地点`
   - `location=<纬度:经度>` → 正常返回「**北京**,北京,内蒙古,中国」

   所以默认地点是**市区的坐标**，不是城市名。用户要求「越精确越好，市区没有就北京」，
   而接口对这两个坐标回的就是北京 —— 已经是能拿到的最细一级。

### 频率（用户特意交代过）

免费套餐按次计费，超了回 `AP010014`。策略在 `WeatherClient.shouldFetch`（纯函数、有测试）：

- 成功结果 **30 分钟**内不再问（`POLICY_TTL_MS`）；
- 失败也要 **5 分钟**冷却（`POLICY_RETRY_MS`），不能拿坏 Key 疯狂重试；
- 结果**落盘**（`Config.saveWeatherCache`）—— 重开电视不算「新的一次查询」，
  也不会一开机就是「天气获取中…」。

一天最多几十次，离免费额度很远。

### 易读性

浮层按老人的读法排三行，不是把 JSON 丢出来：

```
现在 多云 17°
今天 阴 转晴 11~27°
明天 多云 9~28°
```

语音念的是「现在是下午三点二十，北京多云，气温十七度，今天十一到二十七度，明天多云，九到二十八度」。

顺手修了一个真 bug：`WeatherClient.test()` 的注释写着「null = 成功」，实现却在成功时
返回「连接成功，当前晴 17 度」这句话，而调用方只认 null —— **配置页把试连成功显示成了失败**。
现在返回值是明确的 `Verdict(ok, message)`。

私钥不写进仓库：走项目已有的那条路（`local.properties` → gradle instrumentation 参数 → 测试），
模板见 `local.properties.example`。

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
.\build.ps1 testDebugUnitTest    # 单元测试（195 项：农历/节气/缓存/导航/按键反馈/格式判定/解码策略/渲染通路/moov 改写/数据源约定/观看记录/天气策略/界面缩放/选码与 10bit 防线/帧率归因）
.\build.ps1 connectedDebugAndroidTest   # 插桩测试（58 项：播放桥接/硬解通路/解码真值/编解码能力探测/轨道探针/配置页/真 NAS 联调/格式实测/SMB 吞吐/直播失败可见性/天气解析）
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

### 出问题时先跑这三个诊断

```powershell
# 1) 网络到底通不通（模拟器里 ping 不通是正常的，NAT 不回 ICMP，只有 TCP 能说明问题）
.\build.ps1 connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.firefly.tv.diag.NetworkDiagTest
adb logcat -s FireflyDiag

# 2) 每种片源到底能不能播、有没有声音
.\build.ps1 connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.firefly.tv.player.FormatMatrixTest
adb logcat -s FireflyFormat

# 3) 【上真机第一件事】这台设备到底能硬解什么：
#    打印每个 video/hevc 解码器的 profileLevels、4K / 4K@60 支持、有没有 COLOR_FormatSurface
.\build.ps1 connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.firefly.tv.player.CodecCapabilityProbeTest
adb logcat -s FireflyCodec
```

播放时另外两条日志值得盯：

```powershell
# 实际用的是哪个解码器（真机期望 MEDIACODEC + OMX.<厂商>.*；模拟器必然 NO_CODEC）
adb logcat -s FireflyTV | Select-String "硬解选码器"

# 这个编码的所有候选解码器 + ijkplayer 给它们的分数
# 电视 SoC 的解码器在 ijkplayer 的已知机型表里没有条目，会拿 700（= 未知、没测过）
adb logcat -s FireflyTV | Select-String "候选解码器"
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
- [调研：4K 电视上的 UI 缩放](docs/RESEARCH-4K-TV-SCALING.md) —— 真机上 DisplayMetrics 到底报什么、
  `config_maxUiWidth` 的版本沿革、四种缩放方案对比、官方 10 英尺可读性建议（每条都带来源链接）
- [调研：ijkplayer 0.8.8 硬解通路](docs/research/ijkplayer-0.8.8-mediacodec-mtk-report.md) ——
  逐行核对源码的选码五道闸、13 种失效模式、完整选项默认值表、哪些选项其实是**空操作**

## 状态

🚧 阶段 1–2 已完成并通过真实 NAS 联调；阶段 3–5 部分待真机验收。
**格式兼容性已逐类实测**（见上「已知限制」）。

模拟器上（AVD `firefly_tv`，API 22 / x86 / 直连真实 NAS）实测：

| 项 | 结果 |
| :--- | :--- |
| 《娘道》1080p H.264 | **23.6 fps**（片源 25fps，满帧） |
| 《猫和老鼠》2960×2160 HEVC | 9.9 fps ← 环境天花板，见「模拟器到不了 24fps」 |
| 《大宅门》3840×2160 HEVC | 8.4 fps ← 同上 |
| 切换首帧（按键 → 画面） | **0.4~0.9 秒** |
| 单元测试 / 插桩测试 | 195 / 58，全绿 |

⏳ **待真机验收的关键一条**：电视的 MediaCodec 能不能硬解 4K H.265
（跑 `CodecCapabilityProbeTest`）。4K 那两部能不能看，取决于它 ——
模拟器没有硬解可用，测不出这一条。

## 明确不做

刮削、海报墙、选集界面、进度条/暂停界面、返回键功能。

> 每增加一个界面，老人就多一次迷路的可能。
