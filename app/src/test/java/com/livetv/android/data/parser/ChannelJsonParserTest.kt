package com.livetv.android.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChannelJsonParser` 的纯 JVM 单元测试。
 *
 * 覆盖：标准解析、缺省字段容错、禁用/空分类过滤、URL 协议安全拦截、异常数据恢复。
 * 注意：本类依赖真实 `org.json` 实现（`testImplementation("org.json:json:20231013")`），
 * 否则在纯 JVM 环境下会命中 android.jar 的 "not mocked" Stub。
 */
class ChannelJsonParserTest {

    // ---------- 标准配置解析 ----------

    @Test
    fun `parses a complete multi category config`() {
        val json = """
        {
          "version": 1,
          "categories": [
            {
              "id": "news",
              "name": "新闻",
              "channels": [
                {
                  "id": "cctv-news",
                  "name": "新闻频道",
                  "description": "新闻直播",
                  "streamUrl": "https://example.com/live.m3u8",
                  "backupUrls": ["https://backup.example.com/live.m3u8"],
                  "headers": {"User-Agent": "LiveTV/1.0", "Referer": "https://example.com/"},
                  "logo": "https://example.com/logo.png",
                  "epgId": "epg-cctv-news",
                  "enabled": true
                }
              ]
            },
            {
              "id": "sports",
              "name": "体育",
              "channels": [
                {
                  "id": "cctv-sports",
                  "name": "体育频道",
                  "description": "赛事直播",
                  "streamUrl": "http://example.com/sports.m3u8",
                  "backupUrls": [],
                  "headers": {},
                  "logo": "",
                  "epgId": "",
                  "enabled": true
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val categories = ChannelJsonParser.parse(json)

        assertEquals(2, categories.size)

        val news = categories[0]
        assertEquals("news", news.id)
        assertEquals("新闻", news.name)
        assertEquals(1, news.channels.size)

        val newsChannel = news.channels[0]
        assertEquals("cctv-news", newsChannel.id)
        assertEquals("新闻频道", newsChannel.name)
        assertEquals("新闻直播", newsChannel.description)
        assertEquals("https://example.com/live.m3u8", newsChannel.streamUrl)
        assertEquals(listOf("https://backup.example.com/live.m3u8"), newsChannel.backupUrls)
        assertEquals(
            mapOf("User-Agent" to "LiveTV/1.0", "Referer" to "https://example.com/"),
            newsChannel.headers,
        )
        assertEquals("https://example.com/logo.png", newsChannel.logo)
        assertEquals("epg-cctv-news", newsChannel.epgId)
        assertTrue(newsChannel.enabled)

        val sports = categories[1]
        assertEquals("sports", sports.id)
        assertEquals("体育", sports.name)
        assertEquals(1, sports.channels.size)
        assertEquals("cctv-sports", sports.channels[0].id)
        assertEquals("http://example.com/sports.m3u8", sports.channels[0].streamUrl)
    }

    // ---------- 缺省字段容错 ----------

    @Test
    fun `falls back to defaults when optional fields are missing`() {
        val json = """
        {
          "categories": [
            {
              "id": "minimal",
              "name": "极简分类",
              "channels": [
                {"id": "m1", "name": "最小频道", "streamUrl": "https://example.com/min.m3u8"}
              ]
            }
          ]
        }
        """.trimIndent()

        val categories = ChannelJsonParser.parse(json)

        assertEquals(1, categories.size)
        val channel = categories[0].channels[0]
        assertEquals("m1", channel.id)
        assertEquals("最小频道", channel.name)
        assertEquals("https://example.com/min.m3u8", channel.streamUrl)
        assertEquals("", channel.description)
        assertEquals(emptyList<String>(), channel.backupUrls)
        assertEquals(emptyMap<String, String>(), channel.headers)
        assertEquals("", channel.logo)
        assertEquals("", channel.epgId)
        assertTrue(channel.enabled)
    }

    @Test
    fun `uses category id as name when name is missing`() {
        val json = """
        {
          "categories": [
            {"id": "no-name", "channels": [
              {"id": "x1", "name": "频道", "streamUrl": "https://example.com/x.m3u8"}
            ]}
          ]
        }
        """.trimIndent()

        val categories = ChannelJsonParser.parse(json)

        assertEquals(1, categories.size)
        assertEquals("no-name", categories[0].name)
    }

    // ---------- 禁用与空分类过滤 ----------

    @Test
    fun `drops disabled channels and categories that become empty`() {
        val json = """
        {
          "categories": [
            {
              "id": "all-disabled",
              "name": "全禁用",
              "channels": [
                {"id": "d1", "name": "禁用频道", "streamUrl": "https://example.com/d1.m3u8", "enabled": false}
              ]
            },
            {
              "id": "mixed",
              "name": "混合",
              "channels": [
                {"id": "d2", "name": "禁用频道", "streamUrl": "https://example.com/d2.m3u8", "enabled": false},
                {"id": "d3", "name": "启用频道", "streamUrl": "https://example.com/d3.m3u8", "enabled": true}
              ]
            }
          ]
        }
        """.trimIndent()

        val categories = ChannelJsonParser.parse(json)

        assertEquals(1, categories.size)
        assertEquals("mixed", categories[0].id)
        assertEquals(1, categories[0].channels.size)
        assertEquals("d3", categories[0].channels[0].id)
    }

    // ---------- URL 协议安全拦截 ----------

    @Test
    fun `rejects channels whose stream url is not http or https`() {
        val json = """
        {
          "categories": [
            {
              "id": "unsafe",
              "name": "不安全",
              "channels": [
                {"id": "u1", "name": "空地址", "streamUrl": ""},
                {"id": "u2", "name": "本地文件", "streamUrl": "file:///etc/passwd"},
                {"id": "u3", "name": "脚本注入", "streamUrl": "javascript:alert(1)"},
                {"id": "u4", "name": "RTMP", "streamUrl": "rtmp://example.com/live"},
                {"id": "u5", "name": "合法", "streamUrl": "https://example.com/ok.m3u8"}
              ]
            }
          ]
        }
        """.trimIndent()

        val categories = ChannelJsonParser.parse(json)

        assertEquals(1, categories.size)
        assertEquals(1, categories[0].channels.size)
        assertEquals("u5", categories[0].channels[0].id)
        assertEquals("https://example.com/ok.m3u8", categories[0].channels[0].streamUrl)
    }

    @Test
    fun `cleans illegal backup urls but keeps legal ones`() {
        val json = """
        {
          "categories": [
            {
              "id": "backup",
              "name": "备源",
              "channels": [
                {
                  "id": "b1",
                  "name": "带备源",
                  "streamUrl": "https://example.com/main.m3u8",
                  "backupUrls": [
                    "https://backup1.example.com/a.m3u8",
                    "file:///etc/shadow",
                    "javascript:alert(1)",
                    "",
                    "http://backup2.example.com/b.m3u8"
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val categories = ChannelJsonParser.parse(json)

        assertEquals(1, categories.size)
        assertEquals(
            listOf("https://backup1.example.com/a.m3u8", "http://backup2.example.com/b.m3u8"),
            categories[0].channels[0].backupUrls,
        )
    }

    @Test
    fun `url validation accepts http and https regardless of case`() {
        assertTrue(ChannelJsonParser.isValidStreamUrl("https://example.com/a.m3u8"))
        assertTrue(ChannelJsonParser.isValidStreamUrl("http://example.com/a.m3u8"))
        assertTrue(ChannelJsonParser.isValidStreamUrl("HTTPS://example.com/a.m3u8"))
        assertFalse(ChannelJsonParser.isValidStreamUrl(""))
        assertFalse(ChannelJsonParser.isValidStreamUrl("   "))
        assertFalse(ChannelJsonParser.isValidStreamUrl("file:///etc/passwd"))
        assertFalse(ChannelJsonParser.isValidStreamUrl("javascript:alert(1)"))
        assertFalse(ChannelJsonParser.isValidStreamUrl("rtmp://example.com/live"))
        assertFalse(ChannelJsonParser.isValidStreamUrl("ftp://example.com/a"))
    }

    // ---------- 异常数据恢复（防崩溃） ----------

    @Test
    fun `returns empty list for blank or broken json without throwing`() {
        assertEquals(emptyList<Any>(), ChannelJsonParser.parse(""))
        assertEquals(emptyList<Any>(), ChannelJsonParser.parse("   "))
        assertEquals(emptyList<Any>(), ChannelJsonParser.parse("{bad"))
        assertEquals(emptyList<Any>(), ChannelJsonParser.parse("not json at all"))
        assertEquals(emptyList<Any>(), ChannelJsonParser.parse("[]"))
    }

    @Test
    fun `returns empty list when top level structure does not match the schema`() {
        assertEquals(emptyList<Any>(), ChannelJsonParser.parse("""{"categories": "not-an-array"}"""))
        assertEquals(emptyList<Any>(), ChannelJsonParser.parse("""{"categories": [{"id": "x", "channels": "nope"}]}"""))
        assertEquals(emptyList<Any>(), ChannelJsonParser.parse("""{"categories": [{"id": "x", "channels": [{"id": "y"}]}]}"""))
        assertEquals(emptyList<Any>(), ChannelJsonParser.parse("""{"categories": [{"name": "无 id", "channels": [{"id": "y", "name": "n", "streamUrl": "https://example.com/a.m3u8"}]}]}"""))
    }

    @Test
    fun `parse to dto returns null for broken json`() {
        assertEquals(null, ChannelJsonParser.parseToDto("{bad"))
        assertEquals(null, ChannelJsonParser.parseToDto(""))
        assertEquals(null, ChannelJsonParser.parseToDto("not json at all"))
    }

    @Test
    fun `parse to dto falls back to default version when version field is missing`() {
        val dto = ChannelJsonParser.parseToDto("""{"categories": []}""")

        assertEquals(1, dto?.version)
        assertEquals(0, dto?.categories?.size)
    }
}
