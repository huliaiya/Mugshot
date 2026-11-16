package com.mugshot.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mugshot.app.watch.WatchManager

/**
 * 手表连接页：扫描附近 BLE 设备、手动选择手表、展示连接进度。
 * 连接成功后设备地址写入 Prefs，之后每次启动自动回连，无需再次进入本页。
 */
class WatchConnectActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var unbindButton: Button
    private var finishOnConnect = false

    private val listener: (WatchManager.Snapshot) -> Unit = { s -> render(s) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch_connect)
        status = findViewById(R.id.connect_status)
        list = findViewById(R.id.connect_list)
        findViewById<View>(R.id.btn_connect_back).setOnClickListener { finish() }
        unbindButton = findViewById(R.id.btn_connect_unbind)
        unbindButton.setOnClickListener { confirmUnbind() }
        findViewById<Button>(R.id.btn_connect_rescan).setOnClickListener {
            startScan(disconnect = WatchManager.snapshot().connected)
        }
        WatchManager.addListener(listener)
        startScan()
    }

    override fun onDestroy() {
        WatchManager.removeListener(listener)
        super.onDestroy()
    }

    private fun startScan(disconnect: Boolean = false) {
        if (WatchManager.snapshot().connected && !disconnect) return
        if (!Prefs.consent) {
            status.text = getString(R.string.watch_need_consent)
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERM)
            return
        }
        if (!Prefs.watchEnabled) WatchManager.setEnabled(true) else WatchManager.rescan(disconnect)
    }

    private fun missingPermissions(): List<String> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (!Prefs.watchEnabled) WatchManager.setEnabled(true) else WatchManager.rescan()
        } else {
            status.text = getString(R.string.watch_perm_denied)
        }
    }

    private fun render(s: WatchManager.Snapshot) {
        status.text = when {
            s.connected -> getString(R.string.watch_connect_done, s.name.ifBlank { s.address })
            s.state == WatchManager.STATE_BINDING -> s.detail.ifBlank { getString(R.string.watch_status_connecting) }
            s.state == WatchManager.STATE_CONNECTING -> getString(R.string.watch_status_connecting)
            s.state == WatchManager.STATE_SCANNING -> s.detail.ifBlank { getString(R.string.watch_status_scanning) }
            s.state == WatchManager.STATE_ERROR -> s.detail.ifBlank { getString(R.string.watch_status_off) }
            else -> getString(R.string.watch_pick_hint)
        }
        unbindButton.visibility = if (s.connected || s.address.isNotBlank()) View.VISIBLE else View.GONE
        renderList(s)
        if (s.connected && finishOnConnect) {
            setResult(RESULT_OK)
            status.postDelayed({ if (!isFinishing) finish() }, 900)
        }
    }

    private fun confirmUnbind() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.watch_unbind_title)
            .setMessage(R.string.watch_unbind_msg)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                finishOnConnect = false
                WatchManager.unbind()
                Toast.makeText(this, R.string.watch_unbind_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderList(s: WatchManager.Snapshot) {
        list.removeAllViews()
        if (s.connected) {
            list.addView(hintRow(getString(R.string.watch_connect_saved)))
            return
        }
        if (s.candidates.isEmpty()) {
            list.addView(hintRow(if (s.state == WatchManager.STATE_SCANNING) getString(R.string.watch_status_scanning) else getString(R.string.watch_no_device)))
            return
        }
        for (c in s.candidates) {
            val base = if (c.name.isBlank()) c.address else c.name
            val tags = mutableListOf<String>()
            if (c.address == Prefs.watchAddress) tags += getString(R.string.watch_tag_saved)
            if (c.likelyWatch) tags += getString(R.string.watch_tag_likely)
            val suffix = if (tags.isEmpty()) "" else "  ·  " + tags.joinToString(" / ")
            val btn = Button(this).apply {
                text = "$base$suffix\n${c.address}   ${c.rssi} dBm"
                isAllCaps = false
                textSize = 13f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    status.text = getString(R.string.watch_status_connecting)
                    finishOnConnect = true
                    WatchManager.connectCandidate(c.address, c.name)
                }
            }
            list.addView(
                btn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = 8 },
            )
        }
    }

    private fun hintRow(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(ContextCompat.getColor(this@WatchConnectActivity, R.color.text_sub))
        textSize = 13f
        setPadding(0, 12, 0, 12)
    }

    private companion object {
        const val REQ_PERM = 2001
    }
}
