package com.ankushjha.visionmate

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.ankushjha.visionmate.ml.ModelManager
import com.ankushjha.visionmate.util.Prefs
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setContentView(R.layout.activity_settings)
        prefs = Prefs.get(this)

        setupThemeSpinner()
        setupSwitches()
        setupTtsRate()
        setupLanguageSpinner()
        renderModelList()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupThemeSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinnerTheme)
        val options = listOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinner.setSelection(prefs.themeMode)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (pos == prefs.themeMode) return  // initial layout trigger
                prefs.themeMode = pos
                val mode = when (pos) {
                    Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(mode)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupSwitches() {
        findViewById<MaterialSwitch>(R.id.switchObstacle).apply {
            isChecked = prefs.obstacleWarningsOn
            setOnCheckedChangeListener { _, v -> prefs.obstacleWarningsOn = v }
        }
        findViewById<MaterialSwitch>(R.id.switchPathGuidance).apply {
            isChecked = prefs.pathGuidanceOn
            setOnCheckedChangeListener { _, v -> prefs.pathGuidanceOn = v }
        }
        findViewById<MaterialSwitch>(R.id.switchVolumeTrigger).apply {
            isChecked = prefs.volumeKeyTriggerOn
            setOnCheckedChangeListener { _, v -> prefs.volumeKeyTriggerOn = v }
        }
    }

    private fun setupTtsRate() {
        val seek = findViewById<SeekBar>(R.id.seekTtsRate)
        seek.progress = prefs.ttsRateStep - 5   // 0..15 → step 5..20
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser) prefs.ttsRateStep = v + 5
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun setupLanguageSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinnerLanguage)
        val options = listOf(
            getString(R.string.lang_auto),
            getString(R.string.lang_en),
            getString(R.string.lang_hi)
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinner.setSelection(prefs.responseLanguage)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.responseLanguage = pos
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun renderModelList() {
        val tv = findViewById<TextView>(R.id.modelListText)
        val loaded = ModelManager.loaded
        val status = { ok: Boolean -> if (ok) " [ok]" else " [--]" }
        tv.text = buildString {
            append("YOLO v11n     ").append(status(loaded && ModelManager.yolo != null)).append('\n')
            append("MiDaS small    ").append(status(loaded && ModelManager.midas != null)).append('\n')
            append("CLIP ViT-B/32  ").append(status(loaded && ModelManager.clip != null)).append('\n')
            append("Caption dec.   ").append(status(loaded && ModelManager.decoder != null)).append('\n')
            append("OCR (ML Kit)   ").append(status(loaded && ModelManager.ocr != null)).append('\n')
            append('\n')
            if (!ModelManager.loaded) append("Loading… reopen to refresh")
            else {
                val missing = ModelManager.totalModels() - ModelManager.readyCount()
                if (missing > 0) append("$missing model(s) missing — see MODELS_SETUP.md")
                else append("All models loaded")
            }
        }
    }
}
