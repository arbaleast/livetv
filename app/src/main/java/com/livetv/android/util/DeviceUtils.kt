package com.livetv.android.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * 设备形态判定：Android TV / 电视盒子 vs 手机、平板等触控设备。
 *
 * 阶段六引入。Leanback 的浏览页在手机竖屏上布局错位（`ImageCardView` 按 313dp x 176dp
 * 固定尺寸排版），因此需要在入口处按设备形态分流到不同的首页实现。
 *
 * **判定规则**（两条满足其一即视为 TV）：
 * 1. 当前 UI 模式是 `Configuration.UI_MODE_TYPE_TELEVISION`（系统报告的电视模式）；
 * 2. 系统声明具备 `PackageManager.FEATURE_LEANBACK` 特性（Leanback 设备）。
 *
 * 第 2 条是主判据：部分电视盒子并不把 UI 模式设成 `TELEVISION`，但都带 leanback 特性；
 * 而手机即使安装了本应用也不会有该特性。
 *
 * ## 纯 JVM 可测性
 *
 * [isTvDevice] 的两个重载刻意拆开：
 * - `isTvDevice(context)` 是 Android 门面，负责从 `Context` 取原始信号；
 * - `isTvDevice(uiModeType, hasLeanbackFeature)` 是**纯判定**，只依赖两个原始值，
 *   因此可以在 `testDebugUnitTest`（纯 JVM，无模拟器）里直接断言。
 *
 * Android 框架里 `Context` 相关方法在纯 JVM 单测中是抛 `RuntimeException("not mocked")`
 * 的桩，因此判定逻辑不能藏在门面里。
 */
object DeviceUtils {

    /**
     * 电视 UI 模式常量，与 `android.content.res.Configuration.UI_MODE_TYPE_TELEVISION` 一致。
     *
     * 这里显式声明而不是直接引用框架常量，是为了让纯判定函数在纯 JVM 单测中
     * 不触碰任何 Android 桩（框架常量本身是编译期常量，引用是安全的；
     * 但显式声明同时让"判定规则"完全自洽，不依赖框架版本）。
     */
    const val UI_MODE_TYPE_TELEVISION = 4

    /** 未定义的 UI 模式，取不到 `UiModeManager` 时的兜底值。 */
    const val UI_MODE_TYPE_UNDEFINED = 0

    /** 普通 UI 模式（手机、平板）。与 `Configuration.UI_MODE_TYPE_NORMAL` 一致。 */
    const val UI_MODE_TYPE_NORMAL = 1

    /** 手表 UI 模式。与 `Configuration.UI_MODE_TYPE_WATCH` 一致。 */
    const val UI_MODE_TYPE_WATCH = 2

    /**
     * Android 门面：判定当前设备是否为 TV。
     *
     * @param context 任意可用上下文（Activity / Application 均可）。
     */
    fun isTvDevice(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val uiModeType = uiModeManager?.currentModeType ?: UI_MODE_TYPE_UNDEFINED
        return isTvDevice(uiModeType, context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
    }

    /**
     * 纯判定：电视 UI 模式 **或** 具备 leanback 特性，即视为 TV 设备。
     *
     * 无任何 Android 依赖，可直接用纯 JVM 单元测试覆盖。
     */
    fun isTvDevice(uiModeType: Int, hasLeanbackFeature: Boolean): Boolean {
        return uiModeType == UI_MODE_TYPE_TELEVISION || hasLeanbackFeature
    }
}
