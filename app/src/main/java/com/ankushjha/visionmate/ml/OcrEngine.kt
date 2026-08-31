package com.ankushjha.visionmate.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR via ML Kit v2 (bundled models — works fully offline).
 * Two recognizers run per frame:
 *   1. Latin (English + romanized text)
 *   2. Devanagari (Hindi)
 *
 * A tiny post-correction layer fixes the most common single-char misreads
 * observed with the old pipeline (e.g. "Poom" → "Room").
 */
class OcrEngine(context: Context) {

    private val latinRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val devanagariRecognizer: TextRecognizer =
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

    /** Reads text from a bitmap; returns cleaned, de-duplicated script text. */
    suspend fun recognize(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latin = runRecognizer(latinRecognizer, image)
        val deva = try {
            runRecognizer(devanagariRecognizer, image)
        } catch (_: Throwable) {
            // Devanagari model unavailable on this device — Latin result is still useful.
            ""
        }
        return merge(latin, deva)
    }

    private suspend fun runRecognizer(
        recognizer: TextRecognizer,
        image: InputImage
    ): String = suspendCancellableCoroutine { cont ->
        // ML Kit tasks cannot be cancelled once started; we simply ignore late
        // results if the coroutine was cancelled.
        recognizer.process(image)
            .addOnSuccessListener { text ->
                if (cont.isActive) cont.resume(text.text)
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resumeWithException(e)
            }
    }

    private fun merge(latin: String, devanagari: String): String {
        val parts = ArrayList<String>()
        latin.trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        devanagari.trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        val merged = parts.joinToString("\n")
        return correctCommonMisreads(merged).trim()
    }

    /** Small heuristic fix-table for frequent OCR confusions in assistive scenes. */
    private fun correctCommonMisreads(text: String): String {
        var out = text
        for ((bad, good) in FIXES) {
            out = out.replace("\\b$bad\\b".toRegex(RegexOption.IGNORE_CASE), good)
        }
        return out
    }

    fun close() {
        try { latinRecognizer.close() } catch (_: Throwable) {}
        try { devanagariRecognizer.close() } catch (_: Throwable) {}
    }

    companion object {
        // Word-level fixes observed in real testing during Phase 1.
        private val FIXES = listOf(
            "poom" to "Room",
            "r0om" to "Room",
            "kichen" to "Kitchen",
            "exlt" to "Exit",
            "wat" to "Wait"
        )
    }
}
