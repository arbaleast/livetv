package com.livetv.android.data.mgtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MgtvSourceSelector] 的白盒筛选与清晰度回退测试（基于**脱敏** fixture）。
 */
class MgtvSourceSelectorTest {

    private fun responseOf(raw: String): MgtvSourceResponse =
        MgtvJsonParser.parseSourceResponse(raw)!!

    @Test
    fun `keeps https m3u8 sources with hdcp zero`() {
        val response = responseOf(MgtvSanitizedFixtures.SOURCE_MIXED)

        val eligible = MgtvSourceSelector.filterSupportedSources(response)

        assertEquals(2, eligible.size)
        assertEquals("https://cdn.example.invalid/ok/360.m3u8", eligible[0].url)
        assertEquals("https://cdn.example.invalid/p2p/720.m3u8", eligible[1].url)
    }

    @Test
    fun `accepts hls source flagged as p2p because we never use p2p transport`() {
        // 实测 221（湖南经视）返回 isp2p=1，但 .m3u8/.ts 均为 HTTP 200 标准 HLS；
        // 若按 isp2p==0 过滤，该频道会永远"无可用源"。
        val response = responseOf(MgtvSanitizedFixtures.SOURCE_MIXED)
        val p2p = response.sources.first { it.url.contains("/p2p/") }

        assertTrue(
            "isp2p=1 但为 https .m3u8 且 hdcp=0 时应放行",
            MgtvSourceSelector.filterSupportedSources(response).any { it.url == p2p.url },
        )
    }

    @Test
    fun `rejects hdcp source`() {
        val response = responseOf(MgtvSanitizedFixtures.SOURCE_MIXED)
        val hdcp = response.sources.first { it.url.contains("/hdcp/") }

        assertTrue(MgtvSourceSelector.filterSupportedSources(response).none { it.url == hdcp.url })
    }

    @Test
    fun `rejects non https source`() {
        val response = responseOf(MgtvSanitizedFixtures.SOURCE_MIXED)
        val plain = response.sources.first { it.url.startsWith("http://") }

        assertTrue(MgtvSourceSelector.filterSupportedSources(response).none { it.url == plain.url })
    }

    @Test
    fun `rejects non m3u8 format`() {
        val response = responseOf(MgtvSanitizedFixtures.SOURCE_MIXED)
        val flv = response.sources.first { it.format == ".flv" }

        assertTrue(MgtvSourceSelector.filterSupportedSources(response).none { it.url == flv.url })
    }

    @Test
    fun `selects preferred definition when available`() {
        val response = responseOf(MgtvSanitizedFixtures.SOURCE_MIXED)

        val selected = MgtvSourceSelector.selectBestSource(response, "360P")

        assertEquals("https://cdn.example.invalid/ok/360.m3u8", selected?.url)
    }

    @Test
    fun `falls back to the highest resolution eligible source when preference misses`() {
        val response = responseOf(MgtvSanitizedFixtures.SOURCE_MIXED)

        val selected = MgtvSourceSelector.selectBestSource(response, "4K")

        assertEquals("https://cdn.example.invalid/p2p/720.m3u8", selected?.url)
    }

    @Test
    fun `falls back deterministically to highest resolution`() {
        val response = MgtvSourceResponse(
            code = "0",
            message = "",
            cameraId = "287",
            cameraName = "金鹰卡通",
            isP2p = 0,
            hdcp = 0,
            serverTimeSec = 0L,
            sources = listOf(
                MgtvSourceItem(
                    url = "https://cdn.example.invalid/a/360.m3u8",
                    format = ".m3u8",
                    definition = "2",
                    name = "360P",
                    urlExpireDurationSec = 28800L,
                ),
                MgtvSourceItem(
                    url = "https://cdn.example.invalid/a/1080.m3u8",
                    format = ".m3u8",
                    definition = "4",
                    name = "1080P",
                    urlExpireDurationSec = 28800L,
                ),
                MgtvSourceItem(
                    url = "https://cdn.example.invalid/a/720.m3u8",
                    format = ".m3u8",
                    definition = "3",
                    name = "720P",
                    urlExpireDurationSec = 28800L,
                ),
            ),
        )

        val selected = MgtvSourceSelector.selectBestSource(response)

        assertEquals("https://cdn.example.invalid/a/1080.m3u8", selected?.url)
    }

    @Test
    fun `returns null when no eligible source exists`() {
        val response = responseOf(MgtvSanitizedFixtures.SOURCE_EMPTY)

        assertNull(MgtvSourceSelector.selectBestSource(response))
        assertEquals(emptyList<MgtvSourceItem>(), MgtvSourceSelector.filterSupportedSources(response))
    }
}
