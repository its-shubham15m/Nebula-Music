package com.shubhamgupta.nebula_music

import android.app.Application
import androidx.media3.common.BuildConfig
import com.shubhamgupta.nebula_music.utils.DebugUtils
import com.shubhamgupta.nebula_music.utils.EqualizerManager
import com.shubhamgupta.nebula_music.utils.ThemeManager

class NebulaMusicApplication : Application() {

    companion object {
        private var instance: NebulaMusicApplication? = null

        fun getAppContext(): Application? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Load equalizer settings when app starts
        EqualizerManager.loadSettings()

        // Apply saved theme when app starts
        ThemeManager.applySavedTheme(this)

        // Initialize debug utils
        DebugUtils.initialize(BuildConfig.DEBUG)

        DebugUtils.logInfo("Nebula Music Application started")
    }
}