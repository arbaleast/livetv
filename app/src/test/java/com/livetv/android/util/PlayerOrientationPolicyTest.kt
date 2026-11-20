package com.livetv.android.util

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放页方向与频道恢复策略的纯 JVM 单元测试。
 */
class PlayerOrientationPolicyTest {

    @Test
    fun `tv device stays locked to landscape orientation`() {
        assertEquals(0, PlayerOrientationPolicy.resolveRequestedOrientation(isTvDevice = true))
    }

    @Test
    fun `touch device follows device orientation including portrait and reverse landscape`() {
        assertEquals(13, PlayerOrientationPolicy.resolveRequestedOrientation(isTvDevice = false))
    }

    @Test
    fun `orientation constants match Android ActivityInfo compile time values`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, PlayerOrientationPolicy.ORIENTATION_LANDSCAPE)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, PlayerOrientationPolicy.ORIENTATION_FULL_USER)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, PlayerOrientationPolicy.ORIENTATION_USER_LANDSCAPE)
    }

    @Test
    fun `restore channel id prefers saved state over original intent extra`() {
        assertEquals(
            "c5",
            PlayerOrientationPolicy.resolveChannelIdToRestore(
                savedChannelId = "c5",
                intentChannelId = "c1",
            ),
        )
    }

    @Test
    fun `restore channel id falls back to intent extra when saved state is null or blank`() {
        assertEquals(
            "c1",
            PlayerOrientationPolicy.resolveChannelIdToRestore(
                savedChannelId = null,
                intentChannelId = "c1",
            ),
        )
        assertEquals(
            "c1",
            PlayerOrientationPolicy.resolveChannelIdToRestore(
                savedChannelId = "   ",
                intentChannelId = "c1",
            ),
        )
    }

    @Test
    fun `restore channel id returns null when both saved state and intent extra are blank`() {
        assertNull(
            PlayerOrientationPolicy.resolveChannelIdToRestore(
                savedChannelId = "",
                intentChannelId = "  ",
            ),
        )
        assertNull(
            PlayerOrientationPolicy.resolveChannelIdToRestore(
                savedChannelId = null,
                intentChannelId = null,
            ),
        )
    }
}
