package com.ankushjha.visionmate.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Owns every on-device model:
 *  - yolo11n_w8a32.tflite        (object detection, weights int8 / activations float32)
 *  - midas_small_w8.tflite       (monocular depth, weight-only int8, float32 IO)
 *  - clip_vision_w8.tflite       (CLIP ViT-B/32 patch encoder, weight-only int8, float32 IO)
 *  - caption_decoder_w8.tflite   (trained PatchCaptionDecoder, weight-only int8, float32 IO)
 *  - tokenizer_vocab.json        (BPE vocab for the decoder)
 *
 * ALL FILES ARE BUNDLED IN app/src/main/assets/models — the app works
 * out of the box with no manual steps.
 *
 * v2.2 IMPORTANT — quantization recipe: the w8 models are quantized with
 * the SAFE weight-only recipe (int8 weights + explicit DEQUANTIZE ops,
 * compute in float32). The first shipment used the `dynamic_wi8_afp32`
 * recipe whose hybrid BATCH_MATMUL ops are MISCOMPUTED by TFLite runtimes
 * ≤ 2.16 on ARM (the caption decoder degenerated into a 'Ġin' token loop
 * that TTS read as "ginininini…"). Never swap back the old files.
 *
 * IMPORTANT: never swap in the *float16* exports from Drive (clip_vision_float16
 * / captioning_decoder_float16). fp16 BATCH_MATMUL activations crash the
 * TFLite CPU kernels ("lhs type not supported"). Only float32-IO variants run.
 *
 * Loading is lazy, sequential, on one background thread; every model is
 * independently optional (missing file → that feature degrades, others work).
 */
object ModelManager {

    private const val TAG = "VisionMate/Models"

    const val DIR_MODELS = "models"

    // Tried in order — first that loads wins.
    // NOTE: these exact filenames now hold the v2.2 SAFE-recipe weights
    // (int8 + explicit DEQUANTIZE, float compute — identical results on
    // every TFLite runtime). Do not mix with files from older zips.
    private val YOLO_FILES = listOf("yolo11n_w8a32.tflite")
    private val MIDAS_FILES = listOf("midas_small_w8.tflite", "midas_small_int8.tflite")
    private val CLIP_FILES = listOf("clip_vision_w8.tflite", "clip_vision_int8.tflite")
    private val DECODER_FILES = listOf("caption_decoder_w8.tflite", "captioning_decoder_float32.tflite")
    private const val VOCAB_FILE = "tokenizer_vocab.json"

    @Volatile var yolo: YoloDetector? = null
        private set
    @Volatile var midas: MiDaSDepth? = null
        private set
    @Volatile var clip: ClipEncoder? = null
        private set
    @Volatile var decoder: CaptionDecoder? = null
        private set
    @Volatile var ocr: OcrEngine? = null
        private set

    @Volatile var loaded = false
        private set

    @Volatile var loadError: String? = null
        private set

    /** Callback fired once on the main thread after the load attempt finishes. */
    fun init(context: Context, onReady: (() -> Unit)? = null) {
        if (loaded) { onReady?.invoke(); return }
        val appCtx = context.applicationContext
        Thread {
            val t0 = System.currentTimeMillis()
            loadAll(appCtx)
            loaded = true
            Log.i(TAG, "Model init finished in ${System.currentTimeMillis() - t0} ms " +
                "(${readyCount()}/${totalModels()} ready)")
            android.os.Handler(android.os.Looper.getMainLooper()).post { onReady?.invoke() }
        }.apply { name = "vm-model-load"; start() }
    }

    private fun loadAll(appCtx: Context) {
        yolo = loadFirst(appCtx, YOLO_FILES) { YoloDetector(it) }
        if (yolo == null) Log.w(TAG, "YOLO unavailable — obstacle detection will be limited")
        midas = loadFirst(appCtx, MIDAS_FILES) { MiDaSDepth(it) }
        if (midas == null) Log.w(TAG, "MiDaS unavailable — distance estimates will be limited")
        clip = loadFirst(appCtx, CLIP_FILES) { ClipEncoder(it) }
        if (clip == null) Log.w(TAG, "CLIP unavailable — scene description will be unavailable")
        decoder = loadDecoder(appCtx)
        if (decoder == null) Log.w(TAG, "Caption decoder unavailable — scene description will be unavailable")
        try {
            ocr = OcrEngine(appCtx)
        } catch (t: Throwable) {
            Log.w(TAG, "OCR unavailable: ${t.message}")
        }
    }

