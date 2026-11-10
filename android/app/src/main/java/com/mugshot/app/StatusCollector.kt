package com.mugshot.app

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager

/**
 * 设备状态采集：
 *  - 前台应用：UsageStatsManager 查询最近事件流，取最后一次 ACTIVITY_RESUMED（需"使用情况访问权限"）
 *  - 屏幕状态：PowerManager.isInteractive
 *  - 电池：BatteryManager 容量 + ACTION_BATTERY_CHANGED 粘性广播（充电状态/来源）
 *  - 网络：ConnectivityManager 活动网络能力（Wi-Fi / 移动数据 / 无）
 */
object StatusCollector {

    data class Snapshot(
        val screenOn: Boolean,
        val foregroundApp: String,
        val foregroundPackage: String,
        val batteryLevel: Int,
        val batteryStatus: String,   // charging | full | discharging | unknown
        val charging: Boolean,
        val chargingSource: String,  // ac | usb | wireless | none
        val networkType: String,     // wifi | mobile | none
        val networkDetail: String,
        val deviceModel: String,
        val appVersion: String,
    )

    fun collect(ctx: Context): Snapshot {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = pm.isInteractive

        val battery = batteryState(ctx)
        val network = networkState(ctx)
        val fg = foregroundApp(ctx)

        return Snapshot(
            screenOn = screenOn,
            foregroundApp = fg.first,
            foregroundPackage = fg.second,
            batteryLevel = battery.first,
            batteryStatus = battery.second,
            charging = battery.second == "charging" || battery.second == "full",
            chargingSource = battery.third,
            networkType = network.first,
            networkDetail = network.second,
            deviceModel = Build.MODEL ?: "",
            appVersion = appVersion(ctx),
        )
    }

    /** (level, status, source) */
    @Suppress("DEPRECATION")
    private fun batteryState(ctx: Context): Triple<Int, String, String> {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: -1

        val intent = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        var status = "unknown"
        var source = "none"
        if (intent != null) {
            val st = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            source = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "none"
            }
            status = when {
                st == BatteryManager.BATTERY_STATUS_FULL -> "full"
                st == BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                st == BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                else -> "unknown"
            }
            if (plugged != 0 && status == "unknown") status = "charging"
        }
        return Triple(level, status, source)
    }

    /** (type, detail) */
    private fun networkState(ctx: Context): Pair<String, String> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Pair("none", "")
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Pair("wifi", "")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                Pair("mobile", mobileDetail(tm))
            }
            else -> Pair("none", "")
        }
    }

    @Suppress("DEPRECATION")
    private fun mobileDetail(tm: TelephonyManager?): String {
        if (tm == null) return ""
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                tm.dataNetworkType
            } else {
                tm.networkType
            }
            when (type) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                else -> ""
            }
        } catch (_: SecurityException) {
            ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 返回 (应用名, 包名)。查最近 5 分钟事件流中最后一次 ACTIVITY_RESUMED。
     * 未授予"使用情况访问权限"时返回空串。
     */
    @SuppressLint("WrongConstant")
    fun foregroundApp(ctx: Context): Pair<String, String> {
        if (!hasUsageAccess(ctx)) return Pair("", "")
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 5 * 60_000L, now)
        var pkg = ""
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED && ev.packageName.isNotBlank()) {
                pkg = ev.packageName
            }
        }
        if (pkg.isEmpty()) return Pair("", "")
        val label = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            ""
        }
        return Pair(label, pkg)
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(now - 60_000L, now)
        return stats.isNotEmpty()
    }

    fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
