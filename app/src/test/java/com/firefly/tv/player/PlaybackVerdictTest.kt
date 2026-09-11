package com.firefly.tv.player

import com.firefly.tv.player.PlaybackEngine.Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「帧率为什么不高」的结论必须说对方向 —— 说错比不说更糟。
 *
 * 用户实机反馈是「还算流畅、声音也跟得上，但帧率不高，25 甚至更低」，
 * 而电视上没有 adb：屏幕上的这一句话就是唯一的排查出口。
 *
 * 2026-09-12 这一组用例被实机数据**推翻重写过一次**：老结论说「4K 片源 = 显示通路吃不下，
 * 换 1080p」，而实机用 4K **H.264** 一测就满帧（中位 50.0）。真正封顶的是
 * **HEVC 解码块**（~150 Mpx/秒 ⇒ 4K 只有 ~18 帧/秒）。见 [PlaybackVerdict.HEVC_DECODE_MPX]。
 */
class PlaybackVerdictTest {

    private fun input(
        playing: Boolean = true,
        decoder: Decoder = Decoder.HARDWARE,
        requestedHardware: Boolean = true,
        codecOffered: Boolean = true,
        videoWidth: Int = 3840,
        videoHeight: Int = 2160,
        videoCodec: String = "hevc",
        sourceFps: Float = 25f,
        decodeFps: Float = 24.8f,
        outputFps: Float = 24.6f,
        dropRatio: Float = 0f,
        cachedMs: Long = 2000,
        readBytesPerSec: Long = 4L * 1024 * 1024,
        bitRateBps: Long = 24_000_000,
    ) = PlaybackVerdict.Input(
        playing = playing,
        decoder = decoder,
        requestedHardware = requestedHardware,
        codecOffered = codecOffered,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        videoCodec = videoCodec,
        sourceFps = sourceFps,
        decodeFps = decodeFps,
        outputFps = outputFps,
        dropRatio = dropRatio,
        cachedMs = cachedMs,
        readBytesPerSec = readBytesPerSec,
        bitRateBps = bitRateBps,
    )

    /** 只想看一句话时用这个。 */
    private fun text(i: PlaybackVerdict.Input) = PlaybackVerdict.of(i).text

    @Test
    fun `还没起播`() {
        assertEquals("还没起播", text(input(playing = false)))
    }

    @Test
    fun `一切正常时给出正常结论`() {
        val v = text(input())
        assertTrue(v, v.startsWith("看起来正常"))
    }

    @Test
    fun `片源本来 25 帧、送显 25 帧 —— 这就不算故障`() {
        // 用户那句「25 甚至更低」的第一种解释：片子本身就只有 25 帧
        val v = text(input(sourceFps = 25f, decodeFps = 25f, outputFps = 25f))
        assertTrue(v, v.startsWith("看起来正常"))
        assertTrue(v, v.contains("25.0"))
    }

    @Test
    fun `片源 25 帧、只送显 18 帧 —— 要明确说低于片源`() {
        val v = text(input(sourceFps = 25f, decodeFps = 25f, outputFps = 18f))
        assertTrue(v, v.contains("低于片源的 25.0 帧/秒"))
    }

    @Test
    fun `低于片源但解码也慢时，锅算解码的`() {
        val v = text(
            input(sourceFps = 25f, decodeFps = 12f, outputFps = 12f, decoder = Decoder.SOFTWARE, requestedHardware = false),
        )
        assertTrue(v, v.contains("软解每秒只出 12.0 帧"))
    }

    @Test
    fun `低于片源但解码够快时，锅算送显的`() {
        val v = text(
            input(videoWidth = 1920, videoHeight = 1080, sourceFps = 25f, decodeFps = 25f, outputFps = 15f, dropRatio = 0.02f),
        )
        assertTrue(v, v.contains("瓶颈在送显/合成"))
    }

    // ---- 4K：锅在**编码**上，不在「4K」上（2026-09-12 实机重写） ----

