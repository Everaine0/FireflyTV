package com.firefly.tv.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 中文语音播报。
 *
 * 风险 2：长虹电视可能没有中文 TTS 引擎 → 必须能优雅退化。
 * 探测失败时 [available] 为 false，浮层照常显示文字，只是不出声。
 */
class TtsSpeaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    /** 系统里到底有没有中文语音。false 时调用方不要反复尝试。 */
    var available = false
        private set

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS 初始化失败，退化为仅文字")
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val res = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                // 再试一次通用中文，有些引擎只认 CHINA
                val res2 = engine.setLanguage(Locale.CHINA)
                available = res2 != TextToSpeech.LANG_MISSING_DATA && res2 != TextToSpeech.LANG_NOT_SUPPORTED
            } else {
                available = true
            }
            if (available) {
                engine.setSpeechRate(0.9f) // 老人听得清比说得快重要
                engine.setPitch(1.0f)
            } else {
                Log.w(TAG, "系统没有中文语音，退化为仅文字显示")
            }
            ready = true
        }
    }

    /** 只在 [available] 为 true 时才有声音。 */
    fun speak(text: String) {
        if (!available || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "firefly")
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        val t = tts ?: return
        tts = null
        ready = false
        available = false
        runCatching {
            t.stop()
            t.shutdown()
        }
    }

    companion object {
        private const val TAG = "FireflyTTS"
    }
}
