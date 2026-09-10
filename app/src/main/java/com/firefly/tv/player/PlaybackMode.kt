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
     * 这条判据是被真实故障逼出来的：《娘道》那种 AC-3 的 TS，因为音频解不了，
     * 内核报出来的时长是错的（一集实际 2560 秒，报 1700ms）。
     * 时长是错的 → 播放器很快就认为「播完了」→ 自动跳下一集、下一部，
     * 用户什么都没按，剧集却在自己往前跑，最后停在别的剧上。
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
     */
    fun tuning(kind: Kind): Tuning = when (kind) {
        Kind.LIVE -> Tuning(
            // 保留包缓冲：完全关掉时视频钟会先跑，音频还在等 AudioTrack 起播，
            // 起播那几秒反而会不同步。留一个很小的缓冲，两头都顾上。
            packetBuffering = true,
            maxBufferBytes = 512L * 1024,
            probesizeBytes = 256L * 1024,
            analyzeDurationUs = 500_000L,
        )

        Kind.ON_DEMAND -> Tuning(
            packetBuffering = false,
            maxBufferBytes = 15L * 1024 * 1024,
            probesizeBytes = 2L * 1024 * 1024,
            analyzeDurationUs = 3_000_000L,
        )
    }
}
