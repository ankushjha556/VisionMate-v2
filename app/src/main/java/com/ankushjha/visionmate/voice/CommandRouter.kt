package com.ankushjha.visionmate.voice

import com.ankushjha.visionmate.util.VoiceResponses

/**
 * Maps a raw speech transcript to an app intent + response language.
 *
 * Bilingual by design: English, Devanagari Hindi, and romanized Hinglish
 * commands all work. Language auto-detection: Devanagari characters or
 * Hindi keywords → Hindi responses; otherwise English.
 */
object CommandRouter {

    enum class Intent { DESCRIBE, READ_TEXT, CHECK_AHEAD, HELP, UNKNOWN }

    data class Command(val intent: Intent, val lang: VoiceResponses.Lang)

    fun route(transcriptRaw: String, responseLangSetting: Int): Command {
        val t = transcriptRaw.trim().lowercase()
        val lang = when (responseLangSetting) {
            com.ankushjha.visionmate.util.Prefs.LANG_EN -> VoiceResponses.Lang.EN
            com.ankushjha.visionmate.util.Prefs.LANG_HI -> VoiceResponses.Lang.HI
            else -> detectLanguage(t)
        }

        // ---- READ TEXT ----
        if (containsAny(t, READ_EN) || containsAny(t, READ_HI) || containsAny(t, READ_ROMAN))
            return Command(Intent.READ_TEXT, lang)

        // ---- CHECK AHEAD / OBSTACLE ----
        if (containsAny(t, AHEAD_EN) || containsAny(t, AHEAD_HI) || containsAny(t, AHEAD_ROMAN))
            return Command(Intent.CHECK_AHEAD, lang)

        // ---- DESCRIBE ----
        if (containsAny(t, DESCRIBE_EN) || containsAny(t, DESCRIBE_HI) || containsAny(t, DESCRIBE_ROMAN))
            return Command(Intent.DESCRIBE, lang)

        // ---- HELP ----
        if (containsAny(t, HELP_EN) || containsAny(t, HELP_HI) || containsAny(t, HELP_ROMAN))
            return Command(Intent.HELP, lang)

        return Command(Intent.UNKNOWN, lang)
    }

    fun detectLanguage(text: String): VoiceResponses.Lang {
        if (text.any { it.code in 0x0900..0x097F }) return VoiceResponses.Lang.HI
        val lower = text.lowercase()
        if (containsAny(lower, HINDI_MARKERS)) return VoiceResponses.Lang.HI
        return VoiceResponses.Lang.EN
    }

    private fun containsAny(text: String, needles: List<String>): Boolean {
        for (n in needles) if (text.contains(n)) return true
        return false
    }

    // ---------- keyword tables ----------
    private val DESCRIBE_EN = listOf(
        "describe", "what do you see", "what's in front", "whats in front",
        "what is in front", "what's around", "whats around", "tell me about the room",
        "what's there", "whats there", "caption", "scene"
    )
    private val DESCRIBE_HI = listOf(
        "बताओ", "बताइए", "क्या दिख", "क्या है", "सामने क्या", "आगे क्या", "वर्णन"
    )
    private val DESCRIBE_ROMAN = listOf(
        "batao", "bata", "kya dikh", "kya hai", "samne kya", "aage kya", "varnan"
    )

    private val READ_EN = listOf("read the text", "read text", "read this", "what does it say",
        "what does this say", "read the sign", "read sign", "read label", "ocr")
    private val READ_HI = listOf("पढ़ो", "पढ़िए", "पढ़", "टेक्स्ट पढ़", "लिखा क्या", "क्या लिखा")
    private val READ_ROMAN = listOf("padho", "padh", "likha kya", "kya likha", "text padho")

    private val AHEAD_EN = listOf("what's ahead", "whats ahead", "obstacle", "is it safe",
        "am i safe", "can i walk", "path clear", "clear path", "any danger", "watch out")
    private val AHEAD_HI = listOf("रुकना", "रास्ता", "अवरोध", "सुरक्षित", "चल सकता")
    private val AHEAD_ROMAN = listOf("raasta", "rasta", "aage dekh", "safe hai", "chal sakt")

    private val HELP_EN = listOf("help", "what can you do", "commands")
    private val HELP_HI = listOf("मदद", "क्या कर सकते")
    private val HELP_ROMAN = listOf("madad", "kya kar sakte")

    private val HINDI_MARKERS = listOf(
        "batao", "padho", "kya", "hai", "raasta", "rasta", "madad", "samne", "aage"
    )
}
