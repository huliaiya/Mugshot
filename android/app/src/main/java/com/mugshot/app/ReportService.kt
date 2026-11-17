package com.mugshot.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mugshot.app.watch.WatchManager

/**
 * 设备状态上报前台服务（specialUse 类型）。
 *
 * 使用 specialUse 而非 dataSync：Android 15 的 dataSync 有每日约 6 小时配额，
 * 超时会被系统停止，典型表现是"滑到后台一段时间就断开"；specialUse 无此配额。
 * 另外三重保活：
 *  - START_STICKY + onTaskRemoved 重新拉起
 *  - 每 15 分钟看门狗闹钟（WatchdogReceiver）检查服务存活
 *  - Shizuku 已授权时应用 Doze 白名单 / 待机桶 active / 后台运行放行
 * 服务被停后仍有 WorkManager HTTP 兜底上报（ReportWorker → POST /api/v1/report）。
 */
class ReportService : Service(), WsClient.Listener {

    companion object {
        const val CHANNEL_ID = "mugshot_status"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.mugshot.app.START"
        const val ACTION_STOP = "com.mugshot.app.STOP"

        @Volatile
        var isRunning = false
            private set

        // 当前活跃的服务实例：供 companion 直接操作服务内的 WS 客户端
        @Volatile
        private var activeService: ReportService? = null

        /** 服务内 WS 客户端当前连接状态（供首页直接读取） */
        @Volatile
        var currentState: String = WsClient.STATE_OFFLINE
            private set

        // 界面订阅者：服务连接状态变化时实时回调，首页无需等到 onResume 才刷新。
        // 回调可能来自 WS 的后台线程，订阅方自行切回主线程。
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<WsClient.Listener>()

        fun addListener(l: WsClient.Listener) {
            if (!listeners.contains(l)) listeners.add(l)
        }

        fun removeListener(l: WsClient.Listener) {
            listeners.remove(l)
        }

        fun start(ctx: Context) {
            val intent = Intent(ctx, ReportService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {
                // 后台拉起前台服务在部分系统上会被限制，失败不应导致崩溃
                LogStore.log("service", "拉起上报服务失败：" + (e.message ?: "未知错误"))
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, ReportService::class.java))
            } catch (e: Exception) {
                LogStore.log("service", "停止上报服务失败：" + (e.message ?: "未知错误"))
            }
        }

        /** App 回到前台时调用：服务 WS 若在退避等待则跳过剩余等待立即重连 */
        fun reconnectNow() {
            activeService?.wsClient?.reconnectNow()
        }
    }

    private var wsClient: WsClient? = null

    /** 手表测量完成回调：服务存活时立刻补一次上报，界面是否在前台都不影响 */
    private val measuredListener: (String) -> Unit = { label ->
        wsClient?.reportNow("手表${label}测量完成")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        activeService = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LogStore.log("service", "收到停止指令（startId=$startId）")
            stopSelf()
            return START_NOT_STICKY
        }
        // null intent = 系统重启服务（START_STICKY），或首次启动：都直接初始化
        if (!Prefs.consent) {
            LogStore.log("service", "未同意隐私政策，不启动上报")
            stopSelf()
            return START_NOT_STICKY
        }
        val alreadyRunning = isRunning
        if (!alreadyRunning) {
            LogStore.markRestart(
                if (intent == null) "启动（系统重建，START_STICKY）" else "启动（第 $startId 次）"
            )
        }
        val notif = buildNotification(getString(R.string.service_connecting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
        scheduleWatchdog()
        applyShizukuOptimizationsOnce()

        if (alreadyRunning && wsClient != null) return START_STICKY
        wsClient?.stop()
        wsClient = WsClient(applicationContext, this).also { it.start() }
        scheduleFallbackWork()
        isRunning = true
        currentState = WsClient.STATE_CONNECTING
        WatchManager.addMeasureListener(measuredListener)
        // START_STICKY：进程被杀后系统重建服务（intent 为 null），上面已处理
        return START_STICKY
    }

    override fun onDestroy() {
        LogStore.log("service", "上报服务停止（isRunning=${isRunning}）")
        WatchManager.removeMeasureListener(measuredListener)
        wsClient?.stop()
        wsClient = null
        if (activeService === this) activeService = null
        isRunning = false
        currentState = WsClient.STATE_OFFLINE
        for (l in listeners) l.onStateChanged(WsClient.STATE_OFFLINE)
        super.onDestroy()
    }

    // 从最近任务里划掉后仍保持上报：已同意隐私且配置完整时，重新拉起前台服务。
    // 部分厂商杀进程较激进，配合 START_STICKY 与电池优化白名单可显著提升存活率。
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Prefs.consent && Prefs.isConfigured()) {
            LogStore.log("service", "任务被划掉，重新拉起上报服务")
            start(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    // ---- WsClient.Listener ----

    override fun onStateChanged(state: String) {
        currentState = state
        val text = when (state) {
            WsClient.STATE_ONLINE -> getString(R.string.service_online)
            WsClient.STATE_CONNECTING -> getString(R.string.service_connecting)
            WsClient.STATE_ERROR -> getString(R.string.service_error)
            else -> getString(R.string.service_offline)
        }
        LogStore.log("state", text)
        updateNotification(text)
        for (l in listeners) l.onStateChanged(state)
    }

    override fun onServiceDegraded(reason: String) {
        LogStore.log("service", reason)
        updateNotification(reason)
        for (l in listeners) l.onServiceDegraded(reason)
    }

    /** 每 30 分钟检查服务存活：被激进 ROM 杀掉后由 WatchdogReceiver 重新拉起。
     *  用非唤醒闹钟（ELAPSED_REALTIME）：设备休眠时不主动唤醒 CPU，只在已醒时执行检查。 */
    private fun scheduleWatchdog() {
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                this,
                1,
                Intent(this, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 30 * 60_000L,
                30 * 60_000L,
                pi,
            )
        } catch (e: Exception) {
            LogStore.log("service", "看门狗闹钟设置失败：" + (e.message ?: "未知错误"))
        }
    }

    /** Shizuku 已授权时应用一次后台保活命令（幂等，放后台线程，失败不影响上报） */
    private fun applyShizukuOptimizationsOnce() {
        if (!ShizukuHelper.hasPermission()) return
        Thread {
            try {
                ShizukuHelper.applyBackgroundOptimizations(applicationContext)
            } catch (_: Throwable) {
                // 保活优化失败不影响正常上报
            }
        }.start()
    }

    // ---- notification ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        // 点击通知直接回到主界面，常驻通知同时也是进入应用的快捷入口
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mugshot)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ---- WorkManager HTTP 兜底上报（WS 不可用 / 服务被杀后仍然工作） ----

    private fun scheduleFallbackWork() {
        val request = PeriodicWorkRequestBuilder<ReportWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "mugshot_http_report",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        LogStore.log("service", "HTTP 兜底上报已注册（每 15 分钟，网络可用时执行）")
    }
}
