package com.livetv.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `Channel` 领域模型的纯 JVM 冒烟测试。
 *
 * 目的：固定模型层的契约（字段语义、默认值、值语义相等），并保证
 * `testDebugUnitTest` 链路通畅。解析与过滤规则见 `ChannelJsonParserTest`。
 */
class ChannelTest {

    @Test
    fun `streamUrl defaults to empty string`() {
        val channel = Channel(id = "n1", name = "综合新闻", description = "24 小时滚动")

        assertEquals("", channel.streamUrl)
    }

    @Test
    fun `optional fields keep backward compatible defaults`() {
        val channel = Channel(id = "n1", name = "综合新闻", description = "24 小时滚动", streamUrl = "https://example.com/live.m3u8")

        assertEquals(emptyList<String>(), channel.backupUrls)
        assertEquals(emptyMap<String, String>(), channel.headers)
        assertEquals("", channel.logo)
        assertEquals("", channel.epgId)
        assertEquals(true, channel.enabled)
    }

    @Test
    fun `keeps every field exactly as provided`() {
        val channel = Channel(
            id = "s1",
            name = "体育赛事",
            description = "直播与集锦",
            streamUrl = "https://example.com/live/s1/index.m3u8",
            backupUrls = listOf("https://backup.example.com/live/s1.m3u8"),
            headers = mapOf("User-Agent" to "LiveTV/1.0"),
            logo = "https://example.com/logo/s1.png",
            epgId = "epg-s1",
            enabled = true,
        )

        assertEquals("s1", channel.id)
        assertEquals("体育赛事", channel.name)
        assertEquals("直播与集锦", channel.description)
        assertEquals("https://example.com/live/s1/index.m3u8", channel.streamUrl)
        assertEquals(listOf("https://backup.example.com/live/s1.m3u8"), channel.backupUrls)
        assertEquals(mapOf("User-Agent" to "LiveTV/1.0"), channel.headers)
        assertEquals("https://example.com/logo/s1.png", channel.logo)
        assertEquals("epg-s1", channel.epgId)
    }

    @Test
    fun `two channels with same values are equal and share hash code`() {
        val first = Channel("c3", "财经频道", "市场与政策", "https://example.com/live/c3.m3u8")
        val second = Channel("c3", "财经频道", "市场与政策", "https://example.com/live/c3.m3u8")

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `copy changes only the replaced field`() {
        val original = Channel("c4", "国际资讯", "全球热点", "https://example.com/live/c4.m3u8")

        val copy = original.copy(name = "国际资讯 24h")

        assertEquals("国际资讯 24h", copy.name)
        assertEquals("c4", copy.id)
        assertEquals("全球热点", copy.description)
        assertEquals("https://example.com/live/c4.m3u8", copy.streamUrl)
        assertNotEquals(original, copy)
    }
}
