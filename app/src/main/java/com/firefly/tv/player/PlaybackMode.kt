package com.firefly.tv.player

import com.firefly.tv.media.Library

/**
 * 「这条流是什么类型」以及「能不能记住进度」。
 *
 * 起因是一个真实的隐蔽 bug：直播也走了续播写盘。IPTV 的 currentPosition 是**从频道
 * 开播算起的毫秒数**，会一路涨到几十小时；它被写进 last_pos 以后，下次从这个数去
 * seek —— 而直播流的 PTS 起点是个大数（实测 娘道 那条 TS 的 start_time=54285 秒），
 * 结果就是起播后长时间音画不同步。
 *
 * 所以规则写死：**只有点播才配拥有进度**。直播的进度永远是 0。
 */
object PlaybackMode {

    enum class Kind {
        /**
         * 点播：NAS 上的文件。有确定时长，可以续播、可以跳。
         */
        ON_DEMAND,

        /**
         * 直播：m3u 里的 HTTP/HLS 流。没有时长，不许存进度、不许 seek。
         */
        LIVE,
    }

    fun of(lib: Library?): Kind = when (lib) {
        is Library.Video -> Kind.ON_DEMAND
        is Library.Live -> Kind.LIVE
        null -> Kind.LIVE // 未知就当直播：宁可不续播，也不要 seek 出一条不同步的流
    }

    /** 这个类型是否允许把进度写进 SharedPreferences。 */
    fun remembersPosition(kind: Kind): Boolean = kind == Kind.ON_DEMAND

    /**
     * 起播时要跳到的位置。直播恒为 0 —— 这一行就是那个 bug 的修复点。
     */
    fun startPositionMs(kind: Kind, requested: Long): Long =
        if (remembersPosition(kind)) requested.coerceAtLeast(0L) else 0L

    /** 播放器的微调参数（DESIGN §6）。 */
    class Tuning(
        val packetBuffering: Boolean,
        val maxBufferBytes: Long,
        val probesizeBytes: Long,
        val analyzeDurationUs: Long,
    )
    /**
     * 播完了能不能自动跳下一个。
     *
     * 这条判据是被真实故障逼出来的：《娘道》那一集内核报出来的时长只有 1700ms
     * （实际 2560 秒）。播放器很快认为「播完了」，于是自动跳下一集、下一部 ——
     * 用户什么都没按，剧集却自己往前跑，最后停在别的剧上。
     *
     * 时长为什么会错？因为**音频流参数没探测出来**（第一条音频包在 2.34 MB 处，
     * 而探测窗口只有 2 MB），FFmpeg 既开不了音频组件、也算不出可信时长。
     * 修好探测窗口以后时长就正常了，但这个门槛仍然要留着：
     * 时长是外部输入，任何时候都可能因为片源问题变得不可信。
     *
     * 所以只有「时长可信」时才敢自动跳。时长不可信时宁可原地接着播同一集 ——
     * 对老人来说，「这一集反复播」远比「整部剧自己跑掉」好收拾。
     *
     * @param durationMs 播放器报出来的时长；直播恒为 0
     */
    fun canAutoAdvance(kind: Kind, durationMs: Long): Boolean =
        kind == Kind.ON_DEMAND && durationMs >= MIN_TRUSTED_DURATION_MS

    /** 短于十秒的时长一定是错的（没有哪一集电视剧只有几秒）。 */
    const val MIN_TRUSTED_DURATION_MS = 10_000L

