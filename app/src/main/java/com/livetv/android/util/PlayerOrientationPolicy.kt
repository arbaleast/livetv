package com.livetv.android.util

/**
 * 播放页屏幕方向与频道恢复策略（纯 Kotlin，无 Android 运行时依赖）。
 *
 * 常量值与 `android.content.pm.ActivityInfo` 编译期常量完全一致，
 * 以便在纯 JVM 单元测试中直接断言，不触碰 Android 框架桩。
 */
object PlayerOrientationPolicy {

    /** 锁定横屏，对应 `ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE`。 */
    const val ORIENTATION_LANDSCAPE = 0

    /** 跟随设备方向（含竖屏、正向横屏、反向横屏，遵循系统自动旋转开关），对应 `ActivityInfo.SCREEN_ORIENTATION_FULL_USER`。 */
    const val ORIENTATION_FULL_USER = 13

    /** 仅限正/反横屏自动旋转（遵循系统自动旋转开关），对应 `ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE`。 */
    const val ORIENTATION_USER_LANDSCAPE = 11

    /**
     * 按设备形态决定播放页请求的屏幕方向：
     * - TV 设备保持锁定横屏；
     * - 手机/平板触控设备跟随设备方向（支持竖屏与正/反横屏，遵循系统自动旋转开关）。
     */
    fun resolveRequestedOrientation(isTvDevice: Boolean): Int =
        if (isTvDevice) ORIENTATION_LANDSCAPE else ORIENTATION_FULL_USER

    /**
     * 进程重建时优先恢复保存的当前频道 ID，未保存或空白时再回退到 Intent 传入的初始频道 ID。
     */
    fun resolveChannelIdToRestore(
        savedChannelId: String?,
        intentChannelId: String?,
    ): String? =
        savedChannelId?.trim()?.takeIf { it.isNotEmpty() }
            ?: intentChannelId?.trim()?.takeIf { it.isNotEmpty() }
}
