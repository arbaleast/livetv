package com.livetv.android.data

import com.livetv.android.data.parser.ChannelJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 校验随包发布的默认频道配置 `app/src/main/assets/channels.json`。
 *
 * 直接从源码目录读文件（不依赖 Android Context），确保默认 JSON 100% 合法，
 * 且阶段一硬编码的 6 个频道已无损迁移。
 */
class LocalAssetsChannelsTest {

    private val assetsFile: File = File("src/main/assets/channels.json")

    private fun readConfig(): String {
        assertTrue("默认频道配置文件应存在: ${assetsFile.absolutePath}", assetsFile.exists())
        return assetsFile.readText(Charsets.UTF_8)
    }

    @Test
    fun `default asset file exists and parses into two categories`() {
        val categories = ChannelJsonParser.parse(readConfig())

        assertEquals(2, categories.size)
        assertEquals("news", categories[0].id)
        assertEquals("新闻", categories[0].name)
        assertEquals(3, categories[0].channels.size)
        assertEquals("sports", categories[1].id)
        assertEquals("体育", categories[1].name)
        assertEquals(3, categories[1].channels.size)
    }

    @Test
    fun `default asset keeps all six migrated channels intact`() {
        val categories = ChannelJsonParser.parse(readConfig())

        val news = categories[0].channels
        assertEquals("n1", news[0].id)
        assertEquals("综合新闻", news[0].name)
        assertEquals("24 小时滚动", news[0].description)
        assertEquals("https://cctvihrd.cctv.com/live/huaweiiptv/1/1/index.m3u8", news[0].streamUrl)
        assertEquals("n2", news[1].id)
        assertEquals("财经频道", news[1].name)
        assertEquals("市场与政策", news[1].description)
        assertEquals("https://cctvihrd.cctv.com/live/huaweiiptv/2/1/index.m3u8", news[1].streamUrl)
        assertEquals("n3", news[2].id)
        assertEquals("国际资讯", news[2].name)
        assertEquals("全球热点", news[2].description)
        assertEquals("https://cctvihrd.cctv.com/live/huaweiiptv/3/1/index.m3u8", news[2].streamUrl)

        val sports = categories[1].channels
        assertEquals("s1", sports[0].id)
        assertEquals("体育赛事", sports[0].name)
        assertEquals("直播与集锦", sports[0].description)
        assertEquals("https://cctvihrd.cctv.com/live/huaweiiptv/5/1/index.m3u8", sports[0].streamUrl)
        assertEquals("s2", sports[1].id)
        assertEquals("足球世界", sports[1].name)
        assertEquals("联赛专题", sports[1].description)
        assertEquals("https://cctvihrd.cctv.com/live/huaweiiptv/6/1/index.m3u8", sports[1].streamUrl)
        assertEquals("s3", sports[2].id)
        assertEquals("奥林匹克", sports[2].name)
        assertEquals("经典回顾", sports[2].description)
        assertEquals("https://cctvihrd.cctv.com/live/huaweiiptv/7/1/index.m3u8", sports[2].streamUrl)
    }

    @Test
    fun `default asset uses safe optional field values`() {
        val categories = ChannelJsonParser.parse(readConfig())

        categories.forEach { category ->
            category.channels.forEach { channel ->
                assertEquals(emptyList<String>(), channel.backupUrls)
                assertEquals(emptyMap<String, String>(), channel.headers)
                assertEquals("", channel.logo)
                assertEquals("", channel.epgId)
                assertTrue(channel.enabled)
                assertTrue(channel.streamUrl.startsWith("https://"))
            }
        }
    }
}