    /**
     * 直播和点播的取舍不一样：
     *  - 点播放开缓冲，宁可多等一点也不要中途卡；
     *  - 直播必须贴近实时。缓冲堆得越多，画面离「现在」就越远 ——
     *    实测 CCTV1 是「画面比声音**慢**」，正是缓冲堆积造成的落后，
     *    所以直播的缓冲要压到最小：能起播就行，不追求抗抖动。
     *
     * ## probesize 为什么必须这么大（实测数据，不是估的）
     *
     * 《娘道》76 集全是 `MPEG-TS + H.264 + AC-3`，而且**后缀叫 .mp4**。
     * 它的第一条音频包在文件的 **2,340,788 字节（2.34 MB）**处 —— 视频包很大
     * （单包 291 KB），音频包被压在很后面。
     *
     * 探测窗口一旦不够，`avformat_find_stream_info` 就读不到音频参数，
     * 日志里是一句很不显眼的：
     *
     * ```
     * Could not find codec parameters for stream 1
     *   (Audio: ac3 ([129][0][0][0] / 0x0081), 0 channels, fltp): unspecified sample rate
     * ```
     *
     * 采样率为 0 的 `AVCodecContext` 打不开，音频组件**静默失败**（不打错误日志），
     * 表现就是「画面完全正常，就是没声音」，而且时长也算成了 1700ms。
     *
     * 二分实测（`ffprobe -probesize N`，见 `scripts/ts-psi.py` 的说明）：
     * ```
     *   2.0 MB -> 0 channels      （旧值，正好差 340KB）
     *   3.0 MB -> 48000 Hz, 2 ch  （刚够）
     * ```
     * 所以取 16 MB：留足余量，免得换一部剧又踩同一个坑。
     * 代价只是起播前多读几 MB —— SMB 上几百毫秒，远比「没声音」划算。
     *
     * **不要把这里调回几 MB 以下。** `PlaybackModeTest` 会把下限钉住。
     *
     * ## 直播要按**传输协议**分开，不能一套参数打天下
     *
     * 这里原来只有 `kind` 一个维度，直播那档的注释直接写着「直播是 HLS」（见
     * [Transport] 的说明）。换成 `rtsp://` / `udp://` 源以后那两个值就偏小了：
     * HLS 每个分片自带完整流信息，1 MB 够认；而连续 TS over RTSP/UDP 的
     * PMT/PAT 只在流开头出现一次，窗口太小会认不出参数。
     */
    fun tuning(kind: Kind, transport: Transport = Transport.HTTP): Tuning = when (kind) {
        Kind.LIVE -> when (transport) {
            // HLS：10 秒分片，分片里自带流信息，小窗口起播快（保持原值不动）
            Transport.HTTP -> Tuning(
                // 保留包缓冲：完全关掉时视频钟会先跑，音频还在等 AudioTrack 起播，
                // 起播那几秒反而会不同步。留一个很小的缓冲，两头都顾上。
                packetBuffering = true,
                maxBufferBytes = 512L * 1024,
                probesizeBytes = 1L * 1024 * 1024,
                analyzeDurationUs = 500_000L,
            )

            // RTSP / UDP 组播：连续 TS，没有「分片」这个概念。
            // 探测窗口给 4 MB（PMT/PAT 与首个关键帧都可能靠后），
            // 缓冲也放大到 4 MB —— 原来的 512 KB 加上 rw_timeout 只要抖一下就断。
            Transport.RTSP, Transport.UDP -> Tuning(
                packetBuffering = true,
                maxBufferBytes = 4L * 1024 * 1024,
                probesizeBytes = 4L * 1024 * 1024,
                analyzeDurationUs = 1_000_000L,
            )
        }

        Kind.ON_DEMAND -> Tuning(
            packetBuffering = false,
            maxBufferBytes = 15L * 1024 * 1024,
            // 见上面的实测说明：娘道需要 >2.34 MB，取 16 MB 留余量
            probesizeBytes = MIN_PROBESIZE_BYTES,
            analyzeDurationUs = 3_000_000L,
        )
    }

    /**
     * 点播探测窗口的下限。
     *
     * 16 MB 的依据：实测《娘道》第一条音频包在 2.34 MB 处，
     * 2 MB 的旧值读不到音频参数（→ 静默无声），3 MB 刚够。
     */
    const val MIN_PROBESIZE_BYTES = 16L * 1024 * 1024

