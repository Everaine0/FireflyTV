package com.firefly.tv.player

/**
 * 「画面为什么不够流畅」——**把几个数字翻译成一句人话**。
 *
 * 起因：用户实机反馈「还算流畅、声音也跟得上，但明显感觉帧率不高，25 甚至更低」，
 * 而这台电视**没有 adb**。屏幕上能给的只有几个计数器，
 * 但读数本身不能告诉他该动哪里 —— 这一层负责给出结论。
 *
 * 判据（按优先级，都是「用现象区分根因」，不是猜）：
 *
 * 1. **硬解静默回落**：ijkplayer 建 MediaCodec 失败时只在 native 层打一行日志就换
 *    FFmpeg 软解（见 [MediaCodecChoice]），4K H.265 软解在 Cortex-A53 上是个位数帧率 ——
 *    它看起来「只是有点慢」，实际是通路根本不对。
 *    注意要区分「选码器给了解码器名却没接上」（要修）和「这台设备本来就没有硬解」（不是故障）。
 * 2. **送显低于片源**：知道自己片子是多少帧（[Input.sourceFps]）之后，
 *    「25 甚至更低」才有对照物：片源本来 25 帧就不是故障，掉到 18 帧才是。
 * 3. **解码跟不上**：解码帧率明显低于**片源**帧率（不是低于送显 —— 见下）。
 * 4. **送显跟不上**：解码够快但有丢帧 —— 瓶颈在渲染/合成。
 * 5. **读取跟不上**：实测读取速率低于码率（SMB 供不上），解码器再强也没用。
 *
 * ## 2026-09-12 实机更正：锅在**编码**上，不在「4K」上
 *
 * 老版本这里写着「4K 片源 + 这台电视的显示通路吃不下，换 1080p」。
 * 那是**只测过 4K HEVC** 得出的结论，实机用 4K **H.264** 一测就被推翻了：
 *
 * | 片源 | 编码 | 解码 | 送显 | 结论 |
 * | :--- | :--- | :--- | :--- | :--- |
 * | 3840×2160@50 | H.264 | 54.5（中位 50） | 46.8（中位 **50.0**） | **满帧，能看** |
 * | 2960×2160@23.98 | HEVC | 24.0 | 24.0 | 满帧 |
 * | 3840×2160@25 | HEVC | 18.6 | 17.2 | 只有 ~18 帧/秒 |
 * | 1920×1080@60 | H.264 | 59.7 | 59.7 | 满帧 |
 *
 * 所以这台电视真正的边界是：**HEVC 解码块约 150 Mpx/秒**（4K 一帧 8.3 Mpx ⇒ 顶多 ~18 帧/秒，
 * 与片源帧率无关），而 **H.264 的 4K 能跑到 ~390 Mpx/秒**。
 * 于是结论必须先看编码（[uhdHint]），否则会把用户支去换分辨率 ——
 * 而其实「把 4K HEVC 换成 4K H.264」就够了。
 *
 * 顺带一条反面教训：「解码低于送显 ⇒ 锅在解码」这个判据（老版本 [Input.decodeIsRealBottleneck]）
 * 是错的 —— 送显卡住会**反压**解码、把两者一起拖低，看起来两个都低时锅反而被算到送显头上。
 * 现在只问一句：**解码自己够不够片源**。
 *
 * 抽成纯函数是为了能用单元测试钉住：这些结论会被贴到用户界面上，
 * 说错方向比不说更糟。
 */
object PlaybackVerdict {

    /**
     * 低于这个帧率就认为「跟不上」。
     *
     * 取 20：电视剧/动画常见 23.976 / 24 / 25 / 30 / 50 帧，
     * 低于 20 一定是掉了帧，不可能是「片源本来就这样」。
     * 只在**不知道片源帧率**时才用它兜底（知道就按比例比）。
     */
    const val LOW_FPS = 20f

    /** 送显低于片源这个比例就算掉帧（85%：23.976 的片子掉到 20.4 帧以下才算）。 */
    const val FPS_TOLERANCE = 0.85f

    /** 缓存低于这个时长就认为「数据供不上」。 */
    const val LOW_BUFFER_MS = 400L

    /**
     * 丢帧**比例**超过这个值就认为送显有问题。
     *
     * ⚠️ ijkplayer 的 `drop_frame_rate` 是 `drop_count / decode_count`（0~1 的比例），
     * **不是每秒丢几帧** —— 面板上按百分比显示。
     */
    const val DROP_RATIO_NOTICEABLE = 0.05f

