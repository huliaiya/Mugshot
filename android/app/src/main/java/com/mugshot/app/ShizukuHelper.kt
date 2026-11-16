package com.mugshot.app

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 可选增强（需用户自行安装并启动 Shizuku App）。
 *
 * 作用：
 *  - 代为授予「使用情况访问权限」（部分 ROM 的设置页藏得深或直接没有），
 *    从而拿到前台应用、24 小时使用时长与打开次数；
 *  - 执行后台保活命令：电池优化白名单、Doze 白名单、待机桶设为 active、
 *    放宽 RUN_IN_BACKGROUND 限制，缓解"一滑到后台就断连"；
 *  - 使用情况权限缺失时，用 shell 读取 dumpsys 前台 Activity 作为兜底。
 *
 * 未安装 / 未启动 / 未授权时所有方法安全降级，不影响主流程。
 */
object ShizukuHelper {
    const val REQUEST_CODE = 4821
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** 请求授权：结果通过 Shizuku.OnRequestPermissionResultListener 回调 */
    fun requestPermission(): Boolean {
        if (!isRunning()) {
            LogStore.log("shizuku", "Shizuku 服务未运行，请先打开 Shizuku 应用")
            return false
        }
        return try {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        } catch (t: Throwable) {
            LogStore.log("shizuku", "请求 Shizuku 授权失败：" + (t.message ?: "未知错误"))
            false
        }
    }

    // Shizuku 13.1.5 把 newProcess 声明为 private（仅库内部使用），
    // 这里通过反射调用同一实现，避免引入整包 UserService 方案。
    private val newProcessMethod: java.lang.reflect.Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    /** 用 Shizuku 的 shell 身份执行命令，返回 (退出码, 输出)；stderr 一并捕获 */
    fun shell(cmd: String): Pair<Int, String> {
        if (!hasPermission()) return Pair(-1, "未授权 Shizuku")
        val m = newProcessMethod ?: return Pair(-1, "当前 Shizuku 版本不支持执行命令")
        return try {
            val p = m.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            Pair(code, out.trim())
        } catch (t: Throwable) {
            Pair(-1, t.message ?: "shell 执行失败")
        }
    }

    /**
     * 一次性后台保活优化（幂等，可重复执行）：
     *  - 使用情况权限：appops 直接置为 allow，免手动跳设置页
     *  - 后台运行：appops RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND allow
     *  - 电池/Doze 白名单：dumpsys deviceidle whitelist、cmd deviceidle whitelist
     *  - 待机桶：am set-standby-bucket active，避免进入 rare 桶被冻结
     */
    fun applyBackgroundOptimizations(ctx: Context): String {
        if (!hasPermission()) return "未授权 Shizuku"
        val pkg = ctx.packageName
        val cmds = listOf(
            "appops set $pkg GET_USAGE_STATS allow",
            "appops set $pkg RUN_IN_BACKGROUND allow",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "dumpsys deviceidle whitelist +$pkg",
            "cmd deviceidle whitelist +$pkg",
            "am set-standby-bucket $pkg active",
        )
        val failed = mutableListOf<String>()
        for (c in cmds) {
            val (code, out) = shell(c)
            if (code != 0) failed += "$c => ${out.take(80)}"
        }
        val msg = if (failed.isEmpty()) {
            "后台保活优化已应用（${cmds.size} 项）"
        } else {
            "后台优化部分失败（${failed.size}/${cmds.size}）：" + failed.joinToString("；")
        }
        LogStore.log("shizuku", msg)
        return msg
    }

    /**
     * 电量兜底：BatteryManager 与电池广播都读不到时，用 shell 读 dumpsys battery
     * 的 level 字段。结果缓存 1 分钟，避免高频 fork shell 进程。
     */
    fun batteryLevel(): Int {
        val now = System.currentTimeMillis()
        if (now - batteryCacheAt < 60_000L) return batteryCache
        batteryCacheAt = now
        batteryCache = -1
        if (!hasPermission()) return batteryCache
        val (code, out) = shell("dumpsys battery")
        if (code != 0) return batteryCache
        val m = Regex("""level:\s*(\d{1,3})""").find(out) ?: return batteryCache
        batteryCache = m.groupValues[1].toIntOrNull()?.takeIf { it in 0..100 } ?: -1
        if (batteryCache >= 0) LogStore.log("shizuku", "dumpsys 读取电量=${batteryCache}%")
        return batteryCache
    }

