package com.mugshot.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class MugshotApp : Application() {
    companion object {
        lateinit var instance: MugshotApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        applyTheme()
    }

    /** 按用户设置应用主题（跟随系统 / 浅色 / 深色）；变更后 AppCompat 会自动重建界面 */
    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (Prefs.themeMode) {
                Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }
}
