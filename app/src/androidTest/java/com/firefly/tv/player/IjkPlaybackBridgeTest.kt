package com.firefly.tv.player

import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 验证 ijkplayer 能不能通过 [SmbMediaDataSource]（IMediaDataSource 桥接）解码播放。
 *
 * 这是 DESIGN §10 阶段 1 要提前验证的两个最大风险之一：
 * 真机上是 SMB 字节源，这里是本地文件字节源 —— 走的是**同一条 IMediaDataSource 通路**，
 * 所以这个测试过了，就说明 readAt 的随机读语义、ABI 完整性都没问题。
 * SMB 网络层另有 [SmbRandomAccessSource]，只是换了 read 的实现。
 */
@RunWith(AndroidJUnit4::class)
class IjkPlaybackBridgeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 通过IMediaDataSource能解码播放本地视频() {
        val video = TestVideo.ensure(context)
        assertTrue("测试视频没生成出来", video.length() > 1000)
        playAndAssert(FileRandomAccessSource(video.absolutePath), "文件字节源")
    }

    /** 纯内存字节源：用来区分「块缓存逻辑」和「底层读」两类问题。 */
    @Test
    fun 内存字节源也能解码播放() {
        val video = TestVideo.ensure(context)
        playAndAssert(ByteArrayRandomAccessSource(video.readBytes()), "内存字节源")
    }

    private fun playAndAssert(source: RandomAccessSource, label: String) {
        val prepared = CountDownLatch(1)
        val firstFrame = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val duration = AtomicReference(0L)

        val main = Handler(Looper.getMainLooper())
        val engine = IjkPlaybackEngine(context)
        main.post {
            engine.setListener(object : PlaybackEngine.Listener {
                override fun onPrepared(durationMs: Long) {
                    duration.set(durationMs)
                    prepared.countDown()
                }

                override fun onCompletion() = Unit

                override fun onError(friendlyMessage: String, fatal: Boolean) {
                    failure.set(friendlyMessage)
                    prepared.countDown()
                    firstFrame.countDown()
                }

                override fun onFirstFrame() {
                    firstFrame.countDown()
                }
            })
            engine.playSource(source, 0L)
        }

        assertTrue("$label prepare 超时，错误=${failure.get()}", prepared.await(30, TimeUnit.SECONDS))
        assertTrue("$label 播放报错：${failure.get()}", failure.get() == null)
        assertTrue("$label 时长应该大于 0，实际 ${duration.get()}", duration.get() > 0)
        assertTrue("$label 等不到首帧", firstFrame.await(30, TimeUnit.SECONDS))

        main.post { engine.release() }
    }

    /**
     * 纯本地字节源不涉及网络，但离线时 [FileRandomAccessSource] 仍要能在
     * 非顺序访问下正确返回数据 —— ijkplayer 找 moov box 就是靠跳读。
     */
    @Test
    fun 本地字节源支持随机读() {
        val video = TestVideo.ensure(context)
        FileRandomAccessSource(video.absolutePath).use { src ->
            assertTrue(src.size > 1000)

            // 顺序读
            val head = ByteArray(64)
            val n = src.read(0, head, 0, head.size)
            assertTrue("读头部失败", n > 0)

            // 跳读：读到末尾必须能拿到数据
            val tail = ByteArray(64)
            val tn = src.read(src.size - 32, tail, 0, 64)
            assertTrue("末尾跳读失败", tn > 0)

            // 越界读返回 -1
            assertTrue("越界应返回 -1", src.read(src.size, tail, 0, 16) == -1)
        }
    }

    /**
     * moov 在文件末尾的 mp4（非 faststart）能不能播。
     *
     * 结论记录：**这个用例会失败**。ijkplayer 的 IMediaDataSource 通道下（0.8.8），
     * mov 解复用器不会回尾读 moov，于是报 "moov atom not found"。
     * 相机/下载来的 mp4 大多是这种布局，所以这是必须记在案的限制，
     * 用 @Ignore 标注并在 DESIGN 风险清单里登记，而不是假装它能播。
     */
    /**
     * moov 在文件末尾的 mp4（非 faststart）也要能播 —— 靠 [MoovRelocatingSource] 把 moov 搬到头部。
     *
     * 背景：ijkplayer 0.8.8 在 IMediaDataSource 通道下只顺序读、不回尾读 moov，
     * 直接播会报 "moov atom not found"（DESIGN 风险 8）。相机录的、下载来的 mp4 大多是这种布局，
     * 所以这条必须过，否则等于一半片源打不开。
     */
    @Test
    fun moov在末尾的mp4也能播放() {
        val video = TailMoovVideo.ensure(context)
        // 先确认这个素材确实是非 faststart，否则这条测试就是假绿
        val moovAt = findMoovOffset(video.readBytes())
        assertTrue("测试素材的 moov 应在文件尾部，实际偏移 $moovAt", moovAt > video.length() / 2)

        playAndAssert(FileRandomAccessSource(video.absolutePath), "moov 在末尾")
    }

    private fun findMoovOffset(data: ByteArray): Int {
        for (i in 0 until data.size - 4) {
            if (data[i] == 'm'.code.toByte() && data[i + 1] == 'o'.code.toByte() &&
                data[i + 2] == 'o'.code.toByte() && data[i + 3] == 'v'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    /** 跳读必须真的生效，否则续播和拖进度都会失灵（DESIGN §6）。 */
    @Test
    fun 跳读后能继续解码() {
        val video = TestVideo.ensure(context)
        val source = FileRandomAccessSource(video.absolutePath)

        val prepared = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val engineRef = AtomicReference<IjkPlaybackEngine?>(null)
        val main = Handler(Looper.getMainLooper())

        main.post {
            val engine = IjkPlaybackEngine(context)
            engineRef.set(engine)
            engine.setListener(object : PlaybackEngine.Listener {
                override fun onPrepared(durationMs: Long) {
                    prepared.countDown()
                }

                override fun onCompletion() = Unit

                override fun onError(friendlyMessage: String, fatal: Boolean) {
                    failure.set(friendlyMessage)
                    prepared.countDown()
                }

                override fun onFirstFrame() = Unit
            })
            engine.playSource(source, 0L)
        }

        assertTrue("prepare 超时/报错：${failure.get()}", prepared.await(30, TimeUnit.SECONDS))
        assertTrue("播放报错：${failure.get()}", failure.get() == null)

        // 跳到 3 秒处：这一步必须走 readAt 的随机读分支
        val seekDone = CountDownLatch(1)
        main.post {
            engineRef.get()?.seekTo(3000)
            seekDone.countDown()
        }
        assertTrue(seekDone.await(10, TimeUnit.SECONDS))

        // 等一等再取位置：位置应当接近 3 秒而不是回到 0
        Thread.sleep(2000)
        val pos = AtomicReference(0L)
        val posDone = CountDownLatch(1)
        main.post {
            pos.set(engineRef.get()?.positionMs() ?: 0L)
            posDone.countDown()
        }
        assertTrue(posDone.await(10, TimeUnit.SECONDS))
        // 跳读后位置必须明显离开 0 且贴近目标；上界留宽一点，
        // 因为从 prepare 到此刻的墙钟时间里解码器也在往前走
        assertTrue("跳读后位置应在 2000..6000ms，实际 ${pos.get()}", pos.get() in 2000..6000)

        main.post { engineRef.get()?.release() }
    }
}
