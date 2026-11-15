package com.mugshot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 上报服务看门狗：ReportService 每 15 分钟设置一次重复闹钟，
 * 系统把服务杀掉（激进杀后台的 ROM）时由这里重新拉起。
 * 只在用户已同意隐私且配置完整时动作。
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!Prefs.consent || !Prefs.isConfigured()) return
        if (ReportService.isRunning) return
        LogStore.log("service", "看门狗：上报服务未运行，重新拉起")
        ReportService.start(context)
    }
}
