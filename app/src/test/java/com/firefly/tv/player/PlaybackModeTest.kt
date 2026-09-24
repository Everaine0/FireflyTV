package com.firefly.tv.player

import com.firefly.tv.media.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「直播不许有进度」这条规则。
 *
 * 这是用户实测到的「IPTV 起播音画不同步」的根：直播的 currentPosition 是从频道开播
 * 算起的毫秒数，被当成续播进度写盘以后，下次起播就拿它去 seek。直播流没有可 seek
 * 的时间轴，结果就是音画长时间不同步。规则必须能用测试钉死。
 */
class PlaybackModeTest {

    private val video = Library.Video("电视剧")
    private val live = Library.Live("IPTV", "央视.m3u")

    @Test
    fun `库类型决定播放模式`() {
        assertEquals(PlaybackMode.Kind.ON_DEMAND, PlaybackMode.of(video))
        assertEquals(PlaybackMode.Kind.LIVE, PlaybackMode.of(live))
    }

    @Test
    fun `库还不知道时按直播处理`() {
        // 宁可不续播，也不要 seek 出一条不同步的流
        assertEquals(PlaybackMode.Kind.LIVE, PlaybackMode.of(null))
    }

    @Test
    fun `只有点播才配拥有进度`() {
        assertTrue(PlaybackMode.remembersPosition(PlaybackMode.Kind.ON_DEMAND))
        assertFalse(PlaybackMode.remembersPosition(PlaybackMode.Kind.LIVE))
    }

    @Test
    fun `直播永远从零开始不管外面传了什么`() {
        // 这就是那个 bug：以前会把 7200000（两小时）原样透传给 seekTo
        assertEquals(0L, PlaybackMode.startPositionMs(PlaybackMode.Kind.LIVE, 7_200_000L))
        assertEquals(0L, PlaybackMode.startPositionMs(PlaybackMode.Kind.LIVE, -1L))
    }

    @Test
    fun `点播保留请求的进度`() {
        assertEquals(7_200_000L, PlaybackMode.startPositionMs(PlaybackMode.Kind.ON_DEMAND, 7_200_000L))
        // 负数是脏数据，夹到 0
        assertEquals(0L, PlaybackMode.startPositionMs(PlaybackMode.Kind.ON_DEMAND, -5L))
    }

    @Test
    fun `直播与点播用不同的缓冲参数`() {
        val t = PlaybackMode.tuning(PlaybackMode.Kind.LIVE)
        val v = PlaybackMode.tuning(PlaybackMode.Kind.ON_DEMAND)

        // 直播要开包缓冲：完全关掉时视频钟会先跑，音频还在等 AudioTrack 起播，
        // 起播那几秒就是音画不同步。开一个小缓冲把两者起点对齐。
        assertTrue(t.packetBuffering)
        assertFalse(v.packetBuffering)

        // 直播缓冲必须小，否则延迟越积越多
        assertTrue("直播缓冲应远小于点播", t.maxBufferBytes < v.maxBufferBytes)
        // 直播探流要快，否则换台要等
        assertTrue("直播探流应比点播快", t.probesizeBytes < v.probesizeBytes)
        assertTrue(t.analyzeDurationUs < v.analyzeDurationUs)
    }

    // ---- 探测窗口的下限 ----
    //
    // 《娘道》没声音的**真正根因**就在这里，不是解码器缺失。
    //
    // 它的第一条音频包在文件的 2,340,788 字节（2.34 MB）处 —— 视频包很大
    // （单包 291 KB），音频包被压在很后面。旧值 2 MB 的探测窗口读不到音频参数，
    // FFmpeg 只报一句很不显眼的
    //   "Could not find codec parameters for stream 1 (Audio: ac3 ... 0 channels)"
    // 然后音频组件**静默失败**：画面完全正常，就是没声音。
    //
    // 二分实测（ffprobe -probesize）：2.0 MB → 0 channels；3.0 MB → 48000 Hz/2ch。
    // 这个测试把下限钉住，防止有人为了「起播快一点」把它调回去。

    @Test
    fun `点播探测窗口必须足够大才能读到音频参数`() {
        val v = PlaybackMode.tuning(PlaybackMode.Kind.ON_DEMAND)
        assertTrue(
            "点播 probesize 不能小于娘道需要的 2.34 MB（否则整部剧没声音）",
            v.probesizeBytes > 2_340_788L,
        )
        assertEquals(
            "下限应显式声明，方便以后有实测数据时调整",
            PlaybackMode.MIN_PROBESIZE_BYTES,
            v.probesizeBytes,
        )
    }

