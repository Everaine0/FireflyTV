package com.firefly.tv.media

import com.firefly.tv.smb.SmbClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体库结构的单元测试。
 *
 * 用例来自实机反馈：在 NAS 上新建一个文件夹放两个测试片（**平铺**，没有剧名子目录），
 * 电视上「这个库打不开，会快速跳过」。原因是老版本只把子文件夹当剧，
 * 平铺的库于是被判成空库，接着就是无休止地跳下一个库。
 *
 * 这里钉住的就是「平铺的库也是库」。
 */
class ScannerTest {

    private val lib = Library.Video("测试")

    private fun dir(name: String) = SmbClient.Entry(name, isDir = true, size = 0L)
    private fun file(name: String, size: Long = 1024L) = SmbClient.Entry(name, isDir = false, size = size)

    @Test
    fun `平铺的视频文件也算一部剧`() {
        val names = Scanner.showNames(
            listOf(
                file("BV1cP34zRE33.mp4", 175_814_249L),
                file("BV1TuGE6AES8.mp4", 97_771_332L),
            ),
        )
        // 顺序就是自然序（不区分大小写）：c < t，所以 c 那个在前。
        // 这个顺序决定「进库先播哪个」，所以钉住。
        assertEquals(listOf("BV1cP34zRE33.mp4", "BV1TuGE6AES8.mp4"), names)
    }

    @Test
    fun `子文件夹和散装文件混在一起时都算剧且按自然序排`() {
        val names = Scanner.showNames(
            listOf(
                dir("第10季"),
                file("电影2.mp4"),
                dir("第2季"),
                file("电影10.mkv"),
            ),
        )
        // 自然排序：数字段按数值比 —— 「第2季」在「第10季」前、「电影2」在「电影10」前。
        // 汉字之间按码位（不是拼音），这是既有行为，不在这里改；重要的是
        // 散装文件和文件夹混排时用的是**同一把尺子**，且数字段仍然按数值。
        assertEquals(listOf("电影2.mp4", "电影10.mkv", "第2季", "第10季"), names)
    }

    @Test
    fun `不认识的杂项文件和直播列表不算剧`() {
        val names = Scanner.showNames(
            listOf(
                file("说明.txt"),
                file("央视.m3u"),
                file("海报.jpg"),
                file("正片.mp4"),
            ),
        )
        assertEquals(listOf("正片.mp4"), names)
    }

    @Test
    fun `散装文件的路径不再多一层`() {
        assertEquals("测试/BV1TuGE6AES8.mp4", Scanner.episodePath(lib, "BV1TuGE6AES8.mp4", "BV1TuGE6AES8.mp4"))
    }

    @Test
    fun `文件夹结构的路径照旧多一层`() {
        assertEquals("电视剧/娘道/01.mp4", Scanner.episodePath(Library.Video("电视剧"), "娘道", "01.mp4"))
    }

    @Test
    fun `后缀不可信时靠内容探出来的散装文件也走单集路径`() {
        // 实测那种「整季 .mp4 其实是 MPEG-TS」的反面：文件后缀完全不认识，
        // 靠内容才认出来是视频。集名 == 剧名，路径同样不该多一层。
        assertTrue(Scanner.isLooseShow("娘道第01集", "娘道第01集"))
        assertEquals("库/娘道第01集", Scanner.episodePath(Library.Video("库"), "娘道第01集", "娘道第01集"))
    }

    @Test
    fun `剧名目录不会被误判成散装文件`() {
        assertFalse(Scanner.isLooseShow("娘道", "娘道01.mp4"))
        assertFalse(Scanner.isLooseShow("猫和老鼠", "第01集.mkv"))
    }

    @Test
    fun `剧名恰好带视频后缀时按散装文件处理`() {
        // 目录名写成 xxx.mp4 属于极罕见情况，这里只钉住「宁可当单集，也不要多拼一层目录」
        assertTrue(Scanner.isLooseShow("合集.mp4", "合集.mp4"))
        assertTrue(Scanner.isLooseShow("合集.mp4", ""))
    }

    /**
     * 「按内容探测」必须有总时限。
     *
     * 这条钉的是一类真实事故：老实现是 `Future.get()` **不带超时**，而探测本身是
     * 同步 SMB I/O —— NAS 半死不活时每个文件最坏几十秒，`PROBE_LIMIT = 400` 个叠起来
     * 就是几十分钟占着扫描线程，界面上表现为"永远在加载"，而 `runCatching` 接不住
     * "永远不返回"。所以预算必须是硬上限，到点就得收手（宁可少列几集）。
     */
    @Test
    fun `按内容探测的预算到点必须收手`() {
        val t0 = 1_000_000L
        // 典型情况：正常 NAS 上几十个文件是"基本瞬发"，预算绰绰有余
        assertTrue("刚开始必须还有预算", Scanner.probeBudgetLeftMs(t0, t0 + 300L) > 0)
        // 预算边界：正好用完
        assertEquals(0L, Scanner.probeBudgetLeftMs(t0, t0 + 20_000L))
        // 超预算：必须是负数（调用方据此停止等待、返回已探到的结果）
        assertTrue("超预算必须为负", Scanner.probeBudgetLeftMs(t0, t0 + 60_000L) < 0)
        // 自定义预算也成立（测试里缩短用）
        assertEquals(0L, Scanner.probeBudgetLeftMs(t0, t0 + 500L, budgetMs = 500L))
    }
}
