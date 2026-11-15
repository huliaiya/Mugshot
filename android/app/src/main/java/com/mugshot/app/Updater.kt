package com.mugshot.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：GitHub Releases 版本检查（主源 api.github.com + 备用源 releases/latest 重定向解析）。
 * 下载 APK 到外部缓存目录后经 FileProvider 拉起安装（需"安装未知应用"授权）。
 *
 * 下载健壮性：.part 临时文件 + 校验后原子改名（杜绝半截包被安装）、
 * 失败自动重试 3 次、下载链路独立 30s/60s 超时（弱网 GitHub 慢速场景）。
 */
object Updater {

    const val DEFAULT_REPO = "huliaiya/Mugshot"
    private const val TIMEOUT_SEC = 30L

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    // 下载专用：GitHub 大文件在弱网下容易读超时，放宽到 60s 并带重试
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class ReleaseInfo(val tag: String, val notes: String, val apkUrl: String?)

    /** 当前构建对应的发布版本号（tag 构建注入，dev 构建为 0.0.0），用于更新比较 */
    fun currentVersion(ctx: Context): String = BuildConfig.GIT_TAG

    /** 主源 + 备用源 failover */
    fun checkUpdate(repo: String): ReleaseInfo? {
        val r = repo.trim().trimEnd('/')
        if (r.isEmpty() || r.contains(" ")) return null
        return try {
            checkViaApi(r)
        } catch (_: Exception) {
            try {
                checkViaRedirect(r)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun checkViaApi(repo: String): ReleaseInfo {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw RuntimeException("empty body")
            val json = JSONObject(body)
            val assets = json.optJSONArray("assets") ?: JSONArray()
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url", "")
                    break
                }
            }
            return ReleaseInfo(json.optString("tag_name", ""), json.optString("body", ""), apkUrl)
        }
    }

    /** 备用源：跟随 /releases/latest 的 302 重定向，从 Location 提取 tag */
    private fun checkViaRedirect(repo: String): ReleaseInfo {
        val noRedirect = client.newBuilder().followRedirects(false).build()
        val req = Request.Builder().url("https://github.com/$repo/releases/latest").build()
        noRedirect.newCall(req).execute().use { resp ->
            val loc = resp.header("Location") ?: throw RuntimeException("no redirect")
            val tag = loc.trimEnd('/').substringAfterLast('/')
            val apkUrl = "https://github.com/$repo/releases/download/$tag/Mugshot-$tag.apk"
            return ReleaseInfo(tag, "", apkUrl)
        }
    }

    /** 数值化比较版本：v1.2.3 -> [1,2,3] */
    fun isNewer(remote: String, local: String): Boolean {
        fun parse(v: String) = v.removePrefix("v").removePrefix("V").split(".", "-")
            .map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val a = parse(remote)
        val b = parse(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 下载 APK（.part 临时文件 + 校验 + 原子改名，重试 3 次），返回可安装的完整包 */
    fun downloadApk(ctx: Context, url: String): File {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.cacheDir
        val file = File(dir, "mugshot-update.apk")
        val part = File(dir, "mugshot-update.apk.part")
        var lastErr: Exception? = null
        for (attempt in 1..3) {
            try {
                val expected = tryDownload(url, part)
                if (part.length() <= 0L) throw RuntimeException("下载内容为空")
                if (expected > 0L && part.length() != expected) {
                    throw RuntimeException("下载不完整（${part.length()}/${expected} 字节）")
                }
                // 校验通过后原子替换，保证安装时拿到的永远是完整包
                if (file.exists() && !file.delete()) throw RuntimeException("清理旧安装包失败")
                if (!part.renameTo(file)) throw RuntimeException("安装包落盘失败")
                LogStore.log("update", "APK 下载完成（${file.length() / 1024}KB，第 $attempt 次尝试）")
                return file
            } catch (e: Exception) {
                lastErr = e
                LogStore.log("update", "下载失败（第 $attempt/3 次）：${e.message ?: "未知错误"}")
                try {
                    part.delete()
                } catch (_: Exception) {
                }
                if (attempt < 3) Thread.sleep(1500L * attempt)
            }
        }
        throw lastErr ?: RuntimeException("下载失败")
    }

    private fun tryDownload(url: String, part: File): Long {
        val req = Request.Builder().url(url).build()
        downloadClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("下载失败 HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("下载内容为空")
            val expected = body.contentLength()
            body.byteStream().use { input ->
                part.outputStream().use { output -> input.copyTo(output) }
            }
            return expected
        }
    }

    /** 拉起安装器（FileProvider + package archive）；Android 8+ 未授权"安装未知应用"时先跳授权页 */
    fun installApk(activity: Activity, apk: File) {
        if (!apk.exists() || apk.length() <= 0L) {
            LogStore.log("update", "安装包不存在或为空（${apk.absolutePath}），放弃安装")
            return
        }
        val uri = try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        } catch (e: Exception) {
            LogStore.log("update", "生成 APK 内容 URI 失败：${e.message ?: "未知错误"}")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            LogStore.log("update", "未授予「安装未知应用」权限，先跳转授权页（授权后再次检查更新即可安装）")
            openUnknownSourceSettings(activity)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
            LogStore.log("update", "已拉起系统安装器（${apk.length() / 1024}KB）")
        } catch (e: Exception) {
            LogStore.log("update", "拉起安装器失败（${e.message ?: "未知错误"}），转授权页")
            openUnknownSourceSettings(activity)
        }
    }

    private fun openUnknownSourceSettings(activity: Activity) {
        try {
            activity.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${activity.packageName}")),
            )
        } catch (_: Exception) {
        }
    }
}