    @Test
    fun `探测窗口下限留了余量`() {
        // 实测「刚够」是 3 MB。取值必须明显大于它，
        // 免得换一部音频包更靠后的剧又踩同一个坑。
        assertTrue(
            "下限应比实测的临界值（3 MB）留出余量",
            PlaybackMode.MIN_PROBESIZE_BYTES >= 8L * 1024 * 1024,
        )
    }

    // ---- 自动跳集的门槛 ----
    //
    // 被真实故障逼出来的：《娘道》那种 AC-3 的 TS，内核报出来的时长是 1700ms
    // （实际 2560 秒）。播放器很快认为「播完了」，于是自动跳下一集、下一部 ——
    // 用户什么都没按，剧集却自己跑掉了。所以时长不可信时必须拒绝自动跳。

    @Test
    fun `时长正常时点播可以自动跳集`() {
        assertTrue(PlaybackMode.canAutoAdvance(PlaybackMode.Kind.ON_DEMAND, 2_560_000L))
        assertTrue(PlaybackMode.canAutoAdvance(PlaybackMode.Kind.ON_DEMAND, 45_000L))
    }

    @Test
    fun `娘道那种假时长必须拒绝自动跳集`() {
        // 实测值：内核报 1700ms
        assertFalse(
            "1700ms 是坏时长，绝不能据此跳集",
            PlaybackMode.canAutoAdvance(PlaybackMode.Kind.ON_DEMAND, 1_700L),
        )
        assertFalse(PlaybackMode.canAutoAdvance(PlaybackMode.Kind.ON_DEMAND, 5_405L))
        assertFalse(PlaybackMode.canAutoAdvance(PlaybackMode.Kind.ON_DEMAND, 0L))
        assertFalse(PlaybackMode.canAutoAdvance(PlaybackMode.Kind.ON_DEMAND, -1L))
    }

    @Test
    fun `直播永远不自动跳集`() {
        // 直播的 duration 恒为 0，而且「播完了」通常只是断流
        assertFalse(PlaybackMode.canAutoAdvance(PlaybackMode.Kind.LIVE, 0L))
        assertFalse(PlaybackMode.canAutoAdvance(PlaybackMode.Kind.LIVE, 3_600_000L))
    }

    // ---- 「播完了」到底该干什么 ----
    //
    // 这条规则错了**不会报错，只会黑屏**，所以必须用测试钉住。
    // 用户报的「上一集播完，下一集直接黑屏」就是它：界面层换到直播时打开的
    // `liveReconnect` 从来没有被关掉，于是「先看直播、再回电视剧」以后，
    // 点播的一集播完会被当成直播断流 —— 引擎先 release 播放器（画面立刻变黑），
    // 再去重连一个换库时就已经清掉的频道地址（界面层的 provider 返回 null），
    // 黑屏就永远停在那儿了。

    @Test
    fun `点播播完就是跳下一集哪怕直播开关还开着`() {
        assertEquals(
            PlaybackMode.OnCompletion.NEXT,
            PlaybackMode.onCompletion(PlaybackMode.Kind.ON_DEMAND, liveReconnect = false),
        )
        assertEquals(
            "看直播时打开的开关没关掉，但当前播的是点播 —— 绝不能当成断流",
            PlaybackMode.OnCompletion.NEXT,
            PlaybackMode.onCompletion(PlaybackMode.Kind.ON_DEMAND, liveReconnect = true),
        )
    }

    @Test
    fun `只有正在播直播时才把播完当断流`() {
        assertEquals(
            PlaybackMode.OnCompletion.LIVE_RETRY,
            PlaybackMode.onCompletion(PlaybackMode.Kind.LIVE, liveReconnect = true),
        )
        assertEquals(
            "没开重连就交给界面层处理",
            PlaybackMode.OnCompletion.NEXT,
            PlaybackMode.onCompletion(PlaybackMode.Kind.LIVE, liveReconnect = false),
        )
    }

    @Test
    fun `门槛是十秒`() {
        assertFalse(PlaybackMode.canAutoAdvance(PlaybackMode.Kind.ON_DEMAND, 9_999L))
        assertTrue(PlaybackMode.canAutoAdvance(PlaybackMode.Kind.ON_DEMAND, 10_000L))
        assertEquals(10_000L, PlaybackMode.MIN_TRUSTED_DURATION_MS)
    }

