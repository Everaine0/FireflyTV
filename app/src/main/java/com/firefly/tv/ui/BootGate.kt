package com.firefly.tv.ui

/**
 * 开机页什么时候可以退场。
 *
 * 这个页面出现的理由有两个：把遥控器说明摆到老人面前，以及盖住「NAS 硬盘休眠、
 * 第一次打开要等十几秒」那段黑屏。两条都要守住，所以退场不能只按时间：
 *
 *  - 画面已经出来 → 该走了，但**至少要待够 [MIN_SHOW_MS]**，否则三个按键的说明
 *    还没轮完一遍就闪过去了；
 *  - 故障页已经写着原因 → **立刻走**。把「连不上 NAS」藏在开机页下面是最坏的情况：
 *    用户看到的是「正在唤醒硬盘」，实际原因是密码错了或网络断了；
 *  - 一直什么都没有 → 到 [MAX_SHOW_MS] 也必须走，不能永远盖着。
 *
 * 抽成纯函数是为了能用单元测试钉住。这里的边界多等或少等一秒都不会崩，
 * 只会表现成「有时教程没看完」「有时错误被挡住」，事后很难复现。
 */
object BootGate {

    /**
     * 最短停留时间。
     *
     * 取 5 秒：三个按键提示每个约 1.6 秒，刚好轮完一遍（4.8 秒）。
     * 更短则说明看不完，更长则每次开机都要等它 —— 对一个「打开就在播」的应用来说，
     * 强制等待本身就是代价，5 秒是这个代价的上限。
     */
    const val MIN_SHOW_MS = 5_000L

    /**
     * 最长停留时间。
     *
     * 和 `StartupGrace.WINDOW_MS` 对齐：超过 90 秒还没画面，说明起播宽限也已经用尽，
     * 该让故障页/重试界面接过去了，不能还用「正在唤醒硬盘」盖着。
     */
    const val MAX_SHOW_MS = 90_000L

    /** 淡出时长；调用方按它安排后续动作（恢复播放）。 */
    const val FADE_MS = 350L

    enum class Exit {
        /** 首帧已上屏，且已经待够最短时间。 */
        READY,

        /** 故障页要说话，立刻让位。 */
        FAULT,

        /** 等到上限还没有画面，交给下面的界面。 */
        TIMEOUT,
    }

    fun elapsedMs(now: Long, shownAt: Long): Long = (now - shownAt).coerceAtLeast(0L)

    /**
     * 现在该不该退场；不该就返回 null。
     *
     * 故障优先于「最短停留」：错误必须立刻可见。这也是唯一会打破强制展示的情况。
     */
    fun exitReason(now: Long, shownAt: Long, firstFrame: Boolean, faultVisible: Boolean): Exit? {
        val e = elapsedMs(now, shownAt)
        if (faultVisible) return Exit.FAULT
        // 首帧已到就按 READY 收：否则到 90 秒时会返回 TIMEOUT，onBootExit 不恢复播放，
        // 画面会停在暂停那一帧。上限只用于“一直没有首帧”的情况。
        if (firstFrame && e >= MIN_SHOW_MS) return Exit.READY
        if (e >= MAX_SHOW_MS) return Exit.TIMEOUT
        return null
    }

    /** 首帧已经来了，但还没待够最短时间：还差多少毫秒。 */
    fun remainingMs(now: Long, shownAt: Long): Long =
        (MIN_SHOW_MS - elapsedMs(now, shownAt)).coerceAtLeast(0L)
}