    @Test
    fun `4K HEVC 跟不上时要指出是 HEVC 解码块的锅`() {
        // 实机：3840×2160@25 HEVC → 解码 18.6、送显 17.2、丢帧 8.6%
        // 期望：说清「低于片源」+ **指出换编码**（不是笼统地说「4K 不行」）
        val r = PlaybackVerdict.of(
            input(videoCodec = "hevc", sourceFps = 25f, decodeFps = 18.6f, outputFps = 17.2f, dropRatio = 0.086f),
        )
        assertTrue(r.text, r.text.contains("低于片源的 25.0 帧/秒"))
        assertTrue(r.text, r.text.contains("HEVC"))
        assertTrue(r.text, r.text.contains("H.264") || r.text.contains("h264"))
        assertTrue(r.text, r.text.contains("1080p"))
        assertTrue(r.problem)
    }

    @Test
    fun `不能再把锅推给「4K 显示通路」`() {
        // 老结论（已推翻）：4K 片源 + 硬解 ⇒「显示通路吃不下」。
        // 实机反证：4K50 的 H.264 送显中位 50.0 帧/秒（388 Mpx/秒）。
        val v = text(input(videoCodec = "hevc", sourceFps = 25f, decodeFps = 18.6f, outputFps = 17.2f))
        assertFalse(v, v.contains("显示通路"))
        assertFalse(v, v.contains("缩到 1080p"))
    }

    @Test
    fun `4K 的 H264 满帧时是好消息 —— 不许报警`() {
        // 实机：3840×2160@50 H.264 → 解码 54.5、送显中位 50.0、丢帧 5%
        val r = PlaybackVerdict.of(
            input(videoCodec = "h264", sourceFps = 50f, decodeFps = 54.5f, outputFps = 50f, dropRatio = 0.05f),
        )
        assertFalse(r.text, r.problem)
        assertTrue(r.text, r.text.contains("看起来正常"))
    }

    @Test
    fun `4K 的 H264 也跟不上时给出路的说法是换帧率档`() {
        // 实机：同一台电视 4K50 偶有掉帧（低帧时约 35 帧/秒）
        val v = text(
            input(videoCodec = "h264", sourceFps = 50f, decodeFps = 40f, outputFps = 35f, dropRatio = 0.08f),
        )
        assertTrue(v, v.contains("H.264"))
        assertTrue(v, v.contains("25/30 帧"))
        assertTrue(v, v.contains("1080p"))
    }

    @Test
    fun `准 4K 的 HEVC 满帧时不报警`() {
        // 实机：2960×2160@23.98 HEVC → 解码 24.0、送显 24.0、丢帧 0%（153 Mpx/秒，刚好在线上）
        val r = PlaybackVerdict.of(
            input(
                videoWidth = 2960, videoHeight = 2160, videoCodec = "hevc",
                sourceFps = 23.98f, decodeFps = 24f, outputFps = 24f,
            ),
        )
        assertFalse(r.text, r.problem)
    }

    /**
     * 这条是**回归测试里最重要的一条**（2026-09-12 重写）。
     *
     * 老版本在这里写着「反压造成的低解码帧率不能报成解码跟不上」，因为当时以为
     * 4K 送显卡住 → 反压解码，解码帧率低只是假象。实机改正：
     * 4K HEVC 上解码**确实**只能出 ~18 帧/秒（换深队列也一样，21.6/18.2），
     * 所以该报解码 —— 但必须报成「HEVC 这一档不行」，而不是「这台电视放不了 4K」。
     */
    @Test
    fun `4K HEVC 的 18 帧要报成解码的锅且指向编码`() {
        val v = text(
            input(videoCodec = "hevc", sourceFps = 25f, decodeFps = 18.4f, outputFps = 16.4f, dropRatio = 0.10f),
        )
        assertTrue(v, v.contains("硬解每秒只出 18.4 帧"))
        assertTrue(v, v.contains("HEVC"))
        assertFalse("别再谎报成显示通路", v.contains("显示通路"))
    }

    @Test
    fun `解码低于片源时怪解码 —— 判据是片源帧率而不是送显帧率`() {
        // 判据从「解码 < 送显」改成「解码 < 片源」：送显卡住会反压解码，
        // 拿送显当尺子会把锅判反（老版本就是这么错的）。
        val v = text(input(sourceFps = 25f, decodeFps = 9f, outputFps = 16f, dropRatio = 0f))
        assertTrue(v, v.contains("低于片源的 25.0 帧/秒"))
        assertTrue(v, v.contains("硬解每秒只出 9.0 帧"))
    }

