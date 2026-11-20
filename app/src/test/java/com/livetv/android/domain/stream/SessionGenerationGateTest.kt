package com.livetv.android.domain.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionGenerationGate] 的并发语义测试：
 * 后台解析回主线程时必须能判断"这次结果是不是已经过期"。
 */
class SessionGenerationGateTest {

    @Test
    fun `initial generation is zero`() {
        assertEquals(0, SessionGenerationGate().current())
    }

    @Test
    fun `next increments and becomes current`() {
        val gate = SessionGenerationGate()

        val g1 = gate.next()
        assertEquals(1, g1)
        assertTrue(gate.isCurrent(g1))

        val g2 = gate.next()
        assertEquals(2, g2)
        assertTrue(gate.isCurrent(g2))
    }

    @Test
    fun `previous generation is stale after switching again`() {
        val gate = SessionGenerationGate()
        val first = gate.next()
        val second = gate.next()

        assertFalse("快速连续切台时，旧代际的结果必须被丢弃", gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `unknown generation is never current`() {
        val gate = SessionGenerationGate()
        gate.next()

        assertFalse(gate.isCurrent(0))
        assertFalse(gate.isCurrent(99))
    }

    @Test
    fun `current is stable without new requests`() {
        val gate = SessionGenerationGate()
        val g = gate.next()

        assertTrue(gate.isCurrent(g))
        assertTrue(gate.isCurrent(g))
        assertEquals(1, gate.current())
    }
}
