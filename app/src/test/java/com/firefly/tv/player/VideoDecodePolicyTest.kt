package com.firefly.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「电视上极慢 + 没声音」的根因是**硬解从来没被打开**：
 * ijkplayer 的 `mediacodec*` 选项默认全是 0，不显式写就等于全走 FFmpeg 软解。
 *
 * 这组测试把三件事钉死：
 *  1. 硬解打开时，判定条件里那四个开关必须真的是 1（少一个都可能不生效）；
 *  2. 退回软解时必须**四个一起显式写 0**，不能靠「不写」——管线是「任何一个为 1
 *     就试硬解」，漏一个就等于软解兜底失效；
 *  3. `max-fps` 不能再用默认的 31：它在流打开时会把 `skip_frame` 抬到 NONREF，
 *     1080p50 的《娘道》会被一直丢帧。
 */
class VideoDecodePolicyTest {

    /** 名字 → 值。选项在播放器上就是「最后写进去的那条生效」，用 map 便于断言。 */
    private fun optionsOf(hardware: Boolean): Map<String, Long> =
        VideoDecodePolicy.options(hardware).associate { it.name to it.value }

    /**
     * 管线判定条件里认的四个开关，一个都不能漏。
     *
     * 依据 ijkplayer k0.8.8 `ijkmedia/ijkplayer/android/pipeline/ffpipeline_android.c`：
     * `if (ffp->mediacodec_all_videos || ffp->mediacodec_avc || ffp->mediacodec_hevc || ffp->mediacodec_mpeg2)`
     * 才去建 MediaCodec 解码器。`mediacodec-mpeg4` **不在**这个条件里。
     */
    private val gate = listOf(
        "mediacodec-all-videos",
        "mediacodec-avc",
        "mediacodec-hevc",
        "mediacodec-mpeg2",
    )

    // ---- 硬解真的被打开了 ----

    @Test
    fun `硬解把四个判定开关都打开`() {
        val opts = optionsOf(hardware = true)
        for (name in gate) {
            assertEquals("$name 必须打开，否则这条流还是软解", 1L, opts[name])
        }
    }

    @Test
    fun `用 all-videos 覆盖 H265 而不是只写 avc`() {
        // 用户实测「极慢 + 没声音」的两部剧都是 4K H.265。
        // 只写 mediacodec（它是 mediacodec-avc 的别名）只对 H.264 生效，H.265 还是软解。
        assertEquals(1L, optionsOf(hardware = true)["mediacodec-all-videos"])
    }

    @Test
    fun `不允许只写 mediacodec-mpeg4 这种不在判定条件里的开关`() {
        // ffpipeline_android.c 的条件里没有 mediacodec-mpeg4，单写它等于没写。
        // 现在统一由 all-videos 决定，所以这个键不该出现（出现就说明有人又想逐编码配）
        val names = VideoDecodePolicy.options(hardware = true).map { it.name }
        assertFalse("mpeg4 不在判定条件里，单独配置只会误导", names.contains("mediacodec-mpeg4"))
    }

    @Test
    fun `分辨率变化交给 MediaCodec 重建解码器`() {
        // 央视各频道会在标清/高清节目之间换分辨率，直播里真的会遇到
        assertEquals(1L, optionsOf(hardware = true)["mediacodec-handle-resolution-change"])
    }

    // ---- 软解兜底 ----

    @Test
    fun `软解时四个判定开关必须一起显式写零`() {
        val opts = optionsOf(hardware = false)
        for (name in gate) {
            assertTrue("$name 必须显式关掉，否则软解兜底会失效", opts.containsKey(name))
            assertEquals("$name 没有真的关掉，会变成硬解", 0L, opts[name])
        }
        assertEquals(0L, opts["mediacodec-handle-resolution-change"])
    }

    @Test
    fun `硬解连续失败两次以后就不再试`() {
        assertTrue(VideoDecodePolicy.canTryHardware(0))
        assertTrue(VideoDecodePolicy.canTryHardware(1))
        assertFalse("再试就是让老人每集都多等一个超时", VideoDecodePolicy.canTryHardware(2))
        assertFalse(VideoDecodePolicy.canTryHardware(99))
    }

    @Test
    fun `硬解首帧超时只给硬解用且比界面层看门狗短`() {
        // 硬解卡住要能比界面层的 25 秒更早被发现，否则用户先看到故障页
        assertTrue(VideoDecodePolicy.FIRST_FRAME_TIMEOUT_MS < 25_000L)
        assertTrue("太短会把正常的建解码器误判成卡死", VideoDecodePolicy.FIRST_FRAME_TIMEOUT_MS >= 8_000L)
        assertEquals(2, VideoDecodePolicy.MAX_FAILURES)
    }

    // ---- max-fps ----

    @Test
    fun `硬解时不许再用默认的 max-fps 31`() {
        // max-fps 是「超过就丢非参考帧 + 不做去块滤波」的一次性判断，
        // 《娘道》是 1080p50 —— 用默认值会被一直丢帧。
        val v = optionsOf(hardware = true)["max-fps"]!!
        assertTrue("50fps 的片源不能被判定成高帧率", v > 50L)
        assertEquals(VideoDecodePolicy.MAX_FPS_HARDWARE, v)
    }

