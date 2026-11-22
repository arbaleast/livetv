package com.livetv.android.webplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CctvWebChannel` 纯 JVM 单测：
 * 验证 14 路移动直连 `/m/`、cctv9/14 slug 映射、3/6/8 桌面 UA 直连、4K 轮播标记与 8K 拒绝。
 */
class CctvWebChannelTest {

    @Test
    fun `maps 14 mobile live channels directly to mobile m urls including cctv9 and cctv14 slugs`() {
        val expectedMobile = mapOf(
            "cctv:cctv1" to "https://tv.cctv.com/live/cctv1/m/",
            "cctv:cctv2" to "https://tv.cctv.com/live/cctv2/m/",
            "cctv:cctv4" to "https://tv.cctv.com/live/cctv4/m/",
            "cctv:cctv5" to "https://tv.cctv.com/live/cctv5/m/",
            "cctv:cctv7" to "https://tv.cctv.com/live/cctv7/m/",
            "cctv:cctv9" to "https://tv.cctv.com/live/cctvjilu/m/",
            "cctv:cctv10" to "https://tv.cctv.com/live/cctv10/m/",
            "cctv:cctv11" to "https://tv.cctv.com/live/cctv11/m/",
            "cctv:cctv12" to "https://tv.cctv.com/live/cctv12/m/",
            "cctv:cctv13" to "https://tv.cctv.com/live/cctv13/m/",
            "cctv:cctv14" to "https://tv.cctv.com/live/cctvchild/m/",
            "cctv:cctv15" to "https://tv.cctv.com/live/cctv15/m/",
            "cctv:cctv16" to "https://tv.cctv.com/live/cctv16/m/",
            "cctv:cctv17" to "https://tv.cctv.com/live/cctv17/m/",
        )
        assertEquals(14, expectedMobile.size)
        expectedMobile.forEach { (id, url) ->
            assertEquals(url, CctvWebChannel.liveUrl(id))
            assertEquals(CctvWebPlayMode.MOBILE_LIVE, CctvWebChannel.playMode(id))
            assertFalse(CctvWebChannel.requiresDesktopUserAgent(id))
            assertFalse(CctvWebChannel.isVodPoolChannel(id))
        }
        // 大小写与空白容错
        assertEquals("https://tv.cctv.com/live/cctvjilu/m/", CctvWebChannel.liveUrl("  CCTV:CCTV9  "))
        assertEquals("https://tv.cctv.com/live/cctvchild/m/", CctvWebChannel.liveUrl("cctv14"))
    }

    @Test
    fun `maps cctv3 cctv6 cctv8 to desktop live urls requiring desktop user agent`() {
        listOf("cctv3", "cctv6", "cctv8").forEach { slug ->
            val id = "cctv:$slug"
            assertEquals("https://tv.cctv.com/live/$slug/", CctvWebChannel.liveUrl(id))
            assertEquals(CctvWebPlayMode.DESKTOP_LIVE, CctvWebChannel.playMode(id))
            assertTrue("$id 必须启用桌面 UA 规避 /m/ 强跳央视频", CctvWebChannel.requiresDesktopUserAgent(id))
            assertFalse(CctvWebChannel.isVodPoolChannel(id))
        }
    }

    @Test
    fun `recognizes cctv4k as vod pool channel without static live url`() {
        assertEquals("cctv4k", CctvWebChannel.keyOf("cctv:cctv4k"))
        assertEquals("cctv4k", CctvWebChannel.keyOf("CCTV4K"))
        assertEquals(CctvWebPlayMode.VOD_4K_POOL, CctvWebChannel.playMode("cctv:cctv4k"))
        assertTrue(CctvWebChannel.isVodPoolChannel("cctv:cctv4k"))
        assertTrue(CctvWebChannel.isSupported("cctv:cctv4k"))
        assertFalse(CctvWebChannel.requiresDesktopUserAgent("cctv:cctv4k"))
        assertNull("4K 轮播地址来自节目池，不返回静态 /live/ URL", CctvWebChannel.liveUrl("cctv:cctv4k"))
        assertEquals(
            "4K 点播轮播台黑幕超时兜底应放宽至 12s",
            12_000L,
            CctvWebChannel.focusTimeoutMs("cctv:cctv4k"),
        )
        assertEquals(
            "普通直播台黑幕超时兜底应保持 7s",
            7_000L,
            CctvWebChannel.focusTimeoutMs("cctv:cctv13"),
        )
    }

    @Test
    fun `rejects cctv8k out of range and unsupported channels`() {
        listOf(
            "cctv0",
            "cctv01",
            "cctv18",
            "cctv8k",
            "cctv:cctv8k",
            "mgtv:cctv1",
            "cctvbook",
        ).forEach { id ->
            assertFalse("应拒绝 $id", CctvWebChannel.isSupported(id))
            assertNull(id, CctvWebChannel.liveUrl(id))
            assertNull(id, CctvWebChannel.playMode(id))
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
            assertFalse("应拒绝 $id", CctvWebChannel.isSupported(id))
            assertNull("应拒绝 $id", CctvWebChannel.liveUrl(id))
        }
    }
}
