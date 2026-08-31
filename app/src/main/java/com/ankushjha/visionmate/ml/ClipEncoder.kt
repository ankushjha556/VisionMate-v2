package com.ankushjha.visionmate.ml

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * CLIP ViT-B/32 vision encoder (TFLite, weight-only int8 weights / float32 IO).
 *
 * Input : float32 [1, 224, 224, 3] NHWC, RGB, CLIP-normalized
 *         (mean 0.48145466/0.4578275/0.40821073, std 0.26862954/0.26130258/0.27577711).
 *         Normalization is done by the caller — the graph starts directly at the
 *         patch-embedding conv (verified against the Phase-1 ONNX export).
 * Output: last_hidden_state [1, 50, 768] (CLS at position 0) [+ pooler_output].
 *         The CLS row is stripped here so the decoder always gets 49 patches.
 *         Exports without CLS ([1, 49, 768]) are handled too.
 */
class ClipEncoder(private val interpreter: Interpreter) {

    companion object {
        const val SIZE = 224
        const val PATCH_COUNT = 49
        const val FEAT_DIM = 768
        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    private val inputBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(1 * SIZE * SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Number of output tensors this export produces (1 or 2). */
    private val numOutputs: Int = interpreter.outputTensorCount

    /** Token count of output tensor 0 (49 without CLS, 50 with). */
    private val tokenCount: Int

    /** The [1, tokens, 768] container mapped to output tensor 0. */
    private val hiddenOut: Array<Array<FloatArray>>

    /** Optional pooler container (only allocated when the export has 2 outputs). */
    private val poolerOut: Array<FloatArray>?

    init {
        val shape = interpreter.getOutputTensor(0).shape()
        require(shape.size == 3 && shape[2] == FEAT_DIM) {
            "Unexpected CLIP output shape ${shape.joinToString()}"
        }
        tokenCount = shape[1]
        hiddenOut = Array(1) { Array(tokenCount) { FloatArray(FEAT_DIM) } }
        poolerOut = if (numOutputs >= 2 &&
            interpreter.getOutputTensor(1).shape().size == 2
        ) Array(1) { FloatArray(FEAT_DIM) } else null
    }

    /** Returns [49][768] patch features for the upright bitmap. */
    fun patchFeatures(bitmap: Bitmap): Array<FloatArray> {
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

        val inputs = arrayOf<Any>(inputBuffer.rewind() as java.nio.Buffer)
        val outputs = HashMap<Int, Any>()
        outputs[0] = hiddenOut
        if (poolerOut != null) outputs[1] = poolerOut
        interpreter.runForMultipleInputsOutputs(inputs, outputs)

        val seq = hiddenOut[0]
        // Strip CLS token if present (Phase 1 export kept it at position 0).
        return if (tokenCount == PATCH_COUNT + 1) seq.copyOfRange(1, tokenCount) else seq
    }

    fun close() { interpreter.close() }
}
