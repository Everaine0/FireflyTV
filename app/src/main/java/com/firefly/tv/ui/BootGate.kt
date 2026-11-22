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
     * 计时的起点：**页面真正画到屏幕上那一刻**。
     *
     * 为什么不能在 `show()` 里就开始计时：冷启动（尤其是开机自启）时，
     * Activity 的 onCreate 比窗口真正出画早得多 —— 电视越慢差得越多。
     * 实测反馈「开机动画大概 2 秒就没了」，就是最短 5 秒里有一截耗在了
     * 页面还没露面的时候。以首帧绘制（[drawnAt]）为起点，用户看到的才是完整的 5 秒。
     *
     * @param drawnAt 首次绘制时刻；0 = 还没画过（此时把起点推到 now，等于刚开始计时）
     */
    fun visibleAt(drawnAt: Long, now: Long): Long = if (drawnAt > 0L) drawnAt else now

    /**
     * 开机页最多替故障页挡几次「会自动重试的失败」。
     *
     * 开机那十几秒里 NAS 没连上、列目录超时这类失败**本来就会自动重试**，
     * 以前它们一出现就把开机页顶掉，用户看到的就是「动画闪一下，接着还是旧版那句打不开」。
     * 现在先在开机页的状态行里把原因写出来；连续 [MAX_TRANSIENT_FAULTS] 次仍不行才让位。
     */
    const val MAX_TRANSIENT_FAULTS = 2

    /**
     * 故障页要说话时，开机页现在该不该让位。
     *
     * @param retryable 这个故障会不会自动重试（见 MainActivity.showFault 的 retry 参数）
     * @param transientFaults 开机页显示期间已经挡下的可重试故障次数（含这一次）
     */
    fun yieldsToFault(retryable: Boolean, transientFaults: Int): Boolean =
        !retryable || transientFaults > MAX_TRANSIENT_FAULTS

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
