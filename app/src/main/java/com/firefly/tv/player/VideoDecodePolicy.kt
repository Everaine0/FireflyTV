package com.firefly.tv.player

/**
 * 视频解码：**硬解优先、软解兜底**。
 *
 * ## 为什么必须显式写这些选项（这是「装到电视上极慢、还没声音」的根因）
 *
 * ijkplayer 的 `mediacodec*` 系列选项**默认全部是 0**
 * （`ijkmedia/ijkplayer/ff_ffplay_options.h`，k0.8.8 里逐条写着 `OPTION_INT(0, 0, 1)`）。
 * 也就是说：**不显式打开，ijkplayer 就一定会走 FFmpeg 软解** —— 和「这台设备有没有
 * 硬解能力」无关。之前的代码一次都没设过这些选项，所以：
 *
 *  - 模拟器上：`DESIGN §9 风险 8` 里记的「大宅门首帧 ~36s、猫和老鼠 ~27s」，
 *    以及「1080p25 本来就跑不满帧率」，都是纯软解的代价；
 *  - 电视上（Cortex-A53 + 4K 面板）：大宅门 / 猫和老鼠 都是 **4K H.265**，
 *    软解一帧要几百毫秒 —— 表现就是**画面极慢**；
 *    与此同时 CPU 被软解线程占满，音频线程拿不到时间片，AudioTrack 一直欠载（输出静音），
 *    于是**也没声音**。两个症状同一个根因。
 *
 * 硬解通路本身是**随包就在**的，不需要重编内核：
 *  - `libijkplayer.so` 里有 `ffpipeline_create_from_android` /
 *    `ffpipenode_create_video_decoder_from_android_mediacodec`（自己的 MediaCodec 管线）；
 *  - `libijksdl.so` 里有 51 个 `J4AC_android_media_MediaCodec__*` JNI 包装
 *    和 `SDL_AMediaCodec_configure_surface`（解码结果直接送 Surface，零拷贝）。
 *
 * 注意：硬解**不是**靠 FFmpeg 的 `h264_mediacodec` 解码器（那一族在随包内核里确实没有，
 * 官方包也没有）。ijkplayer 走的是自己那条管线，和 `--enable-mediacodec` 无关。
 *
 * ## 为什么只写 `mediacodec-all-videos` 一个开关
 *
 * 逐编码的 `mediacodec-avc` / `mediacodec-hevc` / `mediacodec-mpeg2` 也能用，
 * 但 `mediacodec-mpeg4` **不在管线的判定条件里**（`ffpipeline_android.c` 只认
 * all-videos / avc / hevc / mpeg2 四个），只写它等于没写；而 `mediacodec` 其实是
 * `mediacodec-avc` 的别名，两个都写会互相覆盖。与其记这套容易踩坑的规则，
 * 不如只写 `all-videos`：它对 H.264 / H.265 / MPEG-2 / MPEG-4 都生效，
 * 其余编码（RMVB / VP9 / WMV 等）在管线内部就被挡回软解。
 *
 * 「一把抓」不会把画面交给**软件实现的 MediaCodec**：ijkplayer 用
 * `IjkMediaCodecInfo` 给候选解码器排名，排名低于 `RANK_LAST_CHANCE` 的
 * （`OMX.google.*` / `omx.ffmpeg.*` 这类纯软件实现）会被拒绝，一个都没有时退回 FFmpeg 软解。
 * 所以模拟器上（只有 `OMX.google.*`）行为不变，仍然是软解。
 *
 * 另外：所有选项都必须**在 `prepareAsync()` 之前**设置，ijkplayer 之后不再读取。
 */
object VideoDecodePolicy {

    enum class Category { PLAYER, FORMAT, CODEC }

    /** 一条要交给 `IjkMediaPlayer.setOption` 的选项。 */
    data class Opt(val category: Category, val name: String, val value: Long)

