package com.livetv.android.webplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CctvWebGestureController] 纯 JVM 单元测试：
 * 覆盖左半屏亮度钳制、右半屏音量浮点累加器、以及待起播/原控/纯视频三态触摸分流。
 */
class CctvWebGestureControllerTest {

    @Test
    fun `left half swipe adjusts brightness and clamps between 0_01 and 1_0`() {
        // viewHeight=1000 -> 满行程 600px，向上滑 300px = +0.5
        val up = CctvWebGestureController.computeBrightness(
            startBrightness = 0.4f,
            deltaY = -300f,
            viewHeightPx = 1000,
        )
        assertEquals(0.9f, up, 0.001f)
        assertEquals(90, CctvWebGestureController.brightnessPercent(up))

        // 上限钳制 1.0f
        val max = CctvWebGestureController.computeBrightness(
            startBrightness = 0.8f,
            deltaY = -400f,
            viewHeightPx = 1000,
        )
        assertEquals(1.0f, max, 0.001f)
        assertEquals(100, CctvWebGestureController.brightnessPercent(max))

        // 下限钳制 0.01f
        val min = CctvWebGestureController.computeBrightness(
            startBrightness = 0.2f,
            deltaY = 400f,
            viewHeightPx = 1000,
        )
        assertEquals(0.01f, min, 0.001f)
        assertEquals(1, CctvWebGestureController.brightnessPercent(min))

        // 未知亮度（< 0）从默认基准 0.5f 起步
        val fromUnknown = CctvWebGestureController.computeBrightness(
            startBrightness = -1.0f,
            deltaY = -150f,
            viewHeightPx = 1000,
        )
        assertEquals(0.75f, fromUnknown, 0.001f)
    }

    @Test
    fun `right half swipe uses float accumulator for volume and clamps between 0 and maxVolume`() {
        // viewHeight=1000 -> 满行程 600px，maxVolume=15 -> 每格 40px
        val step1 = CctvWebGestureController.accumulateVolume(
            accumulator = 5f,
            stepDeltaY = -15f,
            viewHeightPx = 1000,
            maxVolume = 15,
        )
        assertEquals(5.375f, step1.accumulator, 0.001f)
        assertEquals(5, step1.volume)

        // 累加微小滑动：第二次上滑 15px 后跨过 5.5，平滑升到 6 格（不跳格）
        val step2 = CctvWebGestureController.accumulateVolume(
            accumulator = step1.accumulator,
            stepDeltaY = -15f,
            viewHeightPx = 1000,
            maxVolume = 15,
        )
        assertEquals(5.75f, step2.accumulator, 0.001f)
        assertEquals(6, step2.volume)
        assertEquals(40, step2.percent)

        // 超过最大值钳制到 15
        val clampedMax = CctvWebGestureController.accumulateVolume(
            accumulator = 14f,
            stepDeltaY = -200f,
            viewHeightPx = 1000,
            maxVolume = 15,
        )
        assertEquals(15f, clampedMax.accumulator, 0.001f)
        assertEquals(15, clampedMax.volume)
        assertEquals(100, clampedMax.percent)

        // 低于最小值钳制到 0
        val clampedMin = CctvWebGestureController.accumulateVolume(
            accumulator = 1f,
            stepDeltaY = 200f,
            viewHeightPx = 1000,
            maxVolume = 15,
        )
        assertEquals(0f, clampedMin.accumulator, 0.001f)
        assertEquals(0, clampedMin.volume)
        assertEquals(0, clampedMin.percent)
    }

    @Test
    fun `tap passes through to WebView while waiting for playback or native controls`() {
        val controller = CctvWebGestureController(touchSlopPx = 24f)

        // 阶段一：待起播态，单击透传
        assertFalse(controller.shouldCaptureTap())
        assertEquals(
            GestureAction.PassToWebView,
            controller.onTouchDown(x = 500f, y = 500f, viewWidthPx = 1000),
        )
        assertEquals(
            GestureAction.PassToWebView,
            controller.onTouchMove(x = 504f, y = 496f, viewHeightPx = 1000),
        )
        assertEquals(GestureAction.PassToWebView, controller.onTouchUp())

        // 阶段二但开启「原控」：单击依然透传
        controller.isPlaying = true
        controller.isNativeControlsVisible = true
        assertFalse(controller.shouldCaptureTap())
        controller.onTouchDown(x = 500f, y = 500f, viewWidthPx = 1000)
        assertEquals(GestureAction.PassToWebView, controller.onTouchUp())
    }

    @Test
    fun `tap toggles native top bar only in pure video playing state`() {
        val controller = CctvWebGestureController(touchSlopPx = 24f).apply {
            isPlaying = true
            isNativeControlsVisible = false
        }

        assertTrue(controller.shouldCaptureTap())
        assertEquals(
            GestureAction.PassToWebView,
            controller.onTouchDown(x = 500f, y = 500f, viewWidthPx = 1000),
        )
        assertEquals(
            GestureAction.PassToWebView,
            controller.onTouchMove(x = 505f, y = 505f, viewHeightPx = 1000),
        )
        assertEquals(GestureAction.ToggleTopBar, controller.onTouchUp())
    }

