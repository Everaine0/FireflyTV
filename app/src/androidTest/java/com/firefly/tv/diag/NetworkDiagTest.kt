package com.firefly.tv.diag

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 「这台设备到底能不能连上 NAS / 直播源」——**只做诊断，不做断言**（除了最后一条自检）。
 *
 * 为什么要单独写一个：模拟器里 ping 是不通的（NAT 不回 ICMP），所以「网络好不好」
 * 不能靠 ping 判断，只能真开一条 TCP。老人家里的电视也一样 —— 出问题时先用它定位
 * 是「网不通」还是「应用有问题」。
 *
 * 用法：
 * ```
 * adb shell am instrument -w -e class com.firefly.tv.diag.NetworkDiagTest \
 *   com.firefly.tv.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * 结果在 logcat 的 `FireflyDiag` 里。
 */
@RunWith(AndroidJUnit4::class)
class NetworkDiagTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(msg: String) = android.util.Log.i("FireflyDiag", msg)

    private fun tcp(host: String, port: Int, timeoutMs: Int = 4000): String = try {
        Socket().use { s ->
            val t0 = System.currentTimeMillis()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            val ms = System.currentTimeMillis() - t0
            "OK (${ms}ms)"
        }
    } catch (t: Throwable) {
        "FAIL ${t.javaClass.simpleName}: ${t.message}"
    }

    @Test
    fun probe() {
        val args = InstrumentationRegistry.getArguments()
        val nasHost = args.getString("ff.smb.host").orEmpty()

        log("=== 设备信息 ===")
        log("release=${android.os.Build.VERSION.RELEASE} abi=${android.os.Build.SUPPORTED_ABIS.joinToString()}")

        log("=== NAS (TCP 445) ===")
        log("host=[$nasHost] -> ${if (nasHost.isBlank()) "未配置" else tcp(nasHost, 445, 6000)}")

        log("=== 直播源 (TCP) ===")
        // 直播源地址因运营商/地区而异，不写进仓库：
        // local.properties 里 ff.live.targets=host:port,host:port，由 build.gradle.kts 转发进来
        val targets = args.getString("ff.live.targets").orEmpty()
            .split(',')
            .mapNotNull { item ->
                val t = item.trim()
                if (t.isEmpty()) return@mapNotNull null
                t.substringBeforeLast(':') to
                    (t.substringAfterLast(':', "").toIntOrNull() ?: 80)
            }
        if (targets.isEmpty()) log("未配置 ff.live.targets（见 local.properties.example），跳过")
        for ((h, p) in targets) log("$h:$p -> ${tcp(h, p)}")

        // 这一条必须成立：测试参数没传进来的话，上面所有结论都是假的
        assertTrue(
            "ff.smb.host 没传进来，说明 local.properties 没有被 build.gradle.kts 转发给测试",
            nasHost.isNotBlank(),
        )
    }

    /** 设备上的 SharedPreferences 里到底存了什么（不打印密码）。 */
    @Test
    fun dumpConfig() {
        val cfg = com.firefly.tv.core.Config.smb(ctx)
        log("configured=${com.firefly.tv.core.Config.configured(ctx)}")
        log("smb host=[${cfg.host}] share=[${cfg.share}] root=[${cfg.root}] user=[${cfg.user}] passLen=${cfg.pass.length}")
        val w = com.firefly.tv.core.Config.weather(ctx)
        log("weather ready=${w.ready} location=[${w.location}] keyLen=${w.key.length}")
        val hist = com.firefly.tv.media.WatchHistory.parse(
            com.firefly.tv.core.Config.watchHistoryText(ctx),
        )
        log("观看记录 lastLib=[${hist.lastLib}] 共 ${hist.records.size} 条")
        for (r in hist.records.values.sortedByDescending { it.at }.take(5)) {
            log("  记录 《${r.show}》 第 ${r.episode + 1} 集 @${r.posMs}ms")
        }
        log("channel=${com.firefly.tv.core.Config.channel(ctx)}")
        val cache = com.firefly.tv.core.Config.loadCache(ctx)
        log("scanCache bytes=${cache?.length ?: 0}")
        if (cache != null) {
            val snap = com.firefly.tv.media.LibraryCache.parse(cache)
            log("cache libraries=${snap.libraries.map { it.name }} showsOf(电视剧)=${snap.showsOf("电视剧")}")
            log("cache channels=${snap.channels.mapValues { it.value.size }}")
            log("cache episodes=${snap.episodes.mapValues { it.value.names.size }}")
        }
    }
}
