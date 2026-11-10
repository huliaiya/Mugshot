package com.mugshot.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 厂商兼容辅助：
 *  - 电池优化白名单引导（ REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 直接申请或跳转设置页）
 *  - 各厂商自启动管理页跳转（仅做引导，跳转失败回退应用详情页）
 */
object VendorHelper {

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** 直接申请电池优化白名单（有明确用户交互场景时使用） */
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        @Suppress("BatteryLife")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${activity.packageName}"))
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            openAppDetails(activity)
        }
    }

    /** 通知权限（Android 13+ 前台服务通知需要用户授权） */
    fun needsNotificationPermission(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    fun requestNotificationPermission(activity: Activity, code: Int) {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                code,
            )
        }
    }

    /** 跳转"使用情况访问权限"设置页（前台应用采集必需） */
    fun openUsageAccessSettings(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
            openAppDetails(activity)
        }
    }

    /** 按厂商尝试跳转自启动管理页；失败回退应用详情页 */
    fun openAutostartSettings(activity: Activity) {
        val candidates = buildCandidates()
        for (c in candidates) {
            if (tryStart(activity, c)) return
        }
        openAppDetails(activity)
    }

    private fun buildCandidates(): List<Manufacturer> {
        val maker = (Build.MANUFACTURER ?: "").lowercase()
        val list = mutableListOf<Manufacturer>()

        fun add(maker: String, pkg: String, cls: String) {
            list.add(Manufacturer(maker, pkg, cls))
        }

        add("xiaomi", "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        add("redmi", "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        add("huawei", "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        add("honor", "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        add("oppo", "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        add("oppo", "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        add("realme", "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        add("vivo", "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        add("iqoo", "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        add("samsung", "com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
        add("meizu", "com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")
        add("letv", "com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
        add("oneplus", "com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")

        // 厂商优先排序：匹配当前厂商的候选放前面
        return list.sortedByDescending { maker.contains(it.maker) || it.maker.contains(maker) }
    }

    private data class Manufacturer(val maker: String, val pkg: String, val cls: String)

    private fun tryStart(activity: Activity, m: Manufacturer): Boolean {
        val intent = Intent().setClassName(m.pkg, m.cls)
        return try {
            // 某些页面需要 putExtra 才定位到具体应用
            if (m.cls.endsWith("SHOW_APPSEC")) {
                intent.putExtra("packageName", activity.packageName)
            }
            activity.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openAppDetails(activity: Activity) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${activity.packageName}")),
            )
        } catch (_: Exception) {
        }
    }

    fun vendorHint(ctx: Context): String {
        val maker = (Build.MANUFACTURER ?: "").lowercase()
        return when {
            maker.contains("xiaomi") || maker.contains("redmi") ->
                "小米/红米：请同时开启 自启动、后台弹出界面、锁屏显示 三项权限"
            maker.contains("huawei") || maker.contains("honor") ->
                "华为/荣耀：请在 启动管理 中关闭 自动管理，允许 自启动/关联启动/后台活动"
            maker.contains("oppo") || maker.contains("realme") ->
                "OPPO/realme：请允许 自启动 并关闭 智能后台管控"
            maker.contains("vivo") || maker.contains("iqoo") ->
                "vivo/iQOO：请允许 后台弹出界面、自启动，并在电池中改为 后台高耗电"
            maker.contains("samsung") ->
                "三星：请在 电池-后台使用限制 中将本应用移出 深度睡眠"
            else -> "请将本应用加入自启动白名单与电池优化白名单"
        }
    }
}
