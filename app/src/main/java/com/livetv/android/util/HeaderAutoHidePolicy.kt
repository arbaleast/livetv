package com.livetv.android.util

/**
 * 顶栏「随列表滚动自动收起 / 展开」的**纯策略**（无 Android 依赖，可纯 JVM 单测）。
 *
 * 关键约束：滚动方向**反向**时必须立即清零累加器。
 * 否则收起状态下继续下滑会把正向位移越积越多，用户要往回滑同等距离才能唤回顶栏
 * （典型症状：长列表下滑 2000px 后要上滑 2000px 顶栏才出现）。
 *
 * 调用方只负责把一次 `onScrolled(dx, dy)` 的 `dy`、当前是否收起与阈值传进来，
 * 再按返回的 [Action] 执行收起/展开动画即可。
 */
object HeaderAutoHidePolicy {

    /** 一次滚动应触发的顶栏动作。 */
    enum class Action {
        /** 不需要改变顶栏状态，只更新累加器。 */
        NONE,

        /** 收起顶栏。 */
        HIDE,

        /** 展开顶栏。 */
        SHOW,
    }

    /** 策略计算结果：新的累加位移 + 需要执行的动作（触发动作时累加器已归零）。 */
    data class ScrollResult(
        val accumulatedDy: Int,
        val action: Action,
    )

    /**
     * 根据滚动位移推导顶栏状态。
     *
     * - `dy > 0` 为手指上滑（列表向下滚动）；
     * - `dy < 0` 为手指下滑（列表向上回滚）；
     * - 已收起时继续向下滚（`dy > 0`）或已展开时继续向上滚（`dy < 0`）都视为"无效方向"，
     *   累加器立即清零，避免反向唤醒距离被历史位移污染；
     * - 仅当有效方向累计位移超过 [thresholdPx] 时才触发 [Action.HIDE] / [Action.SHOW]。
     */
    fun onScroll(
        accumulatedDy: Int,
        dy: Int,
        headerHidden: Boolean,
        thresholdPx: Int,
    ): ScrollResult {
        if (dy == 0 || thresholdPx <= 0) return ScrollResult(accumulatedDy, Action.NONE)

        if (headerHidden) {
            // 已收起：继续下滑没有任何意义，立刻清零
            if (dy > 0) return ScrollResult(0, Action.NONE)
            val next = accumulatedDy + dy
            return if (next <= -thresholdPx) ScrollResult(0, Action.SHOW) else ScrollResult(next, Action.NONE)
        }

        // 已展开：继续上滑没有任何意义，立刻清零
        if (dy < 0) return ScrollResult(0, Action.NONE)
        val next = accumulatedDy + dy
        return if (next >= thresholdPx) ScrollResult(0, Action.HIDE) else ScrollResult(next, Action.NONE)
    }
}
