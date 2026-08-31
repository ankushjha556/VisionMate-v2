package com.ankushjha.visionmate

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.ankushjha.visionmate.util.Prefs

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Apply persisted theme before any Activity inflates.
        when (Prefs.get(this).themeMode) {
            Prefs.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            Prefs.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
