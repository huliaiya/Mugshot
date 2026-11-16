package com.mugshot.app

import android.content.Context
import com.mugshot.app.watch.WatchManager
import org.json.JSONArray
import org.json.JSONObject

/** 状态采集结果 → 上报 payload（WebSocket 与 WorkManager 兜底共用） */
object ReportPayloadBuilder {
    fun build(ctx: Context, s: StatusCollector.Snapshot): JSONObject = JSONObject().apply {
        put("screen_on", s.screenOn)
        put("foreground_app", s.foregroundApp)
        put("foreground_package", s.foregroundPackage)
        if (s.foregroundApps.size >= 2) {
            val arr = JSONArray()
            for ((label, pkg) in s.foregroundApps) {
                arr.put(JSONObject().put("app", label).put("package", pkg))
            }
            put("foreground_apps", arr)
        }
        if (s.usage24h.isNotEmpty()) {
            val usageArr = JSONArray()
            for (u in s.usage24h) {
                usageArr.put(
                    JSONObject()
                        .put("pkg", u.pkg)
                        .put("app", u.app)
                        .put("min", u.min)
                        .put("count", u.count),
                )
            }
            put("usage_24h", usageArr)
        }
        if (s.foregroundActivity.isNotBlank()) put("foreground_activity", s.foregroundActivity)
        if (s.backgroundApps.isNotEmpty()) {
            val bgArr = JSONArray()
            for ((label, pkg) in s.backgroundApps) {
                bgArr.put(JSONObject().put("app", label).put("package", pkg))
            }
            put("background_apps", bgArr)
        }
        if (s.batteryTempC > 0) put("battery_temp_c", s.batteryTempC)
        put("battery_level", s.batteryLevel)
        put("battery_status", s.batteryStatus)
        put("charging", s.charging)
        put("charging_source", s.chargingSource)
        put("network_type", s.networkType)
        put("network_detail", s.networkDetail)
        put("device_model", s.deviceModel)
        put("app_version", s.appVersion)
        watchJson(ctx)?.let { put("watch", it) }
    }

    /** 手表数据：仅在用户开启手表连接时上报，字段为空时不写入，减小报文 */
    private fun watchJson(ctx: Context): JSONObject? {
        val w = WatchManager.snapshot()
        if (!w.enabled) return null
        return JSONObject().apply {
            put("connected", w.connected)
            if (w.state == WatchManager.STATE_CONNECTED) {
                if (w.name.isNotBlank()) put("name", w.name)
                if (w.battery >= 0) put("battery", w.battery)
                if (w.firmware.isNotBlank()) put("firmware", w.firmware)
                if (w.project.isNotBlank()) put("project", w.project)
                if (w.protocol.isNotBlank()) put("protocol", w.protocol)
                if (w.features.isNotBlank()) put("features", w.features)
                if (w.steps >= 0) put("steps", w.steps)
                if (w.distanceM >= 0) put("distance_m", w.distanceM)
                if (w.kcal >= 0) put("kcal", w.kcal)
                if (w.heartRate >= 0) put("heart_rate", w.heartRate)
                if (w.bpSys >= 0 && w.bpDia >= 0) put("blood_pressure", "${w.bpSys}/${w.bpDia}")
                if (w.spo2 >= 0) put("spo2", w.spo2)
                if (w.sleepMin >= 0) put("sleep_min", w.sleepMin)
                w.sedentaryOn?.let { put("sedentary_on", it) }
                w.raiseWristOn?.let { put("raise_wrist_on", it) }
            }
            if (w.updatedAt > 0) put("updated_at", w.updatedAt)
        }
    }
}
