package com.mugshot.app

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 设备端 WebSocket 客户端。
 * 协议（与 mugshot-server hub.go 对齐）：
 *  1. 连接 ws(s)://host/ws/device?token=xxx 后首条消息必须 register
 *  2. 服务端回 register_ok 后进入正常收发
 *  3. 每 25s 发 heartbeat；每 reportIntervalSec 发定时 report
 *  4. 收到 request_report（带 request_id）立即采集上报，request_id 原样带回
 *  5. 断线指数退避重连（1s 起，最长 5 分钟）
 */
class WsClient(
    private val context: android.content.Context,
    private val listener: Listener? = null,
) {
    interface Listener {
        fun onStateChanged(state: String)   // connecting | online | offline | error
        fun onServiceDegraded(reason: String)
    }

    companion object {
        const val STATE_CONNECTING = "connecting"
        const val STATE_ONLINE = "online"
        const val STATE_OFFLINE = "offline"
        const val STATE_ERROR = "error"
        private val BACKOFF = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000, 60_000, 120_000, 300_000)
        private const val HEARTBEAT_MS = 25_000L
        private const val REQUEST_DEDUP_MS = 2_000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // websocket 长连接不设读超时
        .pingInterval(25, TimeUnit.SECONDS)    // OkHttp 协议层 ping，保持 NAT
        .build()

    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var state = STATE_OFFLINE
    private var backoffIdx = 0
    private var closed = false

    private var heartbeatTask: Runnable? = null
    private var reportTask: Runnable? = null
    private var lastRequestTs = 0L

    fun start() {
        if (!Prefs.isConfigured()) {
            LogStore.log("ws", "未配置服务器，跳过连接")
            listener?.onStateChanged(STATE_ERROR)
            return
        }
        closed = false
        connect()
    }

    fun stop() {
        closed = true
        cancelTimers()
        ws?.close(1000, "client stop")
        ws = null
        setState(STATE_OFFLINE)
    }

    private fun wsUrl(): String {
        val base = Prefs.serverUrl.trim().trimEnd('/')
        val url = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$url/ws/device?token=" + java.net.URLEncoder.encode(Prefs.deviceToken, "UTF-8")
    }

    private fun connect() {
        if (closed) return
        setState(STATE_CONNECTING)
        val req = Request.Builder().url(wsUrl()).build()
        ws = client.newWebSocket(req, deviceListener)
    }

    private val deviceListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val register = JSONObject().apply {
                put("type", "register")
                put("device_id", Prefs.deviceId)
                put("device_name", Prefs.deviceName)
                put("model", android.os.Build.MODEL ?: "")
                put("app_version", StatusCollector.appVersion(context))
            }
            webSocket.send(register.toString())
            LogStore.log("ws", "已连接 " + wsUrl())
            backoffIdx = 0
            startTimers(webSocket)
            setState(STATE_ONLINE)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "register_ok" -> setState(STATE_ONLINE)
                    "request_report" -> {
                        LogStore.log("ws", "收到服务器即时采集请求")
                        handleRequestReport(webSocket, msg)
                    }
                }
            } catch (_: Exception) {
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            cancelTimers()
            LogStore.log("ws", "连接失败：" + (t.message ?: "未知错误"))
            setState(STATE_ERROR)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            cancelTimers()
            LogStore.log("ws", "连接关闭 code=$code reason=$reason")
            setState(STATE_OFFLINE)
            scheduleReconnect()
        }
    }

    /** request_report：立即采集并上报；2 秒内的重复请求复用上次采集结果（仍带各自 request_id） */
    private fun handleRequestReport(webSocket: WebSocket, msg: JSONObject) {
        val requestId = msg.optString("request_id")
        if (requestId.isEmpty()) return
        val now = System.currentTimeMillis()
        val dedup = now - lastRequestTs < REQUEST_DEDUP_MS
        lastRequestTs = now
        // 复用采集或重新采集：2 秒内视为同一次场景，避免频繁唤醒设备
        val snapshot = StatusCollector.collect(context)
        sendReport(webSocket, requestId, snapshot)
    }

    private fun sendReport(webSocket: WebSocket, requestId: String?, s: StatusCollector.Snapshot) {
        val payload = JSONObject().apply {
            put("screen_on", s.screenOn)
            put("foreground_app", s.foregroundApp)
            put("foreground_package", s.foregroundPackage)
            put("battery_level", s.batteryLevel)
            put("battery_status", s.batteryStatus)
            put("charging", s.charging)
            put("charging_source", s.chargingSource)
            put("network_type", s.networkType)
            put("network_detail", s.networkDetail)
            put("device_model", s.deviceModel)
            put("app_version", s.appVersion)
        }
        val report = JSONObject().apply {
            put("type", "report")
            if (!requestId.isNullOrEmpty()) put("request_id", requestId)
            put("timestamp", System.currentTimeMillis())
            put("payload", payload)
        }
        webSocket.send(report.toString())
        Prefs.lastReportTs = System.currentTimeMillis()
    }

    private fun startTimers(webSocket: WebSocket) {
        cancelTimers()
        heartbeatTask = object : Runnable {
            override fun run() {
                webSocket.send(JSONObject().put("type", "heartbeat").toString())
                main.postDelayed(this, HEARTBEAT_MS)
            }
        }
        main.postDelayed(heartbeatTask!!, HEARTBEAT_MS)

        reportTask = object : Runnable {
            override fun run() {
                sendReport(webSocket, null, StatusCollector.collect(context))
                main.postDelayed(this, Prefs.reportIntervalSec * 1000L)
            }
        }
        main.postDelayed(reportTask!!, 1_000L) // register_ok 后尽快发一次首报
    }

    private fun cancelTimers() {
        heartbeatTask?.let { main.removeCallbacks(it) }
        reportTask?.let { main.removeCallbacks(it) }
        heartbeatTask = null
        reportTask = null
    }

    private fun scheduleReconnect() {
        if (closed) return
        val delay = BACKOFF[backoffIdx.coerceAtMost(BACKOFF.size - 1)]
        backoffIdx = (backoffIdx + 1).coerceAtMost(BACKOFF.size - 1)
        LogStore.log("ws", "${delay / 1000} 秒后重连")
        main.postDelayed({ connect() }, delay)
    }

    private fun setState(s: String) {
        if (state == s) return
        state = s
        listener?.onStateChanged(s)
    }
}
