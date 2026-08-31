package com.ankushjha.visionmate.obstacle

import android.graphics.Bitmap
import android.util.Log
import com.ankushjha.visionmate.camera.FrameAnalyzer
import com.ankushjha.visionmate.ml.MiDaSDepth
import com.ankushjha.visionmate.ml.ModelManager
import com.ankushjha.visionmate.util.Closeness
import com.ankushjha.visionmate.util.Detection
import com.ankushjha.visionmate.util.ObstacleAssessment
import com.ankushjha.visionmate.util.PassSide
import com.ankushjha.visionmate.util.VoiceResponses
import com.ankushjha.visionmate.voice.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.ConcurrentHashMap

/**
 * The safety-critical loop ("continuous smart" mode):
 *
 *  - Every CHECK_INTERVAL_MS (1.5s) samples the latest camera frame and runs
 *    YOLO + MiDaS on it (skips if the user is actively commanding the app).
 *  - STOP-tier hazards interrupt TTS immediately + vibrate; CLOSE-tier speak
 *    with QUEUE_ADD; every other tier is silent (display-only boxes).
 *  - Per-class cooldowns prevent the triple-repeated-warning bug from Phase 1:
 *    the same hazard class can only be *spoken* once per cooldown window,
 *    STOP-tier has a shorter, separate window.
 *  - When the path ahead is blocked and path guidance is on, checks left/right
 *    thirds for free space and suggests a side.
 *
 * Distance honesty: MiDaS gives *relative* depth. Closeness tiers combine
 * (a) inverse-depth ratio at the object's bottom-center vs the frame's nearest
 * surface and (b) the object's box height fraction (perspective = nearer).
 * Reported "steps" are calibrated heuristics, not measured meters — this is
 * documented in PHASE2_ANDROID_APP.md.
 */
