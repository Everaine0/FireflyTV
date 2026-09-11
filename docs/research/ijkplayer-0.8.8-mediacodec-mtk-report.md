# ijkplayer k0.8.8 · MediaCodec 选用路径 / MTK 4K HEVC 播放问题 技术报告

> 结论基于**本地 k0.8.8 源码**（`build/ijkbuild/ijkplayer`，`git describe = k0.8.8`，commit `cced91e`，
> 与 GitHub tag k0.8.8 一致）逐行核对。引用格式 `文件:行号`，对应
> `https://github.com/bilibili/ijkplayer/blob/k0.8.8/<文件>#L<行号>`。
> 未能证实的部分一律标 **【未证实】**。
> 目标机型：长虹 CHiQ 43Q3T / MT5520 / Android 5.1 / 4K 面板 / 2GB RAM / Cortex-A53 ×4。

---

## 0. 速览（先看这 10 条）

1. **硬解不是"选出来"的，是"试出来"的**：`func_open_video_decoder` 先尝试建 MediaCodec 管线，
   任何一步失败就**静默回落到 FFmpeg 软解**，只有一个 `ALOGE`（`ffpipeline_android.c:73-77`）。
   4K HEVC 软解在 A53×4 上必然只有个位数 fps —— **这是"很慢"的第一嫌疑**。
   运行时唯一可靠判据：`IjkMediaPlayer.getVideoDecoder()`（`2`=MediaCodec，`1`=avcodec 软解）。
2. **HEVC 分支没有 profile 检查**（H.264 有）。Main10 / 10-bit 会被原样交给只支持 Main 的解码器，
   configure 不会失败（MediaFormat 里**根本没写 profile/level**），症状就是"有声音没画面/极慢"。
   同症状的官方 issue：**#1364 "4K HEVC, yuv420p10le not working"**。
3. `mediacodec-handle-resolution-change` **只对 H.264 生效**（`vdec.c:514`），对 HEVC 是空操作。
4. `overlay-format='_ES2'` 在 MediaCodec 模式下**完全无效**：AMC 帧的 overlay format 被硬编码为
   `SDL_FCC__AMC`，显示分支直接走 `releaseOutputBuffer(idx,true)`（`overlay_android_mediacodec.c:154`、
   `ijksdl_vout_android_nativewindow.c:168-171`）。
5. `max-fps=61` 会**关掉** ijkplayer 自带的 `is_video_high_fps` 启发式（25fps/50fps 都不 > 61），
   于是 `skip_frame/skip_loop_filter/skip_idct` 从不被设置（`ff_ffplay.c:2974-3000`）。
   而对 HEVC 来说它们本来也几乎没用（见 §6）。
6. `threads=auto` 在 FFmpeg 里 = **CPU 核数 + 1**（4 核 → 5），且 HEVC 会选中**帧级线程** → 2GB RAM
   跑 4K 极危险，同时 **WPP 被关掉**。应显式 `threads=2~3` + `thread_type=slice`。
7. 一个越界的 PLAYER 选项会让 `av_opt_set_dict` **中途 return，剩下的选项全部不生效**，
   而 ijkplayer 不检查返回值（`ff_ffplay.c:4287` + `libavutil/opt.c:1579-1583`）。
   例：`max-buffer-size` > 15MB（`MAX_QUEUE_SIZE`）就属于越界。
8. 所有 `OPT_CATEGORY_PLAYER` 选项**只在 `prepareAsync()` 那一刻生效一次**，之后改无效（§5.3）；
   越界值会让 `av_opt_set_dict` 中途放弃、**其余选项全部不生效**（§5.3 末）。
9. **Surface 相关的两个确定性杀手**：`surfaceDestroyed()` 里不 `setDisplay(null)` → 之后
   `configure failed with err 0xffffffea`，并**无限刷错误、永不降级软解**（§2.10，含维护者给的解法）；
   没有 Surface 就 prepare → **dummy codec 假帧**（§2.1）。
10. k0.8.8 在 MTK 上**缺少 4 个关键修复 PR**（含"4K 硬解异常回落软解"#3461），只有 #3188 被合入（§2.12）。

---

## 1. Q1 — MediaCodec 解码器的确切判定路径与条件

### 1.1 调用链

```
read_thread → stream_component_open(ffp, video_st)          ff_ffplay.c:2807
   └─ avcodec_find_decoder / avcodec_open2 (FFmpeg 解码器一定先打开)  ff_ffplay.c:2832, 2876
   └─ case AVMEDIA_TYPE_VIDEO:                              ff_ffplay.c:2945
        decoder_init(&is->viddec, ...)
        ffp->node_vdec = ffpipeline_open_video_decoder(ffp->pipeline, ffp)   ff_ffplay.c:2965
             └─ func_open_video_decoder                      ffpipeline_android.c:68
                  if (mediacodec_all_videos || avc || hevc || mpeg2)
                       node = ffpipenode_create_video_decoder_from_android_mediacodec(...)
                  if (!node) node = ffpipenode_create_video_decoder_from_ffplay(ffp)   ← 软解回落
```

关键点：**FFmpeg 解码器在 MediaCodec 模式下也一定会被 `avcodec_open2` 打开**
（`ff_ffplay.c:2876`），它承担 extradata 解析 / `ffp->is->viddec.avctx` 的角色；
MediaCodec 只是替换了"出帧"的那条管线。所以 `avcodec_open2` 失败会直接让整个 component 打开失败
（`goto fail`），根本走不到 MediaCodec。

### 1.2 第一道闸：`mediacodec-*` 开关（`ffpipeline_android.c:73`）

```c
if (ffp->mediacodec_all_videos || ffp->mediacodec_avc || ffp->mediacodec_hevc || ffp->mediacodec_mpeg2)
    node = ffpipenode_create_video_decoder_from_android_mediacodec(ffp, pipeline, opaque->weak_vout);
if (!node) {
    node = ffpipenode_create_video_decoder_from_ffplay(ffp);   // 静默软解
}
```

- `mediacodec-mpeg4` **不在闸内**（`ffpipenode_*` 内部的 MPEG4 分支因此永远不可达）。
- `mediacodec` 是 `mediacodec-avc` 的别名（同一个字段 `mediacodec_avc`，`ff_ffplay_options.h:181-188`）。
- 四个开关默认全 0（`ff_ffplay_options.h:185-194`）。
- **注意**：这条闸只看"有没有开"，**不看编码类型**；具体编码在下一道闸里判。

### 1.3 第二道闸：codec_id + profile（`ffpipenode_create_video_decoder_from_android_mediacodec`，`vdec.c:1904`）

入口先判 API level（`vdec.c:1906-1908`，`< IJK_API_16_JELLY_BEAN` 直接返回 NULL）和参数非空。

| codec_id | 行号 | 条件 | 结果 |
|---|---|---|---|
| `H264` | 1944-1946 | `!mediacodec_avc && !mediacodec_all_videos` | fail→软解 |
| `H264` profile | 1948-1991 | 白名单：BASELINE / CONSTRAINED_BASELINE / MAIN / EXTENDED / HIGH。**HIGH_10 / HIGH_10_INTRA / HIGH_422* / HIGH_444* / CAVLC_444 / 未知 profile 一律 `goto fail`** | 10bit H.264 永远软解 |
| `HEVC` | 1997-2005 | 仅 `!mediacodec_hevc && !mediacodec_all_videos` → fail。**没有任何 profile 判断** | Main10 也会走硬解 |
| `MPEG2VIDEO` | 2006-2014 | 仅开关判断 | — |
| `MPEG4` | 2015-2027 | 开关 + `codec_tag & 0xFFFF == 0x5864('XD')` = divx → fail | — |
| default（含 **MJPEG**、VP9、AV1…） | 2029-2031 | `not H264 or H265/HEVC` → fail | **`mediacodec_all_videos=1` 也不会让 MJPEG 走硬解** |

随后写 `mcc`（`ijkmp_mediacodecinfo_context`，`ijkplayer_android_def.h:148-154`）：

```c
char mime_type[128];  // in  : "video/avc" / "video/hevc" / "video/mpeg2" / "video/mp4v-es"
int  profile;         // in  : 来自 codecpar->profile（HEVC Main10 = 2）
int  level;           // in
char codec_name[128]; // out : Java 回调填回来的解码器名
```

- mime 常量在 `ijksdl_codec_android_mediadef.h:54-55`：`"video/avc"` / `"video/hevc"`。
- **结构体里没有 width/height** —— 所以"按分辨率挑解码器"在这层根本不可能（见 §3）。

### 1.4 第三道闸：extradata / AnnexB vs AVCC（`recreate_format_l`，`vdec.c:127-260`，被 2051 调用）

```c
if (opaque->codecpar->extradata && opaque->codecpar->extradata_size > 0) {
    if ((codec_id == AV_CODEC_ID_H264 && extradata[0] == 1)
        || (codec_id == AV_CODEC_ID_HEVC && extradata_size > 3
            && (extradata[0] == 1 || extradata[1] == 1))) {         // vdec.c:182-184
        ... convert_sps_pps(...) / convert_hevc_nal_units(...)       // vdec.c:219 / 226
        SDL_AMediaFormat_setBuffer(input_aformat, "csd-0", convert_buffer, sps_pps_size);
    } else if (codec_id == AV_CODEC_ID_MPEG4) { ... esds ... }
    else { ALOGE("csd-0: naked\n"); }                                // vdec.c:250
}
```

