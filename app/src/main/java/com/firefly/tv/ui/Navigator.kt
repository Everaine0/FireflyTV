package com.firefly.tv.ui

import com.firefly.tv.media.Library

/**
 * 按键导航的决策逻辑，与界面/网络解耦以便单元测试。
 *
 * 抽出来的原因：按键行为的 bug（尤其是「上键跑错库」）光靠肉眼看不出来，
 * 而这类状态串味一旦回归，用户看到的是「整部剧不见了」或者「按上键跳台」，
 * 所以必须能用测试钉住。
 */
object Navigator {

    /**
     * 当前正在播的内容。**只由视频库产生** —— 直播库不设剧名，
     * 这样「视频库里按↑↓会不会串到频道」这类问题在类型上就不可能发生。
     */
    sealed class Current {
        object None : Current()
        class Show(val name: String) : Current()
        class Channel(val name: String) : Current()
    }

    sealed class Action {
        /** 播放该库里第 [showIndex] 部剧的第 [episodeIndex] 集，从 [startMs] 起 */
        class PlayShow(val showIndex: Int, val episodeIndex: Int, val startMs: Long) : Action()

        /** 切到第 [channelIndex] 个频道 */
        class TuneChannel(val channelIndex: Int) : Action()

        /** 缓存还没就绪，什么也别做（不要猜、不要跳） */
        object Wait : Action()
    }

    /**
     * 上下键。
     *
     * @param lib 当前库（决定语义：视频库换剧，直播库换频道）
     * @param shows 当前库的剧列表；直播库传空
     * @param channels 当前库的频道列表；视频库传空
     * @param current 正在播的内容
     */
    fun vertical(
        lib: Library?,
        shows: List<String>,
        channels: List<Library.Channel>,
        current: Current,
        delta: Int,
    ): Action = when (lib) {
        is Library.Video -> {
            if (shows.isEmpty()) {
                Action.Wait
            } else {
                val cur = (current as? Current.Show)?.let { shows.indexOf(it.name) } ?: -1
                val next = if (cur < 0) 0 else wrap(cur + delta, shows.size)
                Action.PlayShow(next, 0, 0L)
            }
        }

        is Library.Live -> {
            if (channels.isEmpty()) {
                Action.Wait
            } else {
                val cur = (current as? Current.Channel)?.let { c -> channels.indexOfFirst { it.name == c.name } } ?: -1
                val next = if (cur < 0) 0 else wrap(cur + delta, channels.size)
                Action.TuneChannel(next)
            }
        }

        null -> Action.Wait
    }

    /** 左右键：切换媒体库，返回目标库下标。 */
    fun horizontal(currentIndex: Int, count: Int, delta: Int): Int =
        if (count <= 0) 0 else wrap(currentIndex + delta, count)

    /**
     * 切库时该从哪儿开始播。
     *
     * 这是「按上键跳回 CCTV5」那类串库 bug 的根：记忆里存着「哪个库、哪部剧、第几集」，
     * 如果拿它去另一个库上找，找到的就是另一部内容。所以只有**记忆里的库 == 目标库**
     * 时才认这份记忆，否则一律当作「第一次看这个库」。
     */
    class StartPoint(val show: String, val episode: Int, val positionMs: Long) {
        companion object {
            val FIRST = StartPoint("", 0, 0L)
        }
    }

    fun startPoint(rememberedLib: String, targetLib: String, show: String, episode: Int, positionMs: Long): StartPoint =
        if (rememberedLib == targetLib) {
            StartPoint(show, episode, positionMs.coerceAtLeast(0L))
        } else {
            StartPoint("", 0, 0L)
        }

    /** 取模并保证结果非负（Kotlin 的 % 对负数返回负值）。 */
    private fun wrap(v: Int, size: Int): Int = ((v % size) + size) % size
}
