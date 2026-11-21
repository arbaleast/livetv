package com.livetv.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlayerTouchGestureMath] 纯 JVM 单元测试：覆盖边缘防误触、亮度/音量手势步进与画面比例循环。
 */
class PlayerTouchGestureMathTest {

    @Test
    fun `aspect mode cycles FIT to FILL to ZOOM and back to FIT`() {
        assertEquals(PlayerTouchGestureMath.AspectMode.FILL, PlayerTouchGestureMath.AspectMode.FIT.next())
        assertEquals(PlayerTouchGestureMath.AspectMode.ZOOM, PlayerTouchGestureMath.AspectMode.FILL.next())
        assertEquals(PlayerTouchGestureMath.AspectMode.FIT, PlayerTouchGestureMath.AspectMode.ZOOM.next())
    }

    @Test
    fun `edge exclusion zone protects left and right swipe-back margins`() {
        // 宽度 1000px，边缘保留 60px -> [0, 60) 与 (940, 1000] 被排除，中间 [60, 940] 允许手势
        assertTrue(PlayerTouchGestureMath.isInEdgeExclusionZone(xPx = 0f, viewWidthPx = 1000, edgeExclusionPx = 60f))
        assertTrue(PlayerTouchGestureMath.isInEdgeExclusionZone(xPx = 59.9f, viewWidthPx = 1000, edgeExclusionPx = 60f))
        assertFalse(PlayerTouchGestureMath.isInEdgeExclusionZone(xPx = 60f, viewWidthPx = 1000, edgeExclusionPx = 60f))
        assertFalse(PlayerTouchGestureMath.isInEdgeExclusionZone(xPx = 500f, viewWidthPx = 1000, edgeExclusionPx = 60f))
        assertFalse(PlayerTouchGestureMath.isInEdgeExclusionZone(xPx = 940f, viewWidthPx = 1000, edgeExclusionPx = 60f))
        assertTrue(PlayerTouchGestureMath.isInEdgeExclusionZone(xPx = 940.1f, viewWidthPx = 1000, edgeExclusionPx = 60f))
        assertTrue(PlayerTouchGestureMath.isInEdgeExclusionZone(xPx = 1000f, viewWidthPx = 1000, edgeExclusionPx = 60f))
    }

    @Test
    fun `edge exclusion handles degenerate dimensions safely`() {
        assertTrue(PlayerTouchGestureMath.isInEdgeExclusionZone(xPx = 10f, viewWidthPx = 0, edgeExclusionPx = 60f))
        assertFalse(PlayerTouchGestureMath.isInEdgeExclusionZone(xPx = 10f, viewWidthPx = 1000, edgeExclusionPx = 0f))
    }

    @Test
    fun `dragging up increases brightness and clamps within safe bounds`() {
        // 高度 1000px，满行程 60%（600px）对应 1.0 亮度变化 -> 向上滑 300px = +0.5
        val brighter = PlayerTouchGestureMath.adjustBrightness(
            currentBrightness = 0.25f,
            deltaY = -300f,
            viewHeightPx = 1000,
        )
        assertEquals(0.75f, brighter, 0.001f)

        // 超过上限钳位到 1.0
        val maxClamped = PlayerTouchGestureMath.adjustBrightness(
            currentBrightness = 0.8f,
            deltaY = -400f,
            viewHeightPx = 1000,
        )
        assertEquals(1.0f, maxClamped, 0.001f)

        // 向下滑超过下限钳位到 0.05（避免全黑）
        val minClamped = PlayerTouchGestureMath.adjustBrightness(
            currentBrightness = 0.2f,
            deltaY = 400f,
            viewHeightPx = 1000,
        )
        assertEquals(0.05f, minClamped, 0.001f)
    }

    @Test
    fun `negative system-default brightness starts from 0_5 baseline`() {
        val adjusted = PlayerTouchGestureMath.adjustBrightness(
            currentBrightness = -1.0f,
            deltaY = -150f,
            viewHeightPx = 1000,
        )
        // 0.5 + (150 / 600) = 0.75
        assertEquals(0.75f, adjusted, 0.001f)
    }

    @Test
    fun `dragging up increases volume and clamps between 0 and maxVolume`() {
        // 高度 1000px，满行程 60%（600px）对应 maxVolume=15 -> 向上滑 200px = +5
        assertEquals(
            10,
            PlayerTouchGestureMath.adjustVolume(currentVolume = 5, maxVolume = 15, deltaY = -200f, viewHeightPx = 1000),
        )
        // 向下滑 200px = -5 -> 0
        assertEquals(
            0,
            PlayerTouchGestureMath.adjustVolume(currentVolume = 5, maxVolume = 15, deltaY = 200f, viewHeightPx = 1000),
        )
        // 超过最大值钳位到 15
        assertEquals(
            15,
            PlayerTouchGestureMath.adjustVolume(currentVolume = 12, maxVolume = 15, deltaY = -500f, viewHeightPx = 1000),
        )
        // maxVolume <= 0 返回 0
        assertEquals(
            0,
            PlayerTouchGestureMath.adjustVolume(currentVolume = 0, maxVolume = 0, deltaY = -200f, viewHeightPx = 1000),
        )
    }

    @Test
    fun `toPercent rounds and clamps into 0 to 100`() {
        assertEquals(0, PlayerTouchGestureMath.toPercent(-0.2f))
        assertEquals(5, PlayerTouchGestureMath.toPercent(0.05f))
        assertEquals(75, PlayerTouchGestureMath.toPercent(0.75f))
        assertEquals(100, PlayerTouchGestureMath.toPercent(1.4f))
    }
}
