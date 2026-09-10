package com.firefly.tv.ui

import com.firefly.tv.media.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按键导航决策的单元测试。
 *
 * 这些用例对应真实使用中踩到的坑：
 * 「在《娘道》上按上键，画面跳回了 CCTV5」—— 那是切库时没清掉上一个库的缓存，
 * 于是用 IPTV 遗留的频道列表去换台。用类型 + 缓存归属把这个洞堵死后，这里钉住行为。
 */
class NavigatorTest {

    private val video = Library.Video("电视剧")
    private val live = Library.Live("IPTV", "央视.m3u")

    private val shows = listOf("娘道", "大宅门", "猫和老鼠")
    private val channels = listOf(
        Library.Channel("CCTV1", "http://x/1.m3u8"),
        Library.Channel("CCTV3", "http://x/3.m3u8"),
        Library.Channel("CCTV5", "http://x/5.m3u8"),
    )

    @Test
    fun `视频库里按上键只会在剧之间走_绝不会跳去换台`() {
        val action = Navigator.vertical(
            lib = video,
            shows = shows,
            channels = emptyList(),          // 切到视频库后频道缓存必须是空的
            current = Navigator.Current.Show("大宅门"),
            delta = -1,
        )
        assertTrue("应是换剧，实际 $action", action is Navigator.Action.PlayShow)
        assertEquals(0, (action as Navigator.Action.PlayShow).showIndex)   // 大宅门 -> 娘道
    }

    @Test
    fun `视频库里按上键到第一部再按上回到最后一部`() {
        val action = Navigator.vertical(video, shows, emptyList(), Navigator.Current.Show("娘道"), -1)
        assertEquals(2, (action as Navigator.Action.PlayShow).showIndex)
    }

    @Test
    fun `视频库里按下键到循环回第一部`() {
        val action = Navigator.vertical(video, shows, emptyList(), Navigator.Current.Show("猫和老鼠"), +1)
        assertEquals(0, (action as Navigator.Action.PlayShow).showIndex)
    }

    @Test
    fun `视频库换剧总是从第一集开始`() {
        val action = Navigator.vertical(video, shows, emptyList(), Navigator.Current.Show("娘道"), +1) as Navigator.Action.PlayShow
        assertEquals("按 DESIGN §5，换剧播第 1 集", 0, action.episodeIndex)
        assertEquals("换剧不续播", 0L, action.startMs)
    }

    @Test
    fun `直播库里按上下只会在频道之间走`() {
        val action = Navigator.vertical(live, emptyList(), channels, Navigator.Current.Channel("CCTV3"), +1)
        assertTrue("应换台，实际 $action", action is Navigator.Action.TuneChannel)
        assertEquals(2, (action as Navigator.Action.TuneChannel).channelIndex)
    }

    @Test
    fun `直播库换台会循环`() {
        val action = Navigator.vertical(live, emptyList(), channels, Navigator.Current.Channel("CCTV5"), +1)
        assertEquals(0, (action as Navigator.Action.TuneChannel).channelIndex)
    }

    @Test
    fun `缓存没就绪时按键什么也不做_不猜不乱跳`() {
        // 视频库但剧列表还没加载出来
        assertEquals(Navigator.Action.Wait, Navigator.vertical(video, emptyList(), emptyList(), Navigator.Current.None, +1))
        // 直播库但频道还没加载出来
        assertEquals(Navigator.Action.Wait, Navigator.vertical(live, emptyList(), emptyList(), Navigator.Current.None, +1))
        // 还没选库
        assertEquals(Navigator.Action.Wait, Navigator.vertical(null, shows, channels, Navigator.Current.None, +1))
    }

    @Test
    fun `当前播的是频道但库已经切到视频库时_按上键从第一部剧开始`() {
        // 这正是出问题的那一刻：库切过去了，但"正在播"的语义还停在频道上。
        // 正确行为是当作「没在当前库里播任何剧」→ 从第一部开始，而不是去换台。
        val action = Navigator.vertical(video, shows, emptyList(), Navigator.Current.Channel("CCTV5"), +1)
        assertTrue("绝不能返回换台，实际 $action", action is Navigator.Action.PlayShow)
        assertEquals(0, (action as Navigator.Action.PlayShow).showIndex)
    }

    @Test
    fun `左右键切换库会循环`() {
        assertEquals(1, Navigator.horizontal(0, 3, +1))
        assertEquals(2, Navigator.horizontal(0, 3, -1))   // 负方向要绕回末尾
        assertEquals(0, Navigator.horizontal(2, 3, +1))
        assertEquals(0, Navigator.horizontal(0, 2, -2))
    }

    @Test
    fun `只有一个库时左右键停在原地`() {
        assertEquals(0, Navigator.horizontal(0, 1, +1))
        assertEquals(0, Navigator.horizontal(0, 1, -1))
    }

    @Test
    fun `没有库时不会越界`() {
        assertEquals(0, Navigator.horizontal(0, 0, +1))
    }
}
