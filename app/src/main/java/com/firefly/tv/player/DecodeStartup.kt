package com.firefly.tv.player

/**
 * 硬解起播超时的判据：**别把「NAS 慢」判成「硬解坏」。**
 *
 * 用户报过一种偶发故障：打开电视先卡顿一阵，重新进入就恢复。现场链路是
 * NAS 硬盘休眠 / 网络慢 → 播放器读首帧要等；15 秒硬解看门狗到点时
 * 解码器其实**已经拿到数据、还在往里读**，但一帧都还没出来 —— 旧逻辑只要
 * 「有数据」就立刻退回软解。软解 4K H.265 在这台电视上只有幻灯片水平，
 * 于是用户看到「异常卡顿」；重进应用后 NAS 已经热了，硬解几秒出画面就正常了。
 *
 * 现在的判据分两步：
 *
 *  1. [waitLonger]：数据还在流入、或者根本还没读到数据 —— 都是 I/O 的账，
 *     继续等（最多 [MAX_GRACE] 次）；
 *  2. 到点了仍无首帧，再看 [looksLikeDecoderFault]：
 *     - 缓存里**已经攒下足够播放的秒数/字节**、而且不再增长 → 像解码器卡住，退软解；
 *     - 缓存很少或还在增长 → 是 NAS/读取的问题，**不降级**，交给界面层用硬解重试
 *       （退软解只会把 4K 变成幻灯片，救不了读取）。
 *
 * 抽成纯函数是为了能用单测钉住 —— 判错了不会报错，只会表现成
 * 「电视有时流畅有时像幻灯片」，事后很难复盘。
 */
object DecodeStartup {

    /** 硬解首帧超时最多宽限几次（每次一个完整超时）。 */
    const val MAX_GRACE = 2

    /**
     * 判定「解码器卡住」所需的缓存下限（毫秒）。
     *
     * 取 3 秒：这时候解码器手里已经有实打实的视频数据了，
     * 15 秒还一帧不出就不太可能是"没喂饱"。
     */
    const val ENOUGH_CACHED_MS = 3_000L

    /** 判定「解码器卡住」所需的缓存下限（字节）。和 [ENOUGH_CACHED_MS] 满足其一即可。 */
    const val ENOUGH_CACHED_BYTES = 2L * 1024 * 1024

    /**
     * 这次超时还要不要再等一个周期。
     *
     * @param graceUsed 已经宽限过几次
     * @param hasData 解码器有没有拿到数据（`videoCachedBytes/Duration`）
     * @param progressChanged 与上一个采样点相比，读取/缓存计数有没有变化
     */
    fun waitLonger(graceUsed: Int, hasData: Boolean, progressChanged: Boolean): Boolean =
        graceUsed < MAX_GRACE && (!hasData || progressChanged)

    /**
     * 到点了还不出首帧，这个锅该谁背。
     *
     * @param hasData 解码器有没有拿到数据
     * @param progressChanged 数据是不是还在增长
     * @param cachedMs 已缓存的视频时长
     * @param cachedBytes 已缓存的字节数
     * @return true = 像解码器的问题（该退软解）；false = 像 NAS/读取的问题（该原样重试）
     */
    fun looksLikeDecoderFault(
        hasData: Boolean,
        progressChanged: Boolean,
        cachedMs: Long,
        cachedBytes: Long,
    ): Boolean = hasData && !progressChanged &&
        (cachedMs >= ENOUGH_CACHED_MS || cachedBytes >= ENOUGH_CACHED_BYTES)
}
