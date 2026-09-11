package com.firefly.tv.ui

import com.firefly.tv.media.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- 换剧时的续播点 ----

    /**
     * 用户实测的问题：「**换到别的剧再换回来，只能从头看**」。
     *
     * 原来是写死的 `PlayShow(next, 0, 0L)`。现在按「库 + 剧」查记录：
     * 看过的剧回到上次那一集那一分钟，没看过的（或看得太少）还是第 1 集开头。
     */
    @Test
    fun `换到看过的剧会回到上次那一集那一分钟`() {
        val lib = Library.Video("电视剧")
        val shows = listOf("娘道", "大宅门", "猫和老鼠")
        val action = Navigator.vertical(
            lib = lib,
            shows = shows,
            channels = emptyList(),
            current = Navigator.Current.Show("娘道"),
            delta = +1,
            resume = { if (it == "大宅门") Navigator.Resume(2, 615_000L) else Navigator.Resume.FIRST },
        ) as Navigator.Action.PlayShow
        assertEquals(1, action.showIndex)
        assertEquals(2, action.episodeIndex)
        assertEquals(615_000L, action.startMs)
    }

    @Test
    fun `换到没看过的剧还是从第一集开头开始`() {
        val action = Navigator.vertical(
            lib = Library.Video("电视剧"),
            shows = listOf("娘道", "大宅门"),
            channels = emptyList(),
            current = Navigator.Current.Show("娘道"),
            delta = +1,
        ) as Navigator.Action.PlayShow
        assertEquals(0, action.episodeIndex)
        assertEquals(0L, action.startMs)
    }

    @Test
    fun `续播查询给的是被换到的那部剧`() {
        // 传错剧名就等于「拿 A 剧的进度去播 B 剧」，所以这里把入参钉死
        val asked = ArrayList<String>()
        Navigator.vertical(
            lib = Library.Video("电视剧"),
            shows = listOf("娘道", "大宅门", "猫和老鼠"),
            channels = emptyList(),
            current = Navigator.Current.Show("娘道"),
            delta = -1, // 从第 0 部往回绕 → 最后一部
            resume = { asked += it; Navigator.Resume.FIRST },
        )
        assertEquals(listOf("猫和老鼠"), asked)
    }

    @Test
    fun `直播换台不受续播影响`() {
        val action = Navigator.vertical(
            lib = Library.Live("IPTV", "央视.m3u"),
            shows = emptyList(),
            channels = listOf(
                Library.Channel("CCTV1", "http://a"),
                Library.Channel("CCTV3", "http://b"),
            ),
            current = Navigator.Current.Channel("CCTV1"),
            delta = +1,
            resume = { error("直播不该走这部剧续播的查询") },
        ) as Navigator.Action.TuneChannel
        assertEquals(1, action.channelIndex)
    }

    @Test
    fun `直播间里上下键不会去查剧的记录`() {
        // current 是频道、shows 是空 —— 任何一步走错都会变成「按上键跳台」
        val action = Navigator.vertical(
            lib = Library.Live("IPTV", "央视.m3u"),
            shows = emptyList(),
            channels = listOf(Library.Channel("CCTV1", "http://a")),
            current = Navigator.Current.None,
            delta = +1,
        )
        assertTrue(action is Navigator.Action.TuneChannel)
    }

    // ---- 空库跳过 ----

    @Test
    fun `空库跳过必须封顶`() {
        // 实机反馈「这个媒体库打不开，会快速跳过」：平铺的库被判成空库以后，
        // 跳过没有上限 —— 转完一圈接着转，屏幕上就是唰唰唰跳个不停，也不说为什么。
        assertFalse(Navigator.skipExhausted(skipped = 0, libraryCount = 3))
        assertFalse(Navigator.skipExhausted(skipped = 2, libraryCount = 3))
        assertTrue("转完一圈就该停", Navigator.skipExhausted(skipped = 3, libraryCount = 3))
        assertTrue("越过一圈更不能继续", Navigator.skipExhausted(skipped = 4, libraryCount = 3))
    }

    @Test
    fun `一个库都没有时跳过立即算穷尽`() {
        // 否则「没有库」和「还在跳」会互相绕开，兜底那条路永远走不到
        assertTrue(Navigator.skipExhausted(skipped = 0, libraryCount = 0))
    }

    // ---- 库列表刷新后的重新锚定 ----

    /**
     * 这一组对应实机复现的实验：缓存里是「IPTV / 测试 / _probe2 / 电视剧」四个库
     * （_probe2 后来在 NAS 上删掉了），冷启动先按缓存放《娘道》，后台扫完把列表换成三个。
     * 不重新锚定的话，旧下标 3 在这张表里已经越界：**↓ 毫无反应、→ 跳到「测试」**。
     */
    @Test
    fun `列表少了前面的库时当前库按名字找回来`() {
        val cached = listOf(
            Library.Video("IPTV"), Library.Video("测试"), Library.Video("_probe2"), Library.Video("电视剧"),
        )
        val fresh = listOf(Library.Video("IPTV"), Library.Video("测试"), Library.Video("电视剧"))
        val wasOn = cached[3].name // 正在播《娘道》= 电视剧
        assertEquals(2, Navigator.libraryIndexAfterRefresh(fresh, wasOn))
        // 锚对了以后按键语义才对：→ 从「电视剧」到下一个库（IPTV）
        assertEquals("IPTV", fresh[Navigator.horizontal(2, fresh.size, +1)].name)
    }

    @Test
    fun `列表多出前面的库时同样按名字锚定`() {
        val fresh = listOf(Library.Video("IPTV"), Library.Video("测试"), Library.Video("电视剧"))
        // 用户刚在 NAS 上加了「测试」：旧列表里「电视剧」是 1，新列表里是 2
        assertEquals(2, Navigator.libraryIndexAfterRefresh(fresh, "电视剧"))
    }

    @Test
    fun `当前库在 NAS 上被删了就退回第一个库`() {
        val fresh = listOf(Library.Video("IPTV"), Library.Video("测试"))
        assertEquals(0, Navigator.libraryIndexAfterRefresh(fresh, "已经删掉的库"))
    }

    @Test
    fun `还没播过任何库时从头开始`() {
        val fresh = listOf(Library.Video("IPTV"), Library.Video("测试"))
        assertEquals(0, Navigator.libraryIndexAfterRefresh(fresh, null))
        assertEquals(0, Navigator.libraryIndexAfterRefresh(fresh, ""))
    }
}