    private var batteryCacheAt = 0L
    private var batteryCache = -1

    /** 任务栈快照：visible=屏幕上可见（含分屏/悬浮小窗），background=非可见但仍在最近任务里 */
    data class TaskPackages(val visible: List<String>, val background: List<String>)

    private var tasksCacheAt = 0L
    private var tasksCache = TaskPackages(emptyList(), emptyList())

    /**
     * 用 shell 读任务栈，补足事件流拿不到的场景：
     *  - 部分 ROM 的悬浮小窗 / 自由窗口不产生 ACTIVITY_PAUSED 事件，事件流里看不到
     *  - 后台仍在最近任务里的应用（多任务界面可切回）也只能从任务栈读到
     * 结果缓存 60 秒，避免每次上报都 fork dumpsys（dumpsys activity 输出较大，频繁调用会明显耗电）。
     */
    fun taskPackages(): TaskPackages {
        val now = System.currentTimeMillis()
        if (now - tasksCacheAt < 60_000L) return tasksCache
        tasksCacheAt = now
        if (!hasPermission()) return tasksCache
        val (code, out) = shell("dumpsys activity activities")
        if (code != 0 || out.isEmpty()) return tasksCache
        val visible = LinkedHashSet<String>()
        val background = LinkedHashSet<String>()
        val taskRe = Regex("""Task\{[^}]*\}""")
        val pkgRe = Regex("""\bA=([A-Za-z][\w.]*\.[\w.]+)""")
        for (m in taskRe.findAll(out)) {
            val block = m.value
            val pkg = pkgRe.find(block)?.groupValues?.get(1) ?: continue
            if (block.contains("visible=true")) visible += pkg else background += pkg
        }
        tasksCache = TaskPackages(visible.toList(), background.toList())
        return tasksCache
    }

    /** 窗口快照：visible=屏幕上真正可见的窗口所属应用，focusActivity=当前焦点 Activity */
    data class WindowSnapshot(val visible: List<String>, val focusActivity: String)

    private var winCacheAt = 0L
    private var winCache = WindowSnapshot(emptyList(), "")

    /**
     * 读窗口列表，解决事件流拿不到悬浮小窗/自由窗口的问题（红米澎湃 OS 双小窗 + 前台应用）。
     * 判定可见：窗口属性含 isVisible=true，或 mHasSurface=true 且 mViewVisibility=0x0。
     * 结果缓存 30 秒，dumpsys window 输出较大，不宜高频调用。
     */
    fun windowSnapshot(): WindowSnapshot {
        val now = System.currentTimeMillis()
        if (now - winCacheAt < 30_000L) return winCache
        winCacheAt = now
        if (!hasPermission()) return winCache
        val (code, out) = shell("dumpsys window windows")
        if (code != 0 || out.isEmpty()) return winCache
        val visible = LinkedHashSet<String>()
        val header = Regex("""Window\{[^}]*?\s(?:u\d+\s+)?([A-Za-z][\w.]*)/[\w.$]+\}""")
        val matches = header.findAll(out).toList()
        for ((i, m) in matches.withIndex()) {
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else out.length
            val block = out.substring(m.range.last, end.coerceAtLeast(m.range.last))
            val shown = block.contains("isVisible=true") ||
                (block.contains("mHasSurface=true") && block.contains("mViewVisibility=0x0"))
            if (shown) visible += m.groupValues[1]
        }
        val focus = Regex(
            """mCurrentFocus=Window\{[^}]*?\s(?:u\d+\s+)?([A-Za-z][\w.]*)/([\w.$]+)\}""",
        ).find(out)
        val focusActivity = focus?.let { "${it.groupValues[1]}/${it.groupValues[2]}" } ?: ""
        winCache = WindowSnapshot(visible.toList(), focusActivity)
        return winCache
    }

    /**
     * 使用情况权限不可用时的前台应用兜底：shell 读 dumpsys activity，
     * 取当前 resumed/top 的包名。失败返回空串。
     */
    fun dumpsysForegroundPackage(): String {
        if (!hasPermission()) return ""
        val (code, out) = shell("dumpsys activity activities")
        if (code != 0 || out.isEmpty()) return ""
        val re = Regex(
            """(?:mResumedActivity|topResumedActivity|mFocusedApp)[^\n]*?([A-Za-z][\w.]*\.[\w.]+)/""",
        )
        return re.find(out)?.groupValues?.get(1) ?: ""
    }
}
