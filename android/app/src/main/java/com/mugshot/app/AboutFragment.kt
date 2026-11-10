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
        // 版本显示构建时的 Git 短哈希（CI 注入 -PgitHash=<sha8>）
        view.findViewById<TextView>(R.id.tv_version).text = BuildConfig.GIT_HASH
        view.findViewById<TextView>(R.id.tv_version_name).text = "v${Updater.currentVersion(requireContext())} · ${getString(R.string.about_build_version)}"

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
        val ctx = requireContext()
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { Updater.checkUpdate(Updater.DEFAULT_REPO) }
            val local = Updater.currentVersion(ctx)
            when {
                info == null -> Toast.makeText(ctx, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                !Updater.isNewer(info.tag, local) ->
                    Toast.makeText(ctx, getString(R.string.update_latest, local), Toast.LENGTH_SHORT).show()
                else -> {
                    val apkUrl = info.apkUrl
                    if (apkUrl == null) {
                        Toast.makeText(ctx, getString(R.string.update_no_apk, info.tag), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(ctx, getString(R.string.update_downloading, info.tag), Toast.LENGTH_SHORT).show()
                        try {
                            val apk = withContext(Dispatchers.IO) { Updater.downloadApk(ctx, apkUrl) }
                            Updater.installApk(requireActivity(), apk)
                        } catch (e: Exception) {
                            Toast.makeText(ctx, getString(R.string.update_failed, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}
