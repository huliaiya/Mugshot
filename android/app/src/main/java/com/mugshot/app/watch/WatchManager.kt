package com.mugshot.app.watch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
    private const val WRITE_GAP_MS = 20L
    private const val CONNECT_TIMEOUT_MS = 15000L
    private const val CANDIDATE_PUBLISH_MS = 400L
    private const val HEALTH_WINDOW_MS = 9000L
    private const val STRATEGY_CHALLENGE = "challenge"
    private const val STRATEGY_DEVICE_INFO = "deviceInfo"
    private const val STRATEGY_DIRECT = "direct"
    private val RECONNECT_DELAYS = longArrayOf(5_000, 10_000, 20_000, 40_000, 60_000)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Snapshot) -> Unit>()

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
    private var bindAttempts = 0
    private var bound = false
    private var challenge = ByteArray(0)
    private var connectedDevice: android.bluetooth.BluetoothDevice? = null
    private val writeQueue = ArrayDeque<Pair<ByteArray, String>>()
    private var writeBusy = false
    private var probeIdx = 0
    private var reconnectIdx = 0
    private var suppressReconnect = false
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
        writeBytes(g, ch, item.first, item.second)
        main.postDelayed(writeTick, WRITE_GAP_MS)
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

    fun snapshot(): Snapshot = snap.copy(enabled = Prefs.watchEnabled)

    fun setEnabled(enabled: Boolean) {
        Prefs.watchEnabled = enabled
        if (enabled) startInternal(auto = false) else stopInternal()
        publish()
    }

    /** 立即同步一次（已连接则拉取健康数据，否则尝试回连/扫描） */
    fun refreshNow() {
        if (!Prefs.watchEnabled) return
        val g = gatt
        if (g != null && snap.state == STATE_CONNECTED) {
            requestBattery()
            syncToday()
        } else {
            reconnectIdx = 0
            connectSavedOrScan()
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
        disconnectGatt()
        bindAttempts = 0
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
                try {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                } catch (_: Exception) {
                }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                LogStore.log("watch", "手表连接断开（status=$status）")
                val fatal = suppressReconnect
                suppressReconnect = false
                // 绑定过程中被手表断链，说明这条登录路径被拒绝，下次换一条
                if (snap.state == STATE_BINDING && !bound) {
                    probeIdx++
                    LogStore.log("watch", "绑定路径被手表拒绝，下次尝试：${if (features.valid) "按特性位" else bindStrategy()}")
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
        if (started) main.postDelayed(readTimeout, 2500) else main.postDelayed({ readNext() }, 80)
    }

    private fun handleRead(uuid: UUID, value: ByteArray) {
        when (uuid) {
            UUID.fromString(HryFineProtocol.CHAR_PROTOCOL) -> {
                val text = String(value, Charsets.UTF_8).trim()
                HryFineProtocol.parseProtocolVersion(text)?.let { version = it }
                snap = snap.copy(protocol = text)
            }
            UUID.fromString(HryFineProtocol.CHAR_FEATURES) -> features = HryFineProtocol.parseFeatures(value)
            UUID.fromString(HryFineProtocol.CHAR_FIRMWARE) -> lastFirmware = printable(value)
            UUID.fromString(HryFineProtocol.CHAR_MODEL) -> lastProject = printable(value)
            UUID.fromString(HryFineProtocol.BATTERY_CHAR_UUID) -> snap = snap.copy(battery = value.firstOrNull()?.toInt()?.and(0xFF) ?: -1)
        }
        publish()
    }

    private fun beginSession() {
        setState(STATE_BINDING, "绑定并同步数据…")
        val userId = Prefs.watchUserId.toByteArray(Charsets.US_ASCII)
        // 顺序对齐 HryFine：挑战 → 确认 → 设备信息 → 绑定 → 校时 → 设置/健康。
        // 未完成绑定的手表会直接断链，所以校时必须放在绑定之后。
        val strategy = bindStrategy()
        LogStore.log("watch", "绑定路径：$strategy")
        if (strategy == STRATEGY_CHALLENGE) {
            challenge = newChallenge()
            main.postDelayed({ writeFrame(HryFineProtocol.GROUP_CHALLENGE, 0x00, challenge) }, 150)
            main.postDelayed({ writeFrame(HryFineProtocol.GROUP_CHALLENGE_CONFIRM, 0x00, byteArrayOf(0x00)) }, 1200)
        }
        if (strategy != STRATEGY_DIRECT) {
            main.postDelayed({ writeFrame(HryFineProtocol.GROUP_DEVICE_INFO, 0x00) }, 1700)
        }
        main.postDelayed({ writeFrame(HryFineProtocol.GROUP_BIND, HryFineProtocol.SUB_BIND, userId) }, 2100)
        main.postDelayed({
            if (gatt == null || snap.state != STATE_BINDING) return@postDelayed
            completeSession()
        }, HEALTH_WINDOW_MS)
    }

    private fun completeSession() {
        if (snap.state == STATE_CONNECTED) return
        requestBattery()
        main.removeCallbacks(connectTimeout)
        setState(STATE_CONNECTED, if (bound) "已连接" else "已连接（未完成绑定）")
        snap = snap.copy(updatedAt = System.currentTimeMillis())
        publish()
        main.postDelayed({
            writeFrame(HryFineProtocol.GROUP_GENERAL, HryFineProtocol.SUB_TIME_SYNC, HryFineProtocol.packDateTime(System.currentTimeMillis()))
        }, 300)
        main.postDelayed({ writeFrame(HryFineProtocol.GROUP_SETTINGS, HryFineProtocol.SUB_SETTINGS_QUERY) }, 800)
        main.postDelayed({ syncHealth() }, 1300)
        main.postDelayed({ requestBattery() }, 1800)
    }

    private fun newChallenge(): ByteArray {
        val chars = "0123456789abcdef"
        val rnd = java.security.SecureRandom()
        return (0 until 8).map { chars[rnd.nextInt(chars.length)] }.joinToString("").toByteArray(Charsets.US_ASCII)
    }

    private fun bindStrategy(): String = when {
        features.valid -> when {
            features.sdk -> STRATEGY_CHALLENGE
            features.deviceInfo -> STRATEGY_DEVICE_INFO
            else -> STRATEGY_DIRECT
        }
        // 特性位读不到时按参考实现探测：设备信息 → 挑战 → 直接绑定
        probeIdx % 3 == 0 -> STRATEGY_DEVICE_INFO
        probeIdx % 3 == 1 -> STRATEGY_CHALLENGE
        else -> STRATEGY_DIRECT
    }

    private fun requestBattery() {
        val g = gatt ?: return
        val ch = batteryChar ?: return
        try {
            g.readCharacteristic(ch)
        } catch (_: Exception) {
        }
    }

    /** 触发手表测量：心率 / 血压 / 血氧 */
    fun measureHeartRate() = measure(HryFineProtocol.SUB_MEASURE_HEART)

    fun measureBloodPressure() = measure(HryFineProtocol.SUB_MEASURE_BP)

    fun measureSpo2() = measure(HryFineProtocol.SUB_MEASURE_SPO2)

    private fun measure(sub: Int) {
        if (snap.state != STATE_CONNECTED) return
        writeFrame(HryFineProtocol.GROUP_GENERAL, sub, byteArrayOf(0x01))
        LogStore.log("watch", "请求测量 sub=%02X".format(sub))
    }

    private fun syncToday() {
        writeFrame(HryFineProtocol.GROUP_SPORTS, HryFineProtocol.SUB_HEALTH_TODAY)
        main.postDelayed({
            if (snap.state == STATE_CONNECTED) {
                snap = snap.copy(updatedAt = System.currentTimeMillis())
                publish()
            }
        }, 1500)
    }

    private fun syncHealth() {
        writeFrame(HryFineProtocol.GROUP_SPORTS, HryFineProtocol.SUB_HEALTH_SYNC)
        main.postDelayed({
            if (snap.state == STATE_CONNECTED) {
                snap = snap.copy(updatedAt = System.currentTimeMillis())
                publish()
                LogStore.log("watch", "手表数据同步完成：" + describe(snap))
            }
        }, 2500)
    }

    @SuppressLint("MissingPermission")
    private fun writeFrame(group: Int, sub: Int, payload: ByteArray = ByteArray(0)) {
        if (gatt == null || writeChar == null) return
        val bytes = HryFineProtocol.encode(group, sub, payload, version)
        val label = "group=%02X sub=%02X".format(group, sub)
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
    private fun writeBytes(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray, label: String) {
        try {
            ch.writeType = writeTypeFor(ch)
            ch.value = bytes
            if (!g.writeCharacteristic(ch)) LogStore.log("watch", "写入未被接受 $label")
        } catch (e: Exception) {
            LogStore.log("watch", "发送指令失败 $label：" + (e.message ?: "未知错误"))
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
        for (raw in reassembler.feed(chunk)) {
            val frame = HryFineProtocol.parse(raw) ?: continue
            if (!frame.isAck) enqueueAck(frame)
            handleFrame(frame)
        }
    }

    private fun handleFrame(frame: HryFineProtocol.Frame) {
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
            HryFineProtocol.GROUP_CHALLENGE -> if (!frame.isAck) {
                val expected = HryFineProtocol.challengeCode(challenge)
                val got = frame.payload.getOrNull(1)?.toInt()?.and(0xFF)
                LogStore.log("watch", "挑战校验：" + if (got == expected) "通过" else "不一致（期望 $expected，收到 $got）")
            }
            HryFineProtocol.GROUP_BIND -> {
                val confirmed = (frame.isAck && (frame.payload.lastOrNull()?.toInt()?.and(0xFF) == 0x01)) ||
                    (!frame.isAck && frame.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x01)
                if (confirmed) {
                    val first = !bound
                    val wasBinding = snap.state == STATE_BINDING
                    onBindConfirmed(first)
                    if (wasBinding) completeSession()
                    if (first && !wasBinding && snap.state == STATE_CONNECTED) {
                        // 手表可能在用户确认后才允许读数据，补一次同步
                        main.postDelayed({
                            writeFrame(HryFineProtocol.GROUP_SETTINGS, HryFineProtocol.SUB_SETTINGS_QUERY)
                            syncHealth()
                        }, 300)
                    }
                    if (bindAttempts < 2 && !frame.isAck) {
                        bindAttempts++
                        LogStore.log("watch", "手表要求确认绑定，重新发送绑定请求（第 $bindAttempts 次）")
                        main.postDelayed({
                            writeFrame(HryFineProtocol.GROUP_BIND, HryFineProtocol.SUB_BIND, Prefs.watchUserId.toByteArray(Charsets.US_ASCII))
                        }, 400)
                    }
                } else {
                    LogStore.log("watch", "绑定未确认（isAck=${frame.isAck} status=${frame.status}）")
                }
            }
            HryFineProtocol.GROUP_BUTTONS -> if (!frame.isAck) {
                when (frame.sub) {
                    HryFineProtocol.SUB_BIND_ACCEPT -> {
                        val first = frame.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x01
                        LogStore.log("watch", "手表同意绑定" + if (first) "（首次绑定）" else "")
                        // 绑定成功后打开手表的配对开关
                        writeFrame(HryFineProtocol.GROUP_GENERAL, HryFineProtocol.SUB_PAIRING, byteArrayOf(0x02))
                        if (!bound) {
                            val wasBinding = snap.state == STATE_BINDING
                            onBindConfirmed(first)
                            if (wasBinding) completeSession()
                        }
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

    private fun onBindConfirmed(first: Boolean) {
        bound = true
        LogStore.log("watch", "绑定已确认")
        val dev = connectedDevice
        if (dev != null) {
            Prefs.watchAddress = dev.address
            val name = safeNameOf(dev)
            if (name.isNotBlank()) Prefs.watchName = name
            LogStore.log("watch", "已保存手表 $name (${dev.address})，下次自动连接" + if (first) "（首次绑定）" else "")
        }
        snap = snap.copy(address = dev?.address ?: snap.address, name = Prefs.watchName)
        publish()
    }

    private fun applyHealth() {
        snap = snap.copy(
            steps = health.steps ?: snap.steps,
            distanceM = health.distanceM ?: snap.distanceM,
            kcal = health.kcal ?: snap.kcal,
            heartRate = health.heartRate ?: snap.heartRate,
            bpSys = health.bpSys ?: snap.bpSys,
            bpDia = health.bpDia ?: snap.bpDia,
            spo2 = health.spo2 ?: snap.spo2,
            sleepMin = health.sleepLightMin?.let { light -> light + (health.sleepDeepMin ?: 0) } ?: snap.sleepMin,
        )
        publish()
    }

    private fun scheduleReconnect() {
        if (!Prefs.watchEnabled || userStopped) return
        val delay = RECONNECT_DELAYS[reconnectIdx.coerceAtMost(RECONNECT_DELAYS.lastIndex)]
        reconnectIdx++
        LogStore.log("watch", "${delay / 1000} 秒后重连手表")
        main.postDelayed({ connectSavedOrScan() }, delay)
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
        if (s.heartRate >= 0) parts += "心率=${s.heartRate}"
        if (s.spo2 >= 0) parts += "血氧=${s.spo2}%"
        if (s.sleepMin >= 0) parts += "睡眠=${s.sleepMin}分钟"
        return parts.joinToString(" ")
    }
}
