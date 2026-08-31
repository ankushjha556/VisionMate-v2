package com.ankushjha.visionmate.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.ankushjha.visionmate.util.VoiceResponses

/**
 * STT wrapper around Android's built-in SpeechRecognizer.
 *
 * Recognition uses hi-IN when the user's response language is Hindi,
 * en-IN otherwise; auto mode tries en-IN first (Google's recognizer on
 * Indian devices handles Hinglish well in the en-IN model) and the
 * CommandRouter detects Devanagari output anyway.
 *
 * Failure-safety (fixes the "voice loop" bug): this class NEVER restarts
 * itself. One startListening() → exactly one terminal callback (onResult /
 * onError), then the recognizer is destroyed. The UI decides what happens
 * next.
 */
class SpeechListener(private val context: Context) {

    companion object { private const val TAG = "VisionMate/STT" }

    interface Callback {
        fun onPartial(text: String) {}
        fun onResult(text: String)
        fun onError(userMessage: String)
    }

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var listening = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    val isListening: Boolean get() = listening

    fun startListening(lang: VoiceResponses.Lang, callback: Callback) {
        if (!available) {
            callback.onError("Speech recognition is not available on this device")
            return
        }
        stop()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        listening = true

        val langTag = if (lang == VoiceResponses.Lang.HI) "hi-IN" else "en-IN"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        var terminated = false
        fun finish(block: () -> Unit) {
            if (terminated) return
            terminated = true
            listening = false
            block()
            stop()
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { Log.d(TAG, "ready") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.d(TAG, "end of speech") }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { if (it.isNotBlank()) callback.onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isBlank()) finish { callback.onError("Nothing was heard. Tap the mic and try again.") }
                else finish { callback.onResult(text) }
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Nothing was heard. Please try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Please try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech needs a network connection on this device."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech engine was busy. Please try again."
                    else -> "Speech recognition failed. Please try again."
                }
                finish { callback.onError(msg) }
            }
        })
        try {
            sr.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "startListening failed: ${t.message}")
            listening = false
            callback.onError("Could not start listening. Please try again.")
        }
    }

    fun stop() {
        val sr = recognizer
        recognizer = null
        listening = false
        if (sr != null) {
            try { sr.stopListening() } catch (_: Throwable) {}
            try { sr.destroy() } catch (_: Throwable) {}
        }
    }
}
