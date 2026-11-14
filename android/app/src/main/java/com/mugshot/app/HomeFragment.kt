package com.mugshot.app

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页：连接状态卡（实时）、设备信息卡（2 秒自动刷新）、权限引导面板。
 * 后台服务运行时直接读服务端当前连接状态；未运行时起临时 WS 探测。
 */
class HomeFragment : Fragment(), WsClient.Listener {

    private var probeWs: WsClient? = null
    private var lastState: String = WsClient.STATE_OFFLINE
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private lateinit var statusView: TextView
    private lateinit var statusDetail: TextView
    private lateinit var stateDot: View
    private lateinit var permPanel: LinearLayout
    private lateinit var probeBtn: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        statusView = view.findViewById(R.id.status_view)
        statusDetail = view.findViewById(R.id.status_detail)
        stateDot = view.findViewById(R.id.state_dot)
        permPanel = view.findViewById(R.id.perm_panel)
        probeBtn = view.findViewById(R.id.btn_probe)

        probeBtn.setOnClickListener { probeConnection() }
        view.findViewById<Button>(R.id.btn_usage).setOnClickListener { VendorHelper.openUsageAccessSettings(requireActivity()) }
        view.findViewById<Button>(R.id.btn_battery).setOnClickListener { VendorHelper.requestIgnoreBatteryOptimizations(requireActivity()) }
        view.findViewById<Button>(R.id.btn_autostart).setOnClickListener { VendorHelper.openAutostartSettings(requireActivity()) }

        // 设备信息 2 秒轮询刷新（仅前台期间）
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                refreshDeviceInfo()
                delay(2000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermPanel()
        refreshProbeState()
        ReportService.removeListener(this)
        if (ReportService.isRunning) {
            // 服务在跑：订阅服务的连接状态，实时刷新（不必等 onResume）
            probeWs?.stop()
            probeWs = null
            ReportService.addListener(this)
            lastState = ReportService.currentState
            renderState(lastState, getString(R.string.home_detail_service))
        } else if (Prefs.isConfigured()) {
            probeConnection()
        } else {
            renderState(WsClient.STATE_OFFLINE, getString(R.string.state_detail_empty))
        }
    }

    override fun onPause() {
        ReportService.removeListener(this)
        probeWs?.stop()
        probeWs = null
        super.onPause()
    }

    // ---- 状态渲染 ----

    private fun renderState(state: String, detail: String) {
        if (view == null) return
        statusView.text = when (state) {
            WsClient.STATE_ONLINE -> getString(R.string.state_online)
            WsClient.STATE_CONNECTING -> getString(R.string.state_connecting)
            WsClient.STATE_ERROR -> getString(R.string.state_error)
            else -> getString(R.string.state_offline)
        }
        statusDetail.text = detail
        stateDot.setBackgroundResource(
            when (state) {
                WsClient.STATE_ONLINE -> R.drawable.dot_green
                WsClient.STATE_CONNECTING -> R.drawable.dot_yellow
                else -> R.drawable.dot_red
            },
        )
    }

    // ---- 设备信息卡 ----

