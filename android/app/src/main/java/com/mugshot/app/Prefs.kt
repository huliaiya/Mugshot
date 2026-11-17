package com.mugshot.app

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 轻量配置存取。device_id 首次启动随机生成并持久化，保证跨重启稳定。
 */
object Prefs {
    private const val FILE = "mugshot_prefs"

    const val KEY_CONSENT = "privacy_consent"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_DEVICE_TOKEN = "device_token"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_REPORT_INTERVAL = "report_interval_sec"
    const val KEY_LAST_REPORT_TS = "last_report_ts"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_LOG_AUTOSCROLL = "log_autoscroll"
    const val KEY_WATCH_ENABLED = "watch_enabled"
    const val KEY_WATCH_ADDRESS = "watch_address"
    const val KEY_WATCH_NAME = "watch_name"
    const val KEY_WATCH_NAME_MANUAL = "watch_name_manual"
    const val KEY_WATCH_USER_ID = "watch_user_id"

    /** 主题模式：0 跟随系统 / 1 浅色 / 2 深色 */
    const val THEME_FOLLOW_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var consent: Boolean
        get() = sp(MugshotApp.instance).getBoolean(KEY_CONSENT, false)
        set(v) = sp(MugshotApp.instance).edit().putBoolean(KEY_CONSENT, v).apply()

    var serverUrl: String
        get() = sp(MugshotApp.instance).getString(KEY_SERVER_URL, "") ?: ""
        set(v) = sp(MugshotApp.instance).edit().putString(KEY_SERVER_URL, v.trim()).apply()

    var deviceToken: String
        get() = sp(MugshotApp.instance).getString(KEY_DEVICE_TOKEN, "") ?: ""
        set(v) = sp(MugshotApp.instance).edit().putString(KEY_DEVICE_TOKEN, v.trim()).apply()

    /** 稳定 device_id：首启生成 UUID，之后不再变化 */
    var deviceId: String
        get() {
            val sp = sp(MugshotApp.instance)
            var id = sp.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrEmpty()) {
                id = UUID.randomUUID().toString()
                sp.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }
        private set(_) {}

    var deviceName: String
        get() {
            val saved = sp(MugshotApp.instance).getString(KEY_DEVICE_NAME, null)
            return saved?.takeIf { it.isNotBlank() } ?: defaultDeviceName()
        }
        set(v) = sp(MugshotApp.instance).edit().putString(KEY_DEVICE_NAME, v.trim()).apply()

    /** 上报周期（秒），服务端 request_report 之外的定时上报 */
    var reportIntervalSec: Int
        get() = sp(MugshotApp.instance).getInt(KEY_REPORT_INTERVAL, 300)
        set(v) = sp(MugshotApp.instance).edit().putInt(KEY_REPORT_INTERVAL, v.coerceIn(30, 3600)).apply()

    /** 是否启用蓝牙手表连接 */
    var watchEnabled: Boolean
        get() = sp(MugshotApp.instance).getBoolean(KEY_WATCH_ENABLED, false)
        set(v) = sp(MugshotApp.instance).edit().putBoolean(KEY_WATCH_ENABLED, v).apply()

    /** 上次连接的手表 MAC，用于启动时直接回连 */
    var watchAddress: String
        get() = sp(MugshotApp.instance).getString(KEY_WATCH_ADDRESS, "") ?: ""
        set(v) = sp(MugshotApp.instance).edit().putString(KEY_WATCH_ADDRESS, v.trim()).apply()

    var watchName: String
        get() = sp(MugshotApp.instance).getString(KEY_WATCH_NAME, "") ?: ""
        set(v) = sp(MugshotApp.instance).edit().putString(KEY_WATCH_NAME, v.trim()).apply()

    /** 用户手动改过手表名称后不再用蓝牙广播名覆盖 */
    var watchNameManual: Boolean
        get() = sp(MugshotApp.instance).getBoolean(KEY_WATCH_NAME_MANUAL, false)
        set(v) = sp(MugshotApp.instance).edit().putBoolean(KEY_WATCH_NAME_MANUAL, v).apply()

    /** 8 位 ASCII 绑定 ID：手表记住该 ID，必须持久化且不要频繁更换 */
    var watchUserId: String
        get() {
            val sp = sp(MugshotApp.instance)
            val saved = sp.getString(KEY_WATCH_USER_ID, null)
            if (!saved.isNullOrEmpty() && saved.length == 8) return saved
            val id = "MG" + (100000..999999).random()
            sp.edit().putString(KEY_WATCH_USER_ID, id).apply()
            return id
        }
        set(v) = sp(MugshotApp.instance).edit().putString(KEY_WATCH_USER_ID, v.take(8)).apply()

    var lastReportTs: Long
        get() = sp(MugshotApp.instance).getLong(KEY_LAST_REPORT_TS, 0)
        set(v) = sp(MugshotApp.instance).edit().putLong(KEY_LAST_REPORT_TS, v).apply()

    /** 运行日志页是否自动滚动到底部（跟随最新日志） */
    var logAutoScroll: Boolean
        get() = sp(MugshotApp.instance).getBoolean(KEY_LOG_AUTOSCROLL, true)
        set(v) = sp(MugshotApp.instance).edit().putBoolean(KEY_LOG_AUTOSCROLL, v).apply()

    var themeMode: Int
        get() = sp(MugshotApp.instance).getInt(KEY_THEME_MODE, THEME_FOLLOW_SYSTEM)
        set(v) = sp(MugshotApp.instance).edit().putInt(KEY_THEME_MODE, v).apply()

    fun isConfigured(): Boolean =
        serverUrl.startsWith("http") && deviceToken.isNotBlank()

    fun defaultDeviceName(): String = android.os.Build.MODEL ?: "Android 设备"

    fun defaultInterval() = 300
}