    @Test
    fun `horizontal drag in pure video mode does not trigger tap toggle`() {
        val controller = CctvWebGestureController(touchSlopPx = 24f).apply {
            isPlaying = true
            isNativeControlsVisible = false
        }

        controller.onTouchDown(x = 500f, y = 500f, viewWidthPx = 1000)
        assertEquals(
            GestureAction.PassToWebView,
            controller.onTouchMove(x = 600f, y = 505f, viewHeightPx = 1000),
        )
        assertEquals(GestureAction.PassToWebView, controller.onTouchUp())
    }

    @Test
    fun `vertical swipe beyond touchSlop intercepts as brightness or volume in all states`() {
        val controller = CctvWebGestureController(touchSlopPx = 24f)

        // 阶段一左半屏垂直滑动 -> 亮度调节
        controller.isPlaying = false
        controller.isNativeControlsVisible = false
        controller.onTouchDown(
            x = 200f,
            y = 500f,
            viewWidthPx = 1000,
            startBrightness = 0.4f,
            startVolume = 5,
            maxVolume = 15,
        )
        val brightnessAction = controller.onTouchMove(x = 202f, y = 350f, viewHeightPx = 1000)
        assertTrue(brightnessAction is GestureAction.AdjustBrightness)
        val b = brightnessAction as GestureAction.AdjustBrightness
        assertEquals(0.65f, b.brightness, 0.001f)
        assertEquals(65, b.percent)
        assertEquals(GestureAction.EndAdjustment, controller.onTouchUp())

        // 阶段二纯视频态右半屏垂直滑动 -> 音量调节（且松手返回 EndAdjustment，不误触 ToggleTopBar）
        controller.isPlaying = true
        controller.isNativeControlsVisible = false
        controller.onTouchDown(
            x = 800f,
            y = 500f,
            viewWidthPx = 1000,
            startBrightness = 0.5f,
            startVolume = 5,
            maxVolume = 15,
        )
        val volumeAction = controller.onTouchMove(x = 802f, y = 380f, viewHeightPx = 1000)
        assertTrue(volumeAction is GestureAction.AdjustVolume)
        val v = volumeAction as GestureAction.AdjustVolume
        assertEquals(8, v.volume)
        assertEquals(53, v.percent)
        assertEquals(GestureAction.EndAdjustment, controller.onTouchUp())
    }

    @Test
    fun `edge exclusion zone blocks accidental vertical brightness and volume drags`() {
        val controller = CctvWebGestureController(touchSlopPx = 24f, edgeExclusionPx = 60f).apply {
            isPlaying = true
            isNativeControlsVisible = false
        }

        // 左边缘 30px（< 60px 防误触区）垂直滑动不触发亮度调节
        controller.onTouchDown(x = 30f, y = 500f, viewWidthPx = 1000, startBrightness = 0.5f)
        assertEquals(
            GestureAction.PassToWebView,
            controller.onTouchMove(x = 32f, y = 300f, viewHeightPx = 1000),
        )
        assertEquals(GestureAction.PassToWebView, controller.onTouchUp())

        // 右边缘 970px（> 1000 - 60）垂直滑动不触发音量调节
        controller.onTouchDown(x = 970f, y = 500f, viewWidthPx = 1000, startVolume = 5, maxVolume = 15)
        assertEquals(
            GestureAction.PassToWebView,
            controller.onTouchMove(x = 968f, y = 300f, viewHeightPx = 1000),
        )
        assertEquals(GestureAction.PassToWebView, controller.onTouchUp())
    }

    @Test
    fun `screen lock blocks vertical drags and turns tap into ToggleLockButton`() {
        val controller = CctvWebGestureController(touchSlopPx = 24f, edgeExclusionPx = 48f).apply {
            isPlaying = true
            isNativeControlsVisible = false
            isScreenLocked = true
        }

        assertTrue("锁屏态必须拦截单击防止透传网页", controller.shouldCaptureTap())

        // 垂直滑动被锁屏拦截，不调整亮度/音量
        controller.onTouchDown(x = 250f, y = 500f, viewWidthPx = 1000, startBrightness = 0.5f)
        assertEquals(
            GestureAction.PassToWebView,
            controller.onTouchMove(x = 252f, y = 250f, viewHeightPx = 1000),
        )
        assertEquals(GestureAction.PassToWebView, controller.onTouchUp())

        // 短按单击返回 ToggleLockButton，短暂浮现解锁按钮
        controller.onTouchDown(x = 500f, y = 500f, viewWidthPx = 1000)
        assertEquals(GestureAction.ToggleLockButton, controller.onTouchUp())
    }

    @Test
    fun `tap toggles controls after overlay reveal timeout even when playback has not started`() {
        val controller = CctvWebGestureController(touchSlopPx = 24f).apply {
            isPlaying = false
            isRevealed = true
            isNativeControlsVisible = false
        }

        assertTrue("撤幕后即使尚未起播也应拦截单击呼出控制栏", controller.shouldCaptureTap())
        controller.onTouchDown(x = 500f, y = 500f, viewWidthPx = 1000)
        assertEquals(GestureAction.ToggleTopBar, controller.onTouchUp())

        // 原控模式下仍透传给网页
        controller.isNativeControlsVisible = true
        assertFalse(controller.shouldCaptureTap())
        controller.onTouchDown(x = 500f, y = 500f, viewWidthPx = 1000)
        assertEquals(GestureAction.PassToWebView, controller.onTouchUp())

        // reset() 必须清除 isRevealed
        controller.reset()
        assertFalse(controller.isRevealed)
        assertFalse(controller.shouldCaptureTap())
    }
}
