package com.firefly.tv.ui

/**
 * 按键之后该给什么视觉反馈。
 *
 * 用户实测的两个问题：
 *  - 「点击左键后页面还是 IPTV」：切库时屏幕没有任何变化，老人不知道键有没有生效，
 *    只能再按 OK 打开浮层去确认「到底换没换」。
 *  - 「可能继续播放或页面卡住」：切库要等 SMB 列目录，这段时间屏幕上什么都没有，
 *    看起来就和死机一样。
 *
 * 所以规则是：**任何一次按键都必须立刻有东西出现在屏幕上**。
 * 换剧/换台先出一条剧名/频道名；换库先出「正在打开 <库名>…」，出来了再换成内容名。
 *
 * 抽成纯逻辑是为了能用测试钉住 —— 之前「按了没反应」这类问题肉眼很难复现。
 */
class SwitchHud(private val briefMs: Long = BRIEF_MS) {

    enum class Style {
        /** 什么都不显示 */
        NONE,

        /** 只显示一条内容名/库名，不挡画面 */
        BRIEF,

        /** 显示「正在打开…」，说明还在等 NAS */
        LOADING,
    }

    /**
     * @param title 要显示的名字（剧名/频道名/库名）
     * @param subtitle 第二行；[Style.LOADING] 时用来说明在等什么
     */
    class State(val style: Style, val title: String, val subtitle: String) {
        companion object {
            val IDLE = State(Style.NONE, "", "")
        }
    }

    private var deadline = 0L
    private var current = State.IDLE

    val state: State get() = current

    /** 要显示就返回状态，否则返回 null（调用方据此隐藏视图）。 */
    fun peek(now: Long): State? {
        if (current.style == Style.NONE) return null
        if (deadline != 0L && now >= deadline) {
            current = State.IDLE
            return null
        }
        return current
    }

    /** 换库：先说「正在打开」，避免看起来像卡死。 */
    fun onLibrarySwitch(libName: String) {
        current = State(Style.LOADING, libName, SUBTITLE_LOADING)
        deadline = 0L // 不超时：SMB 慢的时候必须一直留着提示，不能中途消失
    }

    /** 内容真的开始播了：把「正在打开」换成内容名，并开始倒计时。 */
    fun onPlaying(title: String, now: Long) {
        if (current.style == Style.NONE) return
        current = State(Style.BRIEF, title, "")
        deadline = now + briefMs
    }

    /** 换剧/换台：直接出一条内容名。 */
    fun onContentSwitch(title: String, now: Long) {
        current = State(Style.BRIEF, title, "")
        deadline = now + briefMs
    }

    /** 出错：立刻收起，让故障页独占屏幕。 */
    fun dismiss() {
        current = State.IDLE
        deadline = 0L
    }

    companion object {
        const val BRIEF_MS = 1_600L

        /**
         * 「正在打开」时标题上挂的前缀。
         * 直接写「电视剧」的话，和剧名混在一起分不清是库还是片名；
         * 加上书名号之后用户一眼就知道「这是这个库」。
         */
        const val SUBTITLE_LOADING = "正在打开…"

        fun libraryTitle(libName: String): String = "《$libName》"
    }
}
