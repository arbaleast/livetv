package com.livetv.android.util

import com.livetv.android.util.HeaderAutoHidePolicy.Action
import com.livetv.android.util.HeaderAutoHidePolicy.ScrollResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [HeaderAutoHidePolicy] 纯 JVM 单元测试：验证顶栏收起的累加、反向清零与阈值触发。
 */
class HeaderAutoHidePolicyTest {

    private val threshold = 24

    @Test
    fun `hides only after the valid direction exceeds the threshold`() {
        // 累计 23px 不触发
        val first = HeaderAutoHidePolicy.onScroll(
            accumulatedDy = 0,
            dy = 23,
            headerHidden = false,
            thresholdPx = threshold,
        )
        assertEquals(ScrollResult(23, Action.NONE), first)

        // 再滚 1px 越过阈值 → 收起，累加器归零
        val second = HeaderAutoHidePolicy.onScroll(
            accumulatedDy = first.accumulatedDy,
            dy = 1,
            headerHidden = false,
            thresholdPx = threshold,
        )
        assertEquals(ScrollResult(0, Action.HIDE), second)
    }

    @Test
    fun `reversing direction while expanded clears the accumulator immediately`() {
        val afterUp = HeaderAutoHidePolicy.onScroll(0, 20, headerHidden = false, thresholdPx = threshold)
        assertEquals(20, afterUp.accumulatedDy)

        val afterDown = HeaderAutoHidePolicy.onScroll(
            accumulatedDy = afterUp.accumulatedDy,
            dy = -5,
            headerHidden = false,
            thresholdPx = threshold,
        )
        assertEquals(ScrollResult(0, Action.NONE), afterDown)
    }

    @Test
    fun `scrolling further down while hidden keeps the accumulator at zero`() {
        // 收起态下继续下滑 2000px：不得累加，否则需要上滑同样距离才能唤回
        val result = HeaderAutoHidePolicy.onScroll(
            accumulatedDy = 0,
            dy = 2000,
            headerHidden = true,
            thresholdPx = threshold,
        )
        assertEquals(ScrollResult(0, Action.NONE), result)
    }

    @Test
    fun `scroll down 2000px then up 30px brings the header back immediately`() {
        // 模拟真实滚动序列：先下滑 2000px 收起顶栏，再上滑 30px
        var accumulated = 0
        var hidden = false

        var step = HeaderAutoHidePolicy.onScroll(accumulated, 2000, hidden, threshold)
        accumulated = step.accumulatedDy
        if (step.action == Action.HIDE) hidden = true
        assertEquals(Action.HIDE, step.action)
        assertEquals(0, accumulated)
        assertEquals(true, hidden)

        // 收起后继续下滑（用户惯性滑动）不应累积
        step = HeaderAutoHidePolicy.onScroll(accumulated, 600, hidden, threshold)
        accumulated = step.accumulatedDy
        assertEquals(ScrollResult(0, Action.NONE), step)

        // 反向 30px（> 24px 阈值）即刻唤回
        step = HeaderAutoHidePolicy.onScroll(accumulated, -30, hidden, threshold)
        assertEquals(Action.SHOW, step.action)
        assertEquals(0, step.accumulatedDy)
    }

    @Test
    fun `sub threshold upward movement does not show the header`() {
        val result = HeaderAutoHidePolicy.onScroll(
            accumulatedDy = 0,
            dy = -10,
            headerHidden = true,
            thresholdPx = threshold,
        )
        assertEquals(ScrollResult(-10, Action.NONE), result)

        // 再上滑 10px 仍不足 24px
        val next = HeaderAutoHidePolicy.onScroll(
            accumulatedDy = result.accumulatedDy,
            dy = -10,
            headerHidden = true,
            thresholdPx = threshold,
        )
        assertEquals(ScrollResult(-20, Action.NONE), next)
    }

    @Test
    fun `continuing upward while expanded does not accumulate hide distance`() {
        val result = HeaderAutoHidePolicy.onScroll(
            accumulatedDy = 0,
            dy = -120,
            headerHidden = false,
            thresholdPx = threshold,
        )
        assertEquals(ScrollResult(0, Action.NONE), result)
    }

    @Test
    fun `zero delta and invalid threshold never change state`() {
        assertEquals(
            ScrollResult(11, Action.NONE),
            HeaderAutoHidePolicy.onScroll(11, 0, headerHidden = false, thresholdPx = threshold),
        )
        assertEquals(
            ScrollResult(11, Action.NONE),
            HeaderAutoHidePolicy.onScroll(11, 400, headerHidden = false, thresholdPx = 0),
        )
    }
}
