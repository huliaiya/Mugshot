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
    ) {
        val connected: Boolean get() = state == STATE_CONNECTED
    }

    private const val SCAN_TIMEOUT_MS = 15000L
    private const val HEALTH_WINDOW_MS = 9000L
    private val RECONNECT_DELAYS = longArrayOf(5_000, 10_000, 20_000, 40_000, 60_000)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Snapshot) -> Unit>()

    private lateinit var ctx: Context
    private var initialized = false

    @Volatile
    private var snap = Snapshot()

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var batteryChar: BluetoothGattCharacteristic? = null

    private val reassembler = HryFineProtocol.Reassembler()
    private var version = 0x10
    private var features = HryFineProtocol.Features(false, false, false, false, false)
    private var health = HryFineProtocol.Health()
    private var settings: Map<Int, ByteArray> = emptyMap()
    private var lastFirmware = ""
    private var lastProject = ""
    private var bindAttempts = 0
    private var reconnectIdx = 0
    private var userStopped = false
    private var readQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var readStarted = false
    private val readTimeout = Runnable { readNext() }

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
            syncHealth()
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
        setState(STATE_SCANNING, "正在扫描附近手表…")
        LogStore.log("watch", "开始扫描 HryFine 手表（按服务 UUID / 名称 / 厂商数据匹配）")
        try {
            le.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        } catch (e: Exception) {
            setState(STATE_ERROR, "启动扫描失败：" + (e.message ?: "未知错误"))
            return
        }
        main.postDelayed({
            if (scanning) {
                stopScan()
                setState(STATE_ERROR, "未发现手表，请确认手表已开机并靠近手机")
            }
        }, SCAN_TIMEOUT_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            if (!looksLikeWatch(result)) return
            val name = safeName(result)
            LogStore.log("watch", "发现候选设备 $name (${result.device.address}) rssi=${result.rssi}")
            stopScan()
            Prefs.watchName = name
            Prefs.watchAddress = result.device.address
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            setState(STATE_ERROR, "扫描失败（错误码 $errorCode）")
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(result: ScanResult): String =
        result.scanRecord?.deviceName ?: try {
            result.device.name
        } catch (_: Exception) {
            null
        } ?: "HryFine 手表"

    private fun looksLikeWatch(result: ScanResult): Boolean {
        val record = result.scanRecord
        val uuids = record?.serviceUuids?.map { it.uuid.toString().lowercase() } ?: emptyList()
        if (uuids.contains(HryFineProtocol.SERVICE_UUID)) return true
        val name = (record?.deviceName ?: safeName(result)).lowercase()
        if (name.contains("watch") || name.contains("smart") || name.contains("手表") || name.contains("hry")) return true
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
        setState(STATE_CONNECTING, "正在连接 ${safeNameOf(device)}…")
        LogStore.log("watch", "连接手表 ${safeNameOf(device)} (${device.address})")
        try {
            gatt = device.connectGatt(ctx, false, gattCallback, TRANSPORT_LE)
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
        main.removeCallbacks(readTimeout)
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
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectIdx = 0
                LogStore.log("watch", "GATT 已连接，发现服务")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                LogStore.log("watch", "手表连接断开（status=$status）")
                disconnectGatt()
                if (Prefs.watchEnabled && !userStopped) {
                    setState(STATE_CONNECTING, "手表已断开，准备重连…")
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setState(STATE_ERROR, "服务发现失败（$status）")
                return
            }
            val nus = g.getService(UUID.fromString(HryFineProtocol.SERVICE_UUID)) ?: run {
                setState(STATE_ERROR, "该设备不是 HryFine 手表（缺少命令服务）")
                g.disconnect()
                return
            }
            writeChar = nus.getCharacteristic(UUID.fromString(HryFineProtocol.WRITE_UUID))
            notifyChar = nus.getCharacteristic(UUID.fromString(HryFineProtocol.NOTIFY_UUID))
            batteryChar = g.getService(UUID.fromString(HryFineProtocol.BATTERY_SERVICE_UUID))
                ?.getCharacteristic(UUID.fromString(HryFineProtocol.BATTERY_CHAR_UUID))
            val w = writeChar
            val n = notifyChar
            if (w == null || n == null) {
                setState(STATE_ERROR, "手表命令通道不完整")
                g.disconnect()
                return
            }
            reassembler.reset()
            g.setCharacteristicNotification(n, true)
            val desc = n.getDescriptor(DescriptorCccd)
            if (desc != null) {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(desc)
            } else {
                startReads()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == DescriptorCccd) startReads()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.removeCallbacks(readTimeout)
            if (status == BluetoothGatt.GATT_SUCCESS) handleRead(ch.uuid, ch.value ?: ByteArray(0))
            readNext()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleNotify(ch.value ?: ByteArray(0))
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.removeCallbacks(readTimeout)
            if (status == BluetoothGatt.GATT_SUCCESS) handleRead(ch.uuid, value)
            readNext()
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(value)
        }
    }

    private val DescriptorCccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

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
        main.postDelayed({ writeFrame(HryFineProtocol.GROUP_GENERAL, HryFineProtocol.SUB_TIME_SYNC, HryFineProtocol.packDateTime(System.currentTimeMillis())) }, 100)
        if (features.sdk) {
            val challenge = Prefs.watchUserId.toByteArray(Charsets.US_ASCII)
            main.postDelayed({ writeFrame(HryFineProtocol.GROUP_CHALLENGE, 0x00, challenge) }, 300)
            main.postDelayed({ writeFrame(HryFineProtocol.GROUP_CHALLENGE_CONFIRM, 0x00, byteArrayOf(0x00)) }, 1200)
        }
        if (features.deviceInfo) {
            main.postDelayed({ writeFrame(HryFineProtocol.GROUP_DEVICE_INFO, 0x00) }, 1700)
        }
        main.postDelayed({ writeFrame(HryFineProtocol.GROUP_BIND, HryFineProtocol.SUB_BIND, Prefs.watchUserId.toByteArray(Charsets.US_ASCII)) }, 2100)
        main.postDelayed({ writeFrame(HryFineProtocol.GROUP_SETTINGS, HryFineProtocol.SUB_SETTINGS_QUERY) }, 2700)
        main.postDelayed({ syncHealth() }, 3100)
        main.postDelayed({
            requestBattery()
            setState(STATE_CONNECTED, "已连接")
            snap = snap.copy(updatedAt = System.currentTimeMillis())
            publish()
        }, HEALTH_WINDOW_MS)
    }

    private fun requestBattery() {
        val g = gatt ?: return
        val ch = batteryChar ?: return
        try {
            g.readCharacteristic(ch)
        } catch (_: Exception) {
        }
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
        val g = gatt ?: return
        val ch = writeChar ?: return
        val bytes = HryFineProtocol.encode(group, sub, payload)
        try {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.value = bytes
            g.writeCharacteristic(ch)
        } catch (e: Exception) {
            LogStore.log("watch", "发送指令失败 group=%02X sub=%02X：%s".format(group, sub, e.message ?: "未知错误"))
        }
    }

    private fun handleNotify(chunk: ByteArray) {
        val g = gatt
        for (raw in reassembler.feed(chunk)) {
            val frame = HryFineProtocol.parse(raw) ?: continue
            if (!frame.isAck) {
                val ack = HryFineProtocol.ack(frame)
                try {
                    writeChar?.let { ch ->
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        ch.value = ack
                        g?.writeCharacteristic(ch)
                    }
                } catch (_: Exception) {
                }
            }
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
                val expected = HryFineProtocol.challengeCode(Prefs.watchUserId.toByteArray(Charsets.US_ASCII))
                val got = frame.payload.getOrNull(1)?.toInt()?.and(0xFF)
                LogStore.log("watch", "挑战校验：" + if (got == expected) "通过" else "不一致（期望 $expected，收到 $got）")
            }
            HryFineProtocol.GROUP_BIND -> {
                val confirmed = (frame.isAck && (frame.payload.lastOrNull()?.toInt()?.and(0xFF) == 0x01)) ||
                    (!frame.isAck && frame.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x01)
                if (confirmed && bindAttempts < 2) {
                    bindAttempts++
                    LogStore.log("watch", "手表要求确认绑定，重新发送绑定请求（第 $bindAttempts 次）")
                    main.postDelayed({
                        writeFrame(HryFineProtocol.GROUP_BIND, HryFineProtocol.SUB_BIND, Prefs.watchUserId.toByteArray(Charsets.US_ASCII))
                    }, 400)
                }
            }
            HryFineProtocol.GROUP_BUTTONS -> if (!frame.isAck) {
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
