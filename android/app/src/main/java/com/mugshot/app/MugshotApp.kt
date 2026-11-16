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
        LogStore.init(this)
        installCrashLogger()
        LogStore.log(
            "app", "应用启动 v${StatusCollector.appVersion(this)} " +
                "Android ${android.os.Build.VERSION.RELEASE}（SDK ${android.os.Build.VERSION.SDK_INT}）" +
                " ${android.os.Build.MODEL ?: ""}"
        )
        applyTheme()
        com.mugshot.app.watch.WatchManager.init(this)
    }

    /** 未捕获异常先落运行日志再走系统崩溃流程：闪退后可在日志页回溯现场 */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                LogStore.log("crash", "未捕获异常（线程 ${t.name}）：${e.javaClass.name}: ${e.message ?: "无消息"}")
                android.util.Log.getStackTraceString(e).lineSequence().take(30)
                    .forEach { if (it.isNotBlank()) LogStore.log("crash", it) }
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
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
