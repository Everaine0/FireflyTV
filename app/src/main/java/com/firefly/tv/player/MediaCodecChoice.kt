package com.firefly.tv.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import tv.danmaku.ijk.media.player.IjkMediaCodecInfo

/**
 * 「这次硬解该用哪个解码器」—— 自己的选码器，替换 ijkplayer 的默认实现。
 *
 * ## 为什么要自己写（默认那套有两个真问题）
 *
 * 1. **没有记忆**。默认选择器每次都给同一个答案。而这台电视上
 *    MediaCodec「建得出来但不干活」时，ijkplayer 是**静默回落软解**的
 *    （`ffpipeline_android.c:73-77`：试建失败只有一行 ALOGE 就换 FFmpeg 软解），
 *    于是每次都白试一遍同一个坏解码器，永远退不到「次优但能用」的那个。
 *    这里加了**本次运行的禁用名单**（[pick] 的 `banned`），
 *    真机上判定「选中了却实际在软解」时会把那个名字拉黑，下一次自动换下一个候选。
 *
 * 2. **不查 profile**。ijkplayer 对 HEVC 完全不看 profile
 *    （H.264 有白名单，HEVC 分支只有开关，`vdec.c:1997-2005`），
 *    而且它构造 MediaFormat 时**连 profile 都不写**（只写 mime+宽+高）——
 *    10bit（Main10）片源交给只支持 8bit Main 的解码器时 `configure` **不会报错**，
 *    症状是「有声音、黑屏或极慢」，而且不报错（上游 issue #1364 就是这个）。
 *    现有片源都是 8bit Main，踩不到；但换片源就可能踩，
 *    所以 [profileRejection] 会在**明确是 Main10 而没有任何解码器声明支持**时拒绝硬解，
 *    让它老老实实走软解（慢，但至少不出错）。
 *
 * 排序规则**完全沿用** ijkplayer 的 `IjkMediaCodecInfo`（`omx.mtk.*` 恒为
 * `RANK_TESTED=800`，`omx.google.*`/`omx.ffmpeg.*` 是 `RANK_SOFTWARE=200`，
 * 未知名字 `700`），阈值同样是 `RANK_LAST_CHANCE=600`。
 * 这样「换掉默认选择器」不会改变任何排得上号的设备上的选择结果。
 */
object MediaCodecChoice {

    private const val TAG = "FireflyDecode"

    /** `CodecCapabilities.COLOR_FormatSurface`：能零拷贝出图的颜色格式。 */
    const val SURFACE_COLOR_FORMAT = 0x7f000789

    /** ijkplayer 接受解码器的最低分（低于它就整体退回软解）。 */
    const val MIN_RANK = IjkMediaCodecInfo.RANK_LAST_CHANCE

    // HEVC profile 的原始数值。**不能用** `IjkMediaCodecInfo.getProfileName()`：
    // 那个函数只实现了 AVC，对 HEVC 一律打印 "Unknown"。
    const val HEVC_PROFILE_MAIN = 0x1
    const val HEVC_PROFILE_MAIN10 = 0x2
    const val HEVC_PROFILE_MAIN10_HDR10 = 0x1000
    const val HEVC_PROFILE_MAIN10_HDR10_PLUS = 0x2000

    const val MIME_HEVC = "video/hevc"

    /** 一个候选解码器。 */
    class Candidate(
        val name: String,
        val rank: Int,
        /** 是否支持 `COLOR_FormatSurface`（不支持就不是零拷贝，4K 上基本不能用）。 */
        val surface: Boolean,
        /** 声明支持的 HEVC profile 集合（非 HEVC 时为空）。 */
        val hevcProfiles: Set<Int>,
    )

    /**
     * 按分数挑一个：**跳过禁用名单，取分最高的**；同分取先出现的（和默认一致）。
     * 没有任何候选达到 [MIN_RANK] 就返回 null（调用方据此走软解）。
     */
    fun pick(list: List<Candidate>, banned: Set<String> = emptySet()): Candidate? {
        var best: Candidate? = null
        for (c in list) {
            if (c.name in banned) continue
            if (c.rank < MIN_RANK) continue
            if (best == null || c.rank > best.rank) best = c
        }
        return best
    }

