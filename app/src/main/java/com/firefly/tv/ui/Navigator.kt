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
     * @param resume 「这部剧看到哪儿了」的查询。默认谁都没看过 → 从第 1 集第 0 秒开始。
     *
     * ## 为什么这里要接一个 resume
     *
     * 用户实测的问题：**换到别的剧再换回来，只能从头看**。
     * 原来这里写死 `PlayShow(next, 0, 0L)` —— 不管这部剧看过没有、
     * 看到第几集第几分钟，一律从第 1 集开头起。
     * 现在交给调用方按「库 + 剧」查记录（[com.firefly.tv.media.WatchHistory]）。
     */
    fun vertical(
        lib: Library?,
        shows: List<String>,
        channels: List<Library.Channel>,
        current: Current,
        delta: Int,
        resume: (String) -> Resume = { Resume.FIRST },
    ): Action = when (lib) {
        is Library.Video -> {
            if (shows.isEmpty()) {
                Action.Wait
            } else {
                val cur = (current as? Current.Show)?.let { shows.indexOf(it.name) } ?: -1
                val next = if (cur < 0) 0 else wrap(cur + delta, shows.size)
                val at = resume(shows[next])
                Action.PlayShow(next, at.episode, at.positionMs)
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
     * 库列表被刷新替换后，按**名字**把当前所在的库重新找回来。
     *
     * 为什么必须重新锚：落盘缓存里的库列表和 NAS 上实际的会不一样 —— 用户新加一个文件夹
     * （这次的「测试」就是这么来的）以后，新列表在中间插入一项，**旧下标指向的已经是另一个库**。
     * 实机表现（已复现）：在「电视剧」上按 ↓ 毫无反应（下标越界 → 拿到的库是 null），
     * 按 → 却跳到了「测试」。所以列表一换，下标就得按名字重算；名字没了才退回 0。
     */
    fun libraryIndexAfterRefresh(libs: List<Library>, wasOn: String?): Int {
        if (wasOn.isNullOrBlank()) return 0
        val i = libs.indexOfFirst { it.name == wasOn }
        return if (i >= 0) i else 0
    }

    /**
     * 「空库跳过」是不是已经转完一整圈了。
     *
     * 空的媒体库该跳过（DESIGN §4），但**必须封顶**：实机上「平铺的库被判成空库」
     * 那次的画面就是唰唰唰跳个不停 —— 每跳一次一轮 SMB 列目录，转完一圈接着转，
     * 永远不停，也不说为什么。转完一圈就说明不是某一个库的问题，
     * 该停下来报故障，而不是继续转。
     */
    fun skipExhausted(skipped: Int, libraryCount: Int): Boolean =
        libraryCount <= 0 || skipped >= libraryCount

    /**
     * 一部剧从哪儿接着看。
     *
     * 「按上键跳回 CCTV5」那类串库 bug 在这里从结构上消失了：
     * 记录按「库 + 剧」存（[com.firefly.tv.media.WatchHistory]），
     * 查出来的必然属于当前这个库，不需要再拿库名去比一次。
     */
    class Resume(val episode: Int, val positionMs: Long) {
        companion object {
            /** 没看过 → 第 1 集开头。 */
            val FIRST = Resume(0, 0L)
        }
    }

    /** 取模并保证结果非负（Kotlin 的 % 对负数返回负值）。 */
    private fun wrap(v: Int, size: Int): Int = ((v % size) + size) % size
}
