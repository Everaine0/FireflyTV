package com.firefly.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 观看记录：**每部剧各记各的**。
 *
 * 对应用户反馈：「除直播外的其他影视库没有做播放记录功能 —— 无法做到切换到具体影视时
 * 自动跳转到记录时间；考虑到老人不看了会关闭电视，所以要做到关闭不影响，可以有 10s 左右的偏差」。
 *
 * 记录坏掉的正确表现是「当作没记录」（从头播），**绝不能打不开电视**，
 * 所以解析的容错分支也要逐条钉住。
 */
class WatchHistoryTest {

    private fun rec(show: String, ep: Int, pos: Long, at: Long, lib: String = "电视剧") =
        WatchHistory.Record(lib, show, ep, pos, at)

    @Test
    fun `写进去再读出来，每部剧各是各的`() {
        var book = WatchHistory.Book.EMPTY
        book = WatchHistory.with(book, rec("娘道", 3, 600_000L, 1000L))
        book = WatchHistory.with(book, rec("大宅门", 0, 263_067L, 2000L))

        val back = WatchHistory.parse(WatchHistory.serialize(book))
        assertEquals(2, back.records.size)
        assertEquals(3, back.find("电视剧", "娘道")?.episode)
        assertEquals(600_000L, back.find("电视剧", "娘道")?.posMs)
        assertEquals(263_067L, back.find("电视剧", "大宅门")?.posMs)
    }

    @Test
    fun `同一个库换剧不会覆盖上一部剧的记录`() {
        // 这条正是老实现的病根：只有一份全局记忆，换一部剧就把上一部的位置盖掉
        var book = WatchHistory.with(WatchHistory.Book.EMPTY, rec("娘道", 5, 300_000L, 1L))
        book = WatchHistory.with(book, rec("大宅门", 1, 60_000L, 2L))
        assertEquals(5, book.find("电视剧", "娘道")?.episode)
        assertEquals(1, book.find("电视剧", "大宅门")?.episode)
    }

    @Test
    fun `不同库里的同名剧互不干扰`() {
        var book = WatchHistory.Book.EMPTY
        book = WatchHistory.with(book, rec("猫和老鼠", 1, 10_000L, 1L, lib = "电视剧"))
        book = WatchHistory.with(book, rec("猫和老鼠", 9, 90_000L, 2L, lib = "动画片"))
        assertEquals(1, book.find("电视剧", "猫和老鼠")?.episode)
        assertEquals(9, book.find("动画片", "猫和老鼠")?.episode)
    }

    @Test
    fun `挑出这个库里最近看的那一部`() {
        var book = WatchHistory.Book.EMPTY
        book = WatchHistory.with(book, rec("娘道", 3, 100L, 1000L))
        book = WatchHistory.with(book, rec("大宅门", 7, 200L, 5000L))
        book = WatchHistory.with(book, rec("猫和老鼠", 1, 300L, 3000L))
        book = WatchHistory.with(book, rec("别的库的剧", 1, 400L, 9000L, lib = "电影"))

        assertEquals("大宅门", book.mostRecentIn("电视剧")?.show)
        assertEquals("别的库的剧", book.mostRecentIn("电影")?.show)
        assertNull("这个库没看过就该是 null（开机才能退回「第一部剧第 1 集」）", book.mostRecentIn("动画片"))
    }

    @Test
    fun `上次用的是哪个库也要记住（直播也算）`() {
        // 用户要求：开机回到「上一次使用的影视库」；直播库只记频道、不记时长
        val book = WatchHistory.withLastLib(WatchHistory.Book.EMPTY, "IPTV")
        assertEquals("IPTV", WatchHistory.parse(WatchHistory.serialize(book)).lastLib)
    }

    // ---- 续播阈值 ----

    @Test
    fun `只看了一眼就不续播`() {
        // 老人点开看了一眼就换台，从第 8 秒开始播会让人以为「怎么跳到中间了」
        assertEquals(0L, rec("娘道", 0, 8_000L, 1L).resumeMs)
        assertEquals(0L, rec("娘道", 0, 14_999L, 1L).resumeMs)
    }

    @Test
    fun `看过一会儿就从原处接着看`() {
        assertEquals(15_000L, rec("娘道", 0, 15_000L, 1L).resumeMs)
        assertEquals(1_234_000L, rec("娘道", 0, 1_234_000L, 1L).resumeMs)
    }

    // ---- 容错 ----

