package com.ankushjha.visionmate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ankushjha.visionmate.camera.DetectionOverlayView
import com.ankushjha.visionmate.camera.FrameAnalyzer
import com.ankushjha.visionmate.ml.ModelManager
import com.ankushjha.visionmate.obstacle.ObstacleWarningEngine
import com.ankushjha.visionmate.util.Closeness
import com.ankushjha.visionmate.util.Detection
import com.ankushjha.visionmate.util.Prefs
import com.ankushjha.visionmate.util.VoiceResponses
import com.ankushjha.visionmate.voice.CommandRouter
import com.ankushjha.visionmate.voice.SpeechListener
import com.ankushjha.visionmate.voice.TtsSpeaker
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * The single screen a blind user lives in.
 *
 * Design rules (from real assistive-app practice):
 *  - Every control is BIG (≥ 72dp), high-contrast, and labelled for TalkBack.
 *  - Every action gives immediate spoken + haptic feedback.
 *  - One-time spoken greeting on launch — never a loop, never repeats.
 *  - STOP VOICE always works, even while the app is thinking or speaking.
 *  - Volume keys act as a hands-free trigger (tactile, no aiming needed).
 */
class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "VisionMate/Main" }

    private lateinit var viewFinder: PreviewView
    private lateinit var overlay: DetectionOverlayView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var modelStatusChip: TextView
    private lateinit var btnListen: MaterialButton
    private lateinit var btnDescribe: MaterialButton
    private lateinit var btnOcr: MaterialButton
    private lateinit var btnAhead: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnObstacle: MaterialButton

    private lateinit var prefs: Prefs
    private val frameAnalyzer = FrameAnalyzer()
    private lateinit var tts: TtsSpeaker
    private lateinit var stt: SpeechListener
    private lateinit var obstacleEngine: ObstacleWarningEngine
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraStarted = false
    @Volatile private var busy = false
    @Volatile private var greeted = false
    @Volatile private var lastVolumeKeyAt = 0L

    // ---------------- permissions ----------------
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val cam = result[Manifest.permission.CAMERA] == true
            val mic = result[Manifest.permission.RECORD_AUDIO] == true
            if (cam) startCamera()
            if (mic) applyObstacleSetting()
        }

    // ---------------- lifecycle ----------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        prefs = Prefs.get(this)
        bindViews()
        wireButtons()

        tts = TtsSpeaker(this)
        stt = SpeechListener(this)
        obstacleEngine = ObstacleWarningEngine(frameAnalyzer, tts) { vibrate() }

        tts.onSpeechStart = { setStatus(getString(R.string.status_speaking)) }

        // Edge-to-edge: keep the button panel clear of the gesture-nav bar.
        val root = findViewById<android.view.View>(R.id.bottomPanel)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom + 24)
            insets
        }

        ModelManager.init(this) {
            updateModelChip()
            maybeGreet()
            applyObstacleSetting()
        }

        requestNeededPermissions()
        startOverlayRefresh()
    }

    private fun bindViews() {
        viewFinder = findViewById(R.id.viewFinder)
        overlay = findViewById(R.id.detectionOverlay)
        statusText = findViewById(R.id.statusText)
        hintText = findViewById(R.id.hintText)
        modelStatusChip = findViewById(R.id.modelStatusChip)
        btnListen = findViewById(R.id.btnListen)
        btnDescribe = findViewById(R.id.btnDescribe)
        btnOcr = findViewById(R.id.btnOcr)
        btnAhead = findViewById(R.id.btnAhead)
        btnStop = findViewById(R.id.btnStop)
        btnObstacle = findViewById(R.id.btnObstacle)
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            vibrate()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        modelStatusChip.setOnClickListener {
            vibrate()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun wireButtons() {
        btnListen.setOnClickListener { onMicTrigger() }
        btnDescribe.setOnClickListener { vibrate(); runPipeline(CommandRouter.Intent.DESCRIBE, currentLang()) }
        btnOcr.setOnClickListener { vibrate(); runPipeline(CommandRouter.Intent.READ_TEXT, currentLang()) }
        btnAhead.setOnClickListener { vibrate(); runPipeline(CommandRouter.Intent.CHECK_AHEAD, currentLang()) }
        btnStop.setOnClickListener { stopAllVoice() }
        btnObstacle.setOnClickListener {
            vibrate()
            prefs.obstacleWarningsOn = !prefs.obstacleWarningsOn
            applyObstacleSetting()
            val on = prefs.obstacleWarningsOn && ModelManager.obstacleAvailable
            tts.speak(
                if (on) getString(R.string.obstacle_on) else getString(R.string.obstacle_off),
                currentLang())
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHint()
        tts.rateStep = prefs.ttsRateStep
        obstacleEngine.responseLang = currentLang()
        obstacleEngine.pathGuidanceOn = prefs.pathGuidanceOn
        if (hasPermission(Manifest.permission.CAMERA)) startCamera()
        applyObstacleSetting()
    }

    override fun onPause() {
        super.onPause()
        stt.stop()
        tts.stop()
        obstacleEngine.enabled = false
        obstacleEngine.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        frameAnalyzer.release()
        tts.shutdown()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (prefs.volumeKeyTriggerOn &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeKeyAt < 800) return true  // debounce accidental double-press
            lastVolumeKeyAt = now
            onMicTrigger()
            return true // consume — don't also change ringer volume
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- voice triggers ----------------
    /**
     * Mic button / volume-key behavior:
     *  - If speaking → stop (quiet first).
     *  - If listening → cancel (second press cancels).
     *  - Otherwise → start listening for a command.
     */
    private fun onMicTrigger() {
        vibrate()
        if (tts.isSpeaking) { tts.stop(); setStatus(getString(R.string.status_idle)); return }
        if (stt.isListening) { stt.stop(); setStatus(getString(R.string.status_idle)); return }
        startListening()
    }

    /** STOP VOICE: silences speech and cancels listening. Always responsive. */
    private fun stopAllVoice() {
        vibrate()
        tts.stop()
        stt.stop()
        setStatus(getString(R.string.status_idle))
        tts.speak(VoiceResponses.stoppedSpeaking(currentLang()), currentLang())
    }

    private fun startListening() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_SHORT).show()
            tts.speak(getString(R.string.mic_permission_needed), currentLang())
            return
        }
        setStatus(getString(R.string.status_listening))
        val langHint = if (prefs.responseLanguage == Prefs.LANG_HI)
            VoiceResponses.Lang.HI else VoiceResponses.Lang.EN
        stt.startListening(langHint, object : SpeechListener.Callback {
            override fun onPartial(text: String) {
                setStatus(text)
            }
            override fun onResult(text: String) {
                Log.i(TAG, "Heard: $text")
                val command = CommandRouter.route(text, prefs.responseLanguage)
                if (command.intent == CommandRouter.Intent.UNKNOWN) {
                    tts.speak(VoiceResponses.unknownCommand(command.lang), command.lang)
                    setStatus(getString(R.string.status_idle))
                } else {
                    runPipeline(command.intent, command.lang)
                }
            }
            override fun onError(userMessage: String) {
                setStatus(getString(R.string.status_idle))
                tts.speak(userMessage, currentLang())
            }
        })
    }

    // ---------------- pipelines ----------------
    private fun runPipeline(intent: CommandRouter.Intent, lang: VoiceResponses.Lang) {
        if (busy) return
        val frame = frameAnalyzer.latestFrame()
        if (frame == null) {
            tts.speak(VoiceResponses.cameraWarming(lang), lang)
            return
        }
        busy = true
        obstacleEngine.userCommandInProgress = true
        setStatus(getString(R.string.status_thinking))
        lifecycleScope.launch {
            try {
                when (intent) {
                    CommandRouter.Intent.DESCRIBE -> describeScene(frame, lang)
                    CommandRouter.Intent.READ_TEXT -> readText(frame, lang)
                    CommandRouter.Intent.CHECK_AHEAD -> checkAhead(frame, lang)
                    CommandRouter.Intent.HELP -> tts.speak(VoiceResponses.help(lang), lang)
                    CommandRouter.Intent.UNKNOWN -> tts.speak(VoiceResponses.unknownCommand(lang), lang)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Pipeline failed", t)
                tts.speak(VoiceResponses.unknownCommand(lang), lang)
            } finally {
                busy = false
                obstacleEngine.userCommandInProgress = false
                setStatus(getString(R.string.status_idle))
            }
        }
    }

    private suspend fun describeScene(frame: android.graphics.Bitmap, lang: VoiceResponses.Lang) {
        val caption = withContext(Dispatchers.IO) {
            val clip = ModelManager.clip
            val decoder = ModelManager.decoder
            if (clip == null || decoder == null) null
            else {
                val feats = runInterruptible { clip.patchFeatures(frame) }
                runInterruptible { decoder.generateCaption(feats) }
            }
        }
        if (caption.isNullOrBlank()) {
            tts.speak(VoiceResponses.captionUnavailable(lang), lang)
        } else {
            Log.i(TAG, "Caption: $caption")
            setStatus(caption)
            tts.speak(caption, lang)
        }
    }

    private suspend fun readText(frame: android.graphics.Bitmap, lang: VoiceResponses.Lang) {
        val text = withContext(Dispatchers.IO) {
            ModelManager.ocr?.recognize(frame).orEmpty()
        }
        if (text.isBlank()) {
            tts.speak(VoiceResponses.noText(lang), lang)
        } else {
            Log.i(TAG, "OCR: $text")
            setStatus(text.lineSequence().firstOrNull() ?: text)
            tts.speak(text, lang)
        }
    }

    private suspend fun checkAhead(frame: android.graphics.Bitmap, lang: VoiceResponses.Lang) {
        val blocked = withContext(Dispatchers.IO) {
            val yolo = ModelManager.yolo ?: return@withContext null
            val midas = ModelManager.midas ?: return@withContext null
            val detections = runInterruptible { yolo.detect(frame) }
            if (detections.isEmpty()) return@withContext false
            val depth = runInterruptible { midas.depthNormalized(frame) }
            var nearest: Pair<Detection, Closeness>? = null
            val scored = ArrayList<Pair<Detection, Closeness>>()
            for (d in detections) {
                val sx = com.ankushjha.visionmate.ml.MiDaSDepth.SIZE.toFloat() / frame.width
                val sy = com.ankushjha.visionmate.ml.MiDaSDepth.SIZE.toFloat() / frame.height
                val x1 = ((d.x1 + d.width * 0.25f) * sx).toInt()
                val x2 = ((d.x2 - d.width * 0.25f) * sx).toInt()
                val y1 = ((d.y1 + d.height * 0.45f) * sy).toInt()
                val y2 = ((d.y1 + d.height * 0.85f) * sy).toInt()
                val ratio = midas.regionDepthMean(depth, x1, y1, x2, y2)
                val boxFrac = d.height / frame.height
                val closeness = when {
                    ratio >= 0.72f || boxFrac >= 0.55f -> Closeness.STOP
                    ratio >= 0.48f || boxFrac >= 0.35f -> Closeness.CLOSE
                    else -> Closeness.MID
                }
                val cur = nearest
                if (cur == null || closeness.ordinal > cur.second.ordinal) {
                    nearest = d to closeness
                }
                scored.add(d to closeness)
            }
            val n = nearest
            if (n == null || n.second == Closeness.MID || n.second == Closeness.CLEAR) {
                false
            } else {
                val side = com.ankushjha.visionmate.obstacle.PathGuidance
                    .suggestPassSide(scored, frame.width)
                if (n.second == Closeness.STOP) {
                    vibrate()
                    tts.speak(VoiceResponses.stop(lang, n.first.label), lang, TtsSpeaker.Priority.URGENT)
                } else {
                    tts.speak(VoiceResponses.close(lang, n.first.label, 2), lang)
                }
                if (side != com.ankushjha.visionmate.util.PassSide.NONE) {
                    tts.speak(VoiceResponses.pathSide(lang, side), lang)
                }
                true
            }
        }
        // blocked == null → models missing; false → nothing blocking; true → already spoken
        if (blocked == false) {
            tts.speak(VoiceResponses.pathClear(lang), lang)
        }
    }

    // ---------------- camera ----------------
    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(ContextCompat.getMainExecutor(this), frameAnalyzer) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                Log.i(TAG, "Camera bound")
            } catch (t: Throwable) {
                Log.e(TAG, "Camera failed: ${t.message}")
                cameraStarted = false
                Toast.makeText(this, R.string.camera_error, Toast.LENGTH_LONG).show()
                tts.speak(getString(R.string.camera_error), currentLang())
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------- obstacle loop toggle ----------------
    private fun applyObstacleSetting() {
        val on = prefs.obstacleWarningsOn && ModelManager.obstacleAvailable
        obstacleEngine.enabled = on
        obstacleEngine.pathGuidanceOn = prefs.pathGuidanceOn
        obstacleEngine.responseLang = currentLang()
        if (on) obstacleEngine.start() else obstacleEngine.stop()
        btnObstacle.text = getString(
            if (on) R.string.obstacle_on_short else R.string.obstacle_off_short
        )
    }

    // ---------------- overlay refresh ----------------
    private fun startOverlayRefresh() {
        val refresh = object : Runnable {
            override fun run() {
                val assessment = obstacleEngine.latestAssessment
                val frame = frameAnalyzer.latestFrame()
                if (frame != null && assessment != null) {
                    val nearestLabel = assessment.nearestLabel
                    val items = assessment.detections.map { d ->
                        val closeness = if (d.label == nearestLabel) assessment.closeness else Closeness.MID
                        DetectionOverlayView.OverlayItem(d, closeness)
                    }
                    overlay.update(frame.width, frame.height, items, assessment)
                } else if (frame == null) {
                    overlay.clear()
                }
                mainHandler.postDelayed(this, 1200)
            }
        }
        mainHandler.postDelayed(refresh, 1500)
    }

    // ---------------- misc ----------------
    private fun currentLang(): VoiceResponses.Lang = when (prefs.responseLanguage) {
        Prefs.LANG_HI -> VoiceResponses.Lang.HI
        Prefs.LANG_EN -> VoiceResponses.Lang.EN
        else -> VoiceResponses.Lang.EN
    }

    private fun maybeGreet() {
        // One spoken greeting per app launch — never a loop.
        if (greeted) return
        greeted = true
        val ready = ModelManager.readyCount()
        val total = ModelManager.totalModels()
        setStatus(getString(R.string.status_idle))
        tts.speak(VoiceResponses.appReady(currentLang(), ready, total), currentLang())
    }

    private fun updateModelChip() {
        modelStatusChip.text = "${ModelManager.readyCount()}/${ModelManager.totalModels()} " +
            getString(R.string.model_available)
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private fun refreshHint() {
        hintText.text = getString(R.string.tap_to_talk_hint)
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) needed.add(Manifest.permission.CAMERA)
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
        if (needed.isEmpty()) {
            startCamera()
            applyObstacleSetting()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun vibrate() {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            vib.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) { /* no vibrate hardware */ }
    }
}
