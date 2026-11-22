package com.livetv.android.webplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * [CctvWebSwitchGeneration] 纯 JVM 单测：
 * 验证切台代次严格递增、快速连点时旧代次回调（首帧轮询 / 超时兜底 / 错误 / PixelCopy）一律被过滤。
 */
class CctvWebSwitchGenerationTest {

    @Test
    fun `initial zero generation is never current until first load starts`() {
        val guard = CctvWebSwitchGeneration()
        assertEquals(0L, guard.current)
        assertFalse("初始 0 代不应匹配任何有效回调", guard.isCurrent(0L))
        assertFalse("负数代次不应匹配", guard.isCurrent(-1L))
    }

    @Test
    fun `rapid consecutive switches invalidate all earlier generations`() {
        val guard = CctvWebSwitchGeneration()
        val g1 = guard.nextGeneration()
        val g2 = guard.nextGeneration()
        val g3 = guard.nextGeneration()

        assertEquals(1L, g1)
        assertEquals(2L, g2)
        assertEquals(3L, g3)
        assertEquals(3L, guard.current)

        assertFalse("第 1 次切台的旧轮询/PixelCopy 回调必须丢弃", guard.isCurrent(g1))
        assertFalse("第 2 次切台的旧超时/首帧回调必须丢弃", guard.isCurrent(g2))
        assertTrue("最新第 3 次切台回调必须放行", guard.isCurrent(g3))
    }

    @Test
    fun `stale async callbacks cannot reveal overlay for newer channel`() {
        val guard = CctvWebSwitchGeneration()
        val revealedFor = mutableListOf<Long>()

        fun tryReveal(generation: Long) {
            if (!guard.isCurrent(generation)) return
            revealedFor += generation
        }

        val ch1Gen = guard.nextGeneration()
        val ch2Gen = guard.nextGeneration()
        val ch3Gen = guard.nextGeneration()

        // 模拟旧台 1 的首帧回调、旧台 2 的 PixelCopy 与超时回调迟到到达
        tryReveal(ch1Gen)
        tryReveal(ch2Gen)
        assertTrue("旧代次回调不得触发新台撤幕", revealedFor.isEmpty())

        // 仅最新台 3 命中首帧才允许撤幕
        tryReveal(ch3Gen)
        assertEquals(listOf(ch3Gen), revealedFor)
    }

    @Test
    fun `concurrent nextGeneration produces strictly unique monotonic ids`() {
        val guard = CctvWebSwitchGeneration()
        val threads = 8
        val perThread = 25
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)
        val generated = Collections.synchronizedList(mutableListOf<Long>())

        repeat(threads) {
            Thread {
                startGate.await()
                repeat(perThread) {
                    generated += guard.nextGeneration()
                }
                doneGate.countDown()
            }.start()
        }

        startGate.countDown()
        doneGate.await()

        val expectedTotal = threads * perThread
        assertEquals(expectedTotal, generated.size)
        assertEquals(expectedTotal, generated.toSet().size)
        assertEquals(expectedTotal.toLong(), guard.current)
        assertTrue(guard.isCurrent(expectedTotal.toLong()))
        assertFalse(guard.isCurrent(expectedTotal.toLong() - 1L))
    }
}