- **只认 AVCC/hvcC 风格（length-prefixed, 首字节 1）的 extradata**。真 AnnexB extradata
  （`00 00 00 01`）会落到 `csd-0: naked`，**不送 csd-0**，解码器多半黑屏或报错。
- `convert_hevc_nal_units`（`hevc_nal.h:28-108`）顺带算出 `nal_size = (extradata[21] & 3) + 1`
  （`hevc_nal.h:47-48`），后面逐包用它把**长度前缀改写成 `00 00 01` 起始码，原地修改**
  （`convert_h264_to_annexb`，`h264_nal.h:113-150`，在 `vdec.c:570` 调用）。
  这个改写只动每 NAL 的前 3~4 字节，CPU 开销可忽略，但**要求包内数据保持 AVCC 布局**。
- 该函数失败（`Input Metadata too small` / `Output buffer too small` / `NAL unit size does not match`）
  → `recreate_format_l` 返回 -1 → `AMC: recreate_format_l failed` → **软解回落**。
- 同一函数里还处理旋转：`mediacodec_auto_rotate` + API≥21 才写 `rotation-degrees`（`vdec.c:253-266`）。

### 1.5 第四道闸：Java 选码器（`ffpipeline_select_mediacodec_l`，`vdec.c:2057`）

```c
if (!ffpipeline_select_mediacodec_l(pipeline, &opaque->mcc) || !opaque->mcc.codec_name[0]) {
    ALOGE("amc: no suitable codec\n");  goto fail;      // vdec.c:2057-2060
}
```

`ffpipeline_select_mediacodec_l`（`ffpipeline_android.c:271-281`）调 Java 的
`OnMediaCodecSelectListener`（默认 `IjkMediaPlayer.DefaultMediaCodecSelector.sInstance`）。
**回调只拿到 mimeType/profile/level，拿不到分辨率**；rank 规则与阈值见 §3。

### 1.6 第五道闸：建 codec → configure(surface) → start（`reconfigure_codec_l`，`vdec.c:274`）

```c
static SDL_AMediaCodec *create_codec_l(...) {
    if (opaque->jsurface == NULL) {
        // we don't need real codec if we don't have a surface
        acodec = SDL_AMediaCodecDummy_create();          // vdec.c:129-132
    } else {
        acodec = SDL_AMediaCodecJava_createByCodecName(env, mcc->codec_name);
    }
    ...
    opaque->quirk_reconfigure_with_new_codec = true;     // vdec.c:150  ← 永远为 true
}
```

- **`jsurface == NULL` 时不报错，而是造一个 dummy codec**（`ijksdl_codec_android_mediacodec_dummy.c`），
  它把每个 input buffer 直接回吐成 `AMEDIACODEC__BUFFER_FLAG_FAKE_FRAME` 的假帧
  （`ijksdl_codec_android_mediacodec_dummy.c:95-98`；`getOutputFormat()` 恒返回 NULL，`:41-44`）。
  **症状 = 有声音、黑屏、无任何 error，`ffp->stat.vdec_type` 仍然是 MEDIACODEC。**
- `SDL_AMediaCodec_configure_surface(..., jsurface, NULL /*crypto*/, 0)`（`vdec.c:324`；configure 路径的另一份在 `vdec.c:389`）→
  失败即 `configure_surface: failed` → 返回 -1 → 软解回落。
- 成功后才写 `ffp->stat.vdec_type = FFP_PROPV_DECODER_MEDIACODEC`（`vdec.c:2081`）与
  `ffp_set_video_codec_info(ffp, MEDIACODEC_MODULE_NAME, codec_name)`（`vdec.c:2068`）。

### 1.7 出帧与 pixel format

```c
frame->format = IJK_AV_PIX_FMT__ANDROID_MEDIACODEC;   // amc_fill_frame
```
（`amc_fill_frame`，`vdec.c:440`；`frame->width/height` 直接取 `codecpar`）
→ `func_create_overlay_l`（`ijksdl_vout_android_nativewindow.c:97-104`）按此 format 走
`SDL_VoutAMediaCodec_CreateOverlay`，overlay format 恒为 `SDL_FCC__AMC`
（`ijksdl_vout_overlay_android_mediacodec.c:154`）。

### 1.8 `IJK_AV_PIX_FMT__ANDROID_MEDIACODEC` / pix_fmt 在"选择"阶段的作用

**答案：没有任何作用。** 全仓库搜索 `pix_fmt` 在选择路径上**没有任何判定**；
唯一的"pix_fmt 检查"是**不存在的**。10-bit（`yuv420p10le`）之所以会走硬解，就是因为这条检查缺失。
（对照：H.264 至少还查了 `codecpar->profile`；HEVC 连 profile 都没查。）

### 1.9 `video-mime-type` / mime 比较只存在于 async 路径

`if (strcmp(opaque->mcc.mime_type, ffp->video_mime_type))` 只出现在
`ffpipenode_config_from_android_mediacodec`（`vdec.c:1800`），而该函数只在
`async_init_decoder=1 && video-mime-type 非空 && mediacodec-default-name 非空` 时才被调用
（`stream_open` 的闸：`ff_ffplay.c:3719-3725`）。**正常同步路径（默认）没有 mime 校验。**
`video_mime_type` 默认 NULL（`ff_ffplay_def.h:829`）。

---

## 2. Q2 — MediaCodec 路径"选上了却不出画/极慢/静音"的已知失效模式

每条给：症状 → 代码机制 → 规避手段。带 🔒 的是我从源码逐行确认的机制；带 📋 的是社区 issue 里的实测报告。

### 2.1 🔒 没有 Surface 就准备 → dummy codec → 黑屏有声
- 机制：`vdec.c:129-132`（见 §1.6）。假帧会被 `SDL_VoutOverlayAMediaCodec_releaseFrame_l`
  在 `BUFFER_FLAG_FAKE_FRAME` 分支直接丢弃、**不调用 `releaseOutputBuffer`**
  （`ijksdl_vout_android_nativewindow.c:392-395`）。
- 症状：音频正常、永久黑屏；`getVideoDecoder()` 仍返回 2；无 `onError`。
- 判别：logcat 里**没有** `AMEDIACODEC__INFO_OUTPUT_FORMAT_CHANGED`（`vdec.c:1129-1141`，只有真 codec 才打）。
- 规避：`setDisplay()/setSurface()` 必须在 `prepareAsync()` **之前**；ijkplayer 不会自愈。

### 2.2 📋 长虹 MTK 电视 + SurfaceView 硬解黑屏，TextureView 正常
- issue **#3181**（长虹 43U3C，**Android 5.1.1**，API 22）："ijkplayer 硬解：surfaceView 模式出现黑屏但有声音，
  textureView 模式正常播放"；同帖评论："测试发现 OMX.rk 解码出来的数据可以用 TextureView 渲染，
  其他类型不行，**至少 OMX.mtk 不行**"。
  <https://github.com/bilibili/ijkplayer/issues/3181>
- 这是与目标机型（同为长虹 + Android 5.1 + MTK）**最贴近**的一条实测证据。
- 规避：电视端用 `TextureView`；或改用 ijkplayer 之外的渲染路径（MXPlayer 能正常播，见该 issue 描述）。

### 2.3 ⚠️ 厂商 VPU 首次初始化失败 → 第一次黑屏、重播正常（**SoC 归属已修正**）
- issue **#2675**：4K 视频硬解第一次黑屏，退出再播正常；日志含
  `E/vpu: VPUClient: ioctl VPU_IOC_SET_REG failed ret -1 errno 14 Bad address`、
  `mark hardware decoder error`（后者**不是** ijkplayer 的字符串，`grep` 全仓库无命中，来自上报者自己的壳/系统）。
- **修正**：`VPUClient` / `VPU_IOC_SET_REG` 是 **Rockchip VPU** 驱动的日志（另有版本该日志中出现
  `OMX.rk.video_decoder.hevc`），**不能当作 MTK 证据**。此处只保留"厂商 VPU 首次建 codec 失败、
  第二次成功"这一**现象级**结论，SoC 归属标 **【未证实/疑为 Rockchip】**。
  <https://github.com/bilibili/ijkplayer/issues/2675>
- 规避：失败后自动重试一次（应用层）；或用 `mediacodec-default-name` 指定另一个 codec。

### 2.4 🔒/📋 10-bit HEVC（Main10）硬解黑屏有声，软解正常但极慢
- 机制：HEVC 分支无 profile 闸（`vdec.c:1997-2005`），且 **MediaFormat 里根本没写 profile/level**
  ——`SDL_AMediaFormatJava_createVideoFormat` 只 `createVideoFormat(mime, w, h)`
  （`ijksdl_codec_android_mediaformat_java.c:186-203`），后续只加 `csd-0` 和 `max-input-size=0`。
  所以"Main10 交给只支持 Main 的解码器"在 configure() 阶段**不会报错**。
- 实测：issue **#1364 "4K HEVC, yuv420p10le not working"**（Nexus 6P，k0.4.5.1）：
  "When I play it, I see only black screen, but sound plays normally. With mediaCodec disabled
  it is playing normally, but too slow (as expected)." ——与本项目"4K 很慢/黑屏"完全同型。
  <https://github.com/bilibili/ijkplayer/issues/1364>
- 规避：**没有开关能修**。只能在应用层先读
  `MediaCodecInfo.getCapabilitiesForType("video/hevc").profileLevels`，确认是否含
  `HEVCProfileMain10`（若不含则拒绝硬解/拒绝该文件），或换 8-bit 片源。
  ijkplayer 自己不做这件事（§4）。