    private fun refreshDeviceInfo() {
        val ctx = requireContext().applicationContext
        val s = StatusCollector.collect(ctx)
        setRow(R.id.row_battery, R.string.label_battery, batteryText(s.batteryLevel, s.batteryStatus, s.chargingSource))
        setRow(R.id.row_network, R.string.label_network, networkText(s.networkType, s.networkDetail))
        setRow(R.id.row_screen, R.string.label_screen, getString(if (s.screenOn) R.string.value_screen_on else R.string.value_screen_off))
        setRow(R.id.row_foreground, R.string.label_foreground, foregroundText(ctx, s))
        setRow(R.id.row_device_model, R.string.label_device_model, s.deviceModel.ifBlank { getString(R.string.value_unknown) })
        setRow(R.id.row_android_ver, R.string.label_android_ver, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        setRow(R.id.row_device_name, R.string.label_device_name, Prefs.deviceName)
        setRow(R.id.row_device_id, R.string.label_device_id, Prefs.deviceId.take(8) + "…")
        setRow(R.id.row_report_interval, R.string.label_report_interval, getString(R.string.interval_value, Prefs.reportIntervalSec))
        setRow(
            R.id.row_last_report,
            R.string.label_last_report,
            if (Prefs.lastReportTs > 0) timeFmt.format(Date(Prefs.lastReportTs)) else getString(R.string.value_never),
        )
        setRow(R.id.row_server, R.string.label_server, Prefs.serverUrl.ifBlank { "—" })
    }

    /** 前台应用：拿不到时给出可操作的说明，而非笼统的“未知” */
    private fun foregroundText(ctx: android.content.Context, s: StatusCollector.Snapshot): String {
        if (s.foregroundApp.isNotBlank()) return s.foregroundApp
        if (s.foregroundPackage.isNotBlank()) return s.foregroundPackage
        return when {
            !StatusCollector.hasUsageAccess(ctx) -> "未获取（需授权“使用情况访问”）"
            !s.screenOn -> "未获取（息屏中）"
            else -> getString(R.string.value_unknown)
        }
    }

    private fun setRow(rowId: Int, labelRes: Int, value: String) {
        val row = view?.findViewById<LinearLayout>(rowId) ?: return
        row.findViewById<TextView>(R.id.info_label).setText(labelRes)
        row.findViewById<TextView>(R.id.info_value).text = value
    }

    private fun batteryText(level: Int, status: String, source: String): String {
        val stateText = when {
            status == "full" -> getString(R.string.value_battery_full)
            status == "charging" -> getString(
                when (source) {
                    "usb" -> R.string.value_charging_usb
                    "wireless" -> R.string.value_charging_wireless
                    else -> R.string.value_charging_ac
                },
            )
            status == "discharging" -> getString(R.string.value_discharging)
            else -> getString(R.string.value_unknown)
        }
        return "$level% · $stateText"
    }

    private fun networkText(type: String, detail: String): String = when (type) {
        "wifi" -> getString(R.string.value_net_wifi) + (if (detail.isNotBlank()) " · $detail" else "")
        "mobile" -> getString(R.string.value_net_mobile) + (if (detail.isNotBlank()) " · $detail" else "")
        else -> getString(R.string.value_net_none)
    }

    // ---- 权限面板 ----

    private fun refreshPermPanel() {
        val missing = mutableListOf<String>()
        if (!StatusCollector.hasUsageAccess(requireContext())) missing.add(getString(R.string.perm_name_usage))
        if (!VendorHelper.isIgnoringBatteryOptimizations(requireContext())) missing.add(getString(R.string.perm_name_battery))
        if (VendorHelper.needsNotificationPermission(requireActivity())) missing.add(getString(R.string.perm_name_notification))
        permPanel.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        view?.findViewById<TextView>(R.id.perm_hint)?.text =
            if (missing.isEmpty()) getString(R.string.perm_all_ok)
            else getString(R.string.perm_missing, missing.joinToString("、"))
        if (missing.isNotEmpty()) {
            view?.findViewById<TextView>(R.id.perm_vendor_hint)?.text = VendorHelper.vendorHint(requireContext())
        }
    }

    private fun refreshProbeState() {
        probeBtn.text = if (Prefs.isConfigured()) getString(R.string.btn_retest) else getString(R.string.btn_configure_first)
    }

    // ---- 连接探测（服务未运行时） ----

    private fun probeConnection() {
        if (!Prefs.isConfigured()) {
            Toast.makeText(requireContext(), R.string.toast_need_config, Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.openSettings()
            return
        }
        probeWs?.stop()
        lastState = WsClient.STATE_CONNECTING
        renderState(WsClient.STATE_CONNECTING, getString(R.string.probe_testing))
        probeWs = WsClient(requireContext().applicationContext, this).also { it.start() }
        // 8 秒内没连上就按失败展示
        viewLifecycleOwner.lifecycleScope.launch {
            delay(8000)
            if (probeWs != null && lastState != WsClient.STATE_ONLINE) {
                renderState(WsClient.STATE_ERROR, getString(R.string.probe_timeout))
                probeWs?.stop()
                probeWs = null
            }
        }
    }

    override fun onStateChanged(state: String) {
        lastState = state
        val detail = when (state) {
            WsClient.STATE_ONLINE ->
                "已连接 ${Prefs.serverUrl}\n设备 ID：${Prefs.deviceId.take(8)}…\n设备名：${Prefs.deviceName}"
            WsClient.STATE_CONNECTING -> getString(R.string.probe_testing)
            WsClient.STATE_ERROR -> "连接失败，请检查服务器地址与设备令牌"
            else -> getString(R.string.state_offline)
        }
        lifecycleScope.launch(Dispatchers.Main) { renderState(state, detail) }
    }

    // 首页探测客户端不会收到降级回调（仅前台服务使用），空实现即可
    override fun onServiceDegraded(reason: String) {}
}
