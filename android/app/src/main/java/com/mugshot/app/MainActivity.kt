package com.mugshot.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面：连接状态、配置入口、权限引导（使用情况访问/电池优化/自启动/通知）、检查更新。
 */
class MainActivity : AppCompatActivity(), WsClient.Listener {

    private lateinit var statusView: TextView
    private lateinit var statusDetail: TextView
    private lateinit var stateDot: View
    private lateinit var permPanel: LinearLayout
    private lateinit var probeBtn: Button
    private var probeWs: WsClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status_view)
        statusDetail = findViewById(R.id.status_detail)
        stateDot = findViewById(R.id.state_dot)
        permPanel = findViewById(R.id.perm_panel)

        if (!Prefs.consent) {
            startActivity(android.content.Intent(this, PrivacyActivity::class.java))
            finish()
            return
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_usage).setOnClickListener { VendorHelper.openUsageAccessSettings(this) }
        findViewById<Button>(R.id.btn_battery).setOnClickListener { VendorHelper.requestIgnoreBatteryOptimizations(this) }
        findViewById<Button>(R.id.btn_autostart).setOnClickListener { VendorHelper.openAutostartSettings(this) }
        findViewById<Button>(R.id.btn_privacy).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.title_privacy)
                .setMessage(PrivacyActivity().buildPrivacyText())
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        findViewById<Button>(R.id.btn_check_update).setOnClickListener { checkUpdate() }
        probeBtn = findViewById(R.id.btn_probe)
        probeBtn.setOnClickListener { probeConnection() }

        VendorHelper.requestNotificationPermission(this, 1001)
    }

    override fun onResume() {
        super.onResume()
        refreshPermPanel()
        refreshProbeState()
        // 前台时用临时 WS 客户端展示实时连接状态（服务不在运行也可见）
        if (!ReportService.isRunning) {
            probeConnection()
        } else {
            setStatus(WsClient.STATE_ONLINE, "后台服务运行中")
        }
    }

    override fun onPause() {
        super.onPause()
        probeWs?.stop()
        probeWs = null
    }

    private fun refreshPermPanel() {
        val missing = mutableListOf<String>()
        if (!StatusCollector.hasUsageAccess(this)) missing.add("使用情况访问权限")
        if (!VendorHelper.isIgnoringBatteryOptimizations(this)) missing.add("电池优化白名单")
        if (VendorHelper.needsNotificationPermission(this)) missing.add("通知权限")
        permPanel.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.perm_hint).text =
            if (missing.isEmpty()) getString(R.string.perm_all_ok)
            else getString(R.string.perm_missing, missing.joinToString("、"))
        if (missing.isNotEmpty()) {
            findViewById<TextView>(R.id.perm_vendor_hint).text = VendorHelper.vendorHint(this)
        }
    }

    private fun setStatus(state: String, detail: String) {
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

    private fun refreshProbeState() {
        probeBtn.text = if (Prefs.isConfigured()) getString(R.string.btn_retest) else getString(R.string.btn_configure_first)
    }

    private fun probeConnection() {
        if (!Prefs.isConfigured()) {
            Toast.makeText(this, R.string.toast_need_config, Toast.LENGTH_SHORT).show()
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            return
        }
        probeWs?.stop()
        lastState = WsClient.STATE_CONNECTING
        setStatus(WsClient.STATE_CONNECTING, getString(R.string.probe_testing))
        probeWs = WsClient(applicationContext, this).also { it.start() }
        // 8 秒内没连上就按失败展示
        lifecycleScope.launch {
            kotlinx.coroutines.delay(8000)
            if (probeWs != null && lastState != WsClient.STATE_ONLINE) {
                setStatus(WsClient.STATE_ERROR, getString(R.string.probe_timeout))
                probeWs?.stop()
                probeWs = null
            }
        }
    }

    private var lastState: String = WsClient.STATE_OFFLINE

    override fun onStateChanged(state: String) {
        lastState = state
        lifecycleScope.launch(Dispatchers.Main) {
            val detail = when (state) {
                WsClient.STATE_ONLINE ->
                    "已连接 ${Prefs.serverUrl}\n设备 ID：${Prefs.deviceId.take(8)}…\n设备名：${Prefs.deviceName}"
                WsClient.STATE_CONNECTING -> "正在连接 ${Prefs.serverUrl}"
                WsClient.STATE_ERROR -> "连接失败，请检查服务器地址与设备令牌"
                else -> "未连接"
            }
            setStatus(state, detail)
        }
    }

    // 主界面探测客户端不会收到降级回调（仅前台服务使用），空实现即可
    override fun onServiceDegraded(reason: String) {}

    private fun checkUpdate() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { Updater.checkUpdate(Updater.DEFAULT_REPO) }
            val local = Updater.currentVersion(this@MainActivity)
            when {
                info == null -> Toast.makeText(this@MainActivity, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                !Updater.isNewer(info.tag, local) ->
                    Toast.makeText(this@MainActivity, getString(R.string.update_latest, local), Toast.LENGTH_SHORT).show()
                else -> {
                    val apkUrl = info.apkUrl
                    if (apkUrl == null) {
                        Toast.makeText(this@MainActivity, getString(R.string.update_no_apk, info.tag), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.update_downloading, info.tag), Toast.LENGTH_SHORT).show()
                        try {
                            val apk = withContext(Dispatchers.IO) { Updater.downloadApk(this@MainActivity, apkUrl) }
                            Updater.installApk(this@MainActivity, apk)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, getString(R.string.update_failed, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}