    /**
     * 这个流能不能交给硬解。
     *
     * @return null = 可以；否则是拒绝的原因（写日志用）
     */
    fun profileRejection(mime: String, profile: Int, list: List<Candidate>): String? {
        if (!MIME_HEVC.equals(mime, ignoreCase = true)) return null
        if (!isMain10(profile)) return null
        // 明确是 10bit 及以上：必须有人**声明**支持，否则 configure 不会报错，
        // 只会黑屏/极慢（见类注释）
        val supported = list.any { profile in it.hevcProfiles }
        return if (supported) null else "片源是 HEVC Main10/10bit（profile=$profile），这台设备没有解码器声明支持"
    }

    fun isMain10(profile: Int): Boolean =
        profile == HEVC_PROFILE_MAIN10 ||
            profile == HEVC_PROFILE_MAIN10_HDR10 ||
            profile == HEVC_PROFILE_MAIN10_HDR10_PLUS

    /**
     * 枚举本机候选并选一个。
     *
     * @param banned 本次运行里已经证实「选中了但实际在软解」的解码器名
     * @return 解码器名；null = 这次不要用硬解
     */
    fun choose(mime: String, profile: Int, level: Int, banned: Set<String> = emptySet()): String? {
        val list = runCatching { enumerate(mime) }.getOrElse {
            Log.w(TAG, "枚举解码器失败：${it.javaClass.simpleName}: ${it.message}")
            return null
        }
        logCandidates(mime, list, banned)
        profileRejection(mime, profile, list)?.let {
            Log.w(TAG, "不用硬解：$it")
            return null
        }
        val best = pick(list, banned)
        if (best == null) {
            Log.w(TAG, "没有可用的硬解解码器（候选 ${list.size} 个，禁用 ${banned.size} 个），改用软解")
        }
        return best?.name
    }

    /** 这台机器上所有能解 [mime] 的解码器（含软件实现，分数会很低）。 */
    fun enumerate(mime: String): List<Candidate> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()
        val out = ArrayList<Candidate>()
        for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (info.isEncoder) continue
            val types = info.supportedTypes ?: continue
            if (types.none { it.equals(mime, ignoreCase = true) }) continue
            out += Candidate(
                name = info.name,
                rank = runCatching {
                    IjkMediaCodecInfo.setupCandidate(info, mime)?.mRank ?: 0
                }.getOrDefault(0),
                surface = runCatching {
                    info.getCapabilitiesForType(mime).colorFormats.contains(SURFACE_COLOR_FORMAT)
                }.getOrDefault(false),
                hevcProfiles = runCatching {
                    info.getCapabilitiesForType(mime).profileLevels
                        ?.map { it.profile }?.toSet().orEmpty()
                }.getOrDefault(emptySet()),
            )
        }
        return out
    }

    /**
     * 把候选连分数打进日志。
     *
     * 电视 SoC 的解码器**不在** ijkplayer 那份「已验证机型表」里，
     * 真出问题时这张表就是决定「拉黑谁」的依据，所以要留痕。
     * 这里只写日志，任何异常都吞掉：它不该影响播放。
     */
    private fun logCandidates(mime: String, list: List<Candidate>, banned: Set<String>) {
        for (c in list) {
            Log.i(
                TAG,
                "候选解码器 ${c.name} rank=${c.rank} Surface=${c.surface}" +
                    (if (c.name in banned) " 【已禁用】" else "") +
                    (if (c.hevcProfiles.isNotEmpty()) " profiles=${c.hevcProfiles.joinToString(",")}" else ""),
            )
        }
        Log.i(TAG, "选码 mime=$mime 候选=${list.size} 禁用=${banned.size}")
    }
}
