package com.ankushjha.visionmate.ml

import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer

/**
 * The trained PatchCaptionDecoder (Phase 1 research artifact) —
 * frozen CLIP patch features → Transformer decoder → caption tokens.
 *
 * Inputs : patch_feats float32 [1, 49, 768]
 *          caption_ids int64  [1, 25]
 * Output : logits float32     [1, 25, 4004]
 *
 * NOTE on vocab sizes: the model head has 4004 logits but the tokenizer has
 * 4000 entries (the head was padded to a multiple of 4 during training).
 * argmax is therefore clamped to the tokenizer's effective vocab — without
 * this the model occasionally emits ids 4000..4003 which silently vanish.
 *
 * Caption generation is autoregressive greedy decoding: up to 24 sequential
 * inference calls, each feeding the tokens produced so far (right-padded
 * with PAD_ID) and reading the logits at the last real position.
 *
 * v2.2 SAFETY NET: if the runtime ever miscomputes the model (this exact
 * failure happened with hybrid-quantized BATCH_MATMUL kernels on TFLite
 * ≤ 2.16 — the decoder degenerated into a 'ĠinĠinĠin…' token loop that TTS
 * read as "ginininini"), the repetition guard below stops the loop early and
 * the caller speaks "caption unavailable" instead of nonsense.
 */
class CaptionDecoder(
    private val interpreter: Interpreter,
    vocabJson: String,
    @Suppress("UNUSED_PARAMETER") mergesText: String? = null
) {
    companion object {
        const val MAX_LEN = 25
        private const val START_ID = 1
        private const val END_ID = 2
        private const val PAD_ID = 0
    }

    val tokenizer = BpeTokenizer(vocabJson)

    /** Model head size (4004) clamped to tokenizer size (4000) for argmax. */
    private val headVocab: Int
    private val effVocab: Int

    private val idsBuffer: LongBuffer =
        ByteBuffer.allocateDirect(MAX_LEN * 8).order(ByteOrder.nativeOrder()).asLongBuffer()

    init {
        val vocab = interpreter.getOutputTensor(0).shape()[2]
        require(vocab >= tokenizer.vocabSize) {
            "Decoder vocab ($vocab) < tokenizer vocab (${tokenizer.vocabSize}) — model/tokenizer mismatch"
        }
        headVocab = vocab
        effVocab = minOf(vocab, tokenizer.vocabSize)
    }

    /** Greedy decode of patch features → caption text. Runs up to MAX_LEN-1 inferences. */
    fun generateCaption(patchFeatures: Array<FloatArray>): String {
        if (patchFeatures.size != 49 || patchFeatures[0].size != 768) {
            throw IllegalArgumentException(
                "patch features must be [49][768], got [${patchFeatures.size}][${patchFeatures[0].size}]")
        }
        val feats = Array(1) { patchFeatures }
        val ids = IntArray(MAX_LEN) { PAD_ID }
        ids[0] = START_ID
        var len = 1

        val out = Array(1) { Array(MAX_LEN) { FloatArray(headVocab) } }

        val generated = ArrayList<Int>()
        var repeats = 1
        while (len < MAX_LEN) {
            idsBuffer.rewind()
            for (i in 0 until MAX_LEN) idsBuffer.put(ids[i].toLong())

            val inputs = arrayOf<Any>(feats, idsBuffer.rewind() as java.nio.Buffer)
            interpreter.runForMultipleInputsOutputs(inputs, mapOf(0 to out))

            // Logits at position len-1 predict token `len`. Argmax is clamped
            // to the tokenizer's vocab (ids >= effVocab are head padding).
            val row = out[0][len - 1]
            var best = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (v in 0 until effVocab) {
                if (row[v] > bestScore) { bestScore = row[v]; best = v }
            }
            if (best == END_ID) break
            // Degenerate-output guard: a well-formed caption never repeats
            // the same token 5+ times consecutively; a broken runtime does.
            if (len > 1 && best == ids[len - 1]) {
                repeats++
                if (repeats >= 5) break
            } else {
                repeats = 1
            }
            generated.add(best)
            ids[len] = best
            len++
        }
        // A caption that is overwhelmingly one repeated token is not a caption.
        if (generated.size < 3 || isDegenerate(generated)) return ""
        return tokenizer.decode(generated.toIntArray())
    }

    /** True when a single token makes up > 60% of the output. */
    private fun isDegenerate(tokens: List<Int>): Boolean {
        if (tokens.isEmpty()) return true
        val counts = HashMap<Int, Int>()
        for (t in tokens) counts[t] = (counts[t] ?: 0) + 1
        val maxCount = counts.values.maxOrNull() ?: 0
        return maxCount * 10 > tokens.size * 6
    }

    fun close() { interpreter.close() }
}
