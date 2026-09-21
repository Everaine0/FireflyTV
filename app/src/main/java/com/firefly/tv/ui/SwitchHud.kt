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

    /**
     * 「有过一次还没兑现的换库」。
     *
     * 为什么不能只看 [current]：加载条到 [LOADING_MS] 会自己退场（把 [current] 置回 IDLE），
     * 而内容可能在那之后才起来 —— 这时仍然该把名字报出来（见 [onPlaying]）。
     */
    private var pending = false

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

    /**
     * 换库：先说「正在打开」，避免看起来像卡死。
     *
     * ⚠️ 这里原来写的是 `deadline = 0L`（= **永不超时**），注释理由是「SMB 慢的时候必须
     * 一直留着提示」。问题是它没有任何出口兜底 —— 一旦后面那条链子断在那儿，屏幕上就
     * 永远挂着这句话，用户看到的就是「卡在加载不动」（实机反馈）。
     *
     * 所以现在给一个**很宽但有限**的期限：[LOADING_MS]。真慢到这个数还在等，
     * 说明不是"慢"而是出事了，让提示自己退场，把屏幕交给故障页或启动看门狗
     * （`MainActivity.startupWatchdog` 会在更早的时候接手并自动重启）。
     */
    fun onLibrarySwitch(libName: String, now: Long) {
        current = State(Style.LOADING, libName, SUBTITLE_LOADING)
        pending = true
        deadline = now + LOADING_MS
    }

    /** 内容真的开始播了：把「正在打开」换成内容名，并开始倒计时。 */
    fun onPlaying(title: String, now: Long) {
        // 注意判据是"之前是不是 LOADING"，不是"现在有没有东西在显示"：
        // 加载条可能已经到期限自己退场了（见 [LOADING_MS]），这时内容才姗姗来迟 ——
        // 那也该出一下名字，否则换台成功了屏幕上却一点确认都没有。
        val wasPending = current.style == Style.LOADING || pending
        if (!wasPending) return
        pending = false
        current = State(Style.BRIEF, title, "")
        deadline = now + briefMs
    }

    /** 换剧/换台：直接出一条内容名。 */
    fun onContentSwitch(title: String, now: Long) {
        pending = false
        current = State(Style.BRIEF, title, "")
        deadline = now + briefMs
    }

    /**
     * 还没定位到具体是哪个库时的过渡提示（"正在连接 NAS…"）。
     *
     * 为什么需要：`startPlayback()` 先把故障页清掉，然后才去列库 —— 而"是哪个库"
     * 要等列完才知道。这一段（尤其是**没有缓存**的时候）屏幕上曾经什么都没有：
     * 老人面对一片黑，只能靠重新开关机碰运气。有了它，从清故障页到出内容之间
     * 始终有东西在动。
     */
    fun onRestoring(now: Long) {
        current = State(Style.LOADING, RESTORING, SUBTITLE_RESTORING)
        pending = true
        deadline = now + LOADING_MS
    }

    /** 出错：立刻收起，让故障页独占屏幕。 */
    fun dismiss() {
        current = State.IDLE
        pending = false
        deadline = 0L
    }

    companion object {
        const val BRIEF_MS = 1_600L

        /**
         * 「正在打开…」最多挂多久。
         *
         * 取 60 秒：正常切库/开机是几百毫秒到几秒，SMB 慢的时候十几秒也见过；
         * 60 秒还在等就一定是出事了（而不是慢），该让位给故障页。
         * 还有一层保险在 `MainActivity.startupWatchdog`（40 秒，会真的自动重启），
         * 所以正常情况下用户不会看到提示挂满 60 秒。
         */
        const val LOADING_MS = 60_000L

        /**
         * 「正在打开」时标题上挂的前缀。
         * 直接写「电视剧」的话，和剧名混在一起分不清是库还是片名；
         * 加上书名号之后用户一眼就知道「这是这个库」。
         */
        const val SUBTITLE_LOADING = "正在打开…"

        /** 还没定位到库名时的过渡标题/副标题（见 [onRestoring]）。 */
        const val RESTORING = "正在连接 NAS"
        const val SUBTITLE_RESTORING = "马上就好…"

        fun libraryTitle(libName: String): String = "《$libName》"
    }
}