    private fun loadDecoder(appCtx: Context): CaptionDecoder? {
        for (name in DECODER_FILES) {
            try {
                val vocabJson = appCtx.assets.open("$DIR_MODELS/$VOCAB_FILE")
                    .bufferedReader().use { it.readText() }
                return CaptionDecoder(
                    interpreter = loadModelFile(appCtx, name),
                    vocabJson = vocabJson
                )
            } catch (t: Throwable) {
                Log.w(TAG, "decoder candidate $name failed: ${t.message}")
            }
        }
        return null
    }

    /** Tries each candidate file in order; returns the first successfully wrapped model. */
    private fun <T> loadFirst(
        appCtx: Context,
        candidates: List<String>,
        wrap: (Interpreter) -> T
    ): T? {
        for (name in candidates) {
            try {
                return wrap(loadModelFile(appCtx, name))
            } catch (t: Throwable) {
                Log.w(TAG, "model candidate $name failed: ${t.message}")
            }
        }
        return null
    }

    /**
     * Loads a tflite from assets as a memory-mapped buffer.
     * Strategy 1: direct mmap via AssetFileDescriptor (fast, zero copy) — works
     * because .tflite assets are stored uncompressed (see noCompress in gradle).
     * Strategy 2 (fallback): stream-copy the asset into cacheDir and mmap that —
     * covers any device/AGP quirk with compressed assets.
     */
    private fun loadModelFile(context: Context, assetName: String): Interpreter {
        val mapped = try {
            mmapAsset(context, "$DIR_MODELS/$assetName")
        } catch (t: Throwable) {
            Log.w(TAG, "mmap of $assetName failed (${t.message}); falling back to cache copy")
            copyAssetToCache(context, assetName)?.let { f ->
                FileInputStream(f).use { fis ->
                    fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
                }
            } ?: throw IllegalStateException("asset $assetName not readable")
        }
        return try {
            Interpreter(mapped, Interpreter.Options().apply { numThreads = 2 })
        } catch (t: Throwable) {
            throw IllegalStateException("interpreter init failed for $assetName: ${t.message}", t)
        }
    }

    private fun mmapAsset(context: Context, assetPath: String): MappedByteBuffer {
        context.assets.openFd(assetPath).use { fd ->
            FileInputStream(fd.fileDescriptor).use { fis ->
                return fis.channel.map(
                    FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
                )
            }
        }
    }

    private fun copyAssetToCache(context: Context, assetName: String): File? {
        return try {
            val out = File(context.cacheDir, "models_$assetName")
            if (!out.exists() || out.length() == 0L) {
                context.assets.open("$DIR_MODELS/$assetName").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            out
        } catch (t: Throwable) {
            Log.e(TAG, "cache copy of $assetName failed: ${t.message}")
            null
        }
    }

    /** True only when the full captioning chain (CLIP → decoder → tokenizer) is present. */
    val captioningAvailable: Boolean get() = clip != null && decoder != null

    /** True when detection + depth are both ready (obstacle loop needs both). */
    val obstacleAvailable: Boolean get() = yolo != null && midas != null

    fun readyCount(): Int {
        var n = 0
        if (yolo != null) n++
        if (midas != null) n++
        if (clip != null) n++
        if (decoder != null) n++
        if (ocr != null) n++
        return n
    }

    fun totalModels(): Int = 5

    fun close() {
        try { yolo?.close() } catch (_: Throwable) {}
        try { midas?.close() } catch (_: Throwable) {}
        try { clip?.close() } catch (_: Throwable) {}
        try { decoder?.close() } catch (_: Throwable) {}
        try { ocr?.close() } catch (_: Throwable) {}
        yolo = null; midas = null; clip = null; decoder = null; ocr = null
        loaded = false
    }
}