    /**
     * 启动硬解通路的那几个开关。
     *
     * 写 0 的时候必须**四个一起写**：管线是「任何一个为 1 就尝试 MediaCodec」，
     * 少写一个，只要它在别处被置 1，软解兜底就失效了。
     */
    private val GATE_OPTIONS = listOf(
        "mediacodec-all-videos",
        "mediacodec-avc",   // 也是 "mediacodec" 的同一个字段
        "mediacodec-hevc",
        "mediacodec-mpeg2",
    )

    /**
     * 交给 MediaCodec 的编码由 `mediacodec-all-videos` 统一决定；
     * 这里只把管线的判定条件变成显式的 0 / 1。
     */
    fun options(hardware: Boolean): List<Opt> = buildList {
        for (name in GATE_OPTIONS) {
            add(Opt(Category.PLAYER, name, if (hardware) 1L else 0L))
        }
        // 分辨率变化时让 MediaCodec 自己重建解码器。
        // 直播（央视各频道在标清/高清节目之间切换）真的会中途换分辨率，
        // 不处理就是花屏或直接卡死。注意 ijkplayer 只对 H.264 做了这段处理。
        add(Opt(Category.PLAYER, "mediacodec-handle-resolution-change", if (hardware) 1L else 0L))
        // 音频输出固定走 AudioTrack（ijkplayer 的默认值就是 0 = AudioTrack）。
        // 显式写出来是因为「电视上没声音」太难查：OpenSL ES 在部分电视固件上是坏的，
        // 不能让某天内核改个默认值就把整条音频通路换掉。
        add(Opt(Category.PLAYER, "opensles", 0L))
        // max-fps（默认 31）不是「限帧」，而是在流打开时做一次性判断：
        // 帧率超过它就把 skip_frame / skip_loop_filter / skip_idct 抬到 NONREF，
        // 也就是**丢掉非参考帧、并且不做去块滤波**。
        //  - 硬解：解码器扛得住，不该白丢帧。测试片源里《娘道》是 1080p50，
        //    默认值会让它一直丢帧。取 61 等于对 60fps 以内的内容不再干预，
        //    同时保留对畸形高帧率（>60）的保护。
        //  - 软解：留着默认的 31 有用 —— 电视本来就解不动，丢帧比卡成幻灯片好。
        add(Opt(Category.PLAYER, "max-fps", if (hardware) MAX_FPS_HARDWARE else MAX_FPS_SOFTWARE))
        // 渲染通路：把「CPU 做 YUV→RGB32 + 整帧 memcpy」换成 GLES2 三纹理 + 片元着色器。
        // 只影响软解；硬解走 MediaCodec→Surface 零拷贝，压根不看这个选项（见 OVERLAY_GLES2）。
        add(Opt(Category.PLAYER, "overlay-format", OVERLAY_GLES2))
    }

