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

    var lastReportTs: Long
        get() = sp(MugshotApp.instance).getLong(KEY_LAST_REPORT_TS, 0)
        set(v) = sp(MugshotApp.instance).edit().putLong(KEY_LAST_REPORT_TS, v).apply()

    fun isConfigured(): Boolean =
        serverUrl.startsWith("http") && deviceToken.isNotBlank()

    fun defaultDeviceName(): String = android.os.Build.MODEL ?: "Android 设备"

    fun defaultInterval() = 300
}
