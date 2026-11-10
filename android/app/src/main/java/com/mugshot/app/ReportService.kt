package com.mugshot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager

/**
 * 设备状态上报前台服务（dataSync 类型）。
 *
 * Android 15 起 dataSync 类型前台服务每日累计运行约 6 小时配额，超时后系统强制停止。
 * 服务被停后：
 *  - WorkManager 的 HTTP 周期上报继续兜底（ReportWorker → POST /api/v1/report）
 *  - 下次用户打开 App 时会重新拉起服务
 */
class ReportService : Service(), WsClient.Listener {

    companion object {
        const val CHANNEL_ID = "mugshot_status"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.mugshot.app.START"
        const val ACTION_STOP = "com.mugshot.app.STOP"

        var isRunning = false
            private set

        fun start(ctx: Context) {
            val intent = Intent(ctx, ReportService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ReportService::class.java))
        }
    }

    private var wsClient: WsClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // null intent = 系统重启服务（START_STICKY），或首次启动：都直接初始化
        if (!Prefs.consent) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.service_connecting)))

        wsClient?.stop()
        wsClient = WsClient(applicationContext, this).also { it.start() }
        scheduleFallbackWork()
        isRunning = true
        // START_STICKY：进程被杀后系统重建服务（intent 为 null），上面已处理
        return START_STICKY
    }

    override fun onDestroy() {
        wsClient?.stop()
        wsClient = null
        isRunning = false
        super.onDestroy()
    }

    // ---- WsClient.Listener ----

    override fun onStateChanged(state: String) {
        val text = when (state) {
            WsClient.STATE_ONLINE -> getString(R.string.service_online)
            WsClient.STATE_CONNECTING -> getString(R.string.service_connecting)
            WsClient.STATE_ERROR -> getString(R.string.service_error)
            else -> getString(R.string.service_offline)
        }
        updateNotification(text)
    }

    override fun onServiceDegraded(reason: String) {
        updateNotification(reason)
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

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mugshot)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ---- WorkManager HTTP 兜底上报（WS 不可用 / dataSync 配额耗尽后仍然工作） ----

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
    }
}
