package com.ankushjha.visionmate.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ankushjha.visionmate.util.VoiceResponses
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Single TTS queue with priorities — obstacle STOP alerts interrupt everything;
 * normal responses queue politely. Mirrors the "no triple-repeated warnings"
 * fix from Phase 1: callers suppress repeats, this class serializes speech.
 *
 * Robustness notes:
 *  - speak() before the engine finishes init lands in a pending queue and is
 *    flushed on init (nothing is ever silently dropped).
 *  - All engine calls are wrapped — a dead/failed engine must never crash the
 *    app, the user just stops hearing speech for that utterance.
 */
class TtsSpeaker(context: Context) {

    enum class Priority { NORMAL, URGENT }

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var shutDown = false
    private val pending = ConcurrentLinkedQueue<Triple<String, VoiceResponses.Lang, Priority>>()

    @Volatile var onSpeechStart: (() -> Unit)? = null
    @Volatile var onSpeechDone: (() -> Unit)? = null
    @Volatile private var speaking = false

    @Volatile var rateStep: Int = 10  // 0.5x..2.0x in tenths

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                try {
                    tts?.setSpeechRate(rateStep / 10f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            speaking = true
                            onSpeechStart?.invoke()
                        }
                        override fun onDone(utteranceId: String?) {
                            speaking = false
                            onSpeechDone?.invoke()
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            speaking = false
                            onSpeechDone?.invoke()
                        }
                    })
                } catch (_: Throwable) { /* listener setup must not kill init */ }
                while (pending.isNotEmpty()) {
                    val (text, lang, priority) = pending.poll() ?: break
                    speakInternal(text, lang, priority)
                }
            }
        }
    }

    val isSpeaking: Boolean get() = speaking || tts?.isSpeaking == true

    fun speak(
        text: String,
        lang: VoiceResponses.Lang = VoiceResponses.Lang.EN,
        priority: Priority = Priority.NORMAL
    ) {
        if (text.isBlank()) return
        if (shutDown) return
        if (!ready) {
            pending.add(Triple(text, lang, priority))
            return
        }
        speakInternal(text, lang, priority)
    }

    private fun speakInternal(text: String, lang: VoiceResponses.Lang, priority: Priority) {
        val engine = tts ?: return
        try {
            engine.setSpeechRate(rateStep / 10f)
            val locale = if (lang == VoiceResponses.Lang.HI) Locale("hi", "IN") else Locale.US
            val result = engine.setLanguage(locale)
            // Hindi voice data missing on this device → fall back to English
            // rather than silence.
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.setLanguage(Locale.US)
            }
            val mode = if (priority == Priority.URGENT)
                TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(text, mode, null, "vm_${System.nanoTime()}")
        } catch (_: Throwable) {
            // A single failed utterance must never take the app down.
        }
    }

    /** Immediately silences current speech (does NOT prevent future speaks). */
    fun stop() {
        try { tts?.stop() } catch (_: Throwable) {}
        speaking = false
    }

    fun shutdown() {
        shutDown = true
        pending.clear()
        try { tts?.stop() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        ready = false
    }
}
