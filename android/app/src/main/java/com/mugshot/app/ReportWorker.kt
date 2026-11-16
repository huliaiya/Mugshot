package com.mugshot.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP 兜底上报 Worker：POST /api/v1/report（Bearer Token）。
 * WS 不可用或 Android 15 dataSync 前台服务 6 小时配额耗尽后的降级通道。
 */
class ReportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!Prefs.consent || !Prefs.isConfigured()) return@withContext Result.success()
        // WS 长连接正常时定时上报足够新鲜，跳过本次 HTTP 兜底，避免每 15 分钟
        // 重复采集 + 建立一次 HTTPS 连接（射频唤醒的主要来源之一）。
        val fresh = Prefs.lastReportTs > 0 &&
            System.currentTimeMillis() - Prefs.lastReportTs < 10 * 60_000L
        if (fresh) {
            LogStore.log("worker", "WS 上报新鲜（${(System.currentTimeMillis() - Prefs.lastReportTs) / 1000}s 前），跳过 HTTP 兜底")
            return@withContext Result.success()
        }
        try {
            val snapshot = StatusCollector.collect(applicationContext)
            val payload = ReportPayloadBuilder.build(applicationContext, snapshot)
            val body = JSONObject().apply {
                put("type", "report")
                put("device_id", Prefs.deviceId)
                put("device_name", Prefs.deviceName)
                put("timestamp", System.currentTimeMillis())
                put("payload", payload)
            }
            val base = Prefs.serverUrl.trim().trimEnd('/')
            val normalizedBase = when {
                base.startsWith("http://") || base.startsWith("https://") -> base
                else -> "https://$base"
            }
            val url = URL(normalizedBase + "/api/v1/report")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${Prefs.deviceToken}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                LogStore.log("worker", "HTTP 兜底上报成功：" + StatusCollector.describe(applicationContext, snapshot))
                Result.success()
            } else {
                LogStore.log("worker", "HTTP 兜底上报失败 HTTP $code，等待重试")
                Result.retry()
            }
        } catch (e: Exception) {
            LogStore.log("worker", "HTTP 兜底上报异常：" + (e.message ?: "未知错误"))
            Result.retry()
        }
    }
}