    @Test
    fun `空内容和坏内容都当作没有记录`() {
        assertTrue(WatchHistory.parse(null).isEmpty)
        assertTrue(WatchHistory.parse("").isEmpty)
        assertTrue(WatchHistory.parse("随便什么东西").isEmpty)
        assertTrue(WatchHistory.parse("FF1\nr=字段不够").isEmpty)
        assertTrue(WatchHistory.parse("FF1\nr=电视剧|娘道|不是数字|0|0").isEmpty)
        // 版本不对 → 整体作废（格式变了就是不能复用）
        assertTrue(WatchHistory.parse("WH0\nr=电视剧|娘道|0|100|1").isEmpty)
    }

    @Test
    fun `坏行跳过，好行照常读出来`() {
        val text = "WH1\nlib=电视剧\nr=坏行\nr=电视剧|娘道|2|500|99\n"
        val book = WatchHistory.parse(text)
        assertEquals(1, book.records.size)
        assertEquals(2, book.find("电视剧", "娘道")?.episode)
        assertEquals("电视剧", book.lastLib)
    }

    @Test
    fun `剧名里有竖线也不会把记录读坏`() {
        // 剧名来自文件名，什么字符都可能有；转义写错的话换剧就会「找不到这部剧」
        val weird = "老剧|第一部\t番外"
        val book = WatchHistory.with(WatchHistory.Book.EMPTY, rec(weird, 4, 12_345L, 7L))
        val back = WatchHistory.parse(WatchHistory.serialize(book))
        assertEquals(4, back.find("电视剧", weird)?.episode)
        assertEquals(12_345L, back.find("电视剧", weird)?.posMs)
    }

    @Test
    fun `负数被夹成零，不会拿负数去 seek`() {
        val book = WatchHistory.parse("WH1\nr=电视剧|娘道|-3|-999|1")
        assertEquals(0, book.find("电视剧", "娘道")?.episode)
        assertEquals(0L, book.find("电视剧", "娘道")?.posMs)
    }

    @Test
    fun `记录条数有上限，只留最近的`() {
        var book = WatchHistory.Book.EMPTY
        for (i in 1..WatchHistory.MAX_RECORDS + 50) {
            book = WatchHistory.with(book, rec("剧$i", 0, i.toLong(), i.toLong()))
        }
        val back = WatchHistory.parse(WatchHistory.serialize(book))
        assertEquals(WatchHistory.MAX_RECORDS, back.records.size)
        // 最新的那条必须在（刚看完的那部最要紧）
        val newest = WatchHistory.MAX_RECORDS + 50
        assertEquals(newest.toLong(), back.find("电视剧", "剧$newest")?.posMs)
        assertNull("最老的应该被挤掉了", back.find("电视剧", "剧1"))
    }

    // ---- 写盘判据（「多次切换以后记录丢了」的正解） ----

    /**
     * 用户报的 bug：**多次切换/关机以后播放记录丢了**。
     *
     * 现场：换剧、换库、退到后台都会**立刻**写一条（`force = true`），而那一刻
     * 播放器往往刚被重建（`prepareAsync` 还没走完、硬解退回软解重播中、已经 release），
     * `positionMs()` 回的是 0。老实现把 0 照收 —— 0 不是「看到了片头」，是「问不出来」，
     * 写下去等于把上一次存的好进度抹掉，换回来只能从头看。
     */
    @Test
    fun `问不到进度时绝不写盘，好记录不会被抹成 0`() {
        var book = WatchHistory.with(WatchHistory.Book.EMPTY, rec("大宅门", 2, 1_700_000L, 1L))

        // 换剧那一刻：force 写，但进度问不出来（播放器刚重建）
        val write = WatchHistory.shouldStore(
            posMs = 0L, force = true, now = 2L, lastSavedAt = 0L, lastSavedPos = 0L,
        )
        assertFalse("0 = 问不出来，不是片头；写下去就是把 1_700_000 抹成 0", write)
        if (write) book = WatchHistory.with(book, rec("大宅门", 2, 0L, 2L))

        assertEquals("大宅门的续播点必须原样留着", 1_700_000L, book.find("电视剧", "大宅门")?.posMs)
        assertEquals(2, book.find("电视剧", "大宅门")?.episode)
    }

    @Test
    fun `负数一样当作问不出来`() {
        // 有些播放器在出错/停止时回 -1，同样不能当进度写下去
        assertFalse(WatchHistory.usablePosition(-1L))
        assertFalse(WatchHistory.shouldStore(-1L, force = true, now = 9L, lastSavedAt = 0L, lastSavedPos = 0L))
        assertTrue(WatchHistory.usablePosition(1L))
    }