    /**
     * `overlay-format` 的取值：fourcc `_ES2`（= `SDL_FCC__GLES2`，让 vout 自己挑格式）。
     *
     * ## 默认值是 RV32，在 4K 片源上等于每帧搬 33MB
     *
     * ijkplayer 的 `overlay-format` 默认是 `SDL_FCC_RV32`（RGBX8888，4 字节/像素）。
     * 那条路上每帧要做两件纯 CPU 的苦力活：
     *  1. `ijk_image_convert`（走 libyuv 的 `I420ToABGR`）把 YUV420P 转成 ABGR32；
     *  2. `SDL_Android_NativeWindow_display_l` 把整帧 `memcpy` 进 ANativeWindow 的 buffer。
     *
     * 3840×2160 一帧就是 **33.2 MB**。实测（模拟器，2960×2160 HEVC）：
     * 负责这两步的 `ff_vout` 线程**单核跑满 92%**，整个进程吃掉 1.8 个核，
     * 而画面只有 **4.1 fps**；同一时刻 5 个解码线程加起来才 0.9 个核。
     * 也就是说：**瓶颈不是解码，是「YUV→RGB32 + 整帧 memcpy」这一对 CPU 动作。**
     *
     * ## 设成 `_ES2` 之后这两步都消失
     *
     * `SDL_VoutFFmpeg_CreateOverlay` 在 `overlay_format == SDL_FCC__GLES2` 时，
     * 会把 8bit YUV420P 直接标成 `SDL_FCC_YV12`（**就是解码器原生的三平面格式，不做转换**），
     * 然后 `func_display_overlay_l` 因为 `vout->overlay_format == SDL_FCC__GLES2`
     * 交给 `IJK_EGL_display`：三个平面各上传成一张 GL 纹理，YUV→RGB 的矩阵运算
     * 在片元着色器（`gles2/fsh/yuv420p.fsh`）里由 GPU 做。
     * CPU 侧只剩「把三个平面拷进 overlay」，4K 下约 12.4MB，而不是 33MB + 一次颜色转换。
     *
     * ## 为什么硬解不受影响
     *
     * MediaCodec 那条路解出来的帧格式是 `IJK_AV_PIX_FMT__ANDROID_MEDIACODEC`，
     * overlay 由 `SDL_VoutAMediaCodec_CreateOverlay` 创建，它**直接把 format 写成
     * `SDL_FCC__AMC`**，根本不看 `overlay-format`；显示时也只是把解码器的 buffer
     * `releaseOutputBuffer(render=true)` 送回 Surface —— 零拷贝，一个像素都不碰。
     * 所以这个选项只对「软解 + 8bit YUV420P」生效，正好是需要它的那种场景。
     *
     * ## 已知代价
     *
     * `gles2/renderer_yuv420p.c` 里色彩矩阵写死了 **BT.709**
     * （`IJK_GLES2_getColorMatrix_bt709()`）。对 1080p/4K 片源是对的；
     * 标清（BT.601）片源的颜色会略有偏移。拿这点色差换 4K 能看，值。
     */
    const val OVERLAY_GLES2 = 0x3253455FL // SDL_FOURCC('_','E','S','2')

