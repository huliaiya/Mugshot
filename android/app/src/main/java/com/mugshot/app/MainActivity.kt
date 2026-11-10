package com.mugshot.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * 主界面壳：底部导航（首页 / 设置 / 关于）+ Fragment 容器。
 * 首次启动先进隐私同意页；未同意隐私前不初始化任何采集。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Prefs.consent) {
            startActivity(Intent(this, PrivacyActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)

        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> switchTo(HomeFragment())
                R.id.nav_settings -> switchTo(SettingsFragment())
                R.id.nav_about -> switchTo(AboutFragment())
            }
            true
        }

        if (savedInstanceState == null) {
            if (intent.getStringExtra(EXTRA_TAB) == TAB_SETTINGS) {
                nav.selectedItemId = R.id.nav_settings
            } else {
                switchTo(HomeFragment())
            }
        }

        VendorHelper.requestNotificationPermission(this, 1001)
    }

    private fun switchTo(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    /** 供首页在未配置时跳转到设置页 */
    fun openSettings() {
        findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_settings
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_SETTINGS = "settings"
    }
}
