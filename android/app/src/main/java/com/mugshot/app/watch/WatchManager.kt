package com.mugshot.app.watch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.mugshot.app.LogStore
import com.mugshot.app.Prefs
import java.util.UUID

/**
 * HryFine 手表连接管理：扫描 → GATT 连接 → 读设备信息 → 绑定/校时 → 查询设置与健康数据。
 * 单例运行在应用进程内，前台服务与界面共用；数据只读，不改表端设置。
 */
object WatchManager {
    const val STATE_DISABLED = "disabled"
    const val STATE_SCANNING = "scanning"
    const val STATE_CONNECTING = "connecting"
    const val STATE_BINDING = "binding"
    const val STATE_CONNECTED = "connected"
    const val STATE_ERROR = "error"

    data class Candidate(val name: String, val address: String, val rssi: Int, val likelyWatch: Boolean = false)

    data class Snapshot(
        val enabled: Boolean = false,
        val state: String = STATE_DISABLED,
        val name: String = "",
        val address: String = "",
        val battery: Int = -1,
        val firmware: String = "",
        val project: String = "",
        val protocol: String = "",
        val features: String = "",
        val steps: Int = -1,
        val distanceM: Int = -1,
        val kcal: Int = -1,
        val heartRate: Int = -1,
        val bpSys: Int = -1,
        val bpDia: Int = -1,
        val spo2: Int = -1,
        val sleepMin: Int = -1,
        val sedentaryOn: Boolean? = null,
        val raiseWristOn: Boolean? = null,
        val detail: String = "",
        val updatedAt: Long = 0,
        val candidates: List<Candidate> = emptyList(),
    ) {
        val connected: Boolean get() = state == STATE_CONNECTED
    }

    private const val SCAN_TIMEOUT_MS = 15000L
    private const val CHUNK_SIZE = 20
    private const val WRITE_RETRY_MS = 80L
    private const val WRITE_TIMEOUT_MS = 400L
    private const val CONNECT_TIMEOUT_MS = 15000L
    private const val CANDIDATE_PUBLISH_MS = 400L
    private const val MEASURE_TIMEOUT_MS = 120_000L
    private const val STEP_REFRESH_MS = 60_000L
    private val RECONNECT_DELAYS = longArrayOf(5_000, 10_000, 20_000, 40_000, 60_000)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val measureListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()
    private val syncListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private lateinit var ctx: Context
    private var initialized = false

    @Volatile
    private var snap = Snapshot()

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanning = false
    private var lastCandidatePublish = 0L
    private val candidates = LinkedHashMap<String, Candidate>()
    private var gatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var batteryChar: BluetoothGattCharacteristic? = null

    private val reassembler = HryFineProtocol.Reassembler()
    private var version = 0x01
    private var features = HryFineProtocol.Features(false, false, false, false, false)
    private var health = HryFineProtocol.Health()
    private var settings: Map<Int, ByteArray> = emptyMap()
    private var lastFirmware = ""
    private var lastProject = ""
    private var bound = false
    private var connectedDevice: android.bluetooth.BluetoothDevice? = null
    private val writeQueue = ArrayDeque<Pair<ByteArray, String>>()
    private var writeBusy = false
    private var reconnectIdx = 0
    private var suppressReconnect = false
    private var reconnectScheduled = false
    private var quickDrops = 0
    private var connectedAt = 0L
    private var userStopped = false
    private var readQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var readStarted = false
    private val readTimeout = Runnable { readNext() }
    private val connectTimeout = Runnable {
        if (snap.state == STATE_CONNECTING || snap.state == STATE_BINDING) {
            LogStore.log("watch", "连接超时，断开后重试")
            failAndRetry("连接手表超时，请确认手表在附近并已开机")
        }
    }

    private val writeTick = Runnable {
        writeBusy = false
        pumpWrite()
    }