### 2.5 🔒 大于输入缓冲的包会被**截断**，尾部被当成第二个 buffer 入队
- 机制链：
  `writeInputData` 取 `min(size, GetDirectBufferCapacity)`（`ijksdl_codec_android_mediacodec_java.c:222`）；
  调用方把返回值当作"已消费字节数"，把剩余部分留到下一轮
  （`vdec.c:647` → `vdec.c:681-685`：`pkt_temp.data += copy_size; pkt_temp.size -= copy_size;`），
  而下一轮会用**同一个 pts** 再 `queueInputBuffer` 一次（`vdec.c:655-664`）。
- 后果：一个 4K HEVC 关键帧若超过 codec 输入缓冲，会被拆成两个"访问单元"喂进去 →
  解码器报错/花屏/该 GOP 报废；表现为**每个关键帧处卡一下/花一下**。
- 火上浇油：ijkplayer 自己把 `max-input-size` 显式写成 **0**
  （`ijksdl_codec_android_mediaformat_java.c:202`），某些 OMX 实现会因此给很小的输入缓冲。
  **MTK 是否如此【未证实】**，但截断逻辑本身是确定的。
- 规避：无选项可修。可考虑降低关键帧体积（重编码/`-g` 调小）或打补丁按剩余空间喂包。

### 2.6 🔒 `mediacodec-handle-resolution-change` 对 HEVC 完全无效
- `if (opaque->ffp->mediacodec_handle_resolution_change && opaque->codecpar->codec_id == AV_CODEC_ID_H264)`
  （`vdec.c:514`）——**H.264 only**。
- HEVC 中途换分辨率：不会被识别，`aformat_need_recreate` 也不会置位 → 仍按老分辨率继续 configure，
  典型症状是花屏/绿屏/尺寸错乱。
- 反过来，`quirk_reconfigure_with_new_codec = true` 恒定（`vdec.c:150`、`vdec.c:374`）意味着
  **任何一次真正的 reconfigure 都会重建 MediaCodec**（MTK 上 create+configure 通常在数百 ms 量级）——
  这正是很多项目把 `mediacodec-handle-resolution-change` 设为 0 来规避卡顿的原因。
- 对 HEVC 4K 固定分辨率片源：这个开关是**空操作**，设不设都一样。

### 2.7 🔒 `mediacodec-sync` 改变线程模型（不是"同步/异步"那么简单）
- `mediacodec_sync=0`（默认）：`func_run_sync` 起一个**独立输入线程** `amediacodec_input_thread`
  （`vdec.c:1562-1671` 区间，`SDL_CreateThreadEx(..., enqueue_thread_func, ...)`），
  超时常量 `AMC_INPUT_TIMEOUT_US = AMC_OUTPUT_TIMEOUT_US = 100ms`（`vdec.c:52-53`）。
- `mediacodec_sync=1`：`func_run_sync_loop` 单线程交错 dequeue/feed，超时 30ms
  （`vdec.c:1513-1560`，常量 `vdec.c:55-56`），**且 `ret = drain_output_buffer2(...)` 的返回值
  立刻被下一行 `ret = feed_input_buffer2(...)` 覆盖** → drain 的错误被吞掉。
- 实践含义：`mediacodec-sync=1` 是社区常用的"某些机器不出帧/卡死"规避手段
  （issue 搜索里 `mediacodec-sync` 有 41 条命中），代价是吞吐下降、错误不可见。
  **对 MTK 5.x 是否为净收益【未证实】**，建议 A/B 实测。

### 2.8 🔒 "静音但有画面"的确定路径（非 MTK 专属，但代码上确实存在）
- `read_thread` 里 `stream_component_open(ffp, st_index[AVMEDIA_TYPE_AUDIO]);` 的**返回值被忽略**
  （`ff_ffplay.c:3274`），而 AUDIO 分支里 `audio_open()` 失败会 `goto fail`
  （`ff_ffplay.c:2918-2919`）→ 该流的 `avctx` 被释放、`is->audio_st` 未设置 → **静音但视频继续**，
  且不产生 `onError`。
- 音频输出设备由 `opensles` 决定：`ffpipeline_android.c:85-89`，`opensles=0` → `AudioTrack` 路径。
- **MTK/Android 5.x 专属的静音 bug【未证实】**：我没有找到可引用的 MTK 静音实测报告。

### 2.9 🔒 `framedrop` 的语义陷阱（想"丢帧救帧率"时必须知道）
```c
if (ffp->framedrop > 0 || (ffp->framedrop && ffp_get_master_sync_type(is) != AV_SYNC_VIDEO_MASTER)) {
    ffp->stat.decode_frame_count++;
    ...
    if (is->continuous_frame_drops_early > ffp->framedrop) {
        is->continuous_frame_drops_early = 0;      // 到上限就强制显示一帧
    } else { ...丢... }
}
```
（软解路径：`get_video_frame`，`ff_ffplay.c:1705-1725`；
AMC 路径：`func_run_sync` 内 `vdec.c:1618-1633`，sync 模式在 `drain_output_buffer2` 内 `vdec.c:1477-1492`
—— 两条路径**各自**累加，不会重复计数）
- `framedrop=0`（默认）→ **计数器完全不累加**，`drop_frame_rate` 恒为 0/未定义。
- `framedrop=N` → 最多连续丢 N 帧就必须显示 1 帧。
- `framedrop=-1`（选项范围允许）：`framedrop > 0` 为假，但 `-1` 为真值 → 进块、`decode_frame_count++`；
  然而 `continuous_frame_drops_early` 自增后最小为 1，而 `1 > -1` **恒真** → 每次都走"重置"分支，
  **永不进入丢帧分支**。即 **-1 = 只统计、不丢帧**（这一点常被误读成"更激进地丢帧"）。
- 结论：要"尽量跟上"用 `framedrop=1~3`，而不是 `-1`。

### 2.10 🔒 Surface 生命周期错误 → configure 失败 → **无限刷错误、永不出帧**
（这是上面 §1.6 "第五道闸"失败时的实际行为，代码可证）
- issue **#3021**（同段日志里出现 `OMX.MTK.VIDEO.DECODER.AVC`，MTK 设备）：
  ```
  E/BufferQueueProducer: [SurfaceTexture-0-25339-0] connect(P): already connected (cur=3 req=3)
  E/MediaCodec: configure failed with err 0xffffffea, resetting...
  E/IJKMEDIA: reconfigure_codec_l:configure_surface: failed
  E/IJKMEDIA: feed_input_buffer: reconfigure_codec failed
  E/IJKMEDIA: SDL_AMediaCodecJava_dequeueInputBuffer: dequeueInputBuffer failed   ←此后无限刷
  ```
  <https://github.com/bilibili/ijkplayer/issues/3021>
- **代码机制（关键）**：`reconfigure_codec_l` 失败时 ijkplayer **把错误吃掉并继续循环**：
  ```c
  ret = reconfigure_codec_l(env, node, new_surface);
  if (ret != 0) {
      ALOGE("%s: reconfigure_codec failed\n", __func__);
      ret = 0;                 // ← 吞掉错误
      goto fail;               // ← fail 标签只是 return ret(=0)
  }
  ```
  （`vdec.c:615-623`，位于 `feed_input_buffer2`；外层 `enqueue_thread_func` 的
  `while (!q->abort_request && !opaque->abort)` 会立刻再调一次，`vdec.c:1035-1040`）
  → 表现为**永久无画面 + 日志疯狂重复**，且**不会降级到软解**。
- issue **#2308**（MTK 专属错误日志，`OMX.MTK.VIDEO.DECODER.AVC ERROR(0x80001005)` →
  `IllegalStateException at MediaCodec.native_dequeueOutputBuffer`）里，
  **维护者 bbcallen 给出了唯一一条官方回复**：
  > "If onSurfaceCreated() is called when navigate back to VideoActivity. There must be an
  > onSurfaceDestroyed() before that. **Try setDisplay(NULL) in onSurfaceDestroyed().**"
  <https://github.com/bilibili/ijkplayer/issues/2308>
- 规避：`SurfaceHolder.Callback.surfaceDestroyed()` 里先 `setDisplay(null)`；
  避免"同一个 Surface 被重复 connect"（详见 `ffpipeline_set_surface`，`ffpipeline_android.c:208-242`）。

### 2.11 🔒 MTK 解码器输出的 stride 缺陷（花屏/错位而非掉帧）
- MrMC（Kodi 分支）开发者 davilla 明确指出 Fire TV 2（MT8173，**Android 5.1**）的
  `OMX.MTK.VIDEO.DECODER.HEVC` 在 MediaCodec 上 **stride 报错**（1936×1086 这类非标准宽度）：
  > "The issue is a bug in the FireTV2's OMX.MTK.VIDEO.DECODER.HEVC hw decoder for MediaCodec.
  > The stride is wrong. Plays fine under Nvidia shield."
  <https://forum.mrmc.tv/viewtopic.php?p=5995>
- 相关代码事实：`INFO_OUTPUT_FORMAT_CHANGED` 里 ijkplayer **读了 stride / slice-height / crop 但只打日志，
  不用它们修正 frame 尺寸**（`vdec.c:1104-1141`，注释写着 `// TI decoder could crash after reconfigure`）。
  → 解码器只要报错 stride，画面必然错位/花屏，**没有开关能修**。

