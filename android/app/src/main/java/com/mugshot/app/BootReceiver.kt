package com.mugshot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 后台自启：
 *  - 开机完成（BOOT_COMPLETED）：完成隐私同意且配置完整时自动拉起上报服务
 *  - 应用被覆盖安装/更新后（MY_PACKAGE_REPLACED）：更新会终止进程，重新拉起服务保持在线
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (Prefs.consent && Prefs.isConfigured()) {
                    ReportService.start(context)
                }
            }
        }
    }
}
