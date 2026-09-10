package com.firefly.tv.ui

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频焦点 ↔ 播放行为。
 *
 * 重点只有一条：**直播重播的进度必须是 0**。以前直播丢焦点再拿回来，会把播放器的
 * currentPosition（从开播算起的毫秒数，可能是几小时）当成进度重新 seek，
 * 结果就是用户看到的「IPTV 音画不同步」。
 */
class AudioFocusPolicyTest {

    private val pos = 3_600_000L // 一小时

    @Test
    fun `还没开始播时焦点变化不做任何事`() {
        val a = AudioFocusPolicy.decide(AudioFocusPolicy.Content.None, AudioManager.AUDIOFOCUS_GAIN, pos)
        assertTrue(a is AudioFocusPolicy.Action.Continue)
    }

    @Test
    fun `暂失焦点是暂停而不是停掉`() {
        for (change in listOf(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        )) {
            val a = AudioFocusPolicy.decide(AudioFocusPolicy.Content.OnDemand, change, pos)
            assertTrue("change=$change 应该是暂停", a is AudioFocusPolicy.Action.PauseTransient)
        }
    }

    @Test
    fun `永久失去焦点要停掉并释放`() {
        val a = AudioFocusPolicy.decide(AudioFocusPolicy.Content.OnDemand, AudioManager.AUDIOFOCUS_LOSS, pos)
        assertTrue(a is AudioFocusPolicy.Action.StopAndAbandon)
    }

    @Test
    fun `点播重播要接着原来的位置`() {
        val a = AudioFocusPolicy.decide(AudioFocusPolicy.Content.OnDemand, AudioManager.AUDIOFOCUS_GAIN, pos)
        a as AudioFocusPolicy.Action.Replay
        assertEquals(AudioFocusPolicy.Content.OnDemand, a.content)
        assertEquals(pos, a.resumeMs)
    }

    @Test
    fun `直播重播的进度永远是零`() {
        // 这就是那个音画不同步的修复点：外部传进来一小时，也必须变成 0
        for (p in listOf(0L, 3_600_000L, 86_400_000L)) {
            val a = AudioFocusPolicy.decide(AudioFocusPolicy.Content.Live, AudioManager.AUDIOFOCUS_GAIN, p)
            a as AudioFocusPolicy.Action.Replay
            assertEquals(AudioFocusPolicy.Content.Live, a.content)
            assertEquals("直播不许带进度（p=$p）", 0L, a.resumeMs)
        }
    }

    @Test
    fun `点播的脏进度会被夹到零`() {
        val a = AudioFocusPolicy.decide(AudioFocusPolicy.Content.OnDemand, AudioManager.AUDIOFOCUS_GAIN, -1L)
        a as AudioFocusPolicy.Action.Replay
        assertEquals(0L, a.resumeMs)
    }
}