### 2.12 📋 MTK 硬解相关的 PR 合并状态（决定 k0.8.8 里有没有这些修复）
- ijkplayer 仓库 `is:pr mtk` = **0 结果**；mediacodec 相关 PR 里 **只有 #3188 被合并**
  （vendor OMX "one instance" 限制 → reconfigure 时先 release 再 create；
  k0.8.8 的 `reconfigure_codec_l` 里已有 `SDL_AMediaCodec_decreaseReferenceP(&opaque->acodec)`
  这一步，`vdec.c:308-312`，即**该修复已在 k0.8.8**）。
- **k0.8.8 缺少的四个修复**（均未合并）：
  [#4395](https://github.com/bilibili/ijkplayer/pull/4395) 切 Surface 后硬解失败（首帧非 I 帧，关联 #3181）、
  [#4343](https://github.com/bilibili/ijkplayer/pull/4343) HLS TS 分片 SPS 解析导致 MediaCodec 初始化失败、
  [#3461](https://github.com/bilibili/ijkplayer/pull/3461) 4K 硬解异常时回落软解、
  [#4562](https://github.com/bilibili/ijkplayer/pull/4562) dequeue 期间 acodec 置空的空指针。
  → 若要自建 AAR，**这四个 patch 值得评估是否 cherry-pick**（尤其 #3461，正对"4K 硬解异常"场景）。

### 2.13 🔒/📋 其它同症状的 ijkplayer issue（仅标题级证据，未逐条读正文）
| issue | 标题 | 链接 |
|---|---|---|
| #4601 | Android下 4K视频卡顿，使用MediaPlayer正常 | <https://github.com/bilibili/ijkplayer/issues/4601> |
| #2629 | 4K高清视频无法使用硬解播放 | <https://github.com/bilibili/ijkplayer/issues/2629> |
| #3893 | 相同码率、相同fps，增加分辨率视频为什么会卡顿 | <https://github.com/bilibili/ijkplayer/issues/3893> |
| #2723 | [OMX.hisi.video.decoder] 硬解4K视频失败 | <https://github.com/bilibili/ijkplayer/issues/2723> |
| #1954 | 安卓硬解码性能问题 | <https://github.com/bilibili/ijkplayer/issues/1954> |
| #3201 | 硬件解码切换分辨率的问题 | <https://github.com/bilibili/ijkplayer/issues/3201> |
| #923 | 播放hevc时只有声音没图像 | <https://github.com/bilibili/ijkplayer/issues/923> |
| #4177 | android k0.8.4 某些手机播放视频有声音、无画面 | <https://github.com/bilibili/ijkplayer/issues/4177> |

---

## 3. Q3 — `IjkMediaCodecInfo.setupCandidate` 到底排什么（k0.8.8）

文件：`android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IjkMediaCodecInfo.java`

### 3.1 rank 常量（第 18-25 行）

| 常量 | 值 | 含义 |
|---|---|---|
| `RANK_MAX` | 1000 | 未使用（没有任何分支给出 1000） |
| `RANK_TESTED` | 800 | 实测可用（含**所有** `omx.mtk.`） |
| `RANK_ACCEPTABLE` | 700 | 未知名字，但 `getCapabilitiesForType(mime)` 成功 |
| `RANK_LAST_CHANCE` | 600 | **接受阈值**（见 3.3） |
| `RANK_SECURE` | 300 | 仅 Nvidia `.secure` 显式列出 |
| `RANK_SOFTWARE` | 200 | `omx.google.*` / `omx.ffmpeg.*` / `omx.pv` / `omx.avcodec.*` / `omx.k3.ffmpeg.*` 等 |
| `RANK_NON_STANDARD` | 100 | 名字不以 `omx.` 开头 |
| `RANK_NO_SENSE` | 0 | `omx.ittiam.` / MTK 且 SDK<18 |

### 3.2 判定规则（`setupCandidate`，第 137-195 行）——**纯字符串匹配**

```
codecInfo==null 或 SDK<16            → null
name 为空                             → null
name.toLowerCase(Locale.US)
!startsWith("omx.")                   → 100（NON_STANDARD）
startsWith("omx.pv")                  → 200
startsWith("omx.google.")             → 200
startsWith("omx.ffmpeg.")             → 200
startsWith("omx.k3.ffmpeg.")          → 200   ← 注意在 omx.google 之后，实际先命中 k3 规则也不影响值
startsWith("omx.avcodec.")            → 200
startsWith("omx.ittiam.")             → 0
startsWith("omx.mtk.")                → SDK<18 ? 0 : 800        ← ★ 关键
否则查 knownCodecList（第 45-133 行）：
   命中 → 表里的值
   未命中 → try getCapabilitiesForType(mimeType) 非 null → 700；抛异常/为 null → 600
```

**黑名单/白名单都是硬编码表，不是设备黑名单**：`sKnownCodecList` 只有 ~25 条
（Nvidia / Intel / qcom / SEC / Exynos / 海思 k3+IMG / TI DUCATI1 / rk / amlogic / Marvell / google / ffmpeg / sprd），
表里**没有任何一条 MTK 条目**——MTK 全靠 `startsWith("omx.mtk.")` 这条前缀规则拿 800。
更关键的是：`sKnownCodecList.put(...)` 之后紧跟一大堆
`sKnownCodecList.remove("OMX.Action.Video.Decoder")` 之类（第 101-121 行）——
这些是"先 put 再 remove"，**等于把白名单条目删掉了**（Action / allwinner / BRCM / hantro / hisi /
LG / MS / RENESAS / RTK / sprd / ST / vpu / WMT / bluestacks 全部被删），
所以这些厂牌的解码器会落到"未知 → 700 或 600"。

### 3.3 接受阈值（`IjkMediaPlayer.DefaultMediaCodecSelector.onMediaCodecSelect`，第 1224-1281 行）

```java
int numCodecs = MediaCodecList.getCodecCount();                    // 1233 已废弃 API
for (...) {
    if (codecInfo.isEncoder()) continue;                           // 1237
    for (String type : codecInfo.getSupportedTypes()) {
        if (!type.equalsIgnoreCase(mimeType)) continue;             // 1249
        IjkMediaCodecInfo candidate = IjkMediaCodecInfo.setupCandidate(codecInfo, mimeType);
        if (candidate == null) continue;
        candidateCodecList.add(candidate);
        Log.i(TAG, "candidate codec: %s rank=%d");                  // 1257
        candidate.dumpProfileLevels(mimeType);                      // 1258 只打日志
    }
}
...
bestCodec = rank 最大者;                                            // 1266-1272（严格 >，平手取列表靠前者）
if (bestCodec.mRank < IjkMediaCodecInfo.RANK_LAST_CHANCE) {          // 1274
    Log.w(TAG, "unaccetable codec: ...");  return null;              // → 上层 "amc: no suitable codec" → 软解
}
return bestCodec.mCodecInfo.getName();
```

**阈值 = rank ≥ 600**。因为 `omx.mtk.*` = 800，MTK 上**任何** `omx.mtk.` 解码器都必然通过阈值，
包括 `OMX.MTK.VIDEO.DECODER.AVC.secure` —— `.secure` **没有**被任何规则特殊处理
（只有 Nvidia 那条表项是 SECURE），它照样按前缀拿 800。
平手时取 `MediaCodecList` 枚举顺序里的**第一个**（严格 `>`），所以"选到 secure 变体还是普通变体"
取决于系统枚举顺序，ijkplayer 不干预。

### 3.4 是否检查 `CodecCapabilities`？

- `profileLevels`：**只用于打印**。`dumpProfileLevels`（第 197-225 行）取 maxProfile/maxLevel 后
  `Log.i(TAG, getProfileLevelName(...))`，**不参与筛选**。
- `isSizeSupported` / `VideoCapabilities` / `getVideoCapabilities`：**全仓库 0 处引用**
  （`grep -rn "isSizeSupported\|VideoCapabilities" --include=*.java .` 无结果）。
- `COLOR_FormatSurface`：**全仓库 0 处引用**。它只作为常量出现在"color-format 名字打印表"里：
  `ijksdl_codec_android_mediadef.h:185`（`AMEDIACODEC__OMX_COLOR_FormatSurface = 0x7f000789`）与
  `ijksdl_codec_android_mediadef.c:122`。你日志里"某 codec 是否支持 COLOR_FormatSurface"是**应用自己查的**，
  ijkplayer 内部不用它做任何决定。

---

## 4. Q4 — ijkplayer 会不会问 MediaCodec "你支持 Main10 / 这个尺寸吗"？

**不会。** 三层都不问：

1. **Java 选择层**：只拿 `mimeType/profile/level` 三个入参（`ijkmp_mediacodecinfo_context`，
   `ijkplayer_android_def.h:148-154`），**没有宽高**，因此无法调 `isSizeSupported`；
   `profile/level` 只进日志和 `dumpProfileLevels`。
2. **configure 层**：MediaFormat 里**不写 profile / level / color-format**
   （`ijksdl_codec_android_mediaformat_java.c:186-203`，只 mime+width+height，之后加 csd-0 与
   `max-input-size=0`）。所以"profile 不支持"在 configure() 处**不可能被框架拦下**。
3. **native 层**：HEVC 分支没有 profile 判断（`vdec.c:1997-2005`），
   H.264 分支则有（`vdec.c:1948-1991`）。

### 4.1 Main 解码器收到 Main10 流会发生什么？

- configure/start 大概率**成功**（没有 profile 提示），然后：
  - 要么 `dequeueOutputBuffer` 永远 `TRY_AGAIN_LATER`（`vdec.c:1144-1146` 只打 AMCTRACE），
  - 要么吐出错帧/绿屏。
- ijkplayer **对"永远没有输出"没有任何超时/降级逻辑**：
  `func_run_sync` 的循环条件是 `while (!q->abort_request)`（`vdec.c:1599`，sync 版在 `vdec.c:1538`），
  没有"N 秒无输出就回落软解"。→ 表现就是**永久黑屏有声**（issue #1364）或**极慢**。
- 唯一会"自动降级"的情况是 **create/configure/start 抛异常**（那才会 `goto fail` → 软解）。

### 4.2 MT5520 / MT55xx 的 HEVC 能力（分证据等级）

| 结论 | 证据 | 等级 |
|---|---|---|
| MT5520 真实存在，长虹 Q3T 宣传页写明"MT5520 芯片…四核 Cortex A53…2 核 T860 GPU…HEVC/H.265 4K2K 格式 60 帧硬解码…4K VP9" | 长虹 Q3T 发布会讲稿（ZNDS 存档）<https://www.znds.com/tv-392099-1-1.html> | 厂商宣传（一手） |
| MT5520 也被联想 17TV 55i2 采用（A53 + Mali-T860 + 2GB + HDR） | <https://tech.china.com.cn/elec/20160607/232412.shtml>、<https://m.techweb.com.cn/article/2016-06-07/2344622.shtml> | 媒体（两家互证） |
| 长虹 43Q3T 官方参数：3840×2160、Mali-T860、Android 5.1、支持 HDR；ZOL 补 RAM=2GB、刷新率 60Hz、上市 2016-04 | <https://b2b.changhong.com/AgentProducts/Details/260841>、<https://detail.zol.com.cn/series/314/1589/param_20232_115acfba_0_1.html> | 官方/电商 |
| 43Q3T 机芯号 **ZLM65HiS2**（同机芯覆盖 43~75Q3T），可用于识别固件/平台 | <https://www.znds.com/archiver/tid-1217460.html> | 社区（可交叉验证） |
| **MT5520 是否支持 HEVC Main10 / 10-bit：无官方 datasheet，【未证实】** | 联发科官网无 MT5520 产品页；Wayback CDX 前缀枚举（`/en/products/home-entertainment/digital-tv/dtv/`）里只有 mt5301/5366/5367/5389/5395/5396/5398/5505/5580，**无 mt5520**；`archive.org/wayback/available?url=mediatek.com/products/digital-tv/mt5520` 零快照 | 未证实 |
| 同代同架构旁证：MT5596（A53×4 + Mali-T860 MP2）媒体稿称"4K H.265/VP9、**10-bit**、4K/60fps"；MT5597 **官方页**列 HDR10 (SMPTE2084)/HLG/Dolby Vision（HDR10 必然是 Main10 解码） | <https://tech.ifeng.com/a/20160116/41540379_0.shtml>、<https://www.pcpop.com/article/1749403.shtml>、<https://www.mediatek.com/zh-tw/products/pentonic/mt5597>、<https://en.wikipedia.org/wiki/List_of_MediaTek_systems_on_chips#Smart_TV_SoCs> | 同级旁证 |
| ⚠️ 长虹 Q3T 文案里的"10bit（10.7 亿色彩深度）…相比 8bit（1670 万色彩深度）**屏**"是**面板/色彩深度营销话术**，**不能**当成 HEVC Main10 解码能力 | 同上 ZNDS 存档 | 需注意的误读 |
| Android 5.1 AOSP 自带的 **Google 软件 HEVC 解码器只声明 `ProfileMain` 且 `max="2048x2048"`** | `frameworks/av/media/libstagefright/data/media_codecs_google_video.xml` @ android-5.1.1_r38 <https://android.googlesource.com/platform/frameworks/av/+/android-5.1.1_r38/media/libstagefright/data/media_codecs_google_video.xml> | 一手 |
| Android 5.x 上真实存在的 MTK 解码器名：`OMX.MTK.VIDEO.DECODER.HEVC`、`OMX.MTK.VIDEO.DECODER.AVC`，且 HEVC 解码器**确实以 `COLOR_FormatSurface`(0x7f000001) 输出到 Surface** | 真机日志：<https://forum.mrmc.tv/viewtopic.php?p=5995>（Fire TV 2 / MT8173 / Android 5.1）、<https://github.com/solid-software/flutter_vlc_player/issues/221>、<https://github.com/bilibili/ijkplayer/issues/2308> | 一手日志 |
| ❌ **不存在** `OMX.MTK.VIDEO.DECODER.HEVC.hw` 这类 `.hw` 变体（`.hw.` 是 Broadcom 命名习惯，如 `OMX.brcm.video.h264.hw.decoder`）；MTK 的 `.sw.` 后缀同样无证据 | 见 `IjkMediaCodecInfo.java` 的已知表 + 上述日志枚举 | 未找到证据 |
| ⚠️ 不能在代码里硬编码 MTK codec 名 | 见上；用 mime `video/hevc` + 运行时枚举 | — |

**结论**：MT5520 规格上具备 4K H.265（含 VP9）硬解、宣传 4K2K@60；
**Main10 支持未证实**（同级 MT5596/MT5597 支持 10-bit，但那是推断）。
即便芯片支持，MTK Android 5.x 的 OMX 在 4K/10-bit 上能否跑到 25fps 也无从查证。
→ **工程上唯一可靠办法是运行时读 `profileLevels` 用事实说话**（下面给可执行判据）。
⚠️ **同时注意**：`profileLevels` **不能**从 `media_codecs.xml` 推出（AOSP 的 angler 文件里 HEVC 条目
根本没有 `<ProfileLevels>` 声明），它是 OMX 组件运行时上报的 —— 必须上机 dump
（`dumpsys media.codec` 或 `MediaCodecList`）。

### 4.3 建议的运行时判据（应用侧，不依赖 ijkplayer）

```kotlin
// 1) 这个 SoC 到底会不会解 10bit HEVC
val caps = MediaCodecInfo.codecCapabilitiesForType("video/hevc")   // API 21+ 用 MediaCodecList(REGULAR_CODECS)
val main10 = caps.profileLevels.any { it.profile == CodecProfileLevel.HEVCProfileMain10 }
// 2) 这个尺寸是否支持
val vc = caps.videoCapabilities
val ok4k = vc?.isSizeSupported(3840, 2160) == true
// 3) 容器的 profile：用 MediaExtractor 读 MediaFormat.KEY_PROFILE（API 24+ 才有常量，21 上可用字符串 "profile"）
```
**注意**：ijkplayer 的 `OnMediaCodecSelectListener` **拿不到宽高**，所以"按分辨率否决某个 codec"
只能在应用自己的回调里做——但回调签名里没有分辨率，只能**全局缓存"当前要播的文件的分辨率/profile"**再在
回调里做判断（callback 是在 `prepareAsync` 的同一流程里被调用的，时序上可行）。

⚠️ **另一个坑**：`IjkMediaCodecInfo.getProfileName()` / `getLevelName()`
（`IjkMediaCodecInfo.java:232-292`）**只实现了 AVC 的 profile/level 名称**，
没有任何 HEVC case → 对 HEVC 解码器它只会打印 `Unknown Profile Level 0 (x,y)`。
**所以不要用 ijkplayer 的 `profile-level:` 日志判断 HEVC 是 Main 还是 Main10**，
必须读原始数字：`HEVCProfileMain=0x01`、`HEVCProfileMain10=0x02`、`HEVCProfileMain10HDR10=0x1000`
（`CodecProfileLevel` 常量，API 21 起可用）。

⚠️ 另需知道：Android 5.1 平台自带的 `OMX.google.hevc.decoder` 只声明 Main 且
`max="2048x2048"`（AOSP `media_codecs_google_video.xml` @ android-5.1.1_r38）——
它 rank=200 会被 ijkplayer 阈值（≥600）拒掉，所以**本机不存在任何"MediaCodec 软解 HEVC 4K"的后路**；
一旦 MediaCodec 路线失败，落到的是 **ijkplayer 自带的 FFmpeg 软解**（另一套东西，与 MediaCodec 无关）。

---

## 5. Q5 — 性能相关选项：默认值、何时被读、推荐值

### 5.1 `ff_ffplay_options.h`（k0.8.8）里的**全部**相关选项

> `OPTION_INT(default, min, max)`；`OPTION_STR(default)` 的 min/max 均为 0（不校验）。
> 表中 `OFFSET` 指 `FFPlayer` 结构体字段。

| 选项名 | 默认 | min | max | 说明（原文字段） | 行号 |
|---|---|---|---|---|---|
| `an` / `vn` / `nodisp` | 0 | 0 | 1 | disable audio / video / display | 63-70 |
| `volume` | 100 | 0 | 100 | 启动音量 | 71-72 |
| `fast` | **0** | 0 | 1 | "non spec compliant optimizations"（置 1 → `avctx->flags2 |= AV_CODEC_FLAG2_FAST`，`ff_ffplay.c:2862-2863`） | 74-75 |
| `loop` | 1 | INT_MIN | INT_MAX | 循环次数 | 77-78 |
| `infbuf` | **0** | 0 | 1 | "don't limit the input buffer size" | 79-80 |
| `framedrop` | **0** | **-1** | **120** | "drop frames when cpu is too slow" | 81-82 |
| `seek-at-start` | 0 | 0 | INT_MAX | — | 83-84 |
| `subtitle` | **0** | 0 | 1 | 解字幕流 | 85-86 |
| `rdftspeed` | 0 | 0 | INT_MAX | — | 94-95 |
| `find_stream_info` | 1 | 0 | 1 | — | 99-100 |
| `max-fps` | **31** | **-1** | **121** | "drop frames in video whose fps is greater than max-fps" | 103-104 |
| `overlay-format` | `SDL_FCC_RV32`(0x32315652) | INT_MIN | INT_MAX | 含 `fcc-_es2`/`fcc-i420`/`fcc-yv12`/`fcc-rv16`/`fcc-rv24`/`fcc-rv32` 常量 | 106-114 |
| `start-on-prepared` | 1 | 0 | 1 | — | 116-117 |
| `video-pictq-size` | **3** (`VIDEO_PICTURE_QUEUE_SIZE_DEFAULT`, min 3, max 16) | 3 | 16 | 图像队列长度 | 119-122 |
| `max-buffer-size` | **15 MB** (`MAX_QUEUE_SIZE = 15*1024*1024`) | 0 | 15 MB | 预读上限 | 124-125 |
| `min-frames` | **50000** (`DEFAULT_MIN_FRAMES`) | 2 | 50000 | 停止预读的最小帧数 | 126-127 |
| `first/next/last-high-water-mark-ms` | 100 / 1000 / 5000 | 100 | 5000 | 唤醒 read_thread 的时机 | 128-142 |
| `packet-buffering` | **1** | 0 | 1 | "pause output until enough packets have been read after stalling" | 144-145 |
| `sync-av-start` | 1 | 0 | 1 | — | 146-147 |
| `no-time-adjust` | 0 | 0 | 1 | — | 150-151 |
| `preset-5-1-center-mix-level` | √½ | -32 | 32 | — | 152-153 |
| `enable-accurate-seek` | 0 | 0 | 1 | — | 155-156 |
| `accurate-seek-timeout` | `MAX_ACCURATE_SEEK_TIMEOUT` | 0 | 同 | — | 157-158 |
| `skip-calc-frame-rate` | 0 | 0 | 1 | 不计算真实帧率（作为 format 选项下发，`ff_ffplay.c:3114`） | 159-160 |
| `async-init-decoder` | **0** | 0 | 1 | 见 §1.9 | 163-164 |
| `video-mime-type` | **NULL** | — | — | 仅 async 路径用 | 165-166 |
| `mediacodec` (=`mediacodec-avc`) | **0** | 0 | 1 | — | 181-188 |
| `mediacodec-all-videos` | **0** | 0 | 1 | — | 185-186 |
| `mediacodec-hevc` | **0** | 0 | 1 | — | 189-190 |
| `mediacodec-mpeg2` | **0** | 0 | 1 | — | 191-192 |
| `mediacodec-mpeg4` | **0** | 0 | 1 | **管线闸里没它（§1.2）** | 193-194 |
| `mediacodec-handle-resolution-change` | **0** | 0 | 1 | **H.264 only（§2.6）** | 195-196 |
| `mediacodec-auto-rotate` | 0 | 0 | 1 | — | 183-184 |
| `mediacodec-sync` | **0** | 0 | 1 | "use msg_queue for synchronise" | 201-202 |
| `mediacodec-default-name` | **NULL** | — | — | **只在 async 路径生效（§1.9）** | 203-204 |
| `opensles` | **0** | 0 | 1 | 0 → AudioTrack | 197-198 |
| `soundtouch` | 0 | 0 | 1 | — | 199-200 |
| `ijkmeta-delay-init` | 0 | 0 | 1 | — | 205-206 |
| `render-wait-start` | **0** | 0 | 1 | 见 `ff_ffplay.c:902-910, 2716, 3323-3332` | 207-208 |

**默认值常量出处**：`ff_ffplay_def.h:85`（`MAX_QUEUE_SIZE 15MB`）、`:90-92`（min-frames）、
`:183-185`（pictq 3/16）、`:76-78`（high water marks）。

### 5.2 **不存在的选项**（问了但 k0.8.8 没有）

| 你以为有的 | 实际情况 |
|---|---|
| `framedrop_delay` | **不存在**。全仓库无此字符串。 |
| `video-packet-buffer` | **不存在**。对应能力 = `max-buffer-size` + `video-pictq-size` + `min-frames` + 三个 `*-high-water-mark-ms`。 |
| `drm` | **不存在**（无 Widevine/MediaDrm 相关代码路径）。 |
| `mediacodec-ndk` | **不存在**。k0.8.8 只有 Java API 路径（`ijksdl_codec_android_mediacodec_java.c`），没有 AMediaCodec NDK 实现。 |
| `genpts`（player 级） | **不在选项表里**。`ffp->genpts` 字段存在且恒为 0（`ff_ffplay_def.h:753`），但没有任何 option 写它。要开只能走 **FORMAT 类**的 `fflags`：`setOption(OPT_CATEGORY_FORMAT, "fflags", "+genpts")`（`libavformat/options_table.h:42-45`，`AVFMT_FLAG_GENPTS`）。 |
| `sws-*` | 选项表里**没有任何 sws 项**。`OPT_CATEGORY_SWS` 是一个自由字典（`ff_ffplay.c:4043`），**唯一消费者**是 `configure_video_filters`（`ff_ffplay.c:1786-1795`），而它只在设置了 `vf0` 视频滤镜时才被调用 → **不设 `vf0` 时 SWS 选项全部无效**。软解 overlay 的 sws 上下文另有硬编码 `SWS_BILINEAR`（`ijksdl_vout_overlay_ffmpeg.c:318`）。 |
| `threads` / `skip_loop_filter` / `skip_frame` / `skip_idct` / `lowres` | **不是 ijkplayer 选项**（不在 `ff_ffplay_options.h`），它们是 **FFmpeg AVCodecContext 选项**，只能通过 `OPT_CATEGORY_CODEC` 传（`filter_codec_opts` 会保留 AVCodecContext 类选项，`ff_cmdutils.c:170-178`）。 |

### 5.3 什么时候被读？——"prepare 之后就改不动了"的精确边界

| 类别 | 存放 | 被读取的位置 | prepare 后还能改吗 |
|---|---|---|---|
| `OPT_CATEGORY_PLAYER` | `ffp->player_opts` 字典 | **`av_opt_set_dict(ffp, &ffp->player_opts)`，只在 `ffp_prepare_async_l` 里执行一次**（`ff_ffplay.c:4287`） | **不能**。`ffp_set_option` 只是 `av_dict_set`（`ff_ffplay.c:4157-4164`），写进一个已经被消费掉的字典；而 `av_opt_set_dict` 会把**已识别的键从字典里删掉**（`libavutil/opt.c:1575-1588`），**所以之后设的值永远不会被搬进 `FFPlayer` 字段**。注意区分两件事：(1) 同一次 prepare 之后字段值仍在（值存在 struct 里，不是每次从字典取），所以"第二次 prepareAsync"仍然是有效配置；(2) 但你在第二次 prepare 之前**新设/改动**的值不会生效。清空字段的唯一途径是 `reset()`——它会**销毁整个 native player**（见 5.4）。 |
| `OPT_CATEGORY_FORMAT` | `ffp->format_opts` | `avformat_open_input`（`ff_ffplay.c:3119`）、`find_stream_info`（`:3151`） | 不能（只在 prepare 打开输入时用一次）。字典**不会被消费删除**，但输入已经打开。 |
| `OPT_CATEGORY_CODEC` | `ffp->codec_opts` | `filter_codec_opts` → `avcodec_open2`，在 `stream_component_open` 内（`ff_ffplay.c:2869-2876`） | **部分可以**：字典不会被删，每次 `stream_component_open`（例如切流 `ff_ffplay.c:4860`、重新 prepare）都会重新取用；但**对当前已打开的解码器无效**。 |
| `OPT_CATEGORY_SWS` | `ffp->sws_dict` | 仅 `configure_video_filters`（`ff_ffplay.c:1786`） | 无 `vf0` 时永远无效。 |
| 例外：`setSpeed()` / `setLooping()` / `setVolume()` | 直接改属性 | `ffp_set_property_*` | **可以**运行时改。除此之外没有别的运行时入口（k0.8.8 **没有** `setOverlayFormat` 的 Java API，grep 无结果）。 |

**越界的代价（很容易踩）**：`av_opt_set_int` 对 `[min,max]` 外的值返回 `AVERROR(ERANGE)`
（`libavutil/opt.c:97-105`，只打一行 `Value ... out of range`），
而 `av_opt_set_dict2` 遇到错误会 **`return ret` 直接中止**、**剩下的选项一个都不应用**
（`libavutil/opt.c:1579-1583`），ijkplayer 又**不检查返回值**（`ff_ffplay.c:4287`）。
→ 典型坑：`max-buffer-size` = 20MB（>15MB）就会让字典里排在它**字母序之后**的
`mediacodec-*` / `opensles` / `overlay-format` / `packet-buffering` / `subtitle` 等**全部失效**，
而日志里只有一行 out-of-range。
（本项目当前取值 512KB / 15MB 均在范围内，**没有踩到**。）

### 5.4 `reset()` 的语义（会影响"选项要不要重设"）

`IjkMediaPlayer.reset()` → `_reset()` → `IjkMediaPlayer_reset`（`android/ijkplayer_jni.c:386-399`）
= `IjkMediaPlayer_release()` + `IjkMediaPlayer_native_setup()` + `ijkmp_dec_ref_p`，
即 **销毁并重建 native player**；`ffp_destroy` → `ffp_reset_internal`（`ff_ffplay_def.h:728-739`）
会 `av_opt_free(ffp)` 并 `av_dict_free` **所有五个选项字典**。
→ **`reset()` 之后所有 `setOption` 的结果全部丢失，必须重设。**
（本项目每次播放 `IjkMediaPlayer()` 新建、结束 `release()`，见 `PlaybackEngine.kt:408, 776`，不受影响。）

### 5.5 推荐值

**(a) 弱 Android TV 放 4K HEVC（本机型）**

| 选项 | 推荐 | 理由（代码层） |
|---|---|---|
| `mediacodec-all-videos` / `-hevc` | 1 | 走硬解是唯一出路；两者都置 1 无副作用（HEVC 分支两条件或关系）。 |
| `mediacodec-handle-resolution-change` | 1（无害）/ 0 | **对 HEVC 无效**（`vdec.c:514`）；设 1 只影响 H.264 流的 reconfigure。 |
| `framedrop` | **1~3**（别用 -1） | 默认 0 时 `drop_frame_rate` 恒 0；-1 永不丢帧（§2.9）。 |
| `max-fps` | **61 或 -1**（保持现状即可），但要知道代价 | 25fps 4K 下两者等价（都不触发启发式）。真正的代价见 §6：对 1080p50 H.264 流，61 会让 `skip_frame=NONREF` 失效。 |
| `packet-buffering` | **0**（SMB 本地文件） | 默认 1，语义是"停顿后暂停输出直到读够包"；本地文件不需要，避免多余等待。 |
| `max-buffer-size` | 8~15MB（**硬上限 15MB**） | 4K HEVC 一帧原始数据 ~12MB(8bit YUV420)，但这里限制的是**压缩包**预读量；2GB RAM 下不宜顶满。 |
| `video-pictq-size` | 3（默认）或 4 | 每增加 1 个 picture queue 槽 = 多一个 4K overlay；**AMC 模式 overlay 是 `is_private=1` 不占像素内存**（`overlay_android_mediacodec.c:130,159`），因此调大代价比软解小。 |
| `infbuf` | **0**（不要设 1） | `infbuf=1` 会让 demuxer 不阻塞；SMB 慢链路下队列无上限，2GB 机器有 OOM 风险。 |
| `opensles` | 0（保持） | `ffpipeline_android.c:85-89`，AudioTrack 路径。 |
| `skip-calc-frame-rate` | 1 | 省掉为了估算真实帧率的额外解码（`ff_ffplay.c:3114` 下发到 format 层）。 |
| `probesize` / `analyzeduration` | 保持 1MB / ≤3s | FORMAT 类；HEVC 的 extradata 来自 moov，不需要长探测；太大在 SMB 上拖慢首帧。 |
| **CODEC 类**（新增建议） | `threads=2~3` + `thread_type=slice` | 见 §6；**只在软解回落时才有意义**，但软解回落恰恰是最坏情况，值得预设。 |

**(b) 1080p50 H.264 直播流**

| 选项 | 推荐 | 理由 |
|---|---|---|
| `mediacodec-avc` | 1 | H.264 硬解成熟；HEVC 分支不涉及。 |
| `packet-buffering` | **1**（默认） | 直播需要"停顿后攒够再放"。 |
| `max-fps` | **31（默认）** | 50 > 31 → 触发 `is_video_high_fps=1` → 对 **H.264** 真正生效的 `skip_frame/skip_loop_filter=NONREF`（`ff_ffplay.c:2996-3000` + `h264_slice.c:2061`）。注意代价：非参考帧不再解码（画面会有可见的顿/丢帧）。 |
| `framedrop` | 1~2 | 同上。 |
| `max-buffer-size` | 512KB~2MB | 直播低延迟。 |
| `infbuf` | 0 | 直播用 0 即可；只有"实时流不想阻塞"才考虑 1，但要接受内存风险。 |
| `rw_timeout` / `timeout` | 保持现有值 | FORMAT 类，`prepareAsync` 前设。 |
| `mediacodec-sync` | **先 0**，若卡死再 A/B 试 1 | 直播对输入线程模型敏感（§2.7）。 |

---

## 6. Q6 — `skip_loop_filter` / `AVDISCARD_NONREF` 与 4K HEVC 软解；`threads` 该怎么给

### 6.1 ijkplayer 自己的启发式（`ff_ffplay.c:2974-3000`）

```c
if (ffp->max_fps >= 0) {                       // 默认 31
    ... fps/tbr > max_fps && < 130 → is->is_video_high_fps = 1 ...
}
if (is->is_video_high_fps) {
    avctx->skip_frame       = FFMAX(avctx->skip_frame, AVDISCARD_NONREF);
    avctx->skip_loop_filter = FFMAX(avctx->skip_loop_filter, AVDISCARD_NONREF);
    avctx->skip_idct        = FFMAX(avctx->skip_loop_filter, AVDISCARD_NONREF);   // ← 源码 bug：用的是 skip_loop_filter
}
```

- `avctx->skip_idct` 被赋成了 `skip_loop_filter` 的值，**`skip_idct` 自己的旧值被忽略**——这是 k0.8.8 的既有缺陷
  （影响很小，但引用时要知道）。
- 这三行在 `avcodec_open2`（`ff_ffplay.c:2876`）**之后**执行；H.264/HEVC 的解码循环是逐帧读这些字段的，
  所以"后设"是有效的（对 H.264 而言）。
- 设置 `max-fps=61` 后，25fps / 50fps 都不会 > 61 → **这段启发式完全不触发**。

### 6.2 HEVC 软解**根本不看** `skip_frame`，`skip_loop_filter` 也几乎无效

在随包的 FFmpeg（`extra/ffmpeg`，`RELEASE = 3.2.git`，libavcodec 57.81）里逐条核对：

| 解码器 | `skip_frame` | `skip_loop_filter` | `skip_idct` |
|---|---|---|---|
| H.264 | **有效**：`skip_frame>=AVDISCARD_NONREF && !nal_ref_idc` 等直接跳过整帧解码（`h264_slice.c:2061-2065`） | **有效**，且逐帧细粒度（NONKEY/NONINTRA/BIDIR/**NONREF** 都有分支，`h264_slice.c:1894-1901`） | 有对应字段 |
| HEVC | **完全无效**：`libavcodec/hevc.c` 里**没有任何 `skip_frame` 引用** | 只在 `>= AVDISCARD_ALL(48)` 时才跳过 deblocking：`if (s->avctx->skip_loop_filter < AVDISCARD_ALL) deblocking_filter_CTB(...)`（`hevc_filter.c:845`）；**SAO 不受任何开关控制** | 无 |

`AVDISCARD` 取值：NONE=-16, DEFAULT=0, NONREF=8, BIDIR=16, NONINTRA=24, NONKEY=32, ALL=48
（`libavcodec/avcodec.h:790-796`）。

**结论（可直接用）**：
1. 对 **4K HEVC 软解**，`skip_loop_filter=nonref` / `skip_frame=nonref` / `skip_idct` **不会带来任何加速**
   —— hevc.c 里连 `skip_frame` 都不读。想要 deblocking 加速只能 `skip_loop_filter=all`（48），
   代价是明显块效应，且 SAO 仍然照做，收益有限。
2. ijkplayer 的 `is_video_high_fps` 启发式对 4K HEVC **是空操作**，`max-fps` 设多少对 HEVC 都无所谓。
3. **这些字段在 MediaCodec 模式下也完全无效**：AMC 管线不调用 `avcodec_decode_video2`
   （唯一例外是 H.264 换分辨率时的探测解码，`vdec.c:512-560`，且那里强制 `threads=1`）。
4. **"能提速多少"的量化数据：ijkplayer 文档/代码里没有，我也没有找到可引用的 ARM 实测数字 → 【未证实】。**
   本报告只给出"有效/无效"的确定结论，不给百分比。

### 6.3 `threads`：默认是 `auto`，而在 4 核 A53 上 `auto` = **5 个帧级线程**，且**同时关掉 WPP**

证据链（全部在随包 FFmpeg 源码里）：

1. ijkplayer 在没设 `threads` 时**强行设成 `auto`**：
   `if (!av_dict_get(opts, "threads", NULL, 0)) av_dict_set(&opts, "threads", "auto", 0);`
   （`ff_ffplay.c:2870-2871`）
2. `threads` 选项默认值是 1，`auto` 是常量 0（`options_table.h:367-368`）。
3. `auto` → `thread_count = FFMIN(av_cpu_count() + 1, MAX_AUTO_THREADS=16)`（`pthread_frame.c:622-631`；
   `MAX_AUTO_THREADS 16` 在 `pthread_internal.h:26`）。
   → 4 核 A53 上 = **5**。
4. `thread_type` 默认 `FF_THREAD_SLICE|FF_THREAD_FRAME`（`options_table.h:529-531`），
   而 `validate_thread_parameters` **优先选帧级线程**：
   ```c
   } else if (frame_threading_supported && (avctx->thread_type & FF_THREAD_FRAME)) {
       avctx->active_thread_type = FF_THREAD_FRAME;       // pthread.c:54-55
   ```
5. HEVC 解码器声明了 `AV_CODEC_CAP_FRAME_THREADS | AV_CODEC_CAP_SLICE_THREADS`
   （`hevc.c:3417-3418`）→ **必然走帧级线程**。
6. 帧级线程一旦生效，`hevc_init` 把 `s->threads_number` 设为 1：
   ```c
   if (avctx->active_thread_type & FF_THREAD_SLICE) s->threads_number = avctx->thread_count;
   else                                       s->threads_number = 1;      // hevc.c:3343-3346
   ```
   而 WPP 的入口判据是 `s->threads_number > 1 && s->sh.num_entry_point_offsets > 0`
   （`hevc.c:2828-2831`）→ **WPP 被彻底关掉**（`hls_slice_data_wpp`，`hevc.c:2454-2542`）。

**因此推荐（4K HEVC / 2GB RAM / 4×A53）**：

```kotlin
// 只在软解回落到 FFmpeg 时才有意义，但值得预设；必须在 prepareAsync() 之前设置
p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", 3)      // 显式指定，别用 auto(=5)
p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "thread_type", "slice")  // 要 WPP，不要帧级线程
```

- `thread_type=slice` → 走 `ff_slice_thread_init`（`pthread.c:74-75`），HEVC 用 WPP 逐 CTB 行并行
  （`execute2`，`hevc.c:2542`），每个线程只持有一个 `HEVCLocalContext`，
  **内存占用远小于帧级线程（后者每线程一份完整 `HEVCContext` + 参考帧）**，且**不引入额外解码延迟**。
- **前提**：码流里必须有 WPP 入口点（`num_entry_point_offsets > 0`）。x265 默认开 WPP，
  所以绝大多数 4K HEVC 文件满足；若某个文件是单 slice 无入口点，WPP 会退化成单线程，
  此时用 `threads=2` + 默认 `thread_type`（帧级）反而更好。→ **建议 A/B 实测两种组合**。
- 3 线程的理由：4 核里留 1 核给 SMB/AudioTrack/UI；`MAX_AUTO_THREADS` 上限 16 与本机型无关。
- 注意 `threads` 属于 CODEC 类，**不是** ijkplayer 选项；写入 `OPT_CATEGORY_CODEC` 会被
  `filter_codec_opts` 保留（`ff_cmdutils.c:170-178`，因为 `threads` 是 AVCodecContext 类选项）。

---

## 7. Q7 — 运行时"真的在出帧吗"能可靠判断到什么程度

### 7.1 可用的官方接口（k0.8.8 实际存在）

| 接口 | 值来源 | 何时更新 | 可信度 |
|---|---|---|---|
| `getVideoDecoder()` → `FFP_PROP_INT64_VIDEO_DECODER`=20003 | `ffp->stat.vdec_type`（`ff_ffplay.c:4934-4937`） | **成功建好管线的那一刻**：AMC 成功 = `FFP_PROPV_DECODER_MEDIACODEC`(2)（`vdec.c:2081`）；软解 = `FFP_PROPV_DECODER_AVCODEC`(1)（`ffpipenode_ffplay_vdec.c:57`） | ★★★ **HW/SW 的唯一可靠判别**；但**不证明有帧**（dummy codec 也是 2） |
| `getVideoDecodeFramesPerSecond()` → 10001 | `ffp->stat.vdps`（`ff_ffplay.c:4886-4887`） | AMC 每次 `dequeueOutputBuffer >= 0`（`vdec.c:1154`、`vdec.c:1324`） | ★★ 出帧速率；**假帧也算**；`SDL_SpeedSamplerAdd` 是滑窗均值（`ijksdl_timer.c:135-159`，`count<2` 时返回 0） |
| `getVideoOutputFramesPerSecond()` → 10002 | `ffp->stat.vfps`（`ff_ffplay.c:4888-4889`） | `video_image_display2` 里 `if (vp->bmp)` 块内（`ff_ffplay.c:880, 911-912`） | ★★ 送给 vout 的帧率（**不是** surface 真正上屏）。假帧同样计入。 |
| `FFP_PROP_FLOAT_DROP_FRAME_RATE`=10007 | `drop_frame_count/decode_frame_count` | 仅在 `framedrop != 0` 时累加（`ff_ffplay.c:1702-1719`） | ★★ 默认 `framedrop=0` → **恒为 0**，别当判据 |
| `MEDIA_INFO_VIDEO_RENDERING_START`(3) | `FFP_MSG_VIDEO_RENDERING_START` | `ff_ffplay.c:913-915`，紧接 `SDL_VoutDisplayYUVOverlay` | ★ **会骗人**：假帧/dummy codec 也会发；`releaseOutputBuffer` 失败只打 `ALOGW`（`ijksdl_vout_android_nativewindow.c:398-408`），消息照发 |
| `MEDIA_INFO_VIDEO_DECODED_START`(10004) | `FFP_MSG_VIDEO_DECODED_START` | `ff_ffplay.c:1678-1683`（首帧进入 pictq，同样在 `if (vp->bmp)` 内） | ★ 同上，假帧也算 |

**逐帧解码耗时**：**没有**。`is->viddec.decode_profiler`（`ff_ffplay_def.h:270`）在 k0.8.8 里
只被 `SDL_ProfilerReset`（`ff_ffplay.c:375`、`2977`），**AMC 路径从不 Begin/End 喂它**，
也没有任何 property 导出它。唯一的逐帧耗时统计是**编译期关闭**的调试块：

```c
#ifdef FFP_SHOW_AMC_VDPS     // vdec.c:1159-1173，需自行 #define 后重编 libijkplayer.so
    ... ALOGE("%lf fps, %lf ms/frame, %"PRIu64" frames\n", ...)   // 每 240 帧一次
#endif
```
（开关位置 `ff_ffplay_debug.h:47` 附近；`FFP_SHOW_AMC_VDPS` 在该文件里**未定义**。
注意它测的是"两次 dequeue 的墙钟间隔"，不是纯解码时间。）

### 7.2 最可靠的"真的在用 MediaCodec 出帧"证据（实践排序）

1. **logcat 出现 `AMEDIACODEC__INFO_OUTPUT_FORMAT_CHANGED` + `width-height / color-format /
   stride / slice-height / crop`**（`vdec.c:1129-1141`，`ALOGI`）。
   **dummy codec 的 `getOutputFormat()` 返回 NULL**（`ijksdl_codec_android_mediacodec_dummy.c:41-44`），所以这条日志是
   "真 codec 真的出了 output buffer"的**硬证据**。
2. `getVideoDecoder() == 2`（证明 AMC 管线建成，排除"静默软解回落"）。
3. `getVideoDecodeFramesPerSecond()` 与片源标称帧率对比（25fps 片源长期只有 3~5 → 解码跟不上）。
4. logcat 里**不应出现**：
   - `amc: no suitable codec`（`vdec.c:2058`）
   - `amc: recreate_format_l failed`（`vdec.c:2053`）
   - `configure_surface: failed`（`vdec.c:326` / `vdec.c:391`）
   - `MediaCodec: AVC/H264 is disabled` / `MediaCodec/HEVC is disabled`（`vdec.c:1946/1999`）
   - `AMediaFormat: ... , 0x0`（`vdec.c:178` 的 `AMediaFormat: %s, %dx%d`，宽高为 0 说明探测失败）
5. 需要更细时可重编带 `FFP_SHOW_AMC_VDPS` 的 so（见上）。

### 7.3 一个**未能证实**的观察（来自本项目实测，留给后续）

本项目 `MainActivity` 记录了：`outputFps()`（10002）在 **SMB + `IMediaDataSource`** 通路上
**恒为 0**，而本地文件能读到 14.8fps（commit `bdfe20f`）。
我按源码逐行追过：`stat.vfps` 的赋值在 `video_image_display2` 的 `if (vp->bmp)` 块内
（`ff_ffplay.c:872-916`），而 AMC overlay 的创建（`SDL_VoutAMediaCodec_CreateOverlay`，
`ijksdl_vout_overlay_android_mediacodec.c:137-177`）**除 OOM 外不会失败**，也不依赖 codec 是否已设置。
→ **仅凭代码无法解释该现象**。可能的解释（均未验证）：(a) 查询的是被 `reset()` 重建后的另一个
player 实例（`reset()` 会销毁 native player，§5.4）；(b) 该通路上 `video_image_display2` 未被走到。
建议先按 7.2 的 logcat 判据确认，再回头查 10002。

---

## 8. 针对本项目的"下一步可执行清单"（按收益排序）

1. **先确认到底走的是硬解还是软解**：`getVideoDecoder()`（2/1）+ logcat 的
   `AMEDIACODEC__INFO_OUTPUT_FORMAT_CHANGED`。如果是 1 → 全部问题都是"软解 4K HEVC"，按 §1
   逐道闸看哪一步 fail 了（日志关键词已在 7.2 列出）。
2. **确认片源是不是 10-bit（Main10）**：`ffprobe -show_streams` 看 `profile=Main 10` /
   `pix_fmt=yuv420p10le`；同时用 §4.3 的代码读本机 `profileLevels` 是否含 `HEVCProfileMain10`。
   若"片源 10bit + 机器无 Main10" → 换 8bit 片源是唯一解。
3. 打印完整的 `onMediaCodecSelect` 候选列表（已有）+ 排名与最终选择，
   确认选到的是 `OMX.MTK.VIDEO.DECODER.HEVC` 而不是 `.secure` 变体或 `OMX.google.*`（§3）。
4. 显式设 `OPT_CATEGORY_CODEC "threads"=3` + `"thread_type"="slice"`，为软解回落兜底（§6.3）。
5. `max-fps` 按"4K HEVC 用 61/-1、1080p50 H.264 用 31"分流（§5.5）——现在用一个 61 打天下，
   等于把 H.264 那条唯一有效的跳帧路径关掉了。
6. 若出现 SurfaceView 黑屏有声 → 试 `TextureView`（issue #3181，同为长虹 Android 5.1）。
7. 若出现卡死/不出帧 → A/B 试 `mediacodec-sync=1`（§2.7）。
