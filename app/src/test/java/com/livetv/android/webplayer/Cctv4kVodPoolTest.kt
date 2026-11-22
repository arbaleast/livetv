package com.livetv.android.webplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * [Cctv4kVodPool] 纯 JVM 单元测试：
 * 验证 4K 专辑池 JSON 解析、白名单校验、单条/多条避重随机抽取及内置资产合法性。
 */
class Cctv4kVodPoolTest {

    @Test
    fun `parses valid official vod entries and rejects non-official or malformed entries`() {
        val raw = """
            [
              {"title": "《味道》 保山篇", "url": "https://tv.cctv.com/2022/11/11/VIDE1.shtml"},
              {"title": "重复项", "url": "https://tv.cctv.com/2022/11/11/VIDE1.shtml"},
              {"title": "非官方域", "url": "https://evil.example/video.shtml"},
              {"title": "", "url": "https://tv.cctv.com/2022/11/11/VIDE2.shtml"},
              {"title": "缺链接", "url": ""},
              {"title": "第二条合法", "url": "https://tv.cctv.com/2023/01/01/VIDE3.shtml"}
            ]
        """.trimIndent()

        val items = Cctv4kVodPool.parse(raw)
        assertEquals(2, items.size)
        assertEquals("《味道》 保山篇", items[0].title)
        assertEquals("https://tv.cctv.com/2022/11/11/VIDE1.shtml", items[0].url)
        assertEquals("第二条合法", items[1].title)
        assertEquals("https://tv.cctv.com/2023/01/01/VIDE3.shtml", items[1].url)
    }

    @Test
    fun `fails closed on empty or broken json`() {
        assertTrue(Cctv4kVodPool.parse(null).isEmpty())
        assertTrue(Cctv4kVodPool.parse("").isEmpty())
        assertTrue(Cctv4kVodPool.parse("{broken").isEmpty())
        assertNull(Cctv4kVodPool.pickNext(emptyList()))
    }

    @Test
    fun `pickNext returns sole item when pool size is 1 and avoids current url when pool size exceeds 1`() {
        val item1 = Cctv4kVodItem("第一期", "https://tv.cctv.com/2022/11/11/VIDE1.shtml")
        val item2 = Cctv4kVodItem("第二期", "https://tv.cctv.com/2022/11/12/VIDE2.shtml")
        val item3 = Cctv4kVodItem("第三期", "https://tv.cctv.com/2022/11/13/VIDE3.shtml")

        // 单条池直接返回唯一项
        assertEquals(item1, Cctv4kVodPool.pickNext(listOf(item1), currentUrl = item1.url))

        // 多条池在连续抽取中必须避开当前播放 URL
        val pool = listOf(item1, item2, item3)
        val rng = Random(42)
        var current = item1.url
        repeat(20) {
            val next = Cctv4kVodPool.pickNext(pool, currentUrl = current, random = rng)
            assertNotEquals("多条节目池切下一期不得重复命中当前 URL", current, next?.url)
            current = next!!.url
        }
    }

    @Test
    fun `bundled cctv_4k_vod_pool asset exists and contains diverse valid official 4k urls`() {
        val asset = File("src/main/assets/${Cctv4kVodPool.ASSET_FILE_NAME}")
        assertTrue("应存在内置 4K 节目池资产: ${asset.absolutePath}", asset.isFile)
        val parsed = Cctv4kVodPool.parse(asset.readText(Charsets.UTF_8))
        assertTrue("内置 4K 彩蛋节目池应包含多条官方专辑以保证随机不重复（实际 ${parsed.size} 条）", parsed.size >= 15)
        assertTrue(
            "应内置已验证的首条 4K 专辑链接",
            parsed.any { it.url == "https://tv.cctv.com/2022/11/11/VIDEahPnuXyrQIaPTl2Gb9Ku221111.shtml" },
        )
        val first = Cctv4kVodPool.pickNext(parsed, currentUrl = null, random = Random(1))!!
        val second = Cctv4kVodPool.pickNext(parsed, currentUrl = first.url, random = Random(1))!!
        assertNotEquals("连续两次抽签必须避开上一条视频", first.url, second.url)
    }

    @Test
    fun `bundled cctv_channels asset contains 18 channels without cctv8k`() {
        val asset = File("src/main/assets/cctv_channels.json")
        assertTrue(asset.isFile)
        val text = asset.readText(Charsets.UTF_8)
        assertTrue("应保留 cctv4k", text.contains("\"cctv4k\""))
        assertTrue("应已移除 cctv8k", !text.contains("\"cctv8k\""))
        val channelCount = Regex("\"epgId\"").findAll(text).count()
        assertEquals("央视静态目录应共含 18 路频道（17 路直播 + 1 路 4K 轮播）", 18, channelCount)
    }
}
