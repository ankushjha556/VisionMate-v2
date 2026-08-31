package com.ankushjha.visionmate.ml

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * MiDaS-small depth wrapper (TFLite, dynamic-range int8 weights / float32 IO).
 *
 * Input : float32 [1, 256, 256, 3] NHWC, RGB, ImageNet-normalized
 *         (mean 0.485/0.456/0.406, std 0.229/0.224/0.225) — matching the
 *         PyTorch model semantics. Verified vs PyTorch: Pearson 0.9999.
 * Output: float32 [1, 256, 256] inverse relative depth (larger = closer).
 *
 * MiDaS gives *relative* depth only. We expose it normalized 0..1 where 1 =
 * nearest pixel in the frame — exactly what obstacle tiering and path
 * guidance need.
 */
class MiDaSDepth(private val interpreter: Interpreter) {

    companion object {
        const val SIZE = 256
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private val inputBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(1 * SIZE * SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

    // Nested to match output tensor [1, 256, 256].
    private val output = Array(1) { Array(SIZE) { FloatArray(SIZE) } }

    /** Returns inverse-depth map of size SIZE*SIZE, normalized so max = 1 (nearest surface). */
    fun depthNormalized(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== bitmap) scaled.recycle()

        inputBuffer.rewind()
        // NHWC interleaved fill: R,G,B per pixel, row-major.
        for (i in px.indices) {
            inputBuffer.put((((px[i] shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0])
            inputBuffer.put((((px[i] shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1])
            inputBuffer.put(((px[i] and 0xFF) / 255f - MEAN[2]) / STD[2])
        }

        val inputArray = arrayOf(inputBuffer.rewind() as java.nio.Buffer)
        interpreter.run(inputArray, output)

        // Flatten + normalize to 0..1 (nearest surface = 1).
        var maxV = 1e-6f
        val norm = FloatArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            val row = output[0][y]
            for (x in 0 until SIZE) {
                val v = row[x]
                if (v > maxV) maxV = v
            }
        }
        for (y in 0 until SIZE) {
            val row = output[0][y]
            val base = y * SIZE
            for (x in 0 until SIZE) {
                norm[base + x] = row[x] / maxV
            }
        }
        return norm
    }

    /**
     * Mean inverse-depth in a rectangular region of the depth map (pixel coords).
     * Region is clamped to bounds. Returns value in 0..1 (higher = nearer).
     */
    fun regionDepthMean(depth: FloatArray, x1: Int, y1: Int, x2: Int, y2: Int): Float {
        val l = x1.coerceIn(0, SIZE - 1); val r = x2.coerceIn(l + 1, SIZE)
        val t = y1.coerceIn(0, SIZE - 1); val b = y2.coerceIn(t + 1, SIZE)
        var sum = 0f; var n = 0
        for (y in t until b) {
            val row = y * SIZE
            for (x in l until r) { sum += depth[row + x]; n++ }
        }
        return if (n == 0) 0f else sum / n
    }

    fun close() { interpreter.close() }
}
