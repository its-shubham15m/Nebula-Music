package com.shubhamgupta.nebula_music

import android.app.Application
import androidx.media3.common.BuildConfig
import com.shubhamgupta.nebula_music.utils.DebugUtils
import com.shubhamgupta.nebula_music.utils.EqualizerManager
import com.shubhamgupta.nebula_music.utils.SongCacheManager
import com.shubhamgupta.nebula_music.utils.ThemeManager

class NebulaMusicApplication : Application() {

    companion object {
        private var instance: NebulaMusicApplication? = null
        fun getAppContext(): Application? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // This call now triggers the persistent cache logic. No changes needed.
        SongCacheManager.initializeCache(this)

        EqualizerManager.loadSettings()
        ThemeManager.applySavedTheme(this)
        DebugUtils.initialize(BuildConfig.DEBUG)
        DebugUtils.logInfo("Nebula Music Application started")
    }
}