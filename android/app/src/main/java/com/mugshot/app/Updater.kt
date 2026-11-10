package com.mugshot.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 */
object Updater {

    const val DEFAULT_REPO = "huliaiya/Mugshot"
    private const val TIMEOUT_SEC = 30L

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
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

    /** 下载 APK 到外部缓存，返回文件 */
    fun downloadApk(ctx: Context, url: String): File {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.cacheDir
        val file = File(dir, "mugshot-update.apk")
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("下载失败 HTTP ${resp.code}")
            val sink = file.outputStream()
            resp.body?.byteStream()?.use { input -> sink.use { input.copyTo(it) } }
        }
        return file
    }

    /** 拉起安装器（FileProvider + package archive） */
    fun installApk(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            // 用户未授予"安装未知应用"权限时回退到设置页
            try {
                activity.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${activity.packageName}")),
                )
            } catch (_: Exception) {
            }
        }
    }
}