    @Test
    fun `强制写盘只要进度可用就立刻写，不等防抖`() {
        // 换台/关电视等不了 5 秒
        assertTrue(WatchHistory.shouldStore(30_000L, force = true, now = 1_000L, lastSavedAt = 999L, lastSavedPos = 30_000L))
    }

    @Test
    fun `周期写盘要隔够时间、也要真的前进了才写`() {
        val now = 100_000L
        // 离上次写盘不到 5 秒：不写（老行为，别退化成一个 blob 每 5 秒原样重写）
        assertFalse(WatchHistory.shouldStore(60_000L, force = false, now = now, lastSavedAt = now - 4_999L, lastSavedPos = 0L))
        // 时间够了，但只前进了不到 1 秒：不写
        assertFalse(WatchHistory.shouldStore(60_500L, force = false, now = now, lastSavedAt = now - 5_000L, lastSavedPos = 60_000L))
        // 时间够、进度也真的前进了：写
        assertTrue(WatchHistory.shouldStore(61_000L, force = false, now = now, lastSavedAt = now - 5_000L, lastSavedPos = 60_000L))
    }

    @Test
    fun `刚起播时没有上次写盘时刻也能写`() {
        // lastSavedAt = 0（本次会话还没写过）不能让防抖把第一条记录挡住
        assertTrue(WatchHistory.shouldStore(16_000L, force = false, now = 1_700_000_000_000L, lastSavedAt = 0L, lastSavedPos = 0L))
    }

    // ---- 升级迁移 ----

    @Test
    fun `旧版那份全局记忆能迁移过来`() {
        // 用户电视上正存着上次看到哪儿了，升级后不该从头开始
        val book = WatchHistory.fromLegacy("电视剧", "大宅门", 12, 987_654L, 5L)
        assertEquals("电视剧", book.lastLib)
        assertEquals(12, book.find("电视剧", "大宅门")?.episode)
        assertEquals(987_654L, book.find("电视剧", "大宅门")?.posMs)
    }

    @Test
    fun `旧版记忆里没剧名时只迁移库名`() {
        val book = WatchHistory.fromLegacy("", "", 0, 0L, 5L)
        assertTrue(book.isEmpty)
        assertFalse(WatchHistory.serialize(book).contains("r="))
    }

    // ---- 自动连播时的续播点 ----

    /**
     * 自动连播到下一集时，**不能**把下一集存着的续播点抹成 0。
     *
     * 现场：一集播完自动跳下一集，`playEpisode(0L)` 一进去就按 0 写记录 ——
     * 于是下一集已经看到的进度被覆盖，用户第二天打开从那一集开头重看。
     * 这条只在"上一集播完"那一刻才显形，靠手点很难回归，所以用纯函数钉住。
     */
    @Test
    fun `自动连播要接着下一集自己的进度`() {
        val rec = WatchHistory.Record("电视剧", "娘道", 3, 600_000L, 1L)
        assertEquals(600_000L, WatchHistory.autoAdvanceResumeMs(rec, 3))
    }

    @Test
    fun `自动连播拿到别的集的记录时从头播`() {
        val rec = WatchHistory.Record("电视剧", "娘道", 3, 600_000L, 1L)
        // 记录属于第 3 集，但要播的是第 4 集 —— 拿别人的进度去 seek 会跳到莫名其妙的位置
        assertEquals(0L, WatchHistory.autoAdvanceResumeMs(rec, 4))
    }

    @Test
    fun `自动连播没有记录时从头播`() {
        assertEquals(0L, WatchHistory.autoAdvanceResumeMs(null, 0))
        assertEquals(0L, WatchHistory.autoAdvanceResumeMs(null, 7))
    }

    @Test
    fun `自动连播时刚点开一眼的进度不算数`() {
        // resumeMs 对 < MIN_RESUME_MS（15 秒）的进度返回 0：那是"点开看了一眼"，
        // 从第 8 秒接着播反而让人以为"怎么一打开就跳到中间"
        val barely = WatchHistory.Record("电视剧", "娘道", 0, 8_000L, 1L)
        assertEquals(0L, WatchHistory.autoAdvanceResumeMs(barely, 0))
        // 可信的进度照常传下去
        val watched = WatchHistory.Record("电视剧", "娘道", 0, 600_000L, 1L)
        assertEquals(600_000L, WatchHistory.autoAdvanceResumeMs(watched, 0))
    }
}
