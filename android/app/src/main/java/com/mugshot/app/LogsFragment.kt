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
 * 运行日志页：展示本机上报服务最近 24 小时的运行记录。
 * 每次进入页面自动加载最新内容，页面停留期间每 3 秒自动刷新并滚动到底部。
 */
class LogsFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private var autoRefresh = false

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

    private fun reload() {
        val view = view ?: return
        val tv = view.findViewById<TextView>(R.id.log_view)
        val scroll = view.findViewById<ScrollView>(R.id.log_scroll)
        val logs = LogStore.snapshot()
        tv.text = if (logs.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            logs.joinToString("\n")
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
