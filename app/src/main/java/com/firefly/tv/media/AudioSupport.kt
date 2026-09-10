package com.firefly.tv.media

import android.media.MediaCodecList
import com.firefly.tv.player.PlaybackMode

/**
 * 「这条流能不能出声」—— 在起播前就给用户一句人话。
 *
 * 背景（实测数据，不是推测）：ijkplayer 0.8.8 自带的 `libijkffmpeg.so` 里
 * **没有 AC-3 解码器**（`ff_ac3_decoder` 不存在），而用户的片源恰好是：
 *  - 《娘道》76 集：MPEG-TS + H.264 + **AC-3** → 有画面，没有声音
 *  - IPTV 的 CCTV5：HLS(MPEG-TS) + H.264 + **MP2** → 有画面，没有声音
 *
 * 原来的表现是「电视坏了」和「这个片子坏了」分不清，老人只能反复重启。
 * 现在起播前先认一下音频编码，解不了就直说，并且说明「画面能看，只是没声音」。
 *
 * 判据是**设备能力**而不是写死的清单：有些电视（Amlogic 方案的盒子）系统自带 AC-3 解码，
 * 那种情况下不该误报。所以每次都问一次 [MediaCodecList]。
 */
object AudioSupport {

    /** 已知的音频编码。 */
    enum class Codec(val label: String, val mime: String?) {
        AAC("AAC", "audio/mp4a-latm"),
        MP2("MP2", "audio/mpeg"),
        MP3("MP3", "audio/mpeg"),
        AC3("AC-3", "audio/ac3"),
        EAC3("E-AC-3", "audio/eac3"),
        DTS("DTS", "audio/vnd.dts"),
        FLAC("FLAC", "audio/flac"),
        VORBIS("Vorbis", "audio/vorbis"),
        OPUS("Opus", "audio/opus"),
        PCM("PCM", "audio/raw"),
        UNKNOWN("未知音频", null),
    }

    /**
     * 随包内核里**确定存在**的音频解码器。
     *
     * 依据是逐 ABI 用 `nm` 读 `libijkffmpeg.so` 符号表的结果
     * （`scripts/verify-ijkplayer-decoders.sh`），不是猜的。
     *
     * 这里曾经只有 aac/mp3/flac —— 当时用的是官方 Maven 包，它只编进了 23 个解码器，
     * **AC-3 和 MP2 都没有**，于是娘道（AC-3）和 CCTV5（MP2）「有画面没声音」。
     * 现在换成自己编的内核（`app/libs/ijkplayer-full-0.8.8.aar`），这两个补上了。
     *
     * **改内核必须同步改这里**，两个方向都会误导用户：
     * 漏报 —— 明明能出声却提示「不支持」；误报 —— 用户以为有声音其实没有。
     * 单元测试 `AudioSupportTest` 会遍历 [ALL_FAMILIES] 把两个方向都钉住。
     */
    private val BUILT_IN = setOf(
        Codec.AAC,
        Codec.MP3,
        Codec.MP2,   // 随包内核含 ff_mp2_decoder（CCTV5 就是 MP2）
        Codec.AC3,   // 随包内核含 ff_ac3_decoder（娘道就是 AC-3）
        Codec.EAC3,  // 随包内核含 ff_eac3_decoder
        Codec.DTS,   // 随包内核含 ff_dca_decoder
        Codec.PCM,
        Codec.FLAC,
        Codec.VORBIS,
        Codec.OPUS,
    )

    /** 全部已知编码；测试遍历用，避免以后新增编码时漏掉断言。 */
    val ALL_FAMILIES: List<Codec> = Codec.values().toList()

    /** 从 TS 流类型映射过来。 */
    fun fromTs(kind: TsProbe.Kind): Codec = when (kind) {
        TsProbe.Kind.AUDIO_AAC, TsProbe.Kind.AUDIO_AAC_LATM -> Codec.AAC
        TsProbe.Kind.AUDIO_MP2 -> Codec.MP2
        TsProbe.Kind.AUDIO_AC3 -> Codec.AC3
        TsProbe.Kind.AUDIO_EAC3 -> Codec.EAC3
        TsProbe.Kind.AUDIO_DTS -> Codec.DTS
        else -> Codec.UNKNOWN
    }

    /** 设备（系统 MediaCodec）能不能解这个编码。 */
    fun platformSupports(codec: Codec): Boolean {
        val mime = codec.mime ?: return false
        return try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        } catch (_: Throwable) {
            // 查不到就当不支持：宁可少报一句提示，也不要误导
            false
        }
    }

    /** 播放内核能不能解。 */
    fun kernelSupports(codec: Codec): Boolean = codec in BUILT_IN

    /** 两者都不行 —— 这条流注定没有声音。 */
    fun cannotPlayAudio(codec: Codec): Boolean =
        codec != Codec.UNKNOWN && !kernelSupports(codec) && !platformSupports(codec)

    /**
     * 给用户的一句话。
     *
     * 措辞上有意区分「没声音」和「放不了」：画面是好的，不要吓到老人，
     * 也要让他知道不是自己操作错了、更不是电视坏了。
     */
    fun notice(codec: Codec): String =
        "这个片子画面能看，声音放不出来（${codec.label} 音频，这台电视不支持）"

    /**
     * 起播前的检查结果。
     *
     * @param canProbe false = 没探出来（比如 MP4 的 moov 在尾部），这时**不要**提示，
     *   宁可不说也不要误报 —— 用户已经被假警报折腾过一次了。
     */
    class Verdict(val codec: Codec, val canProbe: Boolean) {
        val warning: String? get() = if (canProbe && cannotPlayAudio(codec)) notice(codec) else null
    }

    /**
     * 探测一条流的音频编码。
     *
     * 目前只对 MPEG-TS 有效（读开头 1MB 就能从 PMT 里拿到确定答案）。
     * 这一步能覆盖用户全部的无声片源：《娘道》和 IPTV 都是 TS。
     * 其它容器返回 [Verdict.canProbe] = false。
     *
     * @param data 文件/流的开头若干字节
     * @param isLive 直播流不适用（它走 URL，不经过字节源）
     */
    fun probe(data: ByteArray, isLive: Boolean): Verdict {
        if (isLive) return Verdict(Codec.UNKNOWN, canProbe = false)
        if (data.size < 188) return Verdict(Codec.UNKNOWN, canProbe = false)
        val info = TsProbe.probe(data) ?: return Verdict(Codec.UNKNOWN, canProbe = false)
        val audio = info.audio.firstOrNull() ?: return Verdict(Codec.UNKNOWN, canProbe = false)
        return Verdict(fromTs(audio.kind), canProbe = true)
    }

    /** 便捷入口：按库类型判断要不要探。 */
    fun probeFor(kind: PlaybackMode.Kind, data: ByteArray): Verdict =
        probe(data, isLive = kind == PlaybackMode.Kind.LIVE)
}