    // ---- 直播源的传输协议 ----
    //
    // ## 为什么必须显式区分（这一段是实机 + 模拟器实测查出来的）
    //
    // 直播源换过一批：原来是 HTTP/HLS（`http://…/live/cctv1hd.m3u8`），
    // 现在是运营商 IPTV 的 **RTSP 单播**（`rtsp://192.0.2.21/PLTV/…smil`）
    // 和 **UDP 组播**（`udp://239.0.0.2.66:4120`）。
    // 但 [PlaybackEngine] 的选项是照 HTTP/HLS 写的，于是两处硬伤：
    //
    // ### 硬伤 1：`timeout` 是**秒**，不是微秒
    //
    // ffmpeg 3.4 的 RTSP 协议选项表（`libavformat/rtsp.c`）原文：
    // ```
    // { "timeout",  "set maximum timeout (in seconds) to wait for incoming connections", … }
    // { "stimeout", "set timeout (in microseconds) of socket TCP I/O operations", … }
    // ```
    // 旧代码给 `timeout` 塞了 `IO_TIMEOUT_US`（15000000），本意是 15 秒，
    // 实际被当成 **15000000 秒（约 173 天）**。它同时是 RTSP 保活间隔的来源
    // （`rtspdec.c`：`>= rt->timeout / 2` 才发 GET_PARAMETER），
    // 于是保活等于关闭 —— 服务端认为客户端早死了，中途掐流。
    // 对一个给老人看的直播应用，这就是「播一段就卡住」。
    //
    // ### 硬伤 2：没有 `rtsp_transport`，默认走 UDP
    //
    // 服务端拒绝 UDP 的 SETUP（回 `405 Method Not Allowed`）时，
    // ffmpeg 3.4 本来有一条回退：`rtspdec.c` 里
    // `ret == AVERROR(ETIMEDOUT) && !rt->packets` → 打 `"UDP timeout, retrying with TCP"`
    // → `resetup_tcp()`。（这句话确实编进了我们的内核，`strings libijkffmpeg.so` 能搜到。）
    //
    // 但这条回退**在模拟器上从来没触发过**：抓 logcat 看到的是一条更硬的失败 ——
    // ```
    // Status 302: Redirecting to rtsp://192.0.2.36:554/…?online=…
    // method SETUP failed: 405 Method Not Allowed
    // rtsp://…: could not find codec parameters
    // FFP_MSG_ERROR → Error (-10000,0)
    // ```
    // 每个频道都一样（CCTV-1/6/8/11 全中），而同一条 URL 在电脑上用
    // `ffprobe`（UDP、TCP 两种 transport 都试了）和手写 RTSP 握手都正常。
    // 直接指定 `tcp` 就能绕开这一整条 UDP→TCP 回退路径，
    // 换台时也不用先等一次 UDP 超时。
    //
    // ### 为什么 `uint32_t timeout` 对 UDP 组播尤其没意义
    //
    // `udp://` 是无连接的，`rw_timeout`/`timeout` 都不作用于它；
    // 组播流停了就是**永远不会自己好**。所以组播另有保底，见
    // [PlaybackEngine] 的播放中存活看门狗，别指望超时选项。
    //
    // ## 实测依据
    //
    // | 场景 | 结果 |
    // | :--- | :--- |
    // | 真实源 + 电脑 `ffprobe`（UDP/TCP） | ✅ 均出流，连续拉 6 分钟零错误 |
    // | 真实源 + 手写 RTSP 握手（UDP/TCP SETUP） | ✅ 均 `200 OK` |
    // | 真实源 + 本 app（模拟器） | ❌ 全部 `SETUP failed: 405` |
    // | 本地自建「拒 UDP」RTSP 服务端 + 电脑 `ffprobe` | ❌ 复现同一条 405 报错 |
    //
    // 也就是说：**服务端和源都没问题，是 app 这条 RTSP 通路没配对。**
    enum class Transport {
        /** 直播是 HLS：`http(s)://…m3u8`，画面是 10 秒一个的 TS 分片。 */
        HTTP,

        /** `rtsp://` 单播。 */
        RTSP,

        /** `udp://` / `rtp://` 组播。 */
        UDP;

        companion object {
            /**
             * 按 scheme 判断。认不出来的一律当 [HTTP] ——
             * 那是改动前的老行为，退回它至少不会把一个能播的源弄坏。
             */
            fun of(url: String): Transport {
                val u = url.trim().lowercase()
                return when {
                    u.startsWith("rtsp://") || u.startsWith("rtsps://") -> RTSP
                    u.startsWith("udp://") || u.startsWith("rtp://") -> UDP
                    else -> HTTP
                }
            }
        }
    }
}