    @Test
    fun `非 4K 片源不受那条 4K 结论影响`() {
        val v = text(
            input(videoWidth = 1920, videoHeight = 1080, sourceFps = 25f, decodeFps = 24.6f, outputFps = 17f),
        )
        assertFalse(v, v.contains("1080p 版本"))
        assertTrue(v, v.contains("瓶颈在送显"))
    }

    @Test
    fun `解码和送显一起掉到同一水平时说整条通路到顶`() {
        val v = text(
            input(videoWidth = 1920, videoHeight = 1080, sourceFps = 50f, decodeFps = 26f, outputFps = 25f),
        )
        assertTrue(v, v.contains("整条通路到顶"))
    }

    @Test
    fun `丢帧明显时说是送显来不及`() {
        // 用 1080p 片源：4K 会先命中「显示通路吃不下」那条（见后面的专项测试）
        val v = text(
            input(videoWidth = 1920, videoHeight = 1080, sourceFps = 25f, decodeFps = 25f, outputFps = 20f, dropRatio = 0.30f),
        )
        assertTrue(v, v.contains("瓶颈在送显"))
    }

    @Test
    fun `硬解没建成是第一条要报的 —— 它会被误当成只是有点慢`() {
        val v = text(
            input(decoder = Decoder.SOFTWARE, sourceFps = 25f, decodeFps = 6f, outputFps = 6f, requestedHardware = true),
        )
        assertTrue(v, v.contains("硬解没建成"))
    }

    @Test
    fun `本机压根没有硬解解码器时不算故障`() {
        val r = PlaybackVerdict.of(
            input(decoder = Decoder.SOFTWARE, requestedHardware = true, codecOffered = false, decodeFps = 6f, outputFps = 6f),
        )
        assertFalse(r.problem)
        assertTrue(r.text, r.text.contains("没有可用的硬解"))
    }

    @Test
    fun `本来就想软解时不报警`() {
        val v = text(
            input(decoder = Decoder.SOFTWARE, requestedHardware = false, decodeFps = 25f, outputFps = 25f),
        )
        assertTrue(v, v.startsWith("看起来正常"))
    }

    @Test
    fun `读取速率明显低于码率时指向 NAS`() {
        val v = text(
            input(readBytesPerSec = 1L * 1024 * 1024, cachedMs = 300, sourceFps = 25f, decodeFps = 18f, outputFps = 18f),
        )
        assertTrue(v, v.contains("读取跟不上"))
    }

    @Test
    fun `读取速率只要够用就不提它`() {
        val v = text(input(readBytesPerSec = 32L * 1024 * 1024 / 10))
        assertTrue(v, v.startsWith("看起来正常"))
    }

    @Test
    fun `不知道码率时不拿读取速率报警`() {
        val v = text(input(bitRateBps = 0, readBytesPerSec = 1))
        assertTrue(v, v.startsWith("看起来正常"))
    }

    @Test
    fun `缓存见底优先于解码 —— 没数据时解码帧率低是结果不是原因`() {
        val v = text(input(cachedMs = 50, sourceFps = 25f, decodeFps = 5f, outputFps = 5f))
        assertTrue(v, v.contains("数据供不上"))
    }

    @Test
    fun `硬解通路送显恒为 0 时不能据此说画面不动`() {
        // MediaCodec 通路上 stat_vfps 可能一直是 0（见 PlaybackEngine.outputFps 的说明），
        // 当年那个存活看门狗就是被这个恒 0 的读数误报逼停的
        val i = input(outputFps = 0f, decodeFps = 25f, sourceFps = 25f)
        assertTrue(i.moving())
        assertFalse(i.fpsShortfall())
        assertTrue(text(i).startsWith("看起来正常"))
    }

    @Test
    fun `不知道片源帧率时退回绝对阈值`() {
        val v = text(input(sourceFps = 0f, decodeFps = 12f, outputFps = 12f, cachedMs = 3000))
        assertTrue(v, v.contains("解码跟不上"))
    }

    @Test
    fun `辅助格式化的数字稳定`() {
        assertEquals("24.8", PlaybackVerdict.one(24.84f))
        assertEquals("3.0", PlaybackVerdict.mb(3L * 1024 * 1024))
    }
}
