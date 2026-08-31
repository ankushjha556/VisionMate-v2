package com.ankushjha.visionmate.ml

import android.graphics.Bitmap
import com.ankushjha.visionmate.util.Detection
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv11n (w8a32 TFLite) wrapper.
 *
 * Input : float32 [1, 3, 320, 320] NCHW (Drive Phase-1 export layout) —
 *         RGB, normalized 0..1 (ultralytics export semantics).
 *         NHWC [1, H, W, 3] models are also supported (auto-detected).
 * Output: float32 [1, 84, N] or [1, N, 84] — 4 box coords (xywh, normalized)
 *         + 80 COCO scores. N = 2100 for 320px input. Layout auto-detected.
 */
class YoloDetector(private val interpreter: Interpreter) {

    companion object {
        const val NUM_CLASSES = 80
        const val CONF_THRESHOLD = 0.45f
        const val IOU_THRESHOLD = 0.5f

        val HAZARD_CLASSES = setOf(
            "person", "bicycle", "car", "motorcycle", "bus", "truck",
            "chair", "couch", "bed", "refrigerator", "table", "bench",
            "dog", "cat", "tv", "laptop", "backpack", "umbrella",
            "handbag", "suitcase", "bottle", "cup", "keyboard", "cell phone"
        )

        val COCO_NAMES = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
        )
    }

    /** Input size read from the model itself (320 for our export; 640 also works). */
    private val inputSize: Int

    /** true → [1,3,H,W] channel-planar; false → [1,H,W,3] interleaved. */
    private val inputIsNchw: Boolean

    init {
        val sh = interpreter.getInputTensor(0).shape()
        require(sh.size == 4) { "Unexpected YOLO input rank ${sh.joinToString()}" }
        inputIsNchw = (sh[1] == 3)
        if (inputIsNchw) {
            require(sh[2] == sh[3]) { "Non-square YOLO input ${sh.joinToString()}" }
            inputSize = sh[2]
        } else {
            require(sh[3] == 3 && sh[1] == sh[2]) { "Unexpected YOLO input ${sh.joinToString()}" }
            inputSize = sh[1]
        }
    }

    private val inputBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

    // Output container: [1][A][B] where {A,B} = {84,N} or {N,84}.
    private var outRows: Array<Array<FloatArray>> = emptyArray()
    private var outputIsTransposed = true
    private var outN = 0

    init {
        val shape = interpreter.getOutputTensor(0).shape() // e.g. [1, 84, 2100]
        val d1 = shape[1]; val d2 = shape[2]
        outputIsTransposed = d1 == 4 + NUM_CLASSES
        outN = if (outputIsTransposed) d2 else d1
        outRows = Array(1) { Array(d1) { FloatArray(d2) } }
    }

    /**
     * Runs detection on an upright bitmap; returns boxes in the bitmap's
     * coordinate space (letterbox padding removed).
     */
    fun detect(bitmap: Bitmap, confThreshold: Float = CONF_THRESHOLD): List<Detection> {
        val bw = bitmap.width; val bh = bitmap.height

        // Letterbox: scale bitmap into inputSize square, remember padding.
        val scale = min(inputSize.toFloat() / bw, inputSize.toFloat() / bh)
        val padW = (inputSize - bw * scale) / 2f
        val padH = (inputSize - bh * scale) / 2f

        val scaled = Bitmap.createScaledBitmap(
            bitmap, max(1, (bw * scale).toInt()), max(1, (bh * scale).toInt()), true)
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)

        fillInput(pixels, scaled.width, scaled.height, padW, padH)
        if (scaled !== bitmap) scaled.recycle()

        val inputArray = arrayOf<Any>(inputBuffer.rewind() as java.nio.Buffer)
        interpreter.runForMultipleInputsOutputs(inputArray, mapOf(0 to outRows))

        return parseOutput(outRows[0], outputIsTransposed, confThreshold, scale, padW, padH, bw, bh)
    }

    /**
     * Fills the float input buffer with RGB 0..1 values.
     * Out-of-image (letterbox padding) pixels are black.
     * NCHW: R plane, then G plane, then B plane.
     * NHWC: R,G,B per pixel.
     */
    private fun fillInput(pixels: IntArray, srcW: Int, srcH: Int, padW: Float, padH: Float) {
        inputBuffer.rewind()
        if (inputIsNchw) {
            // Three passes: R plane, G plane, B plane.
            for (channel in 0 until 3) {
                val shift = 16 - 8 * channel
                for (y in 0 until inputSize) {
                    val sy = y - padH.toInt()
                    if (sy < 0 || sy >= srcH) {
                        for (x in 0 until inputSize) inputBuffer.put(0f)
                        continue
                    }
                    val rowStart = sy * srcW
                    for (x in 0 until inputSize) {
                        val sx = x - padW.toInt()
                        val v = if (sx in 0 until srcW)
                            ((pixels[rowStart + sx] shr shift) and 0xFF) / 255f
                        else 0f
                        inputBuffer.put(v)
                    }
                }
            }
        } else {
            // Interleaved: one pass, R,G,B per pixel.
            for (y in 0 until inputSize) {
                val sy = y - padH.toInt()
                for (x in 0 until inputSize) {
                    val sx = x - padW.toInt()
                    if (sy in 0 until srcH && sx in 0 until srcW) {
                        val px = pixels[sy * srcW + sx]
                        inputBuffer.put(((px shr 16) and 0xFF) / 255f)
                        inputBuffer.put(((px shr 8) and 0xFF) / 255f)
                        inputBuffer.put((px and 0xFF) / 255f)
                    } else {
                        inputBuffer.put(0f); inputBuffer.put(0f); inputBuffer.put(0f)
                    }
                }
            }
        }
    }

    private fun parseOutput(
        rows: Array<FloatArray>,
        transposed: Boolean,
        conf: Float,
        scale: Float, padW: Float, padH: Float,
        bw: Int, bh: Int
    ): List<Detection> {
        val candidates = ArrayList<Detection>()

        for (i in 0 until outN) {
            var bestScore = 0f; var bestCls = -1
            var cx = 0f; var cy = 0f; var w = 0f; var h = 0f
            if (transposed) {
                // Layout: [84, N] — row r is channel r.
                for (c in 0 until NUM_CLASSES) {
                    val s = rows[4 + c][i]
                    if (s > bestScore) { bestScore = s; bestCls = c }
                }
                cx = rows[0][i]; cy = rows[1][i]; w = rows[2][i]; h = rows[3][i]
            } else {
                // Layout: [N, 84] — row i is anchor i.
                val row = rows[i]
                for (c in 0 until NUM_CLASSES) {
                    val s = row[4 + c]
                    if (s > bestScore) { bestScore = s; bestCls = c }
                }
                cx = row[0]; cy = row[1]; w = row[2]; h = row[3]
            }
            if (bestScore < conf || bestCls < 0) continue

            // Box coords are xywh normalized (0..1) to the letterboxed input
            // square (verified empirically against the Phase-1 export):
            // scale to inputSize pixels FIRST, then remove letterbox padding,
            // then map back to original bitmap pixels.
            val x1 = ((cx - w / 2f) * inputSize - padW) / scale
            val y1 = ((cy - h / 2f) * inputSize - padH) / scale
            val x2 = ((cx + w / 2f) * inputSize - padW) / scale
            val y2 = ((cy + h / 2f) * inputSize - padH) / scale

            val clampedX1 = x1.coerceIn(0f, bw.toFloat()); val clampedY1 = y1.coerceIn(0f, bh.toFloat())
            val clampedX2 = x2.coerceIn(0f, bw.toFloat()); val clampedY2 = y2.coerceIn(0f, bh.toFloat())
            if (clampedX2 - clampedX1 < 4f || clampedY2 - clampedY1 < 4f) continue

            candidates.add(Detection(COCO_NAMES[bestCls], bestScore, clampedX1, clampedY1, clampedX2, clampedY2))
        }
        return nms(candidates)
    }

    private fun nms(boxes: List<Detection>): List<Detection> {
        val sorted = boxes.sortedByDescending { it.score }
        val kept = ArrayList<Detection>()
        for (box in sorted) {
            var keep = true
            for (k in kept) {
                if (k.label != box.label) continue
                if (iou(k, box) > IOU_THRESHOLD) { keep = false; break }
            }
            if (keep) kept.add(box)
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ix1 = max(a.x1, b.x1); val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2); val iy2 = min(a.y2, b.y2)
        val iw = max(0f, ix2 - ix1); val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() { interpreter.close() }
}
