package com.ankushjha.visionmate.camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicReference

/**
 * CameraX ImageAnalysis callback. Converts every incoming frame to an upright
 * Bitmap and stores it as the "latest frame" — the single source of truth that
 * the obstacle loop, caption flow, and OCR flow all sample.
 *
 * IMPORTANT: every ImageProxy is closed here, exactly once. Forgetting to close
 * freezes frame delivery (a classic CameraX bug).
 */
class FrameAnalyzer : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "VisionMate/Camera"
        /** Hard cap on stored frame dimension — keeps inference cheap on 12MP+ sensors. */
        private const val MAX_DIM = 1280
    }

    private val latest = AtomicReference<Bitmap?>(null)

    @Volatile var frameCount = 0
        private set

    /** Upright (display-rotation-corrected) latest frame; null until first frame arrives. */
    fun latestFrame(): Bitmap? = latest.get()

    fun dims(): Pair<Int, Int>? = latest.get()?.let { it.width to it.height }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        try {
            val rotation = image.imageInfo.rotationDegrees
            val bmp = image.toBitmap()   // CameraX 1.3+: handles YUV_420_888 → ARGB
            val upright = if (rotation != 0) rotate(bmp, rotation.toFloat()) else bmp
            val capped = capDimension(upright)
            // NOTE: we deliberately never recycle the displaced bitmap here —
            // an inference call may still be reading it. minSdk 26 puts pixel
            // data on the Java heap, so plain GC reclaims it safely.
            latest.set(capped)
            frameCount++
            if (frameCount % 30 == 1) Log.d(TAG, "Frame received: ${capped.width}x${capped.height}")
        } catch (t: Throwable) {
            Log.w(TAG, "Frame conversion failed: ${t.message}")
        } finally {
            image.close()
        }
    }

    private fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun capDimension(src: Bitmap): Bitmap {
        val maxDim = maxOf(src.width, src.height)
        if (maxDim <= MAX_DIM) return src
        val scale = MAX_DIM.toFloat() / maxDim
        return Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1), true
        )
    }

    fun release() {
        latest.set(null)
    }
}
