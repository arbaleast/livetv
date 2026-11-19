package com.livetv.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备形态识别的纯 JVM 单元测试。
 *
 * `DeviceUtils.isTvDevice(context)` 需要真实 `Context`，无法在 JVM 单测里跑；
 * 因此判定规则被抽成纯函数重载 `isTvDevice(uiModeType, hasLeanbackFeature)`，
 * 这里直接对纯函数断言。
 */
class DeviceUtilsTest {

    @Test
    fun `television ui mode is treated as a tv device`() {
        assertTrue(
            DeviceUtils.isTvDevice(
                uiModeType = DeviceUtils.UI_MODE_TYPE_TELEVISION,
                hasLeanbackFeature = false,
            ),
        )
    }

    @Test
    fun `leanback feature alone is enough to be treated as a tv device`() {
        assertTrue(
            DeviceUtils.isTvDevice(
                uiModeType = DeviceUtils.UI_MODE_TYPE_UNDEFINED,
                hasLeanbackFeature = true,
            ),
        )
    }

    @Test
    fun `normal ui mode without leanback feature is a touch device`() {
        assertFalse(
            DeviceUtils.isTvDevice(
                uiModeType = DeviceUtils.UI_MODE_TYPE_NORMAL,
                hasLeanbackFeature = false,
            ),
        )
    }

    @Test
    fun `watch ui mode without leanback feature is not a tv device`() {
        assertFalse(
            DeviceUtils.isTvDevice(
                uiModeType = DeviceUtils.UI_MODE_TYPE_WATCH,
                hasLeanbackFeature = false,
            ),
        )
    }

    @Test
    fun `undefined ui mode without leanback feature is not a tv device`() {
        assertFalse(
            DeviceUtils.isTvDevice(
                uiModeType = DeviceUtils.UI_MODE_TYPE_UNDEFINED,
                hasLeanbackFeature = false,
            ),
        )
    }

    @Test
    fun `local tv mode constant matches the framework value`() {
        // Configuration.UI_MODE_TYPE_TELEVISION == 4；常量是编译期内联，这里做一次交叉校验
        assertEquals(4, DeviceUtils.UI_MODE_TYPE_TELEVISION)
    }
}