    /**
     * 网络层：**关掉 ijkplayer 自带的「DNS 缓存」**。
     *
     * ## 症状：换台以后就一直冻着（用户实测的「从电视[剧]换回直播卡住了」）
     *
     * 直播频道列表里的央视地址是 `http://198.51.100.10:82/live/cctv1hd.m3u8`：
     * `:82` 只负责**重定向**，回一个 302 指向 `:81` 上带一次性 token 的地址
     * （`…:81/live/cctv1md.m3u8?tm=…&key=…`），真正的流在 `:81`。
     * 冷启动第一次换台一切正常；**之后每次换台都变成 403 Forbidden**，
     * 而且**永远不会自己好** —— 用户看到的就是画面冻在最后一帧。
     *
     * ## 根因：那份缓存**只按主机名做键，却把端口一起缓存了**
     *
     * ijkplayer 给 FFmpeg 打了个 patch，在 `libavformat/tcp.c` 里加了 DNS 缓存，
     * 实现在 `libavutil/dns_cache.c`：
     *
     *  - 键：`av_dict_get(context->dns_dictionary, hostname, …)` —— **只有主机名**；
     *  - 值：`new_dns_cache_entry()` 里 `memcpy(new_entry->res->ai_addr, cur_ai->ai_addr, …)`
     *    —— 把 `getaddrinfo()` 返回的 `sockaddr_in` 整个拷走，**`sin_port` 也一起拷**。
     *
     * 于是缓存里躺的是「IP + 当时那个端口」。命中之后 `tcp_open()` 直接拿它去
     * `ff_listen_connect()`，**完全不看 URL 里写的端口**（`restart:` 那段只在
     * `AF_INET6` 且端口为 0 时补端口，IPv4 没有对应处理）。
     *
     * 时间线（tcpdump 抓的是模拟器网卡，`10.0.2.15` 是 guest）：
     *
     * ```
     * 冷启动第 1 次换台（缓存空）—— 正常
     *   10.0.2.15 → 198.51.100.10:82  GET /live/cctv1hd.m3u8       → 302
     *   （连上以后写缓存：hostname=198.51.100.10, sin_port=82）
     *   10.0.2.15 → 198.51.100.10:81  GET /live/cctv1md.m3u8?tm=…   → 206 ✔ 有画面
     *   （连上以后又写一次缓存，这次 sin_port=81）
     *
     * 之后每次换台（缓存里已经是 :81）—— 全挂
     *   10.0.2.15 → 198.51.100.10:81  GET /live/cctv1hd.m3u8
     *                                 Host: 198.51.100.10:82        → 403 ✘
     * ```
     *
     * 最后那一条就是全部真相：**TCP 连到了 `:82` 的缓存端口 `:81`，请求行和
     * Host 却还是 `:82` 那一份**。`:81` 上没有 token 的 `cctv1hd` 是取不到的，
     * openresty 直接 403。缓存活到进程结束，所以换多少次台都一样。
     *
     * 这个 403 和网络环境无关：宿主机直连同一个地址一直是 302 → 200，
     * 只有走 ijkplayer 这条缓存路径才会 403。
     *
     * ## 为什么关缓存而不是改缓存
     *
     * 正确的修法是把键改成 `host:port`（上游后来也是这么修的），但那要重编
     * **armv7a / arm64 / x86 三个 ABI** 的 `libijkffmpeg.so`。而这两个选项
     * 是 tcp 协议自己的 AVOption，从 `format-opts` 就能传下去，
     * **一套 APK 同时修好模拟器和电视**，不必再维护一份自编译内核。
     *
     * 两个一起写是故意的，原因是双保险：
     *  - [DNS_CACHE_DISABLED]：整个缓存代码块（`if (dns_cache_timeout > 0)`）不再执行；
     *  - [DNS_CACHE_CLEAR]：万一这个超时值在别处又被置成正数，`tcp_open()` 每次
     *    连接前都会先 `remove_dns_cache_entry(hostname)`，缓存里那份带错端口的
     *    addrinfo 永远来不及被用上。
     *
     * 代价：每次建连都重新解析一次主机名。对直播这种「一个 IP + 多个端口」的源
     * 反而是必需的；解析结果是数字 IP 时 `getaddrinfo()` 只是本地转换，不查网络。
     */
    val NETWORK_OPTIONS = listOf(
        Opt(Category.FORMAT, "dns_cache_timeout", 0L),
        Opt(Category.FORMAT, "dns_cache_clear", 1L),
    )

    /** 硬解时的 `max-fps`：等于不再干预 60fps 以内的内容。 */
    const val MAX_FPS_HARDWARE = 61L

    /** 软解时的 `max-fps`：ijkplayer 的默认值，靠丢非参考帧换取流畅度。 */
    const val MAX_FPS_SOFTWARE = 31L

    /**
     * 硬解失败多少次以后就不再试了（进程内记住）。
     *
     * 为什么要上限：这台电视的 MediaCodec 万一创建得出来、却解不出画面，
     * 每次起播都要先浪费一个超时再退回软解 —— 老人看到的是「每集都要黑屏十几秒」。
     * 连续失败 [MAX_FAILURES] 次就认定硬解在这台设备上不可用，本次运行不再试。
     */
    const val MAX_FAILURES = 2

    /** 还能不能再试硬解。 */
    fun canTryHardware(failures: Int): Boolean = failures < MAX_FAILURES

    /**
     * 硬解起播后多久没有首帧就判定它不行、改用软解重播。
     *
     * 取 15 秒：4K 片源光是把 moov 搬到头部、探流、建解码器就要几秒
     * （实测模拟器软解要 27~36 秒才出首帧，电视硬解应该在一两秒内），
     * 15 秒足够区分「硬解在建」和「硬解卡住」。
     *
     * 注意这个超时只作用于**硬解**：软解那条路仍然由界面层的
     * `STALL_TIMEOUT_MS`（25 秒）兜底，判据不变。
     */
    const val FIRST_FRAME_TIMEOUT_MS = 15_000L
}
