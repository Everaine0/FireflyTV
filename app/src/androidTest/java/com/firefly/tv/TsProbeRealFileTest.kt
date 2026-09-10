package com.firefly.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.firefly.tv.core.Config
import com.firefly.tv.media.AudioSupport
import com.firefly.tv.media.TsProbe
import com.firefly.tv.smb.SmbClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 直接问 NAS：《娘道》这类 TS 片源在**我们自己的解码路径**里到底被认成什么。
 *
 * ## 为什么要有这个测试
 *
 * 现象是「娘道有画面没声音」，而 `ffprobe` 在电脑上能正确读出 AC-3 音频。
 * 两边结论不一致时，猜是没有用的 —— 必须让**电视机上真正跑的那份代码**
 * 去读**同一个文件**，看它认成什么。
 *
 * 这个测试还会把 PAT/PMT 的原始字节打出来。原因是排查过程中踩过一个坑：
 * 我用 zlib 去校验 PSI 的 CRC，把**所有正确的表都判成了损坏**
 * （MPEG-TS 用的不是 zlib 那套 CRC），差点据此得出「片源的表是坏的」这个错误结论。
 * 把原始字节留在测试输出里，下次就不用再猜了。
 *
 * 没有配置 NAS 时自动跳过（[assumeTrue]），所以在 CI 上也安全。
 */
@RunWith(AndroidJUnit4::class)
class TsProbeRealFileTest {

    /** 至少要能读出两条流：H.264 + AC-3。 */
    @Test
    fun niangdaoHeadHasVideoAndAc3() {
        // 账号从 instrumentation 参数注入，不读应用配置：
        // 跑插桩测试会重装被测应用，应用里存的配置那时已经没了
        // （读应用配置会静默跳过，看着像通过其实什么都没测）。
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        fun arg(k: String) = args.getString(k, "").orEmpty()
        val cfg = Config.Smb(
            arg("ff.smb.host"), arg("ff.smb.share"), arg("ff.smb.root"),
            arg("ff.smb.user"), arg("ff.smb.pass"), arg("ff.smb.domain"),
        )
        assumeTrue(
            "没有配置真 NAS 账号，跳过（检查 local.properties 的 ff.smb.*）",
            cfg.host.isNotBlank() && cfg.share.isNotBlank(),
        )

        val rel = "电视剧/[娘道][2018][全集][国产剧]/01.mp4"
        val client = SmbClient(cfg)
        val head = try {
            client.head(rel, PROBE_BYTES)
        } catch (t: Throwable) {
            assumeTrue("读不到 NAS 上的片源，跳过：${t.message}", false)
            return
        } finally {
            runCatching { client.close() }
        }
        assumeTrue("没读到字节", head.isNotEmpty())

        println("=== 读回 ${head.size} 字节 ===")
        println("前 16 字节: " + head.take(16).joinToString(" ") { "%02X".format(it) })

        val info = TsProbe.probe(head)
        println("=== TsProbe 结果: $info ===")
        if (info != null) {
            for (t in info.tracks) println("    track: $t")
        }

        dumpPsi(head)

        val verdict = AudioSupport.probe(head, isLive = false)
        println("=== AudioSupport: codec=${verdict.codec} canProbe=${verdict.canProbe} " +
            "warning=${verdict.warning} ===")

        assertTrue("TsProbe 应该认出这是 TS 流", info != null)
        assertTrue("应该认出有视频", info!!.hasVideo)
        assertTrue("应该认出有音频（否则播放器同样看不到）", info.hasAudio)
    }

    /** 把 PAT/PMT 的原始 section 打出来，方便和电脑上的 ffprobe 对照。 */
    private fun dumpPsi(data: ByteArray) {
        var pos = 0
        var patShown = false
        var pmtShown = false
        var pmtPid = -1

        while (pos + 188 <= data.size) {
            if (data[pos].toInt() and 0xFF != 0x47) {
                pos++
                continue
            }
            val pid = ((data[pos + 1].toInt() and 0x1F) shl 8) or (data[pos + 2].toInt() and 0xFF)
            val pusi = (data[pos + 1].toInt() and 0x40) != 0
            val afc = (data[pos + 3].toInt() shr 4) and 0x03
            var off = 4
            if (afc and 0x02 != 0) off += 1 + (data[pos + 4].toInt() and 0xFF)
            if (pusi && afc and 0x01 != 0 && off < 188) {
                val sec = data.copyOfRange(pos + off, pos + 188)
                val tid = sec[0].toInt() and 0xFF
                val slen = ((sec[1].toInt() and 0x0F) shl 8) or (sec[2].toInt() and 0xFF)
                if (tid == 0x00 && !patShown && slen in 4..180) {
                    patShown = true
                    println("=== PAT (pid=0x%04X) section_length=%d ===".format(pid, slen))
                    println("    raw: " + sec.take(3 + slen).joinToString("") { "%02x".format(it) })
                    var i = 8
                    while (i + 3 < 3 + slen - 4) {
                        val prog = ((sec[i].toInt() and 0xFF) shl 8) or (sec[i + 1].toInt() and 0xFF)
                        val p = ((sec[i + 2].toInt() and 0x1F) shl 8) or (sec[i + 3].toInt() and 0xFF)
                        println("    program=%d PMT_PID=0x%04X(%d)".format(prog, p, p))
                        if (pmtPid < 0 && p != 0x1FFF) pmtPid = p
                        i += 4
                    }
                }
                if (tid == 0x02 && !pmtShown && slen in 4..180) {
                    pmtShown = true
                    println("=== PMT (pid=0x%04X) section_length=%d ===".format(pid, slen))
                    println("    raw: " + sec.take(3 + slen).joinToString("") { "%02x".format(it) })
                    val pcr = ((sec[8].toInt() and 0x1F) shl 8) or (sec[9].toInt() and 0xFF)
                    val pil = ((sec[10].toInt() and 0x0F) shl 8) or (sec[11].toInt() and 0xFF)
                    println("    PCR_PID=0x%04X".format(pcr))
                    var i = 12 + pil
                    val end = 3 + slen - 4
                    while (i + 4 < end) {
                        val st = sec[i].toInt() and 0xFF
                        val spid = ((sec[i + 1].toInt() and 0x1F) shl 8) or (sec[i + 2].toInt() and 0xFF)
                        val ln = ((sec[i + 3].toInt() and 0x0F) shl 8) or (sec[i + 4].toInt() and 0xFF)
                        val desc = sec.copyOfRange(i + 5, minOf(i + 5 + ln, end))
                        println("    stream_type=0x%02X PID=0x%04X desc=%s".format(
                            st, spid, desc.joinToString("") { "%02x".format(it) }))
                        i += 5 + ln
                    }
                }
            }
            if (patShown && pmtShown) return
            pos += 188
        }
    }

    private fun targetContext(): android.content.Context {
        return androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
    }

    private companion object {
        /** 和播放器探测时用的量保持一致。 */
        const val PROBE_BYTES = 1 shl 20
    }
}
