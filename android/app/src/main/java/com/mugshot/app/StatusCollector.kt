package com.mugshot.app

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    // 采集缓存：24h 时长 2 分钟、打开次数 30 分钟。UsageStats 是跨进程 DB 查询，
    // 缩短轮询频率可明显减少 CPU 唤醒，同时不影响插件展示的时效。
    private const val USAGE_CACHE_MS = 2 * 60_000L
    private const val COUNT_CACHE_MS = 30 * 60_000L

    data class Snapshot(
        val screenOn: Boolean,
        val foregroundApp: String,
        val foregroundPackage: String,
        val foregroundApps: List<Pair<String, String>>, // 分屏/小窗等同时可见的应用，焦点在前，最多 5 个
        val foregroundActivity: String,                 // 焦点 Activity（package/class），Shizuku 授权时可得
        val backgroundApps: List<Pair<String, String>>, // 最近 30 分钟内用过、当前不在前台的运行中应用，最多 5 个
        val batteryLevel: Int,
        val batteryStatus: String,   // charging | full | discharging | unknown
        val charging: Boolean,
        val chargingSource: String,  // ac | usb | wireless | none
        val batteryTempC: Double,    // 电池温度（摄氏度），-1 表示读不到
        val networkType: String,     // wifi | mobile | none
        val networkDetail: String,
        val deviceModel: String,
        val appVersion: String,
        val usage24h: List<UsageEntry> = emptyList(), // 最近 24 小时各应用前台时长（top 8，30 秒缓存）
    )

    /** 24 小时使用条目：包名、应用名、前台分钟数、打开次数（ACTIVITY_RESUMED 计数） */
    data class UsageEntry(val pkg: String, val app: String, val min: Long, val count: Long = 0)

    /**
     * 采集不到的字段及原因（供日志/界面提示）。空表示所有字段都能采集到。
     *  - 前台应用：未授予“使用情况访问权限”时返回空；或息屏期间事件流可能为空
     *  - 电池：部分设备读不到容量时为 -1
     *  - 网络：无活动网络时为 none
     */
    fun missingFields(ctx: Context, s: Snapshot): List<String> {
        val out = mutableListOf<String>()
        if (s.foregroundApp.isBlank() && s.foregroundPackage.isBlank()) {
            out += if (!hasUsageAccess(ctx)) "前台应用：未授予「使用情况访问权限」，无法读取"
            else if (!s.screenOn) "前台应用：息屏期间事件流为空"
            else "前台应用：最近 5 分钟无前台切换记录"
        }
        if (s.batteryLevel < 0) out += "电池电量：本机读不到容量（部分设备不支持）"
        if (s.batteryStatus == "unknown") out += "充电状态：未知"
        if (s.networkType == "none") out += "网络：当前无活动网络"
        if (s.deviceModel.isBlank()) out += "设备型号：未获取"
        return out
    }

    /** 一行可读的上报摘要 + 缺失字段说明，供日志与界面共用 */
    fun describe(ctx: Context, s: Snapshot = collect(ctx)): String {
        val missing = missingFields(ctx, s)
        val app = if (s.foregroundApps.size >= 2) s.foregroundApps.joinToString(" / ") { it.first }
        else s.foregroundApp
        val temp = if (s.batteryTempC > 0) String.format("%.1f°C", s.batteryTempC) else "-"
        val base = "app=${app.ifBlank { "-" }} 电量=${if (s.batteryLevel >= 0) "${s.batteryLevel}%" else "-"} " +
            "温度=$temp 后台=${s.backgroundApps.size} 屏幕=${if (s.screenOn) "亮" else "灭"} 网络=${s.networkType}"
        return if (missing.isEmpty()) base else "$base | 缺失：${missing.joinToString("；")}"
    }

    fun collect(ctx: Context): Snapshot {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = pm.isInteractive

        val battery = batteryState(ctx)
        val network = networkState(ctx)
        val fg = foregroundApp(ctx)
        // 分屏/小窗里失焦的那半屏应用在事件流里是 ACTIVITY_PAUSED（未 STOPPED），单应用逻辑取不到；
        // 息屏时不采集（暂停状态会被误判为可见），低版本系统见 visibleApps 内的说明。
        val fgApps = if (screenOn) visibleApps(ctx) else emptyList()
        val bgApps = if (screenOn) {
            collectBackgroundApps(ctx, fg.second, fgApps.map { it.second }.toSet(), System.currentTimeMillis())
        } else {
            emptyList()
        }

        return Snapshot(
            screenOn = screenOn,
            foregroundApp = fg.first,
            foregroundPackage = fg.second,
            foregroundApps = fgApps,
            foregroundActivity = if (ShizukuHelper.hasPermission()) {
                ShizukuHelper.windowSnapshot().focusActivity
            } else {
                ""
            },
            backgroundApps = bgApps,
            batteryLevel = battery.level,
            batteryStatus = battery.status,
            charging = battery.status == "charging" || battery.status == "full",
            chargingSource = battery.source,
            batteryTempC = battery.tempC,
            networkType = network.first,
            networkDetail = network.second,
            deviceModel = Build.MODEL ?: "",
            appVersion = appVersion(ctx),
            usage24h = collectUsage24h(ctx),
        )
    }

    /**
     * 最近 24 小时各应用打开次数（ACTIVITY_RESUMED 事件计数）。
     * 事件流一次 24 小时窗口可能有上万条，单独做 10 分钟缓存，
     * 与 30 秒的时长缓存解耦；API 29 以下事件 getter 不可用，直接返回空。
     */
    @SuppressLint("WrongConstant")
    private fun collectLaunchCounts(ctx: Context, now: Long): Map<String, Long> {
        if (now - countCacheAt < COUNT_CACHE_MS) return countCache
        countCacheAt = now
        countCache = emptyMap()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return countCache
        if (!hasUsageAccess(ctx)) return countCache
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return countCache
        val events = try {
            usm.queryEvents(now - 24 * 60 * 60_000L, now)
        } catch (_: Exception) {
            null
        } ?: return countCache
        val counts = HashMap<String, Long>()
        val ev = UsageEvents.Event()
        var lastResumed = ""
        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val p = ev.packageName
                if (p.isNullOrBlank()) continue
                // 同一应用内切换 Activity 也会产生 RESUMED，只有"换了应用"才算一次打开
                if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED && p != lastResumed) {
                    counts[p] = (counts[p] ?: 0L) + 1
                    lastResumed = p
                }
            }
        } catch (_: Exception) {
            // 个别 ROM 的事件流会在中途抛异常，返回已统计部分
        }
        countCache = counts
        return counts
    }

    private var usageCache: List<UsageEntry> = emptyList()
    private var usageCacheAt = 0L
    private var countCache: Map<String, Long> = emptyMap()
    private var countCacheAt = 0L

    /**
     * 最近 24 小时各应用前台使用时长（top 8）。
     * 用 queryAndAggregateUsageStats 跨区间聚合，比 queryUsageStats 更贴近
     * “连续 24 小时”语义（后者按日切桶，凌晨会只剩当天零点后的数据）。
     * 30 秒缓存：上报间隔最短 2 秒，跨进程查询没必要每次都跑。
     * totalTimeInForeground 在 API 28 以下不可靠（常为 0），低版本直接不采集。
     * 未授予「使用情况访问权限」时返回空列表。
     */
    @SuppressLint("WrongConstant")
    private fun collectUsage24h(ctx: Context): List<UsageEntry> {
        val now = System.currentTimeMillis()
        if (now - usageCacheAt < USAGE_CACHE_MS) return usageCache
        usageCacheAt = now
        usageCache = emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return usageCache
        if (!hasUsageAccess(ctx)) return usageCache
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return usageCache
        val stats = try {
            usm.queryAndAggregateUsageStats(now - 24 * 60 * 60_000L, now)
        } catch (_: Exception) {
            null
        } ?: return usageCache
        val counts = collectLaunchCounts(ctx, now)
        usageCache = stats.values
            .asSequence()
            .filter { it.totalTimeInForeground > 0 }
            .map {
                UsageEntry(
                    it.packageName,
                    appLabel(ctx, it.packageName).ifBlank { it.packageName },
                    it.totalTimeInForeground / 60_000L,
                    counts[it.packageName] ?: 0L,
                )
            }
            .filter { it.min > 0 }
            .sortedByDescending { it.min }
            .take(8)
            .toList()
        return usageCache
    }

    /** 电池：容量、充电状态、充电来源、温度（读不到时 level=-1、tempC=-1） */
    private data class BatteryState(
        val level: Int,
        val status: String,
        val source: String,
        val tempC: Double,
    )

    @Suppress("DEPRECATION")
    private fun batteryState(ctx: Context): BatteryState {
        val intent = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        var level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: -1
        // 部分 ROM 的 CAPACITY 属性会返回 -1，退回电池广播里的 level/scale（绝大多数机型都能读到）
        if (level < 0 && intent != null) {
            val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
            if (raw in 0..scale) level = (raw * 100 / scale).coerceIn(0, 100)
        }
        // 广播也读不到时（极少数定制系统），用 Shizuku 的 dumpsys battery 兜底
        if (level < 0) level = ShizukuHelper.batteryLevel()

        var status = "unknown"
        var source = "none"
        var tempC = -1.0
        if (intent != null) {
            // EXTRA_TEMPERATURE 单位是 0.1 摄氏度；部分机型会带电池温度，读不到时为 0
            val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            if (rawTemp > 0) tempC = rawTemp / 10.0
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
        return BatteryState(level, status, source, tempC)
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
     * 事件解析需要 API 29+（Event 的 getter 是 29 才加入的，minSdk 26 的
     * 低版本设备上调用会 NoSuchMethodError），低版本直接走统计兜底。
     */
    @SuppressLint("WrongConstant")
    fun foregroundApp(ctx: Context): Pair<String, String> {
        val nowTs = System.currentTimeMillis()
        if (nowTs - fgCacheAt < 8_000L) return fgCache
        fgCacheAt = nowTs
        // 没有使用情况权限时，若 Shizuku 已授权则用 shell 读 dumpsys 兜底
        if (!hasUsageAccess(ctx)) {
            val p = ShizukuHelper.dumpsysForegroundPackage()
            fgCache = if (p.isBlank()) Pair("", "") else Pair(appLabel(ctx, p).ifBlank { p }, p)
            return fgCache
        }
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        var pkg = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val events = usm.queryEvents(now - 5 * 60_000L, now)
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED && !ev.packageName.isNullOrBlank()) {
                    pkg = ev.packageName
                }
            }
        }
        // 事件流为空（例如息屏/刚解锁/低版本）时，退回“最近 5 分钟使用时长最高的应用”作为前台应用
        if (pkg.isEmpty()) {
            pkg = topUsagePackage(ctx, now)
        }
        if (pkg.isEmpty()) {
            fgCache = Pair("", "")
            return fgCache
        }
        fgCache = Pair(appLabel(ctx, pkg), pkg)
        return fgCache
    }

    private var fgCacheAt = 0L
    private var fgCache = Pair("", "")

    /**
     * 同时可见的应用列表（分屏 / 小窗 / 自由窗口 / 多窗口）。
     *
     * 原理：按包名跟踪事件流里每个应用"最新"的活动状态——
     *  - 最新事件是 ACTIVITY_RESUMED：处于前台（含多窗口双焦点）
     *  - 最新事件是 ACTIVITY_PAUSED：已暂停但仍未被 ACTIVITY_STOPPED，
     *    分屏失焦半屏 / 小窗下的底层应用 / 画中画都属于这种状态，视为可见
     *  - 最新事件是 ACTIVITY_STOPPED / ACTIVITY_DESTROYED：已完全退出屏幕
     *
     * 排序按最后一次 ACTIVITY_RESUMED 的时间倒序（焦点应用在前），最多返回 3 个。
     * 需要 API 29+（事件流里才有 ACTIVITY_STOPPED/DESTROYED，低版本无法区分
     * "暂停但可见"与"已退后台"）；低版本返回空列表，前台应用仍走单应用逻辑。
     * 未授予"使用情况访问权限"时同样返回空列表。
     */
    @SuppressLint("WrongConstant")
    fun visibleApps(ctx: Context): List<Pair<String, String>> {
        if (!hasUsageAccess(ctx)) return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val nowTs = System.currentTimeMillis()
        if (nowTs - visibleCacheAt < 20_000L) return visibleCache
        visibleCacheAt = nowTs
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = nowTs
        val events = usm.queryEvents(now - 60 * 60_000L, now)
        val state = HashMap<String, Int>()   // 包名 -> 最新活动事件类型
        val resumeTs = HashMap<String, Long>() // 包名 -> 最后一次 ACTIVITY_RESUMED 时间
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val p = ev.packageName
            if (p.isNullOrBlank()) continue
            val t = ev.eventType
            // 只关心活动生命周期事件，通知/键盘锁等其他事件不影响可见性判定。
            // ACTIVITY_DESTROYED(数值 4) 在公开 SDK 中是 @hide 常量，只能按数值引用；
            // 进程被杀等场景可能只发 DESTROYED 而没有 STOPPED，需要同样视为已离开屏幕
            if (t == UsageEvents.Event.ACTIVITY_RESUMED ||
                t == UsageEvents.Event.ACTIVITY_PAUSED ||
                t == UsageEvents.Event.ACTIVITY_STOPPED ||
                t == 4
            ) {
                if (t == UsageEvents.Event.ACTIVITY_RESUMED) resumeTs[p] = ev.timeStamp
                state[p] = t
            }
        }
        val fromEvents = state.entries
            .filter {
                it.value == UsageEvents.Event.ACTIVITY_RESUMED || it.value == UsageEvents.Event.ACTIVITY_PAUSED
            }
            .sortedByDescending { resumeTs[it.key] ?: 0L }
            .map { it.key }
        // 事件流看不到的悬浮小窗/自由窗口，用 Shizuku 的任务栈与窗口列表补齐（已可见的排前面）
        val pkgs = LinkedHashSet(fromEvents)
        if (ShizukuHelper.hasPermission()) {
            pkgs.addAll(ShizukuHelper.taskPackages().visible)
            pkgs.addAll(ShizukuHelper.windowSnapshot().visible)
        }
        visibleCache = pkgs
            .take(5)
            .map { Pair(appLabel(ctx, it).ifBlank { it }, it) }
        return visibleCache
    }

    private var visibleCacheAt = 0L
    private var visibleCache: List<Pair<String, String>> = emptyList()

    /**
     * 后台应用：最近 30 分钟用过、当前不在前台/可见列表里的应用（按最后使用时间倒序）。
     * 事件流判定"最近用过"，Shizuku 任务栈补充"仍在最近任务里"的条目，两者合并去重。
     * 需要"使用情况访问权限"；没有该权限时只返回任务栈结果（可能为空）。
     */
    private fun collectBackgroundApps(
        ctx: Context,
        foregroundPkg: String,
        visiblePkgs: Set<String>,
        now: Long,
    ): List<Pair<String, String>> {
        if (now - bgCacheAt < 60_000L) return bgCache
        bgCacheAt = now
        val exclude = HashSet(visiblePkgs)
        exclude += foregroundPkg
        val result = LinkedHashSet<String>()
        if (hasUsageAccess(ctx)) {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val stats = try {
                usm?.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 30 * 60_000L, now)
            } catch (_: Exception) {
                null
            }
            stats?.asSequence()
                ?.filter { it.lastTimeUsed > 0 && it.totalTimeInForeground > 0 }
                ?.sortedByDescending { it.lastTimeUsed }
                ?.take(20)
                ?.forEach { st ->
                    val pkg = st.packageName ?: return@forEach
                    if (result.size >= 5) return@forEach
                    if (pkg in exclude) return@forEach
                    result += pkg
                }
        }
        if (ShizukuHelper.hasPermission()) {
            for (p in ShizukuHelper.taskPackages().background) {
                if (result.size >= 5) break
                if (p in exclude) continue
                result += p
            }
        }
        bgCache = result.take(5).map { Pair(appLabel(ctx, it).ifBlank { it }, it) }
        return bgCache
    }

    private var bgCacheAt = 0L
    private var bgCache: List<Pair<String, String>> = emptyList()

    /**
     * 包名 -> 应用名缓存。PM 查询是跨进程调用，每次上报都重复解析同一批包名
     * 既慢也偶发失败（binder 竞态、应用更新/卸载瞬间 NameNotFoundException、
     * 部分 ROM 的包可见性实现不稳）；失败时上层回退用包名展示。
     */
    private val labelCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun appLabel(ctx: Context, pkg: String): String = try {
        val label = ctx.packageManager
            .getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0))
            .toString()
            .ifBlank { pkg }
        if (labelCache.size > 200) labelCache.clear() // 简单防膨胀：全清后下次自动重建
        labelCache[pkg] = label
        label
    } catch (_: Exception) {
        // 查询失败时优先用上次成功的名称，避免偶发失败导致展示退化成包名
        labelCache[pkg] ?: ""
    }

    /** 最近 5 分钟使用时长最高的应用包名（事件流无结果时的兜底） */
    private fun topUsagePackage(ctx: Context, now: Long): String {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 5 * 60_000L, now) ?: return ""
        var best = ""
        var bestTime = 0L
        for (s in stats) {
            // API 28+ 用前台使用时长；更低版本退回“最近使用时间”作为新鲜度近似
            val t = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                s.totalTimeInForeground
            } else {
                s.lastTimeUsed
            }
            if (t > bestTime) {
                bestTime = t
                best = s.packageName ?: ""
            }
        }
        return best
    }

    /**
     * 是否已授予“使用情况访问权限”。
     * 用 AppOps 权限位直接判断（权威且确定）：开机后/统计服务刚被清理时
     * 统计查询会返回空集，用查询结果反推会把已授权误判成未授权，
     * 导致前台应用字段整体缺失（用户反馈"有时获取不到应用名称"的原因之一）。
     */
    fun hasUsageAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.packageName
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