    @Test
    fun `软解保留默认的 max-fps`() {
        // 软解那条路上丢帧是有用的：电视本来就解不动，丢帧比幻灯片好
        assertEquals(31L, optionsOf(hardware = false)["max-fps"])
    }

    // ---- 渲染通路 ----

    /**
     * `overlay-format` 必须是 `_ES2`（给 vout 自己挑）。
     *
     * 默认的 RV32 会让每帧在 CPU 上做「YUV→RGB32 转换 + 整帧 memcpy」，
     * 4K 下就是 33MB/帧 —— 实测模拟器上 `ff_vout` 单核跑满 92%、画面 4.1fps，
     * 而解码线程加起来才 0.9 个核。设成 `_ES2` 后这两步都交给 GPU。
     *
     * 数值来自 ijkplayer 的 `ijksdl_fourcc.h`：
     * `SDL_FCC__GLES2 = SDL_FOURCC('_','E','S','2')`，小端拼出来就是 0x3253455F。
     * 不是随便挑的数，所以这里把字面值也钉住。
     */
    @Test
    fun `渲染走 GLES2 而不是默认的 RV32`() {
        for (hardware in listOf(true, false)) {
            assertEquals(
                "软解时 RV32 会让 CPU 每帧搬 33MB（4K）",
                VideoDecodePolicy.OVERLAY_GLES2,
                optionsOf(hardware)["overlay-format"],
            )
        }
        assertEquals(0x3253455FL, VideoDecodePolicy.OVERLAY_GLES2)
        assertFalse(
            "RV32 是 ijkplayer 的默认值，写它等于什么都没改",
            optionsOf(hardware = false)["overlay-format"] == 0x32335652L,
        )
    }

    // ---- 音频通路 ----

    @Test
    fun `音频输出固定走 AudioTrack`() {
        // opensles 默认就是 0（AudioTrack），这里显式写死：
        // OpenSL ES 在部分电视固件上是坏的，不能让它被悄悄换掉。
        assertEquals(0L, optionsOf(hardware = true)["opensles"])
        assertEquals(0L, optionsOf(hardware = false)["opensles"])
    }

    @Test
    fun `所有选项都写在播放器类别上`() {
        // 写错类别（比如 FORMAT）时 ijkplayer 不会报错，只会**静默忽略** ——
        // 表现就是「改了等于没改」，所以这里逐个钉住类别。
        //
        // 依据：这些键都在 ff_ffplay_options.h 的 ffp_context_options[] 里，
        // 由 av_opt_set_dict(ffp, &ffp->player_opts) 应用。
        for (hardware in listOf(true, false)) {
            for (opt in VideoDecodePolicy.options(hardware)) {
                assertEquals(
                    "「${opt.name}」是 ijkplayer 的播放器选项，写在别的类别上会被静默忽略",
                    VideoDecodePolicy.Category.PLAYER,
                    opt.category,
                )
            }
        }
    }

    // ---- 网络：DNS 缓存 ----

    /**
     * 用户报的第二个「卡住」：**从电视剧换回直播，画面冻住不动**。
     *
     * 根因是 ijkplayer 那份 DNS 缓存：`libavutil/dns_cache.c` 里
     * `get_dns_cache_reference()` 的键**只有主机名**，而
     * `new_dns_cache_entry()` 把 `getaddrinfo()` 的 `sockaddr_in` 整个拷走
     * （`sin_port` 一起拷）。命中以后 `tcp_open()` 直接拿缓存的地址去连，
     * **不看 URL 里的端口**。
     *
     * 央视直播地址 `…:82/live/cctv1hd.m3u8` 是 302 到 `…:81/…?tm=&key=` 的。
     * 于是：连着 `:81` 却发 `GET /live/cctv1hd.m3u8` + `Host: …:82` → 403，
     * 缓存活到进程结束 → 换多少次台都一样。
     *
     * 修法是让 tcp 协议别再信这份缓存，两个选项缺一不可。
     */
    @Test
    fun `关掉 ijkplayer 那份只按主机名做键的 DNS 缓存`() {
        val net = VideoDecodePolicy.NETWORK_OPTIONS.associate { it.name to it.value }

        assertEquals(
            "dns_cache_timeout 必须是 0（关）：留正数就会命中带错端口的缓存条目",
            0L,
            net["dns_cache_timeout"],
        )
        assertEquals(
            "dns_cache_clear 必须是 1：万一超时值在别处被置回正数，" +
                "每次建连前清一次也能让那份错端口永远用不上",
            1L,
            net["dns_cache_clear"],
        )
        // 这两个是 tcp 协议的 AVOption，只能从 format-opts 传下去；
        // 写到 PLAYER 类别上就是静默忽略，403 会原样回来。
        for (opt in VideoDecodePolicy.NETWORK_OPTIONS) {
            assertEquals(
                "「${opt.name}」是 tcp 协议的选项，要从 FORMAT 类别传",
                VideoDecodePolicy.Category.FORMAT,
                opt.category,
            )
        }
    }
}