    /**
     * 宽 ≥ 这个值就算「4K/UHD 片源」。
     *
     * 取 3000 而不是 3840：2960×2160 这类「准 4K」也要按同一档判断
     * （它的像素率和真 4K 只差 23%）。
     */
    const val UHD_MIN_WIDTH = 3000

    /**
     * 这台电视 HEVC 硬解实测的像素率上限（百万像素/秒）。
     *
     * 2026-09-12 实机（长虹 43Q3T / MT5891 / 1080p 面板，同一台机器、同一个包、前后几分钟）：
     * | 片源 | 编码 | 解码 | 送显 | 丢帧 | 送显像素率 |
     * | :--- | :--- | :--- | :--- | :--- | :--- |
     * | 2960×2160@23.98 | HEVC | 24.0 | **24.0** | 0% | 153 Mpx/秒 |
     * | 3840×2160@25 | HEVC | 18.6 | **17.2** | 14% | 143 Mpx/秒 |
     * | 3840×2160@50 | H.264 | 54.5 | **46.8**（中位 50.0） | 5% | 388 Mpx/秒 |
     * | 1920×1080@60 | H.264 | 59.7 | **59.7** | 0% | 124 Mpx/秒 |
     *
     * 也就是说：**「4K 显示通路吃不下」这个结论是错的**（那是只测过 4K HEVC 得出的，
     * 见 [AVC_UHD_MEASURED]。真正封顶的是**这颗芯片的 HEVC 解码块**，约 150 Mpx/秒 ——
     * 3840×2160 一帧 8.3 Mpx，所以 4K HEVC 顶多 ~18 帧/秒，**跟片源帧率无关**；
     * 而 2960×2160@24（153 Mpx/秒）就刚好在线上，实测满帧。
     *
     * 这个数只用来**解释**，不用来报警（报警靠 [Input.fpsShortfall]）。
     */
    const val HEVC_DECODE_MPX = 150f

    /**
     * H.264（AVC）4K 实测能到多少 —— 用来说明「4K 不是都不行」。
     *
     * 实测 3840×2160@50 H.264 送显中位数 **50.0 帧/秒**（= 片源帧率，388 Mpx/秒），
     * 只有约 5% 的帧被丢；换成「深帧队列 + 不丢帧」那档预设后丢帧降到 0%。
     * 所以这台电视放 4K H.264 是**能满帧**的，别再把 4K 一棍子打死。
     */
    const val AVC_UHD_MEASURED_MPX = 388f

    /** 结论 + 它是不是「需要动手修的问题」。 */
    class Result(val text: String, val problem: Boolean) {
        override fun toString(): String = text
    }

    class Input(
        /** 播放器已经起播（`onPrepared` 之后）。 */
        val playing: Boolean,
        val decoder: PlaybackEngine.Decoder,
        /** 这次起播请求过硬解（不代表建成）。 */
        val requestedHardware: Boolean,
        /**
         * 选码器给出过解码器名。
         * `true` 而实际在软解 ⇒ 静默回落（要修）；
         * `false` ⇒ 本机没有可用硬解（不是故障）。
         */
        val codecOffered: Boolean,
        /** 视频原始宽高（像素率为 0 时不显示）。 */
        val videoWidth: Int,
        val videoHeight: Int,
        /**
         * 视频编码器名（`h264` / `hevc` / …）。
         *
         * **结论必须区分编码**：这台电视的 HEVC 解码块 4K 只有 ~18 帧/秒，
         * 而 H.264 的 4K 能满帧 —— 只报「4K 片源」会把用户带到错误的方向
         * （2026-09-12 实机推翻的正是这个结论）。
         */
        val videoCodec: String = "",
        /** 片源自己的帧率；0 = 不知道。 */
        val sourceFps: Float,
        /** 每秒解码出来的帧数。 */
        val decodeFps: Float,
        /** 每秒**送显**的帧数（MediaCodec 通路上可能恒为 0，见 `PlaybackEngine.outputFps`）。 */
        val outputFps: Float,
        /**
         * 丢掉帧的**比例**（0~1）。
         *
         * 来自 `stat.drop_frame_rate = drop_count / decode_count`，
         * 不是「每秒丢几帧」。只有在软解且 `framedrop>0` 时才会累加；
         * MediaCodec 通路上的「追帧跳过」不计数（所以硬解时它常年是 0，
         * 不能拿它当「没有掉帧」的证据）。
         */
        val dropRatio: Float,
        /** 已缓存时长（毫秒）。 */
        val cachedMs: Long,
        /** 实测读取速率（字节/秒）。 */
        val readBytesPerSec: Long,
        /** 播放器自报的码率（bit/s）；0 = 不知道。 */
        val bitRateBps: Long,
    ) {

        /** 按码率折算出来的「这条流至少需要多少字节/秒」；0 = 不知道。 */
        fun neededBytesPerSec(): Long = if (bitRateBps > 0) bitRateBps / 8 else 0L

        /** 读取速率够不够（不知道码率时返回 true，不瞎报警）。 */
        fun readEnough(): Boolean {
            val need = neededBytesPerSec()
            return need <= 0 || readBytesPerSec <= 0 || readBytesPerSec >= need * 8 / 10
        }

        /** 送显的像素率（百万像素/秒）—— 电视视频通路的「吞吐尺子」。 */
        fun outputMpx(): Float = outputFps * videoWidth * videoHeight / 1e6f

        /** 片源需要的像素率。 */
        fun sourceMpx(): Float = sourceFps * videoWidth * videoHeight / 1e6f

        /** 是不是 4K/UHD 片源（见 [UHD_MIN_WIDTH]）。 */
        fun isUhd(): Boolean = videoWidth >= UHD_MIN_WIDTH

        /** 送显够不够片源的帧率。 */
        fun fpsShortfall(): Boolean = sourceFps > 0f && outputFps > 0f && outputFps < sourceFps * FPS_TOLERANCE

        /** 解码够不够片源的帧率（不知道片源帧率时用绝对阈值兜底）。 */
        fun decodeShortfall(): Boolean =
            if (sourceFps > 0f && decodeFps > 0f) {
                decodeFps < sourceFps * FPS_TOLERANCE
            } else {
                decodeFps in 0.01f..(LOW_FPS - 0.01f)
            }

        /**
         * 画面到底有没有在动。
         *
         * 两个计数器都拿不到时（硬解通路 `vfps` 可能是 0）只能返回 true，
         * 不能凭一个恒为 0 的读数说「画面不动了」—— 这正是当年那个存活看门狗
         * 被误报逼停的原因。
         */
        fun moving(): Boolean = outputFps > 0.05f || decodeFps > 0.05f
    }

