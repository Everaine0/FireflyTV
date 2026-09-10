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
     */
    fun tuning(kind: Kind): Tuning = when (kind) {
        Kind.LIVE -> Tuning(
            // 保留包缓冲：完全关掉时视频钟会先跑，音频还在等 AudioTrack 起播，
            // 起播那几秒反而会不同步。留一个很小的缓冲，两头都顾上。
            packetBuffering = true,
            maxBufferBytes = 512L * 1024,
            // 直播是 HLS，每个分片自带完整的流信息，不需要大窗口。
            // 给 1 MB 是因为有些源把 PMT 放在分片靠后处。
            probesizeBytes = 1L * 1024 * 1024,
            analyzeDurationUs = 500_000L,
        )

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
}
