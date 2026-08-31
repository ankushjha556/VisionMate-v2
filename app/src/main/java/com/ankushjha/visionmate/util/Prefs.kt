package com.ankushjha.visionmate.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Central persisted settings. All reads/writes are synchronous —
 * values are tiny and never read in a tight loop.
 */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("visionmate_prefs", Context.MODE_PRIVATE)

    // ---- Theme ----
    @ThemeMode var themeMode: Int
        get() = sp.getInt(KEY_THEME, THEME_SYSTEM)
        set(v) = sp.edit().putInt(KEY_THEME, v).apply()

    // ---- Vision ----
    var obstacleWarningsOn: Boolean
        get() = sp.getBoolean(KEY_OBSTACLE, true)
        set(v) = sp.edit().putBoolean(KEY_OBSTACLE, v).apply()

    var pathGuidanceOn: Boolean
        get() = sp.getBoolean(KEY_PATH, true)
        set(v) = sp.edit().putBoolean(KEY_PATH, v).apply()

    // ---- Voice ----
    var volumeKeyTriggerOn: Boolean
        get() = sp.getBoolean(KEY_VOLUME, true)
        set(v) = sp.edit().putBoolean(KEY_VOLUME, v).apply()

    /** TTS rate multiplier stored as int 5..20 → 0.5x..2.0x */
    var ttsRateStep: Int
        get() = sp.getInt(KEY_TTS_RATE, 10)
        set(v) = sp.edit().putInt(KEY_TTS_RATE, v.coerceIn(5, 20)).apply()

    @LanguageMode var responseLanguage: Int
        get() = sp.getInt(KEY_LANG, LANG_AUTO)
        set(v) = sp.edit().putInt(KEY_LANG, v).apply()

    // ---- Keys ----
    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        const val LANG_AUTO = 0
        const val LANG_EN = 1
        const val LANG_HI = 2

        private const val KEY_THEME = "theme_mode"
        private const val KEY_OBSTACLE = "obstacle_warnings"
        private const val KEY_PATH = "path_guidance"
        private const val KEY_VOLUME = "volume_key_trigger"
        private const val KEY_TTS_RATE = "tts_rate_step"
        private const val KEY_LANG = "response_language"

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }

    annotation class ThemeMode
    annotation class LanguageMode
}
