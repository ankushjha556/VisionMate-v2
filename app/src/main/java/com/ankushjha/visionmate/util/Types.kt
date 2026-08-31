package com.ankushjha.visionmate.util

/** Shared data classes used across camera / ML / obstacle layers. */

/** One YOLO detection, coordinates in the upright analysis bitmap's pixel space. */
data class Detection(
    val label: String,
    val score: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
    val centerX: Float get() = (x1 + x2) / 2f
    val bottomCenterY: Float get() = y2
}

/** Result of an obstacle-cycle analysis. */
data class ObstacleAssessment(
    val nearestLabel: String?,
    val closeness: Closeness,
    /** Horizontal zone of the nearest hazard: 0..1 (0 = far left, 1 = far right). */
    val hazardZoneX: Float,
    val passSide: PassSide,
    val detections: List<Detection>
)

enum class Closeness { CLEAR, MID, CLOSE, STOP }
enum class PassSide { NONE, LEFT, RIGHT, NARROW }

/** Bilingual spoken responses. Auto mode picks based on the user's command language. */
object VoiceResponses {

    enum class Lang { EN, HI }

    fun stop(lang: Lang, label: String): String = if (lang == Lang.HI)
        "रुकिए! सामने $label है।"
    else
        "Stop! $label immediately ahead."

    fun close(lang: Lang, label: String, steps: Int): String = if (lang == Lang.HI)
        "सामने $label है, लगभग $steps कदम दूर।"
    else
        "$label ahead, about $steps step" + (if (steps != 1) "s" else "") + "."

    fun side(lang: Lang, label: String, side: PassSide): String = when (side) {
        PassSide.LEFT -> if (lang == Lang.HI) "$label आपके बाएँ है।" else "$label on your left."
        PassSide.RIGHT -> if (lang == Lang.HI) "$label आपके दाएँ है।" else "$label on your right."
        else -> ""
    }

    fun pathClear(lang: Lang): String = if (lang == Lang.HI)
        "आगे का रास्ता साफ़ है।"
    else
        "Path ahead is clear."

    fun pathSide(lang: Lang, side: PassSide): String = when (side) {
        PassSide.LEFT -> if (lang == Lang.HI) "बाएँ से रास्ता है।" else "Space to pass on your left."
        PassSide.RIGHT -> if (lang == Lang.HI) "दाएँ से रास्ता है।" else "Space to pass on your right."
        PassSide.NARROW -> if (lang == Lang.HI) "आगे रास्ता संकरा है, सावधानी से चलें।" else "Narrow path ahead, proceed carefully."
        PassSide.NONE -> ""
    }

    fun noText(lang: Lang): String = if (lang == Lang.HI)
        "कोई पढ़ने योग्य टेक्स्ट नहीं दिख रहा।"
    else
        "No readable text in view."

    fun captionUnavailable(lang: Lang): String = if (lang == Lang.HI)
        "सीन विवरण उपलब्ध नहीं है क्योंकि मॉडल फ़ाइलें गायब हैं। कृपया MODELS_SETUP देखें।"
    else
        "Scene description is unavailable because the captioning model files are missing. See MODELS_SETUP."

    fun unknownCommand(lang: Lang): String = if (lang == Lang.HI)
        "समझ नहीं आया। कहिए: बताओ, पढ़ो, या आगे क्या है।"
    else
        "I did not understand. You can say: describe, read text, or what is ahead."

    fun help(lang: Lang): String = if (lang == Lang.HI)
        "आप कह सकते हैं: सामने क्या है, टेक्स्ट पढ़ो, या रास्ता बताओ।"
    else
        "You can say: describe what is in front of me, read the text, or what is ahead of me."

    fun wakeGreeting(lang: Lang): String = if (lang == Lang.HI) "हाँ, बताइए?" else "Yes?"

    fun appReady(lang: Lang, ready: Int, total: Int): String = if (lang == Lang.HI)
        "विज़नमेट तैयार है। $ready में से $total मॉडल लोड हुए। बड़ा बटन दबाइए या वॉल्यूम की दबाइए।"
    else
        "VisionMate is ready. $ready of $total models loaded. Tap a big button, or press a volume key to talk."

    fun cameraWarming(lang: Lang): String = if (lang == Lang.HI)
        "कैमरा शुरू हो रहा है, एक सेकंड बाद कोशिश कीजिए।"
    else
        "Camera is still starting. Please try again in a second."

    fun stoppedSpeaking(lang: Lang): String = if (lang == Lang.HI) "रोक दिया।" else "Stopped."

    fun listeningPrompt(lang: Lang): String = if (lang == Lang.HI)
        "सुन रहा हूँ। कहिए: बताओ, पढ़ो, या आगे क्या है।"
    else
        "Listening. Say describe, read text, or what is ahead."
}
