package com.mugshot.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关于页：版本号（构建时的 Git 短哈希）、检查更新、隐私政策。
 */
class AboutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_about, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 软件版本号 = 构建时的 Git 短哈希（与系统设置-应用信息中显示一致）；更新检查按发布 tag 比较
        view.findViewById<TextView>(R.id.tv_version).text = BuildConfig.GIT_HASH

        setRow(view, R.id.row_author, R.string.label_author, "huliaiya")
        setRow(view, R.id.row_repo, R.string.label_repo, Updater.DEFAULT_REPO)
        setRow(view, R.id.row_pkg, R.string.label_package, requireContext().packageName)

        view.findViewById<Button>(R.id.btn_check_update).setOnClickListener { checkUpdate() }
        view.findViewById<Button>(R.id.btn_privacy).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.title_privacy)
                .setMessage(PrivacyActivity().buildPrivacyText())
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun setRow(view: View, rowId: Int, labelRes: Int, value: String) {
        val row = view.findViewById<LinearLayout>(rowId)
        row.findViewById<TextView>(R.id.info_label).setText(labelRes)
        row.findViewById<TextView>(R.id.info_value).text = value
    }

    private fun checkUpdate() {
        val ctx = requireContext().applicationContext
        LogStore.log("update", "手动检查更新（当前版本 ${Updater.currentVersion(ctx)}）")
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { Updater.checkUpdate(Updater.DEFAULT_REPO) }
            val local = Updater.currentVersion(ctx)
            when {
                info == null -> {
                    LogStore.log("update", "更新检查失败（主源与备用源均不可达）")
                    Toast.makeText(ctx, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                }
                !Updater.isNewer(info.tag, local) -> {
                    LogStore.log("update", "已是最新（远端 ${info.tag}，本地 v$local）")
                    Toast.makeText(ctx, getString(R.string.update_latest, local), Toast.LENGTH_SHORT).show()
                }
                else -> {
                    val apkUrl = info.apkUrl
                    if (apkUrl == null) {
                        LogStore.log("update", "发现新版本 ${info.tag}，但 Release 无 APK 附件")
                        Toast.makeText(ctx, getString(R.string.update_no_apk, info.tag), Toast.LENGTH_LONG).show()
                    } else {
                        LogStore.log("update", "发现新版本 ${info.tag}，开始下载")
                        Toast.makeText(ctx, getString(R.string.update_downloading, info.tag), Toast.LENGTH_SHORT).show()
                        try {
                            val apk = withContext(Dispatchers.IO) { Updater.downloadApk(ctx, apkUrl) }
                            val act = activity
                            if (act == null) {
                                // 下载耗时较长，用户可能已离开界面：此时不能 requireActivity()（会崩）
                                LogStore.log("update", "下载完成但界面已离开，未拉起安装器；可再次点击检查更新安装")
                                Toast.makeText(ctx, R.string.update_left_page, Toast.LENGTH_LONG).show()
                            } else {
                                Updater.installApk(act, apk)
                            }
                        } catch (e: Exception) {
                            LogStore.log("update", "更新失败：${e.message ?: "未知错误"}")
                            Toast.makeText(ctx, getString(R.string.update_failed, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}
