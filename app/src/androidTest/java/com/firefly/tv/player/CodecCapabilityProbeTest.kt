package com.firefly.tv.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「这台设备到底能硬解什么」—— 上真机之前先跑这一条。
 *
 * ## 为什么必须实测，不能查资料
 *
 * 目标机型是 2016 年的长虹 CHiQ 43Q3T（联发科 MT5520，4×Cortex-A53 + Mali-T860，
 * 长虹官方写的是「HEVC/H.265 **4K2K@60** 硬解码」）。但官方规格里**没有**逐字写
 * 「HEVC Main10」或 profile/level —— 而 Android 5.1 的 CDD 只强制两件事：
 *
 *  - H.264：电视**必须**支持 High Profile Level 4.2 + 1080p（[CDD 5.3]）→ 剧集那条线稳；
 *  - H.265：只强制 Main Profile Level 3（SD 档）；**Main10 L5 + UHD 只是 SHOULD**。
 *
 * 也就是说「4K H.265 能不能硬解」是厂商可选项，只能问设备本身。
 * 更麻烦的是 ijkplayer **对 HEVC 完全不检查 profile**
 * （`ffpipenode_android_mediacodec_vdec.c` 的 HEVC 分支只看开关；H.264 分支才逐个 profile 判断），
 * 所以 Main10 会被无脑塞给硬解 —— 硬件不认就是「黑屏但有声音」，还不报错。
 *
 * ## 怎么用
 *
 * ```powershell
 * .\build.ps1 connectedDebugAndroidTest `
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.firefly.tv.player.CodecCapabilityProbeTest
 * adb logcat -s FireflyCodec
 * ```
 *
 * 期望在日志里看到形如：
 * ```
 * codec=OMX.MTK.VIDEO.DECODER.HEVC decoder=true
 *   type=video/hevc profileLevels=[Main(1)/L4(156) Main10(2)/L5(180)]
 *   videoCaps 4K=false(2048x2048) 4K60=false 3840x2160@30=false
 *   colorFormats=[0x7f000789(Surface), 0x15(I420)]  ← 要有 Surface(0x7f000789)
 * ```
 *
 * 判定标准（看 `video/hevc` 那一行）：
 *  - `4K=true` 且 `colorFormats` 含 `0x7f000789` → 4K H.265 能硬解直通，最好；
 *  - `4K=false` → 这台设备**不可能**放 4K H.265（软解在 A53 上不可行，见 LibreELEC 的实测口径），
 *    该考虑的是「不要下这种片源」或者提前给用户一句人话提示，而不是让它慢慢卡。
 *
 * 这条**永远通过**：它是探测，不是断言某个具体结果。断言在这台陌生设备上跑会红，
 * 而红了的测试没人看 —— 要的只是那几行日志。
 */
@RunWith(AndroidJUnit4::class)
class CodecCapabilityProbeTest {

    private fun log(msg: String) = android.util.Log.i("FireflyCodec", msg)

    /** `CodecCapabilities.COLOR_FormatSurface`，API 21 起可用。 */
    private val colorSurface = 0x7f000789

    @Test
    fun 打印本机所有视频解码器及其4K能力() {
        log("=== 设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ===")

        var total = 0
        var hevc = 0
        for (i in 0 until MediaCodecList.getCodecCount()) {
            val info = MediaCodecList.getCodecInfoAt(i)
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (!type.startsWith("video/")) continue
                // 只关心播放用得到的三类，别的（vp8/vp9/av01…）噪音太大
                if (type != "video/avc" && type != "video/hevc" && type != "video/mpeg2") continue
                total++
                if (type == "video/hevc") hevc++
                dump(info, type)
            }
        }

        log("=== 共 $total 条（video/avc + video/hevc + video/mpeg2），其中 HEVC $hevc 条 ===")
        if (hevc == 0) {
            // 这条很重要：一个 HEVC 硬解都没有时，用户放 4K H.265 只能软解
            log("⚠️ 本机没有任何 video/hevc 解码器 —— 4K H.265 只能软解，A53 上不可用")
        }

        // 探测本身不判定成败，但至少要能枚举到解码器，否则说明取列表这一步就错了
        assertTrue("一个视频解码器都没枚举到，MediaCodecList 用法有问题", total > 0)
    }

    private fun dump(info: MediaCodecInfo, type: String) {
        val caps = try {
            info.getCapabilitiesForType(type)
        } catch (t: Throwable) {
            log("codec=${info.name} type=$type 取能力失败：${t.message}")
            return
        }

        log("codec=${info.name} type=$type")
        log("  profileLevels=" + caps.profileLevels.joinToString(" ") { "${profileName(type, it.profile)}/L${it.level}" })

        val vc = try {
            caps.videoCapabilities
        } catch (t: Throwable) {
            null
        }
        if (vc != null) {
            val r1080 = runCatching { vc.isSizeSupported(1920, 1080) }.getOrDefault(false)
            val r4k = runCatching { vc.isSizeSupported(3840, 2160) }.getOrDefault(false)
            val r4k30 = runCatching { vc.areSizeAndRateSupported(3840, 2160, 30.0) }.getOrDefault(false)
            val r4k60 = runCatching { vc.areSizeAndRateSupported(3840, 2160, 60.0) }.getOrDefault(false)
            log("  1080p=$r1080 4K=$r4k 4K@30=$r4k30 4K@60=$r4k60 最大=${vc.supportedWidths} x ${vc.supportedHeights}")
        } else {
            log("  （没有 VideoCapabilities —— 不是视频解码器？）")
        }

        val hasSurface = caps.colorFormats.contains(colorSurface)
        log("  colorFormats=" + caps.colorFormats.joinToString(" ") { "0x" + Integer.toHexString(it) } +
            "  Surface可用=$hasSurface")
        if (!hasSurface) {
            // 没有 Surface 颜色格式 = 只能 ByteBuffer 出帧 = 要 CPU 拷贝，4K 上不现实
            log("  ⚠️ 不支持 COLOR_FormatSurface：这条解码器没法零拷贝出图，4K 基本没戏")
        }
    }

    private fun profileName(type: String, profile: Int): String = when (type) {
        "video/hevc" -> when (profile) {
            1 -> "Main"
            2 -> "Main10"
            0x1000 -> "Main10HDR10"
            0x2000 -> "Main10HDR10Plus"
            else -> "HEVC($profile)"
        }
        "video/avc" -> when (profile) {
            1 -> "Baseline"
            2 -> "Main"
            4 -> "High"
            8 -> "High10"
            0x1000 -> "High444"
            else -> "AVC($profile)"
        }
        else -> "P$profile"
    }
}
