package com.livetv.android.data.cctv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CctvChannelCatalog` 的纯 JVM 单元测试。
 *
 * 契约：频道 id 统一 `cctv:` 前缀、分类 id 统一 `cctv:` 前缀、`streamUrl` 恒为空字符串；
 * 损坏 JSON / 缺字段 / 空数组一律 fail-closed 返回空列表，不抛异常。
 */
class CctvChannelCatalogTest {

    private fun catalog(json: String?) = CctvChannelCatalog(catalogLoader = { json })

    private val validJson = """
        {
          "version": 1,
          "categories": [
            {"id": "news", "name": "新闻", "channels": [
              {"id": "cctv1", "name": "CCTV-1 综合", "epgId": "cctv1", "logo": "logo/cctv1.png"},
              {"id": "cctv13", "name": "CCTV-13 新闻", "epgId": "cctv13"}
            ]},
            {"id": "sports", "name": "体育", "channels": [
              {"id": "cctv5", "name": "CCTV-5 体育", "epgId": "cctv5"}
            ]}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses categories with cctv namespace on both category and channel ids`() {
        val categories = catalog(validJson).load()

        assertEquals(listOf("cctv:news", "cctv:sports"), categories.map { it.id })
        assertEquals(listOf("cctv:cctv1", "cctv:cctv13"), categories.first().channels.map { it.id })
    }

    @Test
    fun `every channel keeps an empty stream url`() {
        val channels = catalog(validJson).load().flatMap { it.channels }

        assertTrue(channels.isNotEmpty())
        assertTrue("CCTV 频道不允许携带任何播放地址", channels.all { it.streamUrl.isEmpty() })
    }

    @Test
    fun `maps name epg id and logo`() {
        val channel = catalog(validJson).load().first().channels.first()

        assertEquals("CCTV-1 综合", channel.name)
        assertEquals("cctv1", channel.epgId)
        assertEquals("logo/cctv1.png", channel.logo)
    }

    @Test
    fun `broken json fails closed with empty list`() {
        assertTrue(catalog("{not a json").load().isEmpty())
    }

    @Test
    fun `missing required fields are skipped without crashing`() {
        val json = """
            {"categories": [
              {"id": "", "name": "无 id", "channels": [{"id": "cctv1", "name": "CCTV-1"}]},
              {"id": "news", "name": "", "channels": [{"id": "cctv1", "name": "CCTV-1"}]},
              {"id": "news", "name": "新闻", "channels": [
                {"id": "", "name": "无 id 频道"},
                {"id": "cctv13", "name": ""},
                {"id": "cctv1", "name": "CCTV-1"}
              ]}
            ]}
        """.trimIndent()

        val categories = catalog(json).load()

        assertEquals(1, categories.size)
        assertEquals(listOf("cctv:cctv1"), categories.single().channels.map { it.id })
    }

    @Test
    fun `empty or null payload yields empty list`() {
        assertTrue(catalog(null).load().isEmpty())
        assertTrue(catalog("").load().isEmpty())
        assertTrue(catalog("""{"version":1,"categories":[]}""").load().isEmpty())
    }
}
