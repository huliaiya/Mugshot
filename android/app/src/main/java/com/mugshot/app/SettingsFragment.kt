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
import com.mugshot.app.watch.WatchManager

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

        // 运行日志自动滚动开关
        val swAutoScroll = view.findViewById<android.widget.Switch>(R.id.sw_log_autoscroll)
        swAutoScroll.isChecked = Prefs.logAutoScroll
        swAutoScroll.setOnCheckedChangeListener { _, checked -> Prefs.logAutoScroll = checked }

        // 手表自动同步：总开关 + 间隔 + 三项自动测量（保存即时生效）
        setupWatchAutoSync(view)

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

    /** 手表自动同步设置：所有改动立即落盘并重建定时任务，无需点保存 */
    private fun setupWatchAutoSync(root: View) {
        val swSync = root.findViewById<android.widget.Switch>(R.id.sw_watch_auto_sync)
        val body = root.findViewById<View>(R.id.ll_watch_auto_body)
        val sbMin = root.findViewById<SeekBar>(R.id.sb_watch_auto_min)
        val tvMin = root.findViewById<TextView>(R.id.tv_watch_auto_min)
        val swHr = root.findViewById<android.widget.Switch>(R.id.sw_watch_auto_hr)
        val swBp = root.findViewById<android.widget.Switch>(R.id.sw_watch_auto_bp)
        val swSpo2 = root.findViewById<android.widget.Switch>(R.id.sw_watch_auto_spo2)

        // 间隔范围 5~360 分钟，步进 5
        val step = 5
        val maxProgress = (360 - 5) / step
        sbMin.max = maxProgress
        sbMin.progress = ((Prefs.watchAutoSyncMin - 5) / step).coerceIn(0, maxProgress)
        tvMin.text = getString(R.string.watch_auto_sync_min_value, Prefs.watchAutoSyncMin)

        swSync.isChecked = Prefs.watchAutoSync
        swHr.isChecked = Prefs.watchAutoHeart
        swBp.isChecked = Prefs.watchAutoBp
        swSpo2.isChecked = Prefs.watchAutoSpo2
        body.visibility = if (Prefs.watchAutoSync) View.VISIBLE else View.GONE

        swSync.setOnCheckedChangeListener { _, checked ->
            Prefs.watchAutoSync = checked
            body.visibility = if (checked) View.VISIBLE else View.GONE
            WatchManager.refreshAutoSync()
        }
        sbMin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                tvMin.text = getString(R.string.watch_auto_sync_min_value, 5 + progress * step)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {
                Prefs.watchAutoSyncMin = 5 + bar.progress * step
                WatchManager.refreshAutoSync()
            }
        })
        swHr.setOnCheckedChangeListener { _, checked -> Prefs.watchAutoHeart = checked }
        swBp.setOnCheckedChangeListener { _, checked -> Prefs.watchAutoBp = checked }
        swSpo2.setOnCheckedChangeListener { _, checked -> Prefs.watchAutoSpo2 = checked }
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
        val oldUrl = Prefs.serverUrl
        val oldToken = Prefs.deviceToken
        val oldName = Prefs.deviceName
        val oldInterval = Prefs.reportIntervalSec
        Prefs.serverUrl = url
        Prefs.deviceToken = token
        Prefs.deviceName = name
        Prefs.reportIntervalSec = 30 + sbInterval.progress * 30
        // 变更明细（Token 只记录是否更换，不落日志值）
        LogStore.log(
            "config",
            "配置已保存" +
                (if (oldUrl != url) " 服务器: $oldUrl -> $url" else "") +
                (if (oldToken != token) " Token: 已更换" else "") +
                (if (oldName != name) " 设备名: $oldName -> $name" else "") +
                (if (oldInterval != Prefs.reportIntervalSec) " 上报间隔: ${oldInterval}s -> ${Prefs.reportIntervalSec}s" else "") +
                (if (oldUrl == url && oldToken == token && oldName == name && oldInterval == Prefs.reportIntervalSec)
                    "（内容无变化）" else "")
        )

        // 重启服务应用新配置
        if (Prefs.consent) {
            ReportService.stop(ctx)
            ReportService.start(ctx)
        }
        Toast.makeText(ctx, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }
}
