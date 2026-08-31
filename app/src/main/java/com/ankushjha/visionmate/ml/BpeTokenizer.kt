package com.ankushjha.visionmate.ml

import org.json.JSONObject

/**
 * Minimal GPT-2-style byte-level BPE tokenizer (decode-only for on-device use).
 *
 * Loads `tokenizer_vocab.json` (token → id) exactly as saved by the Phase 1
 * training pipeline (vocab size 4000 incl. PAD=0, START=1, END=2, UNK=3).
 *
 * Only decoding is implemented: the phone never needs to *encode* text —
 * the decoder consumes image features and produces ids, which we map back
 * to words here.
 *
 * v2.2 FIX: the byte-level unicode inversion table was previously built in
 * the WRONG direction (byte→symbol instead of symbol→byte), so the GPT-2
 * space marker 'Ġ' (U+0120) leaked into spoken captions as a literal glyph
 * and TTS read it as "g" — e.g. "ĠinĠinĠin" was spoken as "gin-in-gin-in".
 * gpt2BytesToUnicode() now returns the correct symbol→byte direction.
 */
class BpeTokenizer(vocabJson: String) {

    companion object {
        const val PAD_ID = 0
        const val START_ID = 1
        const val END_ID = 2
    }

    private val idToToken = HashMap<Int, String>()

    /** GPT-2 byte-level symbol char → original byte value (e.g. 'Ġ' → 32). */
    private val symbolToByte: Map<Char, Int>

    init {
        val vocab = JSONObject(vocabJson)
        for (key in vocab.keys()) {
            idToToken[vocab.getInt(key)] = key
        }
        symbolToByte = gpt2BytesToUnicode()
    }

    val vocabSize: Int get() = idToToken.size

    /** Decode token ids into a caption string, stopping at END/PAD. */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == END_ID || id == PAD_ID) break
            if (id == START_ID) continue
            idToToken[id]?.let { sb.append(it) }
        }
        return byteDecode(sb.toString()).trim()
    }

    /**
     * Inverts GPT-2's byte-level encoding: each token char is a printable
     * unicode symbol standing for one raw byte ('Ġ' = 0x20 space,
     * 'Ċ' = 0x0A newline). Unknown chars pass through unchanged.
     */
    private fun byteDecode(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            val byteVal = symbolToByte[ch]
            if (byteVal != null) out.append(byteVal.toChar()) else out.append(ch)
        }
        return out.toString()
    }

    /**
     * GPT-2 `bytes_to_unicode` inverted into the DECODE direction:
     * returns { symbol char → original byte value }.
     *
     * Printable bytes (0x21-0x7E, 0xA1-0xAC, 0xAE-0xFF) map to themselves;
     * every other byte value b is remapped to chr(256 + n) where n counts the
     * non-printables before it (e.g. byte 0x20 → chr(288) = 'Ġ').
     */
    private fun gpt2BytesToUnicode(): Map<Char, Int> {
        val symbol = IntArray(256)   // codepoint of the GPT-2 symbol for byte i
        val original = IntArray(256) // the original byte value i
        var n = 0
        for (i in 0..255) {
            original[i] = i
            if (i in 33..126 || i in 161..172 || i in 174..255) {
                symbol[i] = i
            } else {
                symbol[i] = 256 + n
                n++
            }
        }
        val map = HashMap<Char, Int>(256)
        for (i in 0..255) map[symbol[i].toChar()] = original[i]
        return map
    }
}
