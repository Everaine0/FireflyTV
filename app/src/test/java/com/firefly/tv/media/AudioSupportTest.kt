package com.firefly.tv.media

import com.firefly.tv.player.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这台电视能不能放出声音」的判定。
 *
 * 依据是对 `libijkffmpeg.so` 里解码器注册符号的实际检查：
 * 有 `ff_mp3_decoder` / `ff_aac_decoder`，**没有** `ff_ac3_decoder` / `ff_mp2_decoder`。
 * 而用户的片源恰好是 AC-3（娘道）和 MP2（CCTV5），所以这两类必然没声音。
 */
class AudioSupportTest {

    private fun ts(): ByteArray =
        AudioSupportTest::class.java.classLoader!!.getResourceAsStream("real-ac3.ts")?.readBytes()
            ?: error("测试语料 real-ac3.ts 找不到")

    @Test
    fun `TS 流类型到音频编码的映射`() {
        assertEquals(AudioSupport.Codec.AAC, AudioSupport.fromTs(TsProbe.Kind.AUDIO_AAC))
        assertEquals(AudioSupport.Codec.AAC, AudioSupport.fromTs(TsProbe.Kind.AUDIO_AAC_LATM))
        assertEquals(AudioSupport.Codec.MP2, AudioSupport.fromTs(TsProbe.Kind.AUDIO_MP2))
        assertEquals(AudioSupport.Codec.AC3, AudioSupport.fromTs(TsProbe.Kind.AUDIO_AC3))
        assertEquals(AudioSupport.Codec.EAC3, AudioSupport.fromTs(TsProbe.Kind.AUDIO_EAC3))
        assertEquals(AudioSupport.Codec.DTS, AudioSupport.fromTs(TsProbe.Kind.AUDIO_DTS))
        // 视频流类型不该被当成音频
        assertEquals(AudioSupport.Codec.UNKNOWN, AudioSupport.fromTs(TsProbe.Kind.VIDEO_H264))
    }

    @Test
    fun `内核确定支持的编码`() {
        for (c in listOf(
            AudioSupport.Codec.AAC,
            AudioSupport.Codec.MP3,
            AudioSupport.Codec.PCM,
            AudioSupport.Codec.FLAC,
        )) {
            assertTrue("$c 应该是内核支持的", AudioSupport.kernelSupports(c))
        }
    }

    @Test
    fun `内核确定不支持的编码`() {
        // 这两个就是用户片源里实际出现的：娘道是 AC-3，CCTV5 是 MP2
        for (c in listOf(
            AudioSupport.Codec.AC3,
            AudioSupport.Codec.EAC3,
            AudioSupport.Codec.MP2,
            AudioSupport.Codec.DTS,
        )) {
            assertFalse("$c 不该被认为内核支持", AudioSupport.kernelSupports(c))
        }
    }

    @Test
    fun `不认识编码时不下结论`() {
        // 探测不出来就别说「放不出声音」—— 误报比不报更糟，用户已经被假警报折腾过
        assertFalse(AudioSupport.cannotPlayAudio(AudioSupport.Codec.UNKNOWN))
        assertFalse(AudioSupport.platformSupports(AudioSupport.Codec.UNKNOWN))
        assertEquals(AudioSupport.Codec.UNKNOWN.mime, null)
    }

    @Test
    fun `真实 AC3 片源会被判定为放不出声音`() {
        val verdict = AudioSupport.probeFor(PlaybackMode.Kind.ON_DEMAND, ts())
        assertTrue("真实 TS 应该能探出来", verdict.canProbe)
        assertEquals(AudioSupport.Codec.AC3, verdict.codec)

        val w = verdict.warning
        assertNotNull("AC-3 在内核里没有解码器，必须给出提示", w)
        // 措辞要让人放心：画面能看，不是电视坏了
        assertTrue("提示里要说明画面可以看：$w", w!!.contains("画面"))
        assertTrue("提示里要点出编码名：$w", w.contains("AC-3"))
    }

    @Test
    fun `探测不出来时不提示`() {
        for (data in listOf(ByteArray(0), ByteArray(4096), "不是传输流".toByteArray())) {
            val v = AudioSupport.probeFor(PlaybackMode.Kind.ON_DEMAND, data)
            assertFalse("不该对非 TS 下结论", v.canProbe)
            assertNull(v.warning)
        }
    }

    @Test
    fun `直播不走字节源探测`() {
        val v = AudioSupport.probeFor(PlaybackMode.Kind.LIVE, ts())
        assertFalse("直播是 URL，探测路径不适用", v.canProbe)
        assertNull(v.warning)
    }

    @Test
    fun `提示用词不含技术术语之外的误导`() {
        val w = AudioSupport.notice(AudioSupport.Codec.AC3)
        assertFalse("不要出现英文 error 之类：$w", w.contains("error", ignoreCase = true))
        assertTrue("要说清是这台电视不支持：$w", w.contains("不支持"))
    }
}
