package com.firefly.tv.ui

/**
 * 换库过渡页什么时候可以退场。
 *
 * 换库要等 SMB 列目录/读片源，NAS 硬盘休眠时可能是十几秒。过渡页的职责是
 * “把这段时间解释清楚”，所以不能按固定时长走：
 *
 *  - 新内容首帧到了 → 该走了，但至少待满 [MIN_SHOW_MS]，否则大字还没看清就闪走；
 *  - 故障页要说话 → 立刻走，错误不能被“正在切换”盖住；
 *  - 一直没首帧 → 到 [MAX_SHOW_MS] 也必须走，交给左下角提示/故障页。
 *
 * 用户又按了一次换库（[retarget]）时重置计时：他刚按的新库应重新享有完整的 15 秒；
 * 内部自动跳过空库只改文字、不调用 retarget，避免连续跳过把页面挂过 15 秒。
 *
 * 抽成带状态的纯逻辑是为了能用单元测试钉住“最短/最长/故障优先/重定向重置”四条边界。
 */
class FlashGate(
    private val minMs: Long = MIN_SHOW_MS,
    private val maxMs: Long = MAX_SHOW_MS,
) {

    enum class Exit {
        /** 新内容首帧已到，且已经待够最短时间。 */
        READY,

        /** 故障页要说话，立刻让位。 */
        FAULT,

        /** 等到上限还没有画面，交给下面的界面。 */
        TIMEOUT,
    }

    var shownAt = 0L
        private set

    /** 开始一次新的换库。 */
    fun show(now: Long) {
        shownAt = now
    }

    /** 用户主动换到另一个库：重置 15 秒预算。内部自动跳过空库不要调它。 */
    fun retarget(now: Long) {
        shownAt = now
    }

    fun elapsedMs(now: Long): Long = (now - shownAt).coerceAtLeast(0L)

    /** 现在该不该退场；不该就返回 null。故障优先于最短停留。 */
    fun exitReason(now: Long, contentStarted: Boolean, faultVisible: Boolean): Exit? {
        val e = elapsedMs(now)
        if (faultVisible) return Exit.FAULT
        if (contentStarted && e >= minMs) return Exit.READY
        if (e >= maxMs) return Exit.TIMEOUT
        return null
    }

    /** 首帧已经来了，但还没待够最短时间：还差多少毫秒。 */
    fun remainingMs(now: Long): Long = (minMs - elapsedMs(now)).coerceAtLeast(0L)

    companion object {
        /** 最短停留：让中央大字至少完整出现一次。 */
        const val MIN_SHOW_MS = 500L

        /** 最长停留：超过这个数就该把屏幕交给提示条/故障页。 */
        const val MAX_SHOW_MS = 15_000L
    }
}