    /**
     * 4K HEVC 跟不上时的那句话 —— **换编码，不是换分辨率**。
     *
     * 实机（2026-09-12，同一台电视同一个包）：
     * 2960×2160@23.98 HEVC 满帧（153 Mpx/秒），3840×2160@25 HEVC 只有 17~18 帧/秒（207 Mpx/秒）。
     * 所以出路是「换 H.264 的 4K 版本」或「换 1080p」，而不是「这台电视放不了 4K」。
     */
    const val HEVC_UHD_HINT =
        "这是 4K HEVC 片源，而这颗芯片的 HEVC 解码块实测约 150 Mpx/秒封顶" +
            "（3840×2160 就是 ~18 帧/秒，2960×2160 那一档反而满帧）—— " +
            "换 H.264 的 4K 版本，或换 1080p"

    /**
     * 4K H.264 跟不上时的那句话。
     *
     * 实测 3840×2160@50 H.264：送显中位 50.0 帧/秒、丢帧约 5%（388 Mpx/秒）——
     * 也就是 **4K H.264 这台电视是放得动的**，50 帧这种顶格片源才会偶有掉帧。
     */
    const val AVC_UHD_HINT =
        "这是 4K H.264 片源，实测这台电视 4K H.264 能送到约 390 Mpx/秒" +
            "（3840×2160@50 约 47 帧/秒、丢 5%）—— 换 25/30 帧的 4K 版本，或换 1080p"

    private fun ok(text: String) = Result(text, problem = false)

    private fun bad(text: String) = Result(text, problem = true)

    /** 编码名归一化后判断（`h264` / `hevc` / 也可能带解码器名）。 */
    private fun isHevc(codec: String): Boolean {
        val c = codec.lowercase()
        return c.contains("hevc") || c.contains("h265") || c.contains("h.265")
    }

    private fun isAvc(codec: String): Boolean {
        val c = codec.lowercase()
        return c.contains("h264") || c.contains("h.264") || c.contains("avc")
    }

    /**
     * 4K 片源跟不上时，按**编码**补一句「为什么」—— 这是这台电视上最要命的一条区别：
     * HEVC 的 4K 是死的（解码块 ~150 Mpx/秒），H.264 的 4K 是活的（实测 388 Mpx/秒）。
     * 不分编码地只说「4K 吃不下」，会把用户支去换分辨率，而其实换个编码就够了。
     */
    private fun uhdHint(i: Input): String = when {
        !i.isUhd() || i.decoder != PlaybackEngine.Decoder.HARDWARE -> ""
        isHevc(i.videoCodec) -> " —— $HEVC_UHD_HINT"
        isAvc(i.videoCodec) -> " —— $AVC_UHD_HINT"
        else -> ""
    }

