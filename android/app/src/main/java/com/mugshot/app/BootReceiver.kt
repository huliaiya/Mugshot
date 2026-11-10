package com.mugshot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启：完成隐私同意且配置完整时自动拉起上报服务 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.consent && Prefs.isConfigured()) {
            ReportService.start(context)
        }
    }
}
