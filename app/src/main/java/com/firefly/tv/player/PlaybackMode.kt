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
     * 直播和点播的取舍不一样：
     *  - 点播放开缓冲，宁可多等一点也不要中途卡；
     *  - 直播必须及时出画面，但**不能**完全关掉包缓冲 —— 关了以后视频钟会先跑，
     *    音频还在等 AudioTrack 起播，起播那几秒就是音画不同步。用一个小缓冲把
     *    两者的起点对齐，代价只是多半秒。
     */
    fun tuning(kind: Kind): Tuning = when (kind) {
        Kind.LIVE -> Tuning(
            packetBuffering = true,
            maxBufferBytes = 2L * 1024 * 1024,
            probesizeBytes = 512L * 1024,
            analyzeDurationUs = 1_000_000L,
        )

        Kind.ON_DEMAND -> Tuning(
            packetBuffering = false,
            maxBufferBytes = 15L * 1024 * 1024,
            probesizeBytes = 2L * 1024 * 1024,
            analyzeDurationUs = 3_000_000L,
        )
    }
}
