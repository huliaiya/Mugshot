package com.mugshot.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志：内存缓冲 + files/runtime.log 持久化。
 *
 * - 只保留最近 24 小时的记录，加载与写入时都会裁剪过期行
 * - 日志页每次打开都重新加载最新内容（不清空历史，服务重启只追加分隔行）
 * - 超过单行上限后整文件重写，防止无限膨胀
 */
object LogStore {
    private const val MAX_AGE_MS = 24 * 3_600_000L
    private const val MAX_LINES = 2000
    private const val MAX_BYTES = 512L * 1024
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val lock = Any()
    private var inited = false

    /** 本次运行的起点：重启只清"显示"，文件里的历史保留 */
    private var restartMark = 0

    fun init(ctx: Context) {
        synchronized(lock) {
            if (inited) return
            inited = true
            val f = File(ctx.filesDir, "runtime.log")
            file = f
            if (f.exists()) {
                try {
                    val cutoff = System.currentTimeMillis() - MAX_AGE_MS
                    f.readLines().forEach { line ->
                        if (parseTs(line) >= cutoff) lines.addLast(line)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun log(tag: String, msg: String) {
        val line = fmt.format(Date()) + " " + tag + ": " + msg
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            file?.let { f ->
                try {
                    if (f.length() > MAX_BYTES) {
                        pruneLocked()
                        f.writeText(lines.joinToString("\n") + "\n")
                    } else {
                        f.appendText(line + "\n")
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 最近 24 小时的日志（最新在末尾，来自文件保留的历史） */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    /** 本次运行以来的日志：服务每次（重）启动时打标记，之后只显示新增内容 */
    fun snapshotSinceRestart(): List<String> = synchronized(lock) {
        if (restartMark >= lines.size) emptyList() else lines.toList().drop(restartMark)
    }

    /** 服务（重）启动时调用：显示从标记行重新开始，文件历史不受影响 */
    fun markRestart(reason: String) {
        log("service", "──── 上报服务${reason}，以下为本次运行日志 ────")
        synchronized(lock) { restartMark = (lines.size - 1).coerceAtLeast(0) }
    }

    private fun pruneLocked() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        while (lines.isNotEmpty() && parseTs(lines.first()) < cutoff) lines.removeFirst()
    }

    private fun parseTs(line: String): Long = try {
        val m = line.split(" ", limit = 3)
        if (m.size < 2) 0L
        else fmt.parse(m[0] + " " + m[1])?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}
