package com.livetv.android.util

import kotlin.math.roundToInt

/**
 * 播放页触控手势与画面比例计算的纯策略模块（无 Android 依赖，可纯 JVM 单测）。
 */
object PlayerTouchGestureMath {

    /** 画面缩放模式，与 Media3 `AspectRatioFrameLayout.RESIZE_MODE_*` 对应。 */
    enum class AspectMode {
        /** 完整适应（黑边保留原比例）。 */
        FIT,

        /** 铺满拉伸（填满屏幕无黑边）。 */
        FILL,

        /** 等比裁剪铺满（无变形无黑边）。 */
        ZOOM,
        ;

        fun next(): AspectMode = when (this) {
            FIT -> FILL
            FILL -> ZOOM
            ZOOM -> FIT
        }
    }

    /** 亮度最小值（避免划到全黑看不见解锁按钮）。 */
    const val MIN_BRIGHTNESS = 0.05f

    /** 亮度最大值。 */
    const val MAX_BRIGHTNESS = 1.0f

    /** 当窗口亮度尚未手动设置（< 0）时采用的基准亮度。 */
    const val DEFAULT_BRIGHTNESS = 0.5f

    /** 满量程垂直滑动距离占视图高度的比例（60% 屏幕高度覆盖 0%~100%）。 */
    const val FULL_TRAVEL_HEIGHT_RATIO = 0.6f

    /** 判断触点是否落在左右边缘防误触区域（避让系统侧滑返回手势）。 */
    fun isInEdgeExclusionZone(xPx: Float, viewWidthPx: Int, edgeExclusionPx: Float): Boolean {
        if (viewWidthPx <= 0) return true
        val margin = edgeExclusionPx.coerceAtLeast(0f)
        if (margin == 0f) return false
        return xPx < margin || xPx > viewWidthPx - margin
    }

    /**
     * 按垂直滑动距离计算新的窗口亮度（向上滑 `deltaY < 0` 增亮，向下滑 `deltaY > 0` 变暗）。
     */
    fun adjustBrightness(currentBrightness: Float, deltaY: Float, viewHeightPx: Int): Float {
        val base = if (currentBrightness < 0f) DEFAULT_BRIGHTNESS else currentBrightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        val travel = viewHeightPx * FULL_TRAVEL_HEIGHT_RATIO
        if (travel <= 0f) return base
        val delta = -deltaY / travel
        return (base + delta).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
    }

    /**
     * 按垂直滑动距离计算新的媒体音量（向上滑 `deltaY < 0` 增大，向下滑 `deltaY > 0` 减小）。
     */
    fun adjustVolume(currentVolume: Int, maxVolume: Int, deltaY: Float, viewHeightPx: Int): Int {
        if (maxVolume <= 0) return 0
        val base = currentVolume.coerceIn(0, maxVolume)
        val travel = viewHeightPx * FULL_TRAVEL_HEIGHT_RATIO
        if (travel <= 0f) return base
        val deltaSteps = (-deltaY / travel) * maxVolume
        return (base + deltaSteps).roundToInt().coerceIn(0, maxVolume)
    }

    /** 把 `[0, 1]` 比例转换为 `0..100` 整数百分比。 */
    fun toPercent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100f).roundToInt()
}
