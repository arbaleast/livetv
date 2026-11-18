package com.livetv.android.data

import com.livetv.android.data.parser.M3uPlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 校验随包发布的示例播放列表 `app/src/main/assets/sample.m3u`。
 *
 * 直接从源码目录读文件（不依赖 Android Context），确保默认 M3U 100% 合法，
 * 且包含任务书要求的「至少 2 个分类、4 个频道」。
 */
class LocalM3uAssetTest {

    private val assetFile: File = File("src/main/assets/sample.m3u")

    private fun readPlaylist(): String {
        assertTrue("示例播放列表应存在: ${assetFile.absolutePath}", assetFile.exists())
        return assetFile.readText(Charsets.UTF_8)
    }

    @Test
    fun `sample asset exists and parses into two categories`() {
        val categories = M3uPlaylistParser.parse(readPlaylist())

        assertEquals(2, categories.size)
        assertEquals("新闻", categories[0].name)
        assertEquals(2, categories[0].channels.size)
        assertEquals("卫视", categories[1].name)
        assertEquals(2, categories[1].channels.size)
    }

    @Test
    fun `sample asset keeps all four channels with tvg attributes mapped`() {
        val categories = M3uPlaylistParser.parse(readPlaylist())

        val news = categories[0].channels
        assertEquals("cctv1", news[0].id)
        assertEquals("CCTV-1 综合", news[0].name)
        assertEquals("https://example.com/live/cctv1.m3u8", news[0].streamUrl)
        assertEquals("https://example.com/logo/cctv1.png", news[0].logo)
        assertEquals("cctv1", news[0].epgId)
        assertEquals("cctv13", news[1].id)
        assertEquals("CCTV-13 新闻", news[1].name)
        assertEquals("https://example.com/live/cctv13.m3u8", news[1].streamUrl)

        val satellite = categories[1].channels
        assertEquals("hunan", satellite[0].id)
        assertEquals("湖南卫视", satellite[0].name)
        assertEquals("https://example.com/live/hunan.m3u8", satellite[0].streamUrl)
        assertEquals("zhejiang", satellite[1].id)
        assertEquals("浙江卫视", satellite[1].name)
        assertEquals("https://example.com/live/zhejiang.m3u8", satellite[1].streamUrl)
    }

    @Test
    fun `sample asset uses safe optional field values`() {
        val categories = M3uPlaylistParser.parse(readPlaylist())

        categories.forEach { category ->
            category.channels.forEach { channel ->
                assertEquals(emptyList<String>(), channel.backupUrls)
                assertEquals(emptyMap<String, String>(), channel.headers)
                assertEquals("", channel.description)
                assertTrue(channel.enabled)
                assertTrue(channel.streamUrl.startsWith("https://"))
            }
        }
    }
}