class ObstacleWarningEngine(
    private val frames: FrameAnalyzer,
    private val tts: TtsSpeaker,
    private val vibrate: () -> Unit
) {
    companion object {
        private const val TAG = "VisionMate/Obstacle"
        const val CHECK_INTERVAL_MS = 1500L
        private const val STOP_COOLDOWN_MS = 3000L
        private const val CLOSE_COOLDOWN_MS = 8000L
        private const val CLEAR_ANNOUNCE_COOLDOWN_MS = 10000L

        // Closeness thresholds (inverse-depth ratio where 1 = nearest surface in frame).
        private const val RATIO_STOP = 0.72f
        private const val RATIO_CLOSE = 0.48f
        // Box-height fallback thresholds (fraction of frame height).
        private const val BOXFRACT_STOP = 0.55f
        private const val BOXFRACT_CLOSE = 0.35f
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var loopJob: Job? = null

    @Volatile var enabled = false
    @Volatile var pathGuidanceOn = true
    @Volatile var responseLang = VoiceResponses.Lang.EN
    @Volatile var userCommandInProgress = false

    private val lastSpoken = ConcurrentHashMap<String, Long>()
    @Volatile private var lastBlocked = false
    @Volatile private var lastClearAnnounce = 0L

    /** Latest assessment for the overlay/UI. */
    @Volatile var latestAssessment: ObstacleAssessment? = null
        private set

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            Log.i(TAG, "Obstacle loop started")
            while (enabled) {
                try {
                    if (!userCommandInProgress) tick()
                } catch (t: Throwable) {
                    Log.w(TAG, "Obstacle tick failed: ${t.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
            Log.i(TAG, "Obstacle loop stopped")
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private suspend fun tick() {
        if (!ModelManager.obstacleAvailable) return
        val frame: Bitmap = frames.latestFrame() ?: return
        val yolo = ModelManager.yolo ?: return
        val midas = ModelManager.midas ?: return

        val detections = runInterruptible(Dispatchers.IO) { yolo.detect(frame) }
        if (detections.isEmpty()) {
            handleClear()
            return
        }

        val depth = runInterruptible(Dispatchers.IO) { midas.depthNormalized(frame) }

        // Score each detection for closeness.
        var nearest: Scored? = null
        val scored = ArrayList<Scored>(detections.size)
        for (d in detections) {
            val region = mapBoxToDepthRegion(d, frame)
            val ratio = midas.regionDepthMean(depth, region[0], region[1], region[2], region[3])
            val boxFract = d.height / frame.height
            val closeness = closenessFor(ratio, boxFract)
            val s = Scored(d, ratio, closeness)
            val cur = nearest
            if (cur == null || closeness.ordinal > cur.closeness.ordinal ||
                (closeness == cur.closeness && ratio > cur.ratio)
            ) {
                nearest = s
            }
            scored.add(s)
        }

        val n = nearest ?: return
        if (n.closeness == Closeness.CLEAR || n.closeness == Closeness.MID) {
            handleClear()
            return
        }

        lastBlocked = true
        val now = System.currentTimeMillis()
        val key = n.detection.label

        if (n.closeness == Closeness.STOP) {
            if (now - (lastSpoken["stop_$key"] ?: 0L) > STOP_COOLDOWN_MS) {
                lastSpoken["stop_$key"] = now
                vibrate()
                tts.speak(VoiceResponses.stop(responseLang, n.detection.label), responseLang,
                    TtsSpeaker.Priority.URGENT)
                speakPathGuidance(scored, frame.width)
            }
        } else { // CLOSE
            if (now - (lastSpoken["close_$key"] ?: 0L) > CLOSE_COOLDOWN_MS) {
                lastSpoken["close_$key"] = now
                val steps = estimateSteps(n)
                tts.speak(VoiceResponses.close(responseLang, n.detection.label, steps), responseLang)
            }
        }

        latestAssessment = ObstacleAssessment(
            nearestLabel = n.detection.label,
            closeness = n.closeness,
            hazardZoneX = n.detection.centerX / frame.width,
            passSide = PassSide.NONE,
            detections = detections
        )
    }

    private fun handleClear() {
        val now = System.currentTimeMillis()
        // Only announce "clear" after having been blocked, so the app isn't chatty.
        if (lastBlocked && now - lastClearAnnounce > CLEAR_ANNOUNCE_COOLDOWN_MS) {
            lastBlocked = false
            lastClearAnnounce = now
            tts.speak(VoiceResponses.pathClear(responseLang), responseLang)
        }
        latestAssessment = null
    }

    private fun speakPathGuidance(all: List<Scored>, frameWidth: Int) {
        if (!pathGuidanceOn) return
        val hazards = all.map { it.detection to it.closeness }
        val side = PathGuidance.suggestPassSide(hazards, frameWidth)
        if (side != PassSide.NONE) {
            tts.speak(VoiceResponses.pathSide(responseLang, side), responseLang)
        }
    }

    private fun closenessFor(ratio: Float, boxFraction: Float): Closeness = when {
        ratio >= RATIO_STOP || boxFraction >= BOXFRACT_STOP -> Closeness.STOP
        ratio >= RATIO_CLOSE || boxFraction >= BOXFRACT_CLOSE -> Closeness.CLOSE
        else -> Closeness.MID
    }

    /** Rough step estimate from closeness signals — deliberately conservative. */
    private fun estimateSteps(s: Scored): Int = when (s.closeness) {
        Closeness.STOP -> 1
        Closeness.CLOSE -> if (s.ratio > 0.6f) 2 else 3
        else -> 4
    }

    /** Maps a bitmap-space box into depth-map (256×256) pixel coords. */
    private fun mapBoxToDepthRegion(d: Detection, frame: Bitmap): IntArray {
        val sx = MiDaSDepth.SIZE.toFloat() / frame.width
        val sy = MiDaSDepth.SIZE.toFloat() / frame.height
        // Sample the lower-middle of the box — where an obstacle touches the floor.
        val x1 = ((d.x1 + d.width * 0.25f) * sx).toInt()
        val x2 = ((d.x2 - d.width * 0.25f) * sx).toInt()
        val y1 = ((d.y1 + d.height * 0.45f) * sy).toInt()
        val y2 = ((d.y1 + d.height * 0.85f) * sy).toInt()
        return intArrayOf(x1, y1, x2, y2)
    }

    private data class Scored(val detection: Detection, val ratio: Float, val closeness: Closeness)
}
