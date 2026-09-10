package com.firefly.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落盘缓存的解析/序列化。
 *
 * 这一层坏掉的后果不是「崩」，而是「电视打不开了」或「少了几十集」——
 * 所以坏输入必须被当成「没有缓存」，而不是抛出异常。
 */
class LibraryCacheTest {

    private val libs = listOf(
        Library.Live("IPTV", "央视.m3u"),
        Library.Video("电视剧"),
    )

    private fun roundTrip(snap: LibraryCache.Snapshot) =
        LibraryCache.parse(LibraryCache.serialize(snap))

    @Test
    fun `序列化再解析能完整还原结构和顺序`() {
        var snap = LibraryCache.withLibraries(null, libs)
        snap = LibraryCache.withShows(snap, "电视剧", listOf("娘道", "大宅门", "猫和老鼠"))
        snap = LibraryCache.withEpisodes(snap, "娘道", listOf("01.mp4", "02.mp4", "10.mp4"), byContent = false)
        snap = LibraryCache.withChannels(
            snap,
            "IPTV",
            listOf(Library.Channel("CCTV1", "http://a/1.m3u8"), Library.Channel("CCTV5", "http://b/5.m3u8")),
        )

        val back = roundTrip(snap)

        assertEquals(2, back.libraries.size)
        assertEquals("IPTV", back.libraries[0].name)
        assertTrue(back.libraries[0] is Library.Live)
        assertEquals("央视.m3u", (back.libraries[0] as Library.Live).m3u)
        assertTrue(back.libraries[1] is Library.Video)

        // 顺序必须保持 —— 顺序错了「下一集」就跳到别的集上去了
        assertEquals(listOf("娘道", "大宅门", "猫和老鼠"), back.showsOf("电视剧"))
        assertEquals(listOf("01.mp4", "02.mp4", "10.mp4"), back.episodesOf("娘道"))
        assertEquals(listOf("CCTV1", "CCTV5"), back.channels["IPTV"]!!.map { it.name })
        assertEquals("http://b/5.m3u8", back.channels["IPTV"]!![1].url)
    }

    @Test
    fun `空文本坏文本半截文本都当作没有缓存`() {
        for (text in listOf("", "   ", "FF1", "garbage", "lib=notanumber|x", "sh=|", "ch=a|b", "ep=|0|")) {
            val snap = LibraryCache.parse(text)
            assertTrue("输入 [$text] 应该被当作空缓存", snap.isEmpty)
        }
    }

    @Test
    fun `旧版本号不会被误读`() {
        // 版本行对不上就整份作废 —— 格式换了还硬读，只会读出乱七八糟的名字
        val snap = LibraryCache.parse("FF9\nlib=0|电视剧\n")
        assertTrue("版本不匹配必须当作没有缓存", snap.isEmpty)
    }

    @Test
    fun `首行不是版本号的文本一律当成没有缓存`() {
        // NAS 上直接读到的 m3u 内容、或者被别的程序写坏的文件，都不能崩
        assertTrue(LibraryCache.parse("#EXTM3U\n#EXTINF:-1,CCTV1\nhttp://x/1.m3u8\n").isEmpty)
    }

    @Test
    fun `名字里的分隔符不会把格式撑破`() {
        // 片名/频道名里可能出现 `|`、`,`、`\t`；地址里几乎一定有 `,`
        val tricky = Library.Channel("CCTV1,综合|高清", "http://h/live.m3u8?a=1,b=2&c=3")
        var snap = LibraryCache.withLibraries(null, listOf(Library.Video("剧|集,合")))
        snap = LibraryCache.withShows(snap, "剧|集,合", listOf("剧|集,合"))
        snap = LibraryCache.withChannels(snap, "IPTV", listOf(tricky))

        val back = roundTrip(snap)

        assertEquals(listOf("剧|集,合"), back.libraries.map { it.name })
        assertEquals(listOf("剧|集,合"), back.showsOf("剧|集,合"))
        assertEquals("CCTV1,综合|高清", back.channels["IPTV"]!![0].name)
        assertEquals("http://h/live.m3u8?a=1,b=2&c=3", back.channels["IPTV"]!![0].url)
    }

    @Test
    fun `剧名里带竖线和逗号也能原样读回`() {
        var snap = LibraryCache.withLibraries(null, listOf(Library.Video("电视剧")))
        snap = LibraryCache.withShows(snap, "电视剧", listOf("名|字", "带,逗号", "还有\t制表符"))
        snap = LibraryCache.withEpisodes(snap, "名|字", listOf("第1集|上.mp4", "第1集,下.mp4"), byContent = false)

        val back = roundTrip(snap)

        assertEquals(listOf("名|字", "带,逗号", "还有\t制表符"), back.showsOf("电视剧"))
        assertEquals(listOf("第1集|上.mp4", "第1集,下.mp4"), back.episodesOf("名|字"))
    }

    @Test
    fun `按内容探出来的集列表不允许复用`() {
        // 这是「娘道 76 集后缀全不可信」那条路：探出来的结果可能不全，
        // 复用会让用户看到少了几十集的假象，所以宁可下次重新探。
        val snap = LibraryCache.withEpisodes(null, "娘道", listOf("01.mp4"), byContent = true)
        assertNull(snap.episodesOf("娘道"))
        // 但原始数据还在，不会被丢掉
        assertEquals(listOf("01.mp4"), snap.episodes["娘道"]!!.names)
    }

    @Test
    fun `换库时只保留还存在的剧的集缓存`() {
        var snap = LibraryCache.withLibraries(null, libs)
        snap = LibraryCache.withShows(snap, "电视剧", listOf("娘道", "大宅门"))
        snap = LibraryCache.withEpisodes(snap, "娘道", listOf("01.mp4"), byContent = false)
        snap = LibraryCache.withEpisodes(snap, "已经删掉的剧", listOf("x.mp4"), byContent = false)

        // NAS 上「大宅门」还在、「娘道」被删了
        val after = LibraryCache.withShows(snap, "电视剧", listOf("大宅门", "新剧"))

        assertNull(after.episodesOf("娘道"))
        assertNull(after.episodesOf("已经删掉的剧"))
        assertEquals(listOf("大宅门", "新剧"), after.showsOf("电视剧"))
    }

    @Test
    fun `重新列剧不会丢掉别的库的剧列表`() {
        var snap = LibraryCache.withLibraries(null, libs)
        snap = LibraryCache.withShows(snap, "电视剧", listOf("娘道"))
        snap = LibraryCache.withShows(snap, "电影", listOf("甲方乙方"))

        assertEquals(listOf("娘道"), snap.showsOf("电视剧"))
        assertEquals(listOf("甲方乙方"), snap.showsOf("电影"))
    }

    @Test
    fun `频道被重新加载时旧频道不会残留`() {
        var snap = LibraryCache.withLibraries(null, libs)
        snap = LibraryCache.withChannels(snap, "IPTV", listOf(Library.Channel("CCTV1", "u1")))
        snap = LibraryCache.withChannels(snap, "IPTV", listOf(Library.Channel("CCTV5", "u5")))

        assertEquals(listOf("CCTV5"), snap.channels["IPTV"]!!.map { it.name })
        assertTrue(snap.hasChannels("IPTV"))
        // 「不知道」和「知道但为空」必须能区分开
        assertFalse(snap.hasChannels("戏曲"))
    }

    @Test
    fun `没有缓存时也能增量写入第一级`() {
        val snap = LibraryCache.withLibraries(null, libs)
        assertFalse(snap.isEmpty)
        assertEquals(2, snap.libraries.size)
        assertTrue(snap.showsOf("电视剧").isEmpty())
    }
}