    // ---- 直播源的传输协议 ----
    //
    // 直播源从 HTTP/HLS 换成了运营商 IPTV 的 RTSP 单播 + UDP 组播，
    // 而超时选项的单位/语义**随协议而变** —— 认错协议就会把超时值写错量纲。
    // 实测事故：给 RTSP 的 `timeout` 塞了微秒值 15000000，
    // 被当成 15000000 **秒**（约 173 天），顺带把 RTSP 保活间隔也顶掉了
    // （`rtspdec.c` 里 `>= rt->timeout / 2` 才发 GET_PARAMETER）。

    @Test
    fun `按 scheme 认出传输协议`() {
        assertEquals(
            PlaybackMode.Transport.RTSP,
            PlaybackMode.Transport.of("rtsp://192.0.2.21/PLTV/88888912/224/3221226426/x.smil"),
        )
        assertEquals(PlaybackMode.Transport.RTSP, PlaybackMode.Transport.of("RTSP://host/x"))
        assertEquals(PlaybackMode.Transport.UDP, PlaybackMode.Transport.of("udp://239.0.0.1:4120"))
        assertEquals(PlaybackMode.Transport.UDP, PlaybackMode.Transport.of("rtp://239.1.1.1:5000"))
        assertEquals(
            PlaybackMode.Transport.HTTP,
            PlaybackMode.Transport.of("http://198.51.100.10:82/live/cctv1hd.m3u8"),
        )
        assertEquals(
            PlaybackMode.Transport.HTTP,
            PlaybackMode.Transport.of("https://live.example.com/x.m3u8"),
        )
    }

    @Test
    fun `认不出来的地址退回改动前的老行为`() {
        // 认错协议比认不出更危险，所以默认必须是 HTTP（老行为），不是 RTSP
        assertEquals(PlaybackMode.Transport.HTTP, PlaybackMode.Transport.of(""))
        assertEquals(PlaybackMode.Transport.HTTP, PlaybackMode.Transport.of("rtmp://a/b"))
        assertEquals(PlaybackMode.Transport.HTTP, PlaybackMode.Transport.of("not a url"))
    }

    @Test
    fun `直播默认按 HTTP 调参`() {
        // 不传 transport 时必须完全等于改动前那档 HLS 参数，避免影响既有源
        val d = PlaybackMode.tuning(PlaybackMode.Kind.LIVE)
        val h = PlaybackMode.tuning(PlaybackMode.Kind.LIVE, PlaybackMode.Transport.HTTP)
        assertEquals(d.maxBufferBytes, h.maxBufferBytes)
        assertEquals(d.probesizeBytes, h.probesizeBytes)
        assertEquals(d.analyzeDurationUs, h.analyzeDurationUs)
    }

    @Test
    fun `RTSP和组播的探测窗口要比HLS大`() {
        // RTSP/组播是连续 TS，没有「每片自带流信息」这回事；
        // 原来的 1 MB（按 HLS 分片调的）认不出参数。
        val http = PlaybackMode.tuning(PlaybackMode.Kind.LIVE, PlaybackMode.Transport.HTTP)
        for (tr in listOf(PlaybackMode.Transport.RTSP, PlaybackMode.Transport.UDP)) {
            val t = PlaybackMode.tuning(PlaybackMode.Kind.LIVE, tr)
            assertTrue("$tr 的探测窗口应大于 HLS", t.probesizeBytes > http.probesizeBytes)
            assertTrue("$tr 的缓冲应大于 HLS", t.maxBufferBytes > http.maxBufferBytes)
            assertTrue("$tr 仍要保留包缓冲", t.packetBuffering)
        }
    }

    @Test
    fun `直播各档缓冲都远小于点播`() {
        val v = PlaybackMode.tuning(PlaybackMode.Kind.ON_DEMAND)
        for (tr in PlaybackMode.Transport.values()) {
            val t = PlaybackMode.tuning(PlaybackMode.Kind.LIVE, tr)
            assertTrue("$tr 直播缓冲必须小于点播，否则延迟越积越多", t.maxBufferBytes < v.maxBufferBytes)
            assertTrue("$tr 探流要比点播快，否则换台要等", t.probesizeBytes < v.probesizeBytes)
        }
    }
}
