package com.mugshot.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
 *  5. 断线指数退避重连（1s 起，最长 5 分钟）；网络恢复/切换时立即重连
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

    // 网络状态监听：网络恢复/切换时立即重连，不必等指数退避计时
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var netSeenOnce = false
    private var reconnectRunnable: Runnable? = null

    fun start() {
        if (!Prefs.isConfigured()) {
            LogStore.log("ws", "未配置服务器，跳过连接")
            listener?.onStateChanged(STATE_ERROR)
            return
        }
        closed = false
        netSeenOnce = false
        connect()
        registerNetworkCallback()
    }

    fun stop() {
        closed = true
        cancelReconnect()
        unregisterNetworkCallback()
        cancelTimers()
        ws?.close(1000, "client stop")
        ws = null
        setState(STATE_OFFLINE)
    }

    private fun wsUrl(): String {
        val raw = Prefs.serverUrl.trim().trimEnd('/')
        val base = when {
            raw.startsWith("https://") || raw.startsWith("wss://") -> raw
            raw.startsWith("http://") || raw.startsWith("ws://") -> raw
            // 无 scheme（如 `1.2.3.4:8080`、`mugshot.example.com`）：缺省按 https 处理，
            // 否则 OkHttp 收到的 URL 不合法会直接连不上
            else -> "https://$raw"
        }
        val url = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            base.startsWith("wss://") -> base
            base.startsWith("ws://") -> base
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
            if (webSocket !== ws) return
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
            if (webSocket !== ws) return
            try {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "register_ok" -> setState(STATE_ONLINE)
                    "request_report" -> {
                        val rid = msg.optString("request_id")
                        LogStore.log("ws", "收到服务器主动采集请求（request_id=" + (if (rid.isEmpty()) "无" else "已脱敏") + "），立即采集上报")
                        handleRequestReport(webSocket, msg)
                    }
                }
            } catch (_: Exception) {
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            cancelTimers()
            LogStore.log("ws", "连接失败：" + (t.message ?: "未知错误"))
            setState(STATE_ERROR)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
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
        if (!StatusCollector.hasUsageAccess(context)) {
            LogStore.log("ws", "提示：未授予「使用情况访问权限」，前台应用字段将为空；可到 设置→使用情况访问 授权")
        }
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
        val kind = if (!requestId.isNullOrEmpty()) "响应服务器主动要求" else "定时上报"
        LogStore.log("ws", kind + "已上报：" + StatusCollector.describe(context))
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
        cancelReconnect()
        val delay = BACKOFF[backoffIdx.coerceAtMost(BACKOFF.size - 1)]
        backoffIdx = (backoffIdx + 1).coerceAtMost(BACKOFF.size - 1)
        LogStore.log("ws", "${delay / 1000} 秒后重连")
        val r = Runnable {
            reconnectRunnable = null
            connect()
        }
        reconnectRunnable = r
        main.postDelayed(r, delay)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { main.removeCallbacks(it) }
        reconnectRunnable = null
    }

    // ---- 网络状态监听 ----

    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    if (closed) return@post
                    if (!netSeenOnce) {
                        // 首次回调只是注册时的当前网络快照：仅当尚未连上时才触发重连
                        netSeenOnce = true
                        if (state == STATE_OFFLINE || state == STATE_ERROR) forceReconnect()
                        return@post
                    }
                    LogStore.log("ws", "网络已恢复，立即重连")
                    forceReconnect()
                }
            }
        }
        netCallback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (e: Exception) {
            netCallback = null
            LogStore.log("ws", "网络状态监听注册失败：" + (e.message ?: "未知错误"))
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    /** 立即重连：取消等待中的退避计时、复位退避；若已有连接则先关闭旧连接。 */
    private fun forceReconnect() {
        if (closed) return
        cancelReconnect()
        backoffIdx = 0
        val old = ws
        ws = null
        old?.close(1000, "network change")
        cancelTimers()
        connect()
    }

    private fun setState(s: String) {
        if (state == s) return
        state = s
        listener?.onStateChanged(s)
    }
}
