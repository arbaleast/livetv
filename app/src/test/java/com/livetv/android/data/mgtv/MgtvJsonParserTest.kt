package com.livetv.android.data.mgtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MgtvJsonParser] 的纯 JVM 测试（基于**脱敏** fixture）。
 */
class MgtvJsonParserTest {

    @Test
    fun `unwraps jsonp callback wrapper`() {
        val unwrapped = MgtvJsonParser.unwrapJsonp(MgtvSanitizedFixtures.CATEGORY_JSONP)

        assertTrue(unwrapped.startsWith("{"))
        assertTrue(unwrapped.endsWith("}"))
        assertTrue(unwrapped.contains("\"errno\":\"0\""))
    }

    @Test
    fun `keeps plain json untouched`() {
        val unwrapped = MgtvJsonParser.unwrapJsonp(MgtvSanitizedFixtures.CATEGORY_JSON)

        assertEquals(MgtvSanitizedFixtures.CATEGORY_JSON.trim(), unwrapped)
    }

    @Test
    fun `parses category list from jsonp`() {
        val categories = MgtvJsonParser.parseCategoryList(MgtvSanitizedFixtures.CATEGORY_JSONP)

        assertEquals(2, categories.size)
        val first = categories[0]
        assertEquals("cat_sanitized_1", first.id)
        assertEquals("卫视频道", first.name)
        assertEquals(2, first.channels.size)
        assertEquals("287", first.channels[0].cameraId)
        assertEquals("金鹰卡通", first.channels[0].name)
        assertEquals("https://cdn.example.invalid/logo/sanitized.png", first.channels[0].logoUrl)
        assertEquals("287", first.channels[0].channelNumber)
        assertEquals("脱敏示例节目", first.channels[0].currentProgram)
    }

    @Test
    fun `parses category list from plain json`() {
        val categories = MgtvJsonParser.parseCategoryList(MgtvSanitizedFixtures.CATEGORY_JSON)

        assertEquals(1, categories.size)
        assertEquals(1, categories[0].channels.size)
        assertEquals("287", categories[0].channels[0].cameraId)
    }

    @Test
    fun `returns empty list on business error code`() {
        assertEquals(emptyList<MgtvCategory>(), MgtvJsonParser.parseCategoryList(MgtvSanitizedFixtures.CATEGORY_ERROR_JSON))
    }

    @Test
    fun `returns empty list on malformed json`() {
        assertEquals(emptyList<MgtvCategory>(), MgtvJsonParser.parseCategoryList("{not json"))
        assertEquals(emptyList<MgtvCategory>(), MgtvJsonParser.parseCategoryList(""))
    }

    @Test
    fun `parses source response fields`() {
        val response = MgtvJsonParser.parseSourceResponse(MgtvSanitizedFixtures.SOURCE_OK)

        assertEquals("0", response?.code)
        assertEquals("287", response?.cameraId)
        assertEquals("金鹰卡通", response?.cameraName)
        assertEquals(0, response?.isP2p)
        assertEquals(0, response?.hdcp)
        assertEquals(1700000000L, response?.serverTimeSec)
        assertEquals(1, response?.sources?.size)

        val source = response!!.sources[0]
        assertEquals(".m3u8", source.format)
        assertEquals("h264", source.videoFormat)
        assertEquals("AAC", source.audioFormat)
        assertEquals("2", source.definition)
        assertEquals("360P", source.name)
        assertEquals(28800L, source.urlExpireDurationSec)
        assertEquals("SANITIZED_SID", source.sid)
        // 单源未显式给出 isp2p/hdcp 时沿用响应级取值
        assertNull(source.isP2p)
        assertNull(source.hdcp)
    }

    @Test
    fun `parses per source p2p and hdcp overrides`() {
        val response = MgtvJsonParser.parseSourceResponse(MgtvSanitizedFixtures.SOURCE_MIXED)

        assertEquals(5, response?.sources?.size)
        assertNull(response!!.sources[0].isP2p)
        assertEquals(1, response.sources[1].isP2p)
        assertEquals(1, response.sources[2].hdcp)
    }

    @Test
    fun `returns null for malformed source response`() {
        assertNull(MgtvJsonParser.parseSourceResponse("not json at all"))
        assertNull(MgtvJsonParser.parseSourceResponse(""))
    }
}
