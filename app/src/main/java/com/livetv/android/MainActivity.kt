package com.livetv.android

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import com.livetv.android.ui.MainBrowseFragment
import com.livetv.android.ui.mobile.MobileMainActivity
import com.livetv.android.util.DeviceUtils

/**
 * 应用入口，按设备形态分流：
 *
 * - **Android TV / Leanback 设备**：加载原有的 Leanback `MainBrowseFragment`，
 *   遥控器焦点与浏览体验保持 100% 不变，并锁定横屏。
 * - **手机 / 平板等触控设备**：转交 `MobileMainActivity`（`RecyclerView` 触控网格），
 *   本 Activity 随即结束，屏幕上不会留下多余的空白页。
 *
 * 两端共用底层资产：`LiveTvApplication.catalogStore`（数据）与 `PlayerActivity`（播放）。
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!DeviceUtils.isTvDevice(this)) {
            startActivity(Intent(this, MobileMainActivity::class.java))
            finish()
            return
        }

        // TV 端固定横屏（原 manifest 的 screenOrientation 改为按端设置，避免手机端启动即旋转）
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.main_browse_fragment, MainBrowseFragment())
            }
        }
    }
}
