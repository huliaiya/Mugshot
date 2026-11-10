package com.mugshot.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 运行日志页：默认"只看本次运行"（服务重启后显示自动从头开始），
 * 可切换到"最近 24 小时"查看文件保留的完整历史（重启不清除日志文件）。
 * 每次进入页面自动加载最新内容，页面停留期间每 3 秒自动刷新并滚动到底部。
 */
class LogsFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private var autoRefresh = false
    private var showHistory = false
    private var lastText: String? = null

    private val refreshTask = object : Runnable {
        override fun run() {
            if (!autoRefresh) return
            reload()
            handler.postDelayed(this, 3_000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_logs, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.btn_refresh_log).setOnClickListener { reload() }
        view.findViewById<Button>(R.id.btn_log_mode).setOnClickListener { toggleMode() }
        reload()
    }

    override fun onResume() {
        super.onResume()
        autoRefresh = true
        handler.post(refreshTask)
    }

    override fun onPause() {
        autoRefresh = false
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun toggleMode() {
        showHistory = !showHistory
        view?.findViewById<Button>(R.id.btn_log_mode)?.setText(
            if (showHistory) R.string.btn_log_current else R.string.btn_log_history
        )
        lastText = null
        reload()
    }

    private fun reload() {
        val view = view ?: return
        val tv = view.findViewById<TextView>(R.id.log_view)
        val scroll = view.findViewById<ScrollView>(R.id.log_scroll)
        val logs = if (showHistory) LogStore.snapshot() else LogStore.snapshotSinceRestart()
        val text = if (logs.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            logs.joinToString("\n")
        }
        // 内容不变时跳过重绘，减少刷新抖动
        if (text == lastText) return
        lastText = text
        tv.text = text
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