    fun of(i: Input): Result {
        if (!i.playing) return ok("还没起播")

        if (i.requestedHardware && i.decoder == PlaybackEngine.Decoder.SOFTWARE) {
            return if (i.codecOffered) {
                bad("硬解没建成：选中的解码器实际没接上，ijkplayer 静默退回了软解 —— 这条要修")
            } else {
                ok("这台设备没有可用的硬解解码器，只能软解（4K 片源会很慢）")
            }
        }

        if (i.readBytesPerSec > 0 && !i.readEnough()) {
            return bad("读取跟不上：每秒只读到 ${mb(i.readBytesPerSec)} MB，片子要 ${mb(i.neededBytesPerSec())} MB")
        }

        // 缓存见底优先于解码：没数据的时候解码帧率低是**结果**，不是原因
        if (i.cachedMs in 0 until LOW_BUFFER_MS && i.decodeShortfall()) {
            return bad("数据供不上（缓存只剩 ${i.cachedMs} 毫秒），先查 NAS 读取")
        }

        // 知道片源帧率时，先把「够不够」说清楚 —— 这是用户最关心的一句话
        if (i.fpsShortfall()) {
            val head = "送显 ${one(i.outputFps)} 帧/秒，低于片源的 ${one(i.sourceFps)} 帧/秒："
            // 判据是**解码自己够不够片源**，而不是「解码是否低于送显」。
            // 送显卡住会反压解码、把两者一起拖低，老版本据此说「瓶颈在送显」虽然不算错，
            // 但用户真正需要的答案在编码上（见 [uhdHint]）。
            if (i.decodeShortfall()) {
                val both = i.outputFps > 0f && i.decodeFps <= i.outputFps * 1.1f
                val why = if (both && !i.isUhd()) {
                    "解码和送显都卡在 ${one(i.decodeFps)} 帧/秒上下，整条通路到顶了"
                } else {
                    decodeBlame(i)
                }
                return bad(head + why + uhdHint(i))
            }
            // 解码够快：瓶颈在渲染/合成（4K HEVC 也可能落在这里 —— 队列深时解码能追上来）
            return bad(
                head + if (i.dropRatio >= DROP_RATIO_NOTICEABLE) {
                    "解码够快但丢了 ${pct(i.dropRatio)} 的帧，瓶颈在送显"
                } else {
                    "解码够快，画面却跟不上，瓶颈在送显/合成（送显 ${one(i.outputMpx())} Mpx/秒）"
                } + uhdHint(i),
            )
        }

        if (i.sourceFps > 0f && i.outputFps > 0f) {
            return ok("看起来正常（片源 ${one(i.sourceFps)} 帧/秒，送显 ${one(i.outputFps)} 帧/秒，缓存 ${i.cachedMs} 毫秒）")
        }

        // 拿不到片源帧率时的兜底判据
        if (i.dropRatio >= DROP_RATIO_NOTICEABLE) {
            return bad("解码跟得上（${one(i.decodeFps)} 帧/秒），是送显来不及、丢了 ${pct(i.dropRatio)} 的帧")
        }
        if (i.decodeShortfall()) {
            val head = "解码跟不上：每秒只出 ${one(i.decodeFps)} 帧"
            if (i.outputFps > i.decodeFps * 1.2f) {
                return bad("$head，但送显有 ${one(i.outputFps)} 帧/秒 —— 瓶颈在送显/渲染")
            }
            return bad("$head（${decodeBlame(i)}）")
        }
        if (!i.moving()) return ok("还没解出画面")

        return ok("看起来正常（解码 ${one(i.decodeFps)} 帧/秒，缓存 ${i.cachedMs} 毫秒）")
    }

    /** 解码侧的锅是谁的。 */
    private fun decodeBlame(i: Input): String =
        if (i.decoder == PlaybackEngine.Decoder.HARDWARE) {
            "硬解每秒只出 ${one(i.decodeFps)} 帧"
        } else {
            "软解每秒只出 ${one(i.decodeFps)} 帧"
        }

    /** 比例 → 百分比整数（丢帧比例用）。 */
    fun pct(v: Float): String = "${(v * 100).toInt()}%"

    /** 数字 → 一位小数，避免面板上一串浮点噪声。 */
    fun one(v: Float): String =
        if (!v.isFinite()) "0.0" else String.format(java.util.Locale.US, "%.1f", v)

    /** 字节/秒 → MB/秒，保留一位小数。 */
    fun mb(bytesPerSec: Long): String =
        String.format(java.util.Locale.US, "%.1f", bytesPerSec / 1024.0 / 1024.0)
}
