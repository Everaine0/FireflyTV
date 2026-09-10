package com.firefly.tv.ui

/**
 * 音频焦点 ↔ 播放行为。
 *
 * 两个真实踩过的坑：
 *  1. 电视上别的应用占着声音通道时，不申请焦点会表现成「有画面没声音」。
 *  2. 焦点丢了以后如果按「剧集」的语义处理直播，就会把直播当成视频去 seek ——
 *     而直播的 PTS 是从开机算起的一个大数（实测娘道那条流 start_time=54285 秒），
 *     seek 进去就是长时间音画不同步。所以**直播永远不 seek**（DESIGN §5）。
 */
object AudioFocusPolicy {

    /** 当前在播什么 —— 决定焦点变化时敢不敢用「秒」这个单位。 */
    enum class Content {
        /** 点播：可以记住精确进度，可以 seek */
        OnDemand,

        /** 直播：没有进度概念，只能整条重起 */
        Live,

        /** 还没开始播 */
        None,
    }

    sealed class Action {
        /** 继续播，什么都不用做 */
        object Continue : Action()

        /** 暂停，等焦点回来再原样续上 */
        object PauseTransient : Action()

        /** 彻底丢了：停掉并释放，用户回来再重播 */
        object StopAndAbandon : Action()

        /**
         * 重播。
         * @param resumeMs 只有 [Content.OnDemand] 才是有效进度；直播恒为 0。
         */
        class Replay(val content: Content, val resumeMs: Long) : Action()
    }

    /**
     * @param content 当前内容类型
     * @param focusChange AudioManager 的焦点变化常量
     * @param positionMs 播放器当前进度；直播会被忽略
     */
    fun decide(content: Content, focusChange: Int, positionMs: Long): Action = when (content) {
        Content.None -> Action.Continue

        Content.OnDemand -> when (focusChange) {
            FOCUS_GAIN -> Action.Replay(Content.OnDemand, positionMs.coerceAtLeast(0L))
            FOCUS_LOSS_TRANSIENT, FOCUS_LOSS_TRANSIENT_CAN_DUCK -> Action.PauseTransient
            FOCUS_LOSS -> Action.StopAndAbandon
            else -> Action.Continue
        }

        Content.Live -> when (focusChange) {
            // 直播重播就是从当前时刻重新起播，传 0 表示「别 seek」
            FOCUS_GAIN -> Action.Replay(Content.Live, 0L)
            FOCUS_LOSS_TRANSIENT, FOCUS_LOSS_TRANSIENT_CAN_DUCK -> Action.PauseTransient
            FOCUS_LOSS -> Action.StopAndAbandon
            else -> Action.Continue
        }
    }

    private const val FOCUS_GAIN = android.media.AudioManager.AUDIOFOCUS_GAIN
    private const val FOCUS_LOSS = android.media.AudioManager.AUDIOFOCUS_LOSS
    private const val FOCUS_LOSS_TRANSIENT = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    private const val FOCUS_LOSS_TRANSIENT_CAN_DUCK =
        android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
}
