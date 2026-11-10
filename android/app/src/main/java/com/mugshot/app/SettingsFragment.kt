package com.mugshot.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * 设置页：服务器地址、设备令牌、设备名称、上报周期。
 * 保存后重启上报服务以应用新配置。
 */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etUrl = view.findViewById<EditText>(R.id.et_server_url)
        val etToken = view.findViewById<EditText>(R.id.et_device_token)
        val etName = view.findViewById<EditText>(R.id.et_device_name)
        val sbInterval = view.findViewById<SeekBar>(R.id.sb_interval)
        val tvInterval = view.findViewById<TextView>(R.id.tv_interval)

        etUrl.setText(Prefs.serverUrl)
        etToken.setText(Prefs.deviceToken)
        etName.setText(Prefs.deviceName)

        // 主题外观：切换立即生效（AppCompat 自动重建界面）
        val rgTheme = view.findViewById<RadioGroup>(R.id.rg_theme)
        rgTheme.check(
            when (Prefs.themeMode) {
                Prefs.THEME_LIGHT -> R.id.rb_light
                Prefs.THEME_DARK -> R.id.rb_dark
                else -> R.id.rb_follow
            },
        )
        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_light -> Prefs.THEME_LIGHT
                R.id.rb_dark -> Prefs.THEME_DARK
                else -> Prefs.THEME_FOLLOW_SYSTEM
            }
            if (mode != Prefs.themeMode) {
                Prefs.themeMode = mode
                MugshotApp.instance.applyTheme()
            }
        }

        // 周期范围 30~1830 秒，步进 30
        sbInterval.max = 60
        sbInterval.progress = (Prefs.reportIntervalSec - 30) / 30
        tvInterval.text = getString(R.string.interval_value, Prefs.reportIntervalSec)
        sbInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                tvInterval.text = getString(R.string.interval_value, 30 + progress * 30)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        view.findViewById<Button>(R.id.btn_save).setOnClickListener { save(etUrl, etToken, etName, sbInterval) }
    }

    private fun save(
        etUrl: EditText,
        etToken: EditText,
        etName: EditText,
        sbInterval: SeekBar,
    ) {
        val ctx = requireContext()
        val url = etUrl.text.toString().trim()
        val token = etToken.text.toString().trim()
        val name = etName.text.toString().trim().ifEmpty { Prefs.defaultDeviceName() }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(ctx, R.string.toast_bad_url, Toast.LENGTH_SHORT).show()
            return
        }
        if (token.isEmpty()) {
            Toast.makeText(ctx, R.string.toast_bad_token, Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.serverUrl = url
        Prefs.deviceToken = token
        Prefs.deviceName = name
        Prefs.reportIntervalSec = 30 + sbInterval.progress * 30
        LogStore.log("config", "配置已保存 url=$url interval=${Prefs.reportIntervalSec}s")

        // 重启服务应用新配置
        if (Prefs.consent) {
            ReportService.stop(ctx)
            ReportService.start(ctx)
        }
        Toast.makeText(ctx, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }
}
