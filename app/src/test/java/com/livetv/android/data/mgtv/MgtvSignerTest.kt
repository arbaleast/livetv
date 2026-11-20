package com.livetv.android.data.mgtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MgtvSigner] 的纯 JVM 测试。
 *
 * 期望值由**独立实现**（Node 的 `crypto.createHash('md5')` 按同一拼接规则）预先算出，
 * 因此这些黄金用例能真正钉死"盐值 + 参数排序 + key/value 拼接 + 大写"四个关键细节，
 * 而不是拿实现和自己比较。
 *
 * 全部输入均为人造占位值，不含任何真实设备标识或会话签名。
 */
class MgtvSignerTest {

    @Test
    fun `matches golden vector with sorted key value concatenation`() {
        val params = linkedMapOf(
            "_t" to "1700000000000",
            "deviceId" to "00000000-0000-0000-0000-000000000000",
            "did" to "00000000-0000-0000-0000-000000000000",
            "cameraId" to "287",
        )

        assertEquals("9B4B761AA824F490FE4DD041841FE13F", MgtvSigner.sign(params))
    }

    @Test
    fun `matches golden vector for a two param map`() {
        val params = mapOf("platform" to "4", "cameraId" to "344", "_t" to "1700000000001")

        assertEquals("C2A6F2AC72739D968A40CCE340C675F5", MgtvSigner.sign(params))
    }

    @Test
    fun `matches golden vector for empty params`() {
        assertEquals("0AB48FD1297C2F0FFBBF7FF363038416", MgtvSigner.sign(emptyMap()))
    }

    @Test
    fun `output is uppercase 32 hex`() {
        val sign = MgtvSigner.sign(mapOf("cameraId" to "287", "_t" to "1"))

        assertEquals(32, sign.length)
        assertTrue(Regex("^[0-9A-F]{32}$").matches(sign))
    }

    @Test
    fun `insertion order does not affect the result`() {
        val a = linkedMapOf("b" to "2", "a" to "1", "c" to "3")
        val b = linkedMapOf("c" to "3", "a" to "1", "b" to "2")

        assertEquals(MgtvSigner.sign(a), MgtvSigner.sign(b))
    }

    @Test
    fun `different values produce different signatures`() {
        assertNotEquals(
            MgtvSigner.sign(mapOf("cameraId" to "287", "_t" to "1")),
            MgtvSigner.sign(mapOf("cameraId" to "288", "_t" to "1")),
        )
    }

    @Test
    fun `empty string values still participate in the digest`() {
        val withEmpty = mapOf("uid" to "", "cameraId" to "287")
        val withoutUid = mapOf("cameraId" to "287")

        assertNotEquals(MgtvSigner.sign(withEmpty), MgtvSigner.sign(withoutUid))
    }

    @Test
    fun `sign of full source params is stable across calls`() {
        val params = MgtvEndpoints.sourceParams(
            cameraId = "287",
            deviceId = "00000000-0000-0000-0000-000000000000",
            timestampMs = 1700000000000L,
        )

        assertEquals(MgtvSigner.sign(params), MgtvSigner.sign(params))
        assertTrue(params.containsKey("sign").not())
        assertTrue(params.containsKey("_support").not())
    }
}
