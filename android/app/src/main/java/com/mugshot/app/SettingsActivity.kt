package com.mugshot.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置页：服务器地址、设备令牌、设备名称、上报周期。
 * 保存后重启上报服务以应用新配置。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etName: EditText
    private lateinit var sbInterval: SeekBar
    private lateinit var tvInterval: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etUrl = findViewById(R.id.et_server_url)
        etToken = findViewById(R.id.et_device_token)
        etName = findViewById(R.id.et_device_name)
        sbInterval = findViewById(R.id.sb_interval)
        tvInterval = findViewById(R.id.tv_interval)

        etUrl.setText(Prefs.serverUrl)
        etToken.setText(Prefs.deviceToken)
        etName.setText(Prefs.deviceName)

        // 周期范围 30~1800 秒，步进 30
        val interval = Prefs.reportIntervalSec
        sbInterval.max = 60 // (60+1)*30 = 1830
        sbInterval.progress = (interval - 30) / 30
        tvInterval.text = getString(R.string.interval_value, interval)
        sbInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                tvInterval.text = getString(R.string.interval_value, 30 + progress * 30)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
    }

    private fun save() {
        val url = etUrl.text.toString().trim()
        val token = etToken.text.toString().trim()
        val name = etName.text.toString().trim().ifEmpty { Prefs.defaultDeviceName() }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, R.string.toast_bad_url, Toast.LENGTH_SHORT).show()
            return
        }
        if (token.isEmpty()) {
            Toast.makeText(this, R.string.toast_bad_token, Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.serverUrl = url
        Prefs.deviceToken = token
        Prefs.deviceName = name
        Prefs.reportIntervalSec = 30 + sbInterval.progress * 30

        // 重启服务应用新配置
        if (Prefs.consent) {
            ReportService.stop(this)
            ReportService.start(this)
        }
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
