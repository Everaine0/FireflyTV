package com.firefly.tv.ui

/**
 * 方向键防抖：老人容易连按，而每一次换库都可能是一轮 SMB 列目录。
 *
 * 规则：第一次按下立刻放行，之后 [WINDOW_MS] 内的后续方向键忽略。
 * 长按连发（repeatCount > 0）已经在 MainActivity 里单独吞掉，这里只处理首次按下。
 * OK、设置/信息键不走这里。
 *
 * 窗口取 400ms：和上下翻页的 0.4 秒对齐 —— 上一页刚翻完，下一次按键才被放行，
 * 连按不会在屏幕上叠两层过渡页。时间用单调时钟传入，避免系统时间调整造成误判。
 */
class KeyGuard(private val windowMs: Long = WINDOW_MS) {

    private var lastAt: Long? = null

    /** @return true = 这次按键放行；false = 还在防抖窗口内，忽略 */
    fun allow(now: Long): Boolean {
        val last = lastAt
        // now < last 只可能发生在时钟回拨/测试里：按一次新的处理，不把用户永久锁住
        if (last != null && now >= last && now - last < windowMs) return false
        lastAt = now
        return true
    }

    companion object {
        const val WINDOW_MS = 400L
    }
}