    private fun pumpWrite() {
        if (writeBusy) return
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null) {
            writeQueue.clear()
            return
        }
        val item = writeQueue.removeFirstOrNull() ?: return
        writeBusy = true
        if (!writeBytes(g, ch, item.first, item.second)) {
            // 上一包还没写完，GATT 会拒绝新写入：放回队首，稍后重试，避免丢帧
            writeQueue.addFirst(item)
            main.postDelayed(writeTick, WRITE_RETRY_MS)
            return
        }
        // 正常情况等 onCharacteristicWrite 回调释放；部分设备不回回调，用超时兜底
        main.postDelayed(writeTick, WRITE_TIMEOUT_MS)
    }

    private fun failAndRetry(message: String) {
        suppressReconnect = true
        disconnectGatt()
        setState(STATE_ERROR, message)
        scheduleReconnect()
    }

    fun init(context: Context) {
        if (initialized) return
        ctx = context.applicationContext
        initialized = true
        snap = snap.copy(enabled = Prefs.watchEnabled)
        if (Prefs.watchEnabled && Prefs.consent) startInternal(auto = true)
    }

    fun addListener(l: (Snapshot) -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
        l(snapshot())
    }

    fun removeListener(l: (Snapshot) -> Unit) {
        listeners.remove(l)
    }

    /** 测量完成回调（参数为「心率」「血压」「血氧」等指标名），用于即时上报 */
    fun addMeasureListener(l: (String) -> Unit) {
        if (!measureListeners.contains(l)) measureListeners.add(l)
    }

    fun removeMeasureListener(l: (String) -> Unit) {
        measureListeners.remove(l)
    }

    /** 健康数据同步完成回调：让上报服务立刻补一次上报，插件不必等下一轮定时上报 */
    fun addSyncListener(l: () -> Unit) {
        if (!syncListeners.contains(l)) syncListeners.add(l)
    }

    fun removeSyncListener(l: () -> Unit) {
        syncListeners.remove(l)
    }

    private fun notifySynced() {
        if (syncListeners.isEmpty()) return
        main.post { syncListeners.forEach { it() } }
    }

    fun snapshot(): Snapshot = snap.copy(enabled = Prefs.watchEnabled)

    fun setEnabled(enabled: Boolean) {
        Prefs.watchEnabled = enabled
        if (enabled) {
            startInternal(auto = false)
            refreshAutoSync()
        } else {
            stopAutoSync()
            stopInternal()
        }
        publish()
    }

    /** 立即同步一次（已连接则拉取健康数据，否则尝试回连/扫描） */
    fun refreshNow() {
        if (!Prefs.watchEnabled) return
        val g = gatt
        if (g != null && snap.state == STATE_CONNECTED) {
            requestBattery()
            syncHealth()
        } else {
            reconnectIdx = 0
            connectSavedOrScan()
        }
    }

    // ---- 自动同步（由上报服务驱动）----
    // 用主线程 Handler 定时：服务常驻期间按用户设置的间隔拉取健康数据，
    // 并按开关依次测量心率/血压/血氧。进程被回收后由服务重启重新注册。
    private val autoHandler = Handler(Looper.getMainLooper())
    private var autoScheduled = false
    private val autoTick = object : Runnable {
        override fun run() {
            autoScheduled = false
            autoSyncOnce()
            scheduleNextAuto(Prefs.watchAutoSyncMin * 60_000L)
        }
    }

    /** 上一次记录的自动同步配置，用于避免重复日志 */
    private var lastAutoKey = ""

    /** 设置变更或服务启动时调用：重建自动同步定时任务 */
    fun refreshAutoSync() {
        autoHandler.removeCallbacks(autoTick)
        autoScheduled = false
        val on = Prefs.watchEnabled && Prefs.watchAutoSync
        val key = if (!on) "off" else "on|${Prefs.watchAutoSyncMin}|${Prefs.watchAutoHeart}|${Prefs.watchAutoBp}|${Prefs.watchAutoSpo2}"
        if (key != lastAutoKey) {
            lastAutoKey = key
            if (on) {
                val items = buildList {
                    if (Prefs.watchAutoHeart) add("心率")
                    if (Prefs.watchAutoBp) add("血压")
                    if (Prefs.watchAutoSpo2) add("血氧")
                }.joinToString("、").ifEmpty { "不测量" }
                LogStore.log("watch", "手表自动同步已开启：每 ${Prefs.watchAutoSyncMin} 分钟，自动测量 $items")
            } else {
                LogStore.log("watch", "手表自动同步未启用")
            }
        }
        if (on) scheduleNextAuto(10_000L) // 开启后先尽快同步一次
    }

    fun stopAutoSync() {
        autoHandler.removeCallbacks(autoTick)
        autoScheduled = false
    }

    private fun scheduleNextAuto(delayMs: Long) {
        if (!Prefs.watchEnabled || !Prefs.watchAutoSync || autoScheduled) return
        autoScheduled = true
        autoHandler.postDelayed(autoTick, delayMs.coerceAtLeast(5_000L))
    }

    private fun autoSyncOnce() {
        if (!Prefs.watchEnabled || !Prefs.watchAutoSync) return
        if (snap.state != STATE_CONNECTED) {
            // 未连接时尝试回连；连接成功后本次同步由连接流程补做
            if (Prefs.watchAddress.isNotBlank() && !userStopped && !reconnectScheduled) {
                LogStore.log("watch", "自动同步：手表未连接，尝试回连")
                reconnectIdx = 0
                connectSavedOrScan()
            }
            return
        }
        LogStore.log("watch", "自动同步：拉取健康数据")
        requestBattery()
        syncHealth()
        scheduleAutoMeasure()
    }

    private fun scheduleAutoMeasure() {
        val subs = ArrayList<Int>(3)
        if (Prefs.watchAutoHeart) subs.add(HryFineProtocol.SUB_MEASURE_HEART)
        if (Prefs.watchAutoBp) subs.add(HryFineProtocol.SUB_MEASURE_BP)
        if (Prefs.watchAutoSpo2) subs.add(HryFineProtocol.SUB_MEASURE_SPO2)
        if (subs.isEmpty()) return
        // 与 syncHealth 的多条请求错开：同步约 4 秒完成，之后每 30 秒测一项，
        // 避免上一项测量未回包就被下一项覆盖 pendingMeasureLabel。
        subs.forEachIndexed { i, sub ->
            main.postDelayed({
                if (Prefs.watchEnabled && Prefs.watchAutoSync && snap.state == STATE_CONNECTED) {
                    measure(sub)
                }
            }, 8_000L + i * 30_000L)
        }
    }

    fun shutdown() {
        if (Prefs.watchEnabled) {
            // 保持配置，仅断开本次连接（应用退出时由系统回收）
            disconnectGatt()
        }
    }

    private fun startInternal(auto: Boolean) {
        if (!Prefs.consent) return
        userStopped = false
        if (!hasPermissions()) {
            setState(STATE_ERROR, "缺少蓝牙权限，请在首页重新开启手表连接并授权")
            return
        }
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            setState(STATE_ERROR, "系统蓝牙未开启")
            return
        }
        if (auto) connectSavedOrScan() else {
            reconnectIdx = 0
            connectSavedOrScan()
        }
    }

    private fun stopInternal() {
        userStopped = true
        main.removeCallbacksAndMessages(null)
        autoScheduled = false
        stepTicking = false
        stopScan()
        disconnectGatt()
        health = HryFineProtocol.Health()
        settings = emptyMap()
        lastFirmware = ""
        lastProject = ""
        candidates.clear()
        snap = snap.copy(candidates = emptyList())
        setState(STATE_DISABLED, "")
    }

    private fun adapter(): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun connectSavedOrScan() {
        if (!Prefs.watchEnabled || userStopped) return
        // 已在连接/绑定/已连接时忽略重复触发，避免把正常连接打断
        if (snap.state == STATE_CONNECTED || snap.state == STATE_CONNECTING || snap.state == STATE_BINDING) {
            if (snap.state != STATE_BINDING || gatt != null) return
        }
        if (!hasPermissions()) {
            setState(STATE_ERROR, "缺少蓝牙权限")
            return
        }
        val saved = Prefs.watchAddress
        if (saved.isNotEmpty()) {
            try {
                val dev = adapter()?.getRemoteDevice(saved)
                if (dev != null) {
                    connect(dev)
                    return
                }
            } catch (e: Exception) {
                LogStore.log("watch", "回连失败，改为扫描：" + (e.message ?: "未知错误"))
            }
        }
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        stopScan()
        val le = adapter()?.bluetoothLeScanner
        if (le == null) {
            setState(STATE_ERROR, "当前设备不支持 BLE 扫描")
            return
        }
        scanner = le
        scanning = true
        candidates.clear()
        lastCandidatePublish = 0
        snap = snap.copy(candidates = emptyList())
        setState(STATE_SCANNING, "正在扫描附近手表…")
        LogStore.log("watch", "开始扫描 BLE 设备（列表会显示附近可连接的设备）")
        try {
            le.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        } catch (e: Exception) {
            setState(STATE_ERROR, "启动扫描失败：" + (e.message ?: "未知错误"))
            return
        }
        main.postDelayed({
            if (scanning) {
                stopScan()
                publishCandidates()
                if (candidates.isEmpty()) {
                    setState(STATE_ERROR, "未发现蓝牙设备，请确认手表已开机并靠近手机")
                } else {
                    setState(STATE_SCANNING, "请选择要连接的手表")
                }
            }
        }, SCAN_TIMEOUT_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val name = safeName(result)
            val saved = result.device.address == Prefs.watchAddress
            val watch = looksLikeWatch(result)
            if (name.isBlank() && !saved && !watch) return
            val known = candidates.containsKey(result.device.address)
            candidates[result.device.address] = Candidate(name, result.device.address, result.rssi, watch)
            // 低延迟扫描回调很密集，节流后再刷新界面，避免按钮反复重建
            val now = System.currentTimeMillis()
            if (!known || now - lastCandidatePublish >= CANDIDATE_PUBLISH_MS) {
                lastCandidatePublish = now
                publishCandidates()
            }
            // 已保存的设备直接回连；首次使用则由用户在列表中手动选择
            if (saved) {
                LogStore.log("watch", "发现已保存的手表 $name (${result.device.address})")
                connectCandidate(result.device.address, name)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            setState(STATE_ERROR, "扫描失败（错误码 $errorCode）")
        }
    }

    private fun publishCandidates() {
        val ordered = candidates.values.sortedWith(
            compareByDescending<Candidate> { it.likelyWatch || it.address == Prefs.watchAddress }.thenByDescending { it.rssi },
        )
        snap = snap.copy(candidates = ordered.take(12))
        publish()
    }

    /** 用户在界面上手动选择设备后连接 */
    fun connectCandidate(address: String, name: String) {
        if (!Prefs.watchEnabled || userStopped) return
        val dev = try {
            adapter()?.getRemoteDevice(address)
        } catch (e: Exception) {
            LogStore.log("watch", "无法获取设备 $address：" + (e.message ?: "未知错误"))
            null
        } ?: return
        stopScan()
        reconnectIdx = 0
        Prefs.watchAddress = address
        if (name.isNotBlank()) Prefs.watchName = name
        connect(dev)
    }

    fun rescan(disconnect: Boolean = false) {
        if (!Prefs.watchEnabled || userStopped) return
        reconnectIdx = 0
        if (disconnect) {
            suppressReconnect = true
            disconnectGatt()
        }
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun safeName(result: ScanResult): String =
        result.scanRecord?.deviceName ?: try {
            result.device.name
        } catch (_: Exception) {
            null
        } ?: ""

    private fun looksLikeWatch(result: ScanResult): Boolean {
        val record = result.scanRecord
        val uuids = record?.serviceUuids?.map { it.uuid.toString().lowercase() } ?: emptyList()
        if (uuids.contains(HryFineProtocol.SERVICE_UUID)) return true
        val name = safeName(result).lowercase()
        if (name.contains("watch") || name.contains("smart") || name.contains("手表")) return true
        if (name.startsWith("b0") || name.startsWith("h2") || name.startsWith("hy")) return true
        val md = record?.manufacturerSpecificData
        if (md != null) {
            for (i in 0 until md.size()) {
                val data = md.valueAt(i) ?: continue
                if (data.size >= 2 && (data[data.size - 2].toInt() and 0xFF) == 0x12 && (data[data.size - 1].toInt() and 0xFF) == 0x34) return true
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanner = null
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: android.bluetooth.BluetoothDevice) {
        val current = gatt
        if (current != null && connectedDevice?.address == device.address &&
            snap.state in listOf(STATE_CONNECTING, STATE_BINDING, STATE_CONNECTED)
        ) return
        reconnectScheduled = false
        disconnectGatt()
        bound = false
        suppressReconnect = false
        connectedDevice = device
        setState(STATE_CONNECTING, "正在连接 ${safeNameOf(device)}…")
        LogStore.log("watch", "连接手表 ${safeNameOf(device)} (${device.address})")
        try {
            gatt = device.connectGatt(ctx, false, gattCallback, TRANSPORT_LE)
            main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            setState(STATE_ERROR, "连接失败：" + (e.message ?: "未知错误"))
            scheduleReconnect()
        }
    }

    private fun safeNameOf(device: android.bluetooth.BluetoothDevice): String = try {
        device.name ?: Prefs.watchName.ifBlank { "HryFine 手表" }
    } catch (_: Exception) {
        Prefs.watchName.ifBlank { "HryFine 手表" }
    }

    private const val TRANSPORT_LE = 2

    private fun statusText(status: Int): String = when (status) {
        0 -> "正常"
        8 -> "连接超时"
        19 -> "对端主动断开"
        22 -> "本机主动断开"
        133 -> "GATT 错误"
        257 -> "读取失败"
        else -> ""
    }

    private fun disconnectGatt() {
        val g = gatt
        gatt = null
        notifyChar = null
        writeChar = null
        batteryChar = null
        readQueue.clear()
        readStarted = false
        writeQueue.clear()
        writeBusy = false
        pendingMeasureLabel = null
        pendingMeasureDeadline = 0L
        main.removeCallbacks(writeTick)
        connectedDevice = null
        version = 0x01
        features = HryFineProtocol.Features(false, false, false, false, false)
        main.removeCallbacks(readTimeout)
        main.removeCallbacks(connectTimeout)
        reassembler.reset()
        try {
            g?.disconnect()
            g?.close()
        } catch (_: Exception) {
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // 已被替换或主动关闭的旧连接，回调直接忽略
            if (g !== gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectIdx = 0
                LogStore.log("watch", "GATT 已连接，发现服务")
                connectedAt = System.currentTimeMillis()
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val lived = if (connectedAt > 0) System.currentTimeMillis() - connectedAt else Long.MAX_VALUE
                connectedAt = 0
                quickDrops = if (lived < 4000) quickDrops + 1 else 0
                LogStore.log("watch", "手表连接断开（status=$status ${statusText(status)}，持续 ${lived}ms，连续快速断开 $quickDrops 次）")
                val fatal = suppressReconnect
                suppressReconnect = false
                if (quickDrops >= 3) {
                    // 连续被手表断开：放慢重连节奏，避免高频占用蓝牙
                    reconnectIdx = RECONNECT_DELAYS.lastIndex
                }
                if (snap.state == STATE_BINDING) {
                    LogStore.log("watch", "同步阶段被手表断开")
                }
                disconnectGatt()
                if (Prefs.watchEnabled && !userStopped && !fatal) {
                    setState(STATE_CONNECTING, "手表已断开，准备重连…")
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                LogStore.log("watch", "服务发现失败（$status）")
                failAndRetry("服务发现失败（$status），正在重试")
                return
            }
            val nus = g.getService(UUID.fromString(HryFineProtocol.SERVICE_UUID)) ?: run {
                // 选错设备：清掉地址，让用户重新挑选，避免无限重连同一个错误设备
                suppressReconnect = true
                Prefs.watchAddress = ""
                disconnectGatt()
                setState(STATE_ERROR, "该设备不是 HryFine 手表（缺少命令服务），请重新扫描并选择手表")
                return
            }
            writeChar = nus.getCharacteristic(UUID.fromString(HryFineProtocol.WRITE_UUID))
            notifyChar = nus.getCharacteristic(UUID.fromString(HryFineProtocol.NOTIFY_UUID))
            batteryChar = g.getService(UUID.fromString(HryFineProtocol.BATTERY_SERVICE_UUID))
                ?.getCharacteristic(UUID.fromString(HryFineProtocol.BATTERY_CHAR_UUID))
            val w = writeChar
            val n = notifyChar
            if (w == null || n == null) {
                suppressReconnect = true
                Prefs.watchAddress = ""
                disconnectGatt()
                setState(STATE_ERROR, "手表命令通道不完整，请换一个设备试试")
                return
            }
            reassembler.reset()
            main.removeCallbacks(connectTimeout)
            val subscribed = try {
                g.setCharacteristicNotification(n, true)
            } catch (_: Exception) {
                false
            }
            if (!subscribed) LogStore.log("watch", "本地开启通知失败，仍尝试写入描述符")
            val desc = n.getDescriptor(DescriptorCccd)
            if (desc != null) {
                desc.value = notifyValueFor(n)
                val ok = try {
                    g.writeDescriptor(desc)
                } catch (_: Exception) {
                    false
                }
                if (!ok) {
                    LogStore.log("watch", "订阅通知描述符写入失败，直接读取设备信息")
                    startReads()
                }
            } else {
                startReads()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            main.removeCallbacks(writeTick)
            writeBusy = false
            pumpWrite()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            if (descriptor.uuid == DescriptorCccd) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    LogStore.log("watch", "通知订阅失败（$status），直接读取设备信息")
                }
                startReads()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            main.removeCallbacks(readTimeout)
            if (status == BluetoothGatt.GATT_SUCCESS) handleRead(ch.uuid, ch.value ?: ByteArray(0))
            readNext()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (g !== gatt) return
            handleNotify(ch.value ?: ByteArray(0))
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (g !== gatt) return
            main.removeCallbacks(readTimeout)
            if (status == BluetoothGatt.GATT_SUCCESS) handleRead(ch.uuid, value)
            readNext()
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (g !== gatt) return
            handleNotify(value)
        }
    }

    private val DescriptorCccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** 有 notify 属性用通知，只有 indicate 时用指示 */
    private fun notifyValueFor(ch: BluetoothGattCharacteristic): ByteArray =
        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

    private fun startReads() {
        if (readStarted) return
        readStarted = true
        setState(STATE_BINDING, "读取设备信息…")
        val g = gatt ?: return
        readQueue = ArrayDeque(
            listOfNotNull(
                g.getService(UUID.fromString(HryFineProtocol.DIS_SERVICE_UUID))
                    ?.getCharacteristic(UUID.fromString(HryFineProtocol.CHAR_PROTOCOL)),
                g.getService(UUID.fromString(HryFineProtocol.DIS_SERVICE_UUID))
                    ?.getCharacteristic(UUID.fromString(HryFineProtocol.CHAR_FEATURES)),
                g.getService(UUID.fromString(HryFineProtocol.DIS_SERVICE_UUID))
                    ?.getCharacteristic(UUID.fromString(HryFineProtocol.CHAR_FIRMWARE)),
                g.getService(UUID.fromString(HryFineProtocol.DIS_SERVICE_UUID))
                    ?.getCharacteristic(UUID.fromString(HryFineProtocol.CHAR_MODEL)),
                batteryChar,
            ),
        )
        readNext()
    }

    @SuppressLint("MissingPermission")
    private fun readNext() {
        val g = gatt ?: return
        val ch = readQueue.removeFirstOrNull()
        if (ch == null) {
            beginSession()
            return
        }
        main.removeCallbacks(readTimeout)
        val started = try {
            g.readCharacteristic(ch)
        } catch (e: Exception) {
            LogStore.log("watch", "读取 ${ch.uuid} 失败：" + (e.message ?: "未知错误"))
            false
        }
        if (started) main.postDelayed(readTimeout, 900) else main.postDelayed({ readNext() }, 60)
    }

    private fun handleRead(uuid: UUID, value: ByteArray) {
        when (uuid) {
            UUID.fromString(HryFineProtocol.CHAR_PROTOCOL) -> {
                val text = String(value, Charsets.UTF_8).trim()
                HryFineProtocol.parseProtocolVersion(text)?.let { version = it }
                snap = snap.copy(protocol = text)
            }
            UUID.fromString(HryFineProtocol.CHAR_FEATURES) -> features = HryFineProtocol.parseFeatures(value)
            UUID.fromString(HryFineProtocol.CHAR_FIRMWARE) -> {
                val text = printable(value)
                // 部分手表 2A26 存的是纯数字编号，真正的固件版本在 0xF3 设备信息里
                if (text.any { it.isLetter() || it == '.' || it == '-' }) lastFirmware = text
            }
            UUID.fromString(HryFineProtocol.CHAR_MODEL) -> lastProject = printable(value)
            UUID.fromString(HryFineProtocol.BATTERY_CHAR_UUID) -> snap = snap.copy(battery = value.firstOrNull()?.toInt()?.and(0xFF) ?: -1)
        }
        publish()
    }

    private fun beginSession() {
        if (snap.state == STATE_CONNECTED) return
        setState(STATE_BINDING, "正在同步手表数据…")
        // 该固件绑定不需要在手表上确认，也不强制要求绑定回包：
        // 设备信息读取成功就进入已连接，先只发只读请求，确认链路稳定后再补校时
        main.postDelayed({ if (gatt != null && snap.state == STATE_BINDING) finishSession() }, 300)
    }

    /** 手表主动确认/同意绑定（部分固件会在数据同步前后发来），只做记录与配对开关 */
    private fun onBound() {
        if (bound) return
        bound = true
        LogStore.log("watch", "手表确认绑定")
        writeFrame(HryFineProtocol.GROUP_GENERAL, HryFineProtocol.SUB_PAIRING, byteArrayOf(0x02))
        quickDrops = 0
        if (snap.state != STATE_CONNECTED) finishSession()
    }

    private fun finishSession() {
        if (snap.state == STATE_CONNECTED) return
        val dev = connectedDevice
        if (dev != null) {
            Prefs.watchAddress = dev.address
            val name = safeNameOf(dev)
            if (!Prefs.watchNameManual && name.isNotBlank()) Prefs.watchName = name
            snap = snap.copy(address = dev.address, name = Prefs.watchName)
        }
        main.removeCallbacks(connectTimeout)
        setState(STATE_CONNECTED, "已连接")
        snap = snap.copy(updatedAt = System.currentTimeMillis())
        publish()
        requestBattery()
        main.postDelayed({ if (snap.state == STATE_CONNECTED) writeFrame(HryFineProtocol.GROUP_DEVICE_INFO, 0x00) }, 1200)
        main.postDelayed({ if (snap.state == STATE_CONNECTED) syncHealth() }, 2000)
        startStepTick()
        main.postDelayed({
            if (snap.state == STATE_CONNECTED) {
                writeFrame(HryFineProtocol.GROUP_SETTINGS, HryFineProtocol.SUB_SETTINGS_QUERY)
            }
        }, 5000)
        main.postDelayed({
            if (snap.state == STATE_CONNECTED) {
                writeFrame(HryFineProtocol.GROUP_GENERAL, HryFineProtocol.SUB_TIME_SYNC, HryFineProtocol.packDateTime(System.currentTimeMillis()))
            }
        }, 9000)
        main.postDelayed({ if (snap.state == STATE_CONNECTED) requestBattery() }, 12000)
    }

    private fun requestBattery() {
        val g = gatt ?: return
        val ch = batteryChar ?: return
        try {
            g.readCharacteristic(ch)
        } catch (_: Exception) {
        }
    }

    /** 当前是否已保存手表 */
    fun hasSavedDevice(): Boolean = Prefs.watchAddress.isNotBlank()

    /** 自定义手表名称：卡片、上报与插件展示都用这个名称 */
    fun renameWatch(name: String) {
        val trimmed = name.trim().take(24)
        if (trimmed.isEmpty()) return
        Prefs.watchName = trimmed
        Prefs.watchNameManual = true
        LogStore.log("watch", "手表名称改为 $trimmed")
        publish()
    }

    /** 解除绑定：通知手表解绑、取消系统配对，并清除本地保存的手表 */
    @SuppressLint("MissingPermission")
    fun unbind() {
        val dev = connectedDevice ?: try {
            Prefs.watchAddress.takeIf { it.isNotBlank() }?.let { adapter()?.getRemoteDevice(it) }
        } catch (_: Exception) {
            null
        }
        if (snap.state == STATE_CONNECTED) {
            writeFrame(HryFineProtocol.GROUP_UNBIND, 0x00)
            LogStore.log("watch", "已向手表发送解绑指令")
        }
        suppressReconnect = true
        connectedAt = 0
        quickDrops = 0
        main.postDelayed({
            try {
                if (dev != null && dev.bondState != BluetoothDevice.BOND_NONE) {
                    // removeBond 未暴露在公开 SDK，按反射调用
                    BluetoothDevice::class.java.getMethod("removeBond").invoke(dev)
                }
            } catch (e: Exception) {
                LogStore.log("watch", "取消系统配对失败：" + (e.message ?: "未知错误"))
            }
            Prefs.watchAddress = ""
            Prefs.watchName = ""
            bound = false
            disconnectGatt()
            setState(STATE_ERROR, "已解除绑定，可重新扫描选择手表")
        }, 700)
    }

    /** 触发手表测量：心率 / 血压 / 血氧 */
    fun measureHeartRate() = measure(HryFineProtocol.SUB_MEASURE_HEART)

    fun measureBloodPressure() = measure(HryFineProtocol.SUB_MEASURE_BP)

    fun measureSpo2() = measure(HryFineProtocol.SUB_MEASURE_SPO2)

    private var pendingMeasureLabel: String? = null
    private var pendingMeasureDeadline = 0L

    private fun measure(sub: Int) {
        if (snap.state != STATE_CONNECTED) return
        writeFrame(HryFineProtocol.GROUP_GENERAL, sub, byteArrayOf(0x01))
        val label = when (sub) {
            HryFineProtocol.SUB_MEASURE_HEART -> "心率"
            HryFineProtocol.SUB_MEASURE_BP -> "血压"
            HryFineProtocol.SUB_MEASURE_SPO2 -> "血氧"
            else -> "健康"
        }
        pendingMeasureLabel = label
        pendingMeasureDeadline = System.currentTimeMillis() + MEASURE_TIMEOUT_MS
        LogStore.log("watch", "请求测量 sub=%02X（%s），完成后将即时上报".format(sub, label))
    }

    /** 测量结果落地后通知服务端补一次上报；超时未回则放弃，避免状态残留 */
    private fun notifyMeasuredIfPending(label: String) {
        val pending = pendingMeasureLabel ?: return
        if (pending != label) return
        if (System.currentTimeMillis() > pendingMeasureDeadline) {
            pendingMeasureLabel = null
            return
        }
        pendingMeasureLabel = null
        LogStore.log("watch", "测量结果已更新（$label），触发即时上报")
        main.post { measureListeners.forEach { it(label) } }
    }

    /** 跨天后先清掉昨天的步数/距离/消耗，避免手表未回包时把旧值继续当成今天的数据 */
    private var stepsDay = ""
    private fun resetIfNewDay() {
        val today = java.time.LocalDate.now().toString()
        if (stepsDay == today) return
        val first = stepsDay.isEmpty()
        stepsDay = today
        if (first) return
        snap = snap.copy(steps = -1, distanceM = -1, kcal = -1)
        health = health.copy(steps = null, distanceM = null, kcal = null, hourlySteps = null)
        LogStore.log("watch", "跨天，已清空昨日步数")
        publish()
    }

    private fun syncHealth() {
        if (snap.state != STATE_CONNECTED) return
        resetIfNewDay()
        // 每轮同步前清空活动类字段（步数/距离/消耗），保留心率血压等最近测量值。
        // 背景：整天总计（0x0C）并非每次都会回包，若不清空，上一轮的旧步数会一直
        // 挡住这一轮按小时汇总（0x02）算出的新值，表现为步数停在首次连接时的数字。
        health = health.copy(steps = null, distanceM = null, kcal = null, hourlySteps = null)
        // 0x06 实时步数开关（payload 0x01）：官方在同步前打开，手表才会在步数变化时主动推送，
        // 否则只能等下一次轮询。失败无害，手表会忽略不支持的子命令
        writeFrame(HryFineProtocol.GROUP_SPORTS, HryFineProtocol.SUB_HEALTH_REALTIME_STEP, byteArrayOf(0x01))
        // 0x01 同步开始；0x0A 让手表把当天数据整包推过来
        writeFrame(HryFineProtocol.GROUP_SPORTS, HryFineProtocol.SUB_HEALTH_SYNC)
        val subs = listOf(
            HryFineProtocol.SUB_HEALTH_TODAY,
            HryFineProtocol.SUB_HEALTH_TODAY_TOTAL,
            HryFineProtocol.SUB_HEALTH_HOURLY,
            0x04, // 心率
            0x05, // 血压
            0x09, // 睡眠
            0x0E, // 血氧
            // 睡眠与血氧回包最先被逐时数据流挤掉，末尾补请求一次，避免整轮同步拿不到
            0x09,
            0x0E,
        )
        subs.forEachIndexed { i, sub ->
            main.postDelayed({
                if (snap.state == STATE_CONNECTED) writeFrame(HryFineProtocol.GROUP_SPORTS, sub)
            }, 500L * (i + 1))
        }
        // 0x08 同步结束：不回这个包，手表会停在同步态，后续同步请求可能被忽略
        main.postDelayed({
            if (snap.state == STATE_CONNECTED) {
                writeFrame(HryFineProtocol.GROUP_SPORTS, HryFineProtocol.SUB_HEALTH_END)
                snap = snap.copy(updatedAt = System.currentTimeMillis())
                publish()
                LogStore.log("watch", "手表数据同步完成：" + describe(snap))
                // 同步拿到的新数据要立刻上报，否则插件只能看到上一轮定时上报的快照，
                // 表现为步数一直停在首次连接时的数字
                notifySynced()
            }
        }, 500L * (subs.size + 8))
    }

    @SuppressLint("MissingPermission")
    private fun writeFrame(group: Int, sub: Int, payload: ByteArray = ByteArray(0)) {
        if (gatt == null || writeChar == null) return
        val bytes = HryFineProtocol.encode(group, sub, payload, version)
        val label = "group=%02X sub=%02X".format(group, sub)
        LogStore.log("watch", "发送 $label 长度 ${payload.size}")
        var at = 0
        while (at < bytes.size) {
            val end = minOf(at + CHUNK_SIZE, bytes.size)
            writeQueue.addLast(bytes.copyOfRange(at, end) to label)
            at = end
        }
        pumpWrite()
    }

    private fun enqueueAck(frame: HryFineProtocol.Frame) {
        if (gatt == null || writeChar == null) return
        writeQueue.addLast(HryFineProtocol.ack(frame) to "ack group=%02X sub=%02X".format(frame.group, frame.sub))
        pumpWrite()
    }

    @SuppressLint("MissingPermission")
    private fun writeBytes(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray, label: String): Boolean {
        return try {
            ch.writeType = writeTypeFor(ch)
            ch.value = bytes
            g.writeCharacteristic(ch)
        } catch (e: Exception) {
            LogStore.log("watch", "发送指令失败 $label：" + (e.message ?: "未知错误"))
            false
        }
    }

    /** 与参考实现一致：只有不支持无响应写时才用带响应写 */
    private fun writeTypeFor(ch: BluetoothGattCharacteristic): Int {
        val props = ch.properties
        val noResponse = props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val response = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        return if (response && !noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
    }

    private fun handleNotify(chunk: ByteArray) {
        // 蓝牙回调在 binder 线程，统一投递到主线程串行处理，避免重组与写队列竞争
        main.post { onChunk(chunk) }
    }

    private fun onChunk(chunk: ByteArray) {
        for (raw in reassembler.feed(chunk)) {
            val frame = HryFineProtocol.parse(raw) ?: continue
            onFrameMain(frame)
        }
    }

    private fun onFrameMain(frame: HryFineProtocol.Frame) {
        if (frame.isAck) {
            LogStore.log("watch", "收到 ACK %02X/%02X 状态 %d".format(frame.group, frame.sub, frame.payload.lastOrNull()?.toInt()?.and(0xFF) ?: -1))
        } else {
            enqueueAck(frame)
        }
        handleFrame(frame)
    }

    private fun handleFrame(frame: HryFineProtocol.Frame) {
        if (!frame.isAck) {
            // 测量/健康类帧把整段载荷都记下来：新指标（尿酸/血脂）字段布局要靠实测字节定位
            val full = frame.group == HryFineProtocol.GROUP_GENERAL || frame.group == HryFineProtocol.GROUP_SPORTS
            val hex = if (full) {
                frame.payload.joinToString("") { "%02X".format(it) }
            } else {
                frame.payload.take(24).joinToString("") { "%02X".format(it) }
            }
            LogStore.log("watch", "收到 %02X/%02X 长度 %d%s".format(frame.group, frame.sub, frame.payload.size, if (hex.isEmpty()) "" else " 数据 $hex"))
        }
        when (frame.group) {
            HryFineProtocol.GROUP_SETTINGS -> if (!frame.isAck && frame.sub == HryFineProtocol.SUB_SETTINGS_REPLY) {
                settings = HryFineProtocol.parseSettings(frame.payload)
                snap = snap.copy(
                    sedentaryOn = settings[0x03]?.firstOrNull()?.let { (it.toInt() and 0xFF) != 0 },
                    raiseWristOn = settings[0x07]?.firstOrNull()?.let { (it.toInt() and 0xFF) != 0 },
                )
                publish()
            }
            HryFineProtocol.GROUP_SPORTS -> if (!frame.isAck) {
                health = HryFineProtocol.decodeHealth(frame.sub, frame.payload, health)
                applyHealth()
            }
            HryFineProtocol.GROUP_DEVICE_INFO -> if (!frame.isAck) {
                HryFineProtocol.parseDeviceInfo(frame.payload)?.let {
                    if (it.firmware.isNotBlank()) lastFirmware = it.firmware
                    if (it.project.isNotBlank()) lastProject = it.project
                    publish()
                }
            }
            HryFineProtocol.GROUP_BIND -> if (!bound && frame.isAck) {
                if ((frame.payload.lastOrNull()?.toInt()?.and(0xFF)) == 0x01) onBound()
            }
            HryFineProtocol.GROUP_BAND_INFO -> if (!frame.isAck) {
                LogStore.log("watch", "手表请求系统配对，开始创建绑定")
                val dev = connectedDevice
                if (dev != null) {
                    try {
                        if (dev.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) dev.createBond()
                    } catch (e: Exception) {
                        LogStore.log("watch", "创建系统配对失败：" + (e.message ?: "未知错误"))
                    }
                }
            }
            HryFineProtocol.GROUP_BUTTONS -> if (!frame.isAck) {
                when (frame.sub) {
                    HryFineProtocol.SUB_BIND_ACCEPT -> {
                        val first = frame.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x01
                        LogStore.log("watch", "手表同意绑定" + if (first) "（首次绑定）" else "")
                        if (!bound) onBound()
                    }
                    HryFineProtocol.SUB_BIND_REJECT -> {
                        LogStore.log("watch", "手表拒绝绑定，需要重新选择设备")
                        suppressReconnect = true
                        Prefs.watchAddress = ""
                        disconnectGatt()
                        setState(STATE_ERROR, "手表拒绝绑定，请重新扫描并在手表上同意")
                    }
                    else -> {
                        val label = when (frame.sub) {
                            0x01 -> "查找手机"
                            0x02 -> "遥控拍照"
                            0x0A -> "音乐播放"
                            0x0B -> "音乐暂停"
                            0x0C -> "音乐上一首"
                            0x0D -> "音乐下一首"
                            else -> "按键事件 0x%02X".format(frame.sub)
                        }
                        LogStore.log("watch", "手表事件：$label")
                    }
                }
            }
        }
    }

    private fun applyHealth() {
        val prev = snap
        snap = snap.copy(
            steps = health.steps ?: health.hourlySteps ?: snap.steps,
            distanceM = health.distanceM ?: snap.distanceM,
            kcal = health.kcal ?: snap.kcal,
            heartRate = health.heartRate ?: snap.heartRate,
            bpSys = health.bpSys ?: snap.bpSys,
            bpDia = health.bpDia ?: snap.bpDia,
            spo2 = health.spo2 ?: snap.spo2,
            sleepMin = health.sleepLightMin
                ?.let { light -> light + (health.sleepDeepMin ?: 0) }
                ?.takeIf { it in 1..1440 }
                ?: snap.sleepMin,
        )
        publish()
        // 主动测量：值一旦落到卡片上就立刻上报，用户无需等待下一轮定时上报
        if (snap.heartRate != prev.heartRate) notifyMeasuredIfPending("心率")
        if (snap.bpSys != prev.bpSys || snap.bpDia != prev.bpDia) notifyMeasuredIfPending("血压")
        if (snap.spo2 != prev.spo2) notifyMeasuredIfPending("血氧")
    }

    /** 连接期间每分钟刷新一次步数/距离/消耗，避免只有首次连接时同步过一次 */
    private var stepTicking = false
    private val stepTick = object : Runnable {
        override fun run() {
            if (snap.state != STATE_CONNECTED) {
                stepTicking = false
                return
            }
            // 测量进行中先让路，避免请求交错导致回包被当成测量结果
            if (pendingMeasureLabel != null) {
                main.postDelayed(this, 15_000L)
                return
            }
            health = health.copy(steps = null, distanceM = null, kcal = null, hourlySteps = null)
            resetIfNewDay()
            val before = snap.steps
            // 与完整同步同样成帧：开始 -> 当天总计 -> 逐时汇总 -> 结束
            writeFrame(HryFineProtocol.GROUP_SPORTS, HryFineProtocol.SUB_HEALTH_SYNC)
            val steps = listOf(HryFineProtocol.SUB_HEALTH_TODAY_TOTAL, HryFineProtocol.SUB_HEALTH_HOURLY)
            steps.forEachIndexed { i, sub ->
                main.postDelayed({
                    if (snap.state == STATE_CONNECTED) writeFrame(HryFineProtocol.GROUP_SPORTS, sub)
                }, 400L * (i + 1))
            }
            main.postDelayed({
                if (snap.state == STATE_CONNECTED) {
                    writeFrame(HryFineProtocol.GROUP_SPORTS, HryFineProtocol.SUB_HEALTH_END)
                    // 每分钟刷新只在步数确实变化时补报，避免把上报频率抬到 5 倍
                    if (snap.steps != before) notifySynced()
                }
            }, 400L * (steps.size + 2))
            main.postDelayed(this, STEP_REFRESH_MS)
        }
    }

    /** 只允许存在一条定时链，重连后重复调用不会叠加 */
    private fun startStepTick() {
        if (stepTicking) return
        stepTicking = true
        main.postDelayed(stepTick, STEP_REFRESH_MS)
    }

    private fun scheduleReconnect() {
        if (!Prefs.watchEnabled || userStopped) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        val delay = RECONNECT_DELAYS[reconnectIdx.coerceAtMost(RECONNECT_DELAYS.lastIndex)]
        reconnectIdx++
        LogStore.log("watch", "${delay / 1000} 秒后重连手表")
        main.postDelayed({
            reconnectScheduled = false
            connectSavedOrScan()
        }, delay)
    }

    private fun setState(state: String, detail: String) {
        snap = snap.copy(state = state, detail = detail, enabled = Prefs.watchEnabled)
        publish()
    }

    private fun publish() {
        snap = snap.copy(
            enabled = Prefs.watchEnabled,
            name = Prefs.watchName,
            address = Prefs.watchAddress,
            firmware = lastFirmware.ifBlank { snap.firmware },
            project = lastProject.ifBlank { snap.project },
            protocol = if (snap.protocol.isBlank()) version.toString() else snap.protocol,
            features = describeFeatures(),
        )
        val s = snap
        main.post { listeners.forEach { it(s) } }
    }

    private fun describeFeatures(): String {
        if (!features.valid) return ""
        val list = mutableListOf<String>()
        if (features.sdk) list += "挑战绑定"
        if (features.deviceInfo) list += "设备信息"
        if (features.medicine) list += "吃药提醒"
        if (features.drinkWater) list += "喝水提醒"
        return list.joinToString("、")
    }

    private fun printable(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8).filter { it.code in 32..126 || it.code in 0x4E00..0x9FFF }.trim()

    private fun describe(s: Snapshot): String {
        val parts = mutableListOf<String>()
        if (s.battery >= 0) parts += "电量=${s.battery}%"
        if (s.steps >= 0) parts += "步数=${s.steps}"
        if (s.distanceM >= 0) parts += "距离=${s.distanceM}米"
        if (s.heartRate >= 0) parts += "心率=${s.heartRate}"
        if (s.spo2 >= 0) parts += "血氧=${s.spo2}%"
        if (s.sleepMin >= 0) parts += "睡眠=${s.sleepMin}分钟"
        return parts.joinToString(" ")
    }
}
