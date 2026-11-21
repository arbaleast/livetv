package com.livetv.android.webplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `CctvWebChannel` 的纯 JVM 单测：目录 id → 官方直播页，覆盖正常/越界/注入三类输入。
 */
class CctvWebChannelTest {

    @Test
    fun `maps catalog ids and bare keys to the official live page`() {
        assertEquals("https://tv.cctv.com/live/cctv1/", CctvWebChannel.liveUrl("cctv:cctv1"))
        assertEquals("https://tv.cctv.com/live/cctv17/", CctvWebChannel.liveUrl("cctv:cctv17"))
        assertEquals("https://tv.cctv.com/live/cctv13/", CctvWebChannel.liveUrl("cctv13"))
        assertEquals("https://tv.cctv.com/live/cctv13/", CctvWebChannel.liveUrl("CCTV:CCTV13"))
        assertEquals("https://tv.cctv.com/live/cctv5/", CctvWebChannel.liveUrl("  cctv5  "))
    }

    @Test
    fun `rejects out of range and unsupported channels`() {
        listOf(
            "cctv0",
            "cctv01",
            "cctv18",
            "cctv4k",
            "cctv8k",
            "mgtv:cctv1",
            "cctvbook",
        ).forEach { id ->
            assertNull(id, CctvWebChannel.liveUrl(id))
        }
    }

    @Test
    fun `rejects path injection and malformed ids`() {
        listOf(
            "cctv1/../admin",
            "cctv1?foo=bar",
            "cctv1#hash",
            "cctv1%2f..",
            "cctv1\"><script>alert(1)</script>",
            "cctv:cctv1?x=1",
            "",
            "   ",
            null,
        ).forEach { id ->
            assertNull("应拒绝 $id", CctvWebChannel.liveUrl(id))
        }
    }
}
