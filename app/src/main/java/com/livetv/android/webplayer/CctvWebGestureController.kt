package com.livetv.android.webplayer

import com.livetv.android.util.PlayerTouchGestureMath
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * CCTV WebView 播放页原生手势分流与亮度/音量计算控制器（纯 Kotlin，可纯 JVM 单测）。
 *
 * 分流规则：
 * - 锁屏态：屏蔽亮度/音量滑动并拦截点击，短按返回 `ToggleLockButton` 短暂浮现解锁按钮；
 * - 左右边缘防误触区（默认与 `PlayerTouchGestureMath` 24dp 对齐）：禁止触发垂直滑动，避让系统侧滑返回；
 * - 垂直滑动超过 touchSlop：左半屏调亮度、右半屏调音量（非锁屏三态下均生效）；
 * - 点击：仅「纯视频播放态」（`isPlaying && !isNativeControlsVisible`）呼出原生控制层，
 *   其余状态（待起播 / 原控可见）把点击交还网页；
 * - 水平拖动或已超过 slop 的手势不会被误判为点击。
 */
sealed interface GestureAction {
    data object PassToWebView : GestureAction
    data object ToggleTopBar : GestureAction
    data object ToggleLockButton : GestureAction
    data class AdjustBrightness(val brightness: Float, val percent: Int) : GestureAction
    data class AdjustVolume(val volume: Int, val percent: Int) : GestureAction
    data object EndAdjustment : GestureAction
}

data class VolumeStep(
    val accumulator: Float,
    val volume: Int,
    val percent: Int,
)

class CctvWebGestureController(
    private val touchSlopPx: Float = DEFAULT_TOUCH_SLOP_PX,
    private val edgeExclusionPx: Float = 0f,
) {

    enum class DragMode {
        NONE,
        BRIGHTNESS,
        VOLUME,
    }

    var isPlaying: Boolean = false
    var isRevealed: Boolean = false
    var isNativeControlsVisible: Boolean = false
    var isScreenLocked: Boolean = false

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var viewWidthPx = 0
    private var dragMode = DragMode.NONE
    private var dragEligible = true
    private var movedBeyondSlop = false
    private var startBrightness = DEFAULT_BRIGHTNESS
    private var volumeAccumulator = 0f
    private var maxVolume = 0

    fun shouldCaptureTap(): Boolean = isScreenLocked || ((isPlaying || isRevealed) && !isNativeControlsVisible)

    fun reset() {
        isPlaying = false
        isRevealed = false
        isNativeControlsVisible = false
        clearActiveTouch()
    }

    fun onTouchDown(
        x: Float,
        y: Float,
        viewWidthPx: Int,
        startBrightness: Float = DEFAULT_BRIGHTNESS,
        startVolume: Int = 0,
        maxVolume: Int = 15,
    ): GestureAction {
        downX = x
        downY = y
        lastY = y
        this.viewWidthPx = viewWidthPx
        this.startBrightness = if (startBrightness < 0f) DEFAULT_BRIGHTNESS else startBrightness
        this.maxVolume = maxVolume.coerceAtLeast(0)
        this.volumeAccumulator = startVolume.coerceIn(0, this.maxVolume).toFloat()
        dragMode = DragMode.NONE
        dragEligible = !isScreenLocked && !PlayerTouchGestureMath.isInEdgeExclusionZone(
            xPx = x,
            viewWidthPx = viewWidthPx,
            edgeExclusionPx = edgeExclusionPx,
        )
        movedBeyondSlop = false
        return GestureAction.PassToWebView
    }

    fun onTouchMove(
        x: Float,
        y: Float,
        viewHeightPx: Int,
    ): GestureAction {
        val dx = x - downX
        val dyTotal = y - downY

        if (!movedBeyondSlop) {
            if (abs(dx) > touchSlopPx || abs(dyTotal) > touchSlopPx) {
                movedBeyondSlop = true
                if (dragEligible && abs(dyTotal) > abs(dx)) {
                    dragMode = if (downX < viewWidthPx / 2f) DragMode.BRIGHTNESS else DragMode.VOLUME
                }
            }
        }

        if (isScreenLocked) return GestureAction.PassToWebView

        return when (dragMode) {
            DragMode.BRIGHTNESS -> {
                val brightness = computeBrightness(startBrightness, dyTotal, viewHeightPx)
                GestureAction.AdjustBrightness(brightness, brightnessPercent(brightness))
            }

            DragMode.VOLUME -> {
                val step = y - lastY
                lastY = y
                val stepResult = accumulateVolume(volumeAccumulator, step, viewHeightPx, maxVolume)
                volumeAccumulator = stepResult.accumulator
                GestureAction.AdjustVolume(stepResult.volume, stepResult.percent)
            }

            DragMode.NONE -> GestureAction.PassToWebView
        }
    }

    fun onTouchCancel(): GestureAction {
        if (dragMode != DragMode.NONE) {
            clearActiveTouch()
            return GestureAction.EndAdjustment
        }
        clearActiveTouch()
        return GestureAction.PassToWebView
    }

    fun onTouchUp(): GestureAction {
        val action = when {
            dragMode != DragMode.NONE -> GestureAction.EndAdjustment
            movedBeyondSlop -> GestureAction.PassToWebView
            isScreenLocked -> GestureAction.ToggleLockButton
            shouldCaptureTap() -> GestureAction.ToggleTopBar
            else -> GestureAction.PassToWebView
        }
        clearActiveTouch()
        return action
    }

    private fun clearActiveTouch() {
        dragMode = DragMode.NONE
        dragEligible = false
        movedBeyondSlop = false
    }

    companion object {
        const val MIN_BRIGHTNESS = 0.01f
        const val MAX_BRIGHTNESS = 1.0f
        const val DEFAULT_BRIGHTNESS = 0.5f
        const val FULL_TRAVEL_HEIGHT_RATIO = 0.6f
        const val DEFAULT_TOUCH_SLOP_PX = 24f
        const val EDGE_EXCLUSION_DP = 24
        const val HUD_FADE_DELAY_MS = 800L
        const val TOP_BAR_AUTO_HIDE_MS = 3_500L

        fun computeBrightness(
            startBrightness: Float,
            deltaY: Float,
            viewHeightPx: Int,
        ): Float {
            val travel = viewHeightPx * FULL_TRAVEL_HEIGHT_RATIO
            val base = if (startBrightness < 0f) {
                DEFAULT_BRIGHTNESS
            } else {
                startBrightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
            }
            if (travel <= 0f) return base
            val delta = -deltaY / travel
            return (base + delta).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        }

        fun brightnessPercent(brightness: Float): Int =
            (brightness * 100f).roundToInt().coerceIn(1, 100)

        fun accumulateVolume(
            accumulator: Float,
            stepDeltaY: Float,
            viewHeightPx: Int,
            maxVolume: Int,
        ): VolumeStep {
            if (maxVolume <= 0) return VolumeStep(accumulator, 0, 0)
            val travel = viewHeightPx * FULL_TRAVEL_HEIGHT_RATIO
            if (travel <= 0f) return VolumeStep(accumulator, 0, 0)
            val next = (accumulator + (-stepDeltaY / travel) * maxVolume)
                .coerceIn(0f, maxVolume.toFloat())
            val volume = next.roundToInt().coerceIn(0, maxVolume)
            val percent = (volume * 100f / maxVolume).roundToInt().coerceIn(0, 100)
            return VolumeStep(accumulator = next, volume = volume, percent = percent)
        }
    }
}
