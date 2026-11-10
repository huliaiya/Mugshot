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
 * HTTP 兜底上报 Worker：POST /api/v1/report（Bearer device_token）。
 * WS 不可用或 Android 15 dataSync 前台服务 6 小时配额耗尽后的降级通道。
 */
class ReportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!Prefs.consent || !Prefs.isConfigured()) return@withContext Result.success()
        try {
            val snapshot = StatusCollector.collect(applicationContext)
            val payload = JSONObject().apply {
                put("screen_on", snapshot.screenOn)
                put("foreground_app", snapshot.foregroundApp)
                put("foreground_package", snapshot.foregroundPackage)
                put("battery_level", snapshot.batteryLevel)
                put("battery_status", snapshot.batteryStatus)
                put("charging", snapshot.charging)
                put("charging_source", snapshot.chargingSource)
                put("network_type", snapshot.networkType)
                put("network_detail", snapshot.networkDetail)
                put("device_model", snapshot.deviceModel)
                put("app_version", snapshot.appVersion)
            }
            val body = JSONObject().apply {
                put("type", "report")
                put("device_id", Prefs.deviceId)
                put("device_name", Prefs.deviceName)
                put("timestamp", System.currentTimeMillis())
                put("payload", payload)
            }
            val url = URL(Prefs.serverUrl.trimEnd('/') + "/api/v1/report")
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
                LogStore.log("worker", "HTTP 兜底上报成功")
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
