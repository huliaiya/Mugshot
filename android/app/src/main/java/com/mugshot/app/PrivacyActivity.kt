package com.mugshot.app

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 首启隐私政策页（Launcher 入口）：
 *  - 未同意：仅展示政策，同意后才进入主界面；不同意则退出
 *  - 已同意：直接进入主界面
 *  - 同意前不启动任何网络上报
 */
class PrivacyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.consent) {
            goMain()
            return
        }
        setContentView(R.layout.activity_privacy)

        findViewById<TextView>(R.id.privacy_text).apply {
            text = buildPrivacyText()
            movementMethod = ScrollingMovementMethod()
        }

        findViewById<Button>(R.id.btn_agree).setOnClickListener {
            Prefs.consent = true
            goMain()
        }
        findViewById<Button>(R.id.btn_disagree).setOnClickListener {
            finishAffinity()
        }
    }

    private fun goMain() {
        startActivity(android.content.Intent(this, MainActivity::class.java))
        finish()
    }

    private fun buildPrivacyText(): String = """
        Mugshot 隐私政策

        最后更新：2026-11-10

        1. 我们收集什么
        Mugshot 仅在你明确同意后，采集并向你自行部署的 mugshot-server 上报以下设备状态：
        · 屏幕亮/灭状态
        · 当前前台应用名称与包名
        · 电池电量、充电状态与充电方式（交流电/USB/无线）
        · 网络类型（Wi-Fi/移动数据/无网络）
        · 设备型号与你自定义的设备名称

        2. 我们不收集什么
        · 不收集你的通讯录、短信、通话记录、照片、定位
        · 不读取应用内容，仅记录"哪个应用在前台"
        · 不上报浏览记录、输入内容或剪贴板

        3. 数据去向与存储
        · 数据仅发送到你自行配置的 mugshot-server 地址
        · 服务器仅保留最近 N 天（默认 7 天）状态记录，可随时删除
        · 我们（应用开发者）不收集、不中转、不存储任何数据

        4. 权限说明
        · 使用情况访问权限：用于获取前台应用名称（可选，拒绝后该字段为空）
        · 通知权限：用于展示常驻通知（Android 13+ 必需）
        · 电池优化白名单：用于保持后台稳定上报（可选）

        5. 数据控制
        · 你可以随时在设置中停止服务或卸载应用
        · 服务端数据的保留期限由你自己部署的 mugshot-server 配置决定

        继续使用即表示你理解并同意上述内容。
    """.trimIndent()
}
