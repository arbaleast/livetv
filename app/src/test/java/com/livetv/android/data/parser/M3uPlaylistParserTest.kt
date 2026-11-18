package com.livetv.android.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `M3uPlaylistParser` 的纯 JVM 单元测试。
 *
 * 覆盖：标准 EXTINF 解析与属性映射、无分组与残缺属性容错、非法格式与乱序容错。
 */
class M3uPlaylistParserTest {

    // ---------- 标准解析 ----------

    @Test
    fun `parses extinf attributes and groups channels by group title`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" tvg-logo="https://logo.com/cctv1.png" group-title="央视",CCTV-1 综合
            https://example.com/cctv1.m3u8
            #EXTINF:-1 group-title="卫视",湖南卫视
            https://example.com/hunan.m3u8
        """.trimIndent()

        val categories = M3uPlaylistParser.parse(playlist)

        assertEquals(2, categories.size)
        assertEquals("央视", categories[0].name)
        assertEquals(1, categories[0].channels.size)
        assertEquals("卫视", categories[1].name)
        assertEquals(1, categories[1].channels.size)
    }

    @Test
    fun `maps tvg attributes onto the channel model`() {
        val playlist = """
            #EXTINF:-1 tvg-id="cctv1" tvg-logo="https://logo.com/cctv1.png" group-title="央视",CCTV-1 综合
            https://example.com/cctv1.m3u8
        """.trimIndent()

        val channel = M3uPlaylistParser.parse(playlist)[0].channels[0]

        assertEquals("cctv1", channel.id)
        assertEquals("CCTV-1 综合", channel.name)
        assertEquals("https://example.com/cctv1.m3u8", channel.streamUrl)
        assertEquals("https://logo.com/cctv1.png", channel.logo)
        assertEquals("cctv1", channel.epgId)
        assertEquals("", channel.description)
        assertEquals(emptyList<String>(), channel.backupUrls)
        assertEquals(emptyMap<String, String>(), channel.headers)
        assertTrue(channel.enabled)
    }

    @Test
    fun `trims whitespace around the display name`() {
        val playlist = """
            #EXTINF:-1 group-title="央视",   CCTV-1 综合
            https://example.com/cctv1.m3u8
        """.trimIndent()

        val channel = M3uPlaylistParser.parse(playlist)[0].channels[0]

        assertEquals("CCTV-1 综合", channel.name)
    }

    @Test
    fun `keeps every channel of the same group in one category`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",CCTV-1 综合
            https://example.com/cctv1.m3u8
            #EXTINF:-1 group-title="央视",CCTV-13 新闻
            https://example.com/cctv13.m3u8
        """.trimIndent()

        val categories = M3uPlaylistParser.parse(playlist)

        assertEquals(1, categories.size)
        assertEquals(2, categories[0].channels.size)
        assertEquals("CCTV-1 综合", categories[0].channels[0].name)
        assertEquals("CCTV-13 新闻", categories[0].channels[1].name)
    }

    // ---------- 缺省字段容错 ----------

    @Test
    fun `channels without group title fall into the default category`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,CCTV-1 综合
            https://example.com/cctv1.m3u8
        """.trimIndent()

        val categories = M3uPlaylistParser.parse(playlist)

        assertEquals(1, categories.size)
        assertEquals(M3uPlaylistParser.DEFAULT_CATEGORY_NAME, categories[0].name)
        assertEquals(1, categories[0].channels.size)
    }

    @Test
    fun `channels without tvg attributes use safe defaults`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",CCTV-1 综合
            https://example.com/cctv1.m3u8
        """.trimIndent()

        val channel = M3uPlaylistParser.parse(playlist)[0].channels[0]

        assertEquals("", channel.logo)
        assertEquals("", channel.epgId)
        // 没有 tvg-id 时用频道名做稳定 id
        assertEquals("CCTV-1 综合", channel.id)
    }

    @Test
    fun `tolerates extra spaces between attributes and single quotes`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1   tvg-id='cctv1'    group-title='央视'  ,CCTV-1 综合
            https://example.com/cctv1.m3u8
        """.trimIndent()

        val categories = M3uPlaylistParser.parse(playlist)

        assertEquals(1, categories.size)
        assertEquals("央视", categories[0].name)
        val channel = categories[0].channels[0]
        assertEquals("cctv1", channel.id)
        assertEquals("cctv1", channel.epgId)
    }

    @Test
    fun `supports attributes in any order`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="央视" tvg-logo="https://logo.com/a.png" tvg-id="x1",频道 A
            https://example.com/a.m3u8
        """.trimIndent()

        val channel = M3uPlaylistParser.parse(playlist)[0].channels[0]

        assertEquals("x1", channel.id)
        assertEquals("https://logo.com/a.png", channel.logo)
        assertEquals("央视", M3uPlaylistParser.parse(playlist)[0].name)
    }

    // ---------- 非法格式与乱序容错 ----------

    @Test
    fun `skips extinf entries whose url is missing or invalid`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",没有地址的频道
            #EXTINF:-1 group-title="央视",非法地址
            file:///etc/passwd
            #EXTINF:-1 group-title="央视",合法频道
            https://example.com/ok.m3u8
        """.trimIndent()

        val categories = M3uPlaylistParser.parse(playlist)

        assertEquals(1, categories.size)
        assertEquals(1, categories[0].channels.size)
        assertEquals("合法频道", categories[0].channels[0].name)
    }

    @Test
    fun `keeps the extinf entry when a comment line sits between it and its url`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",被注释隔开的频道
            #EXTVLCOPT:http-user-agent=Mozilla
            https://example.com/ignored.m3u8
            #EXTINF:-1 group-title="央视",正常频道
            https://example.com/ok.m3u8
        """.trimIndent()

        val categories = M3uPlaylistParser.parse(playlist)

        // EXTINF 与 URL 之间允许夹杂注释行（真实 M3U 常见），因此两条都应被解析出来
        assertEquals(1, categories.size)
        assertEquals(2, categories[0].channels.size)
        assertEquals("被注释隔开的频道", categories[0].channels[0].name)
        assertEquals("https://example.com/ignored.m3u8", categories[0].channels[0].streamUrl)
        assertEquals("正常频道", categories[0].channels[1].name)
    }

    @Test
    fun `drops the previous extinf entry when a new extinf line appears before any url`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",没有地址的频道
            #EXTINF:-1 group-title="央视",正常频道
            https://example.com/ok.m3u8
        """.trimIndent()

        val categories = M3uPlaylistParser.parse(playlist)

        assertEquals(1, categories.size)
        assertEquals(1, categories[0].channels.size)
        assertEquals("正常频道", categories[0].channels[0].name)
    }

    @Test
    fun `ignores non extinf comment lines and blank lines`() {
        val playlist = """
            #EXTM3U

            #EXTVLCOPT:http-user-agent=Mozilla
            #PLAYLIST:https://example.com/playlist.m3u
            #EXTINF:-1 group-title="央视",真实频道
            https://example.com/ok.m3u8

        """.trimIndent()

        val categories = M3uPlaylistParser.parse(playlist)

        assertEquals(1, categories.size)
        assertEquals(1, categories[0].channels.size)
        assertEquals("真实频道", categories[0].channels[0].name)
    }

    @Test
    fun `ignores urls that are not preceded by an extinf line`() {
        val playlist = """
            #EXTM3U
            https://example.com/orphan.m3u8
            #EXTINF:-1 group-title="央视",真实频道
            https://example.com/ok.m3u8
        """.trimIndent()

        val categories = M3uPlaylistParser.parse(playlist)

        assertEquals(1, categories.size)
        assertEquals(1, categories[0].channels.size)
        assertEquals("真实频道", categories[0].channels[0].name)
    }

    @Test
    fun `returns empty list for blank or malformed playlists without throwing`() {
        assertEquals(emptyList<Any>(), M3uPlaylistParser.parse(""))
        assertEquals(emptyList<Any>(), M3uPlaylistParser.parse("   \n  \n"))
        assertEquals(emptyList<Any>(), M3uPlaylistParser.parse("not a playlist at all"))
        assertEquals(emptyList<Any>(), M3uPlaylistParser.parse("#EXTM3U\n#EXTINF:-1 group-title=\"x\",没有地址"))
    }

    @Test
    fun `drops channels whose display name is empty`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",
            https://example.com/noname.m3u8
            #EXTINF:-1 group-title="央视",有名字
            https://example.com/ok.m3u8
        """.trimIndent()

        val categories = M3uPlaylistParser.parse(playlist)

        assertEquals(1, categories.size)
        assertEquals(1, categories[0].channels.size)
        assertEquals("有名字", categories[0].channels[0].name)
    }

    @Test
    fun `handles display names that contain commas`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",CCTV-1 综合,高清
            https://example.com/cctv1.m3u8
        """.trimIndent()

        val channel = M3uPlaylistParser.parse(playlist)[0].channels[0]

        assertEquals("CCTV-1 综合,高清", channel.name)
    }

    @Test
    fun `handles unbalanced quotes without crashing`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="unclosed group-title="央视",引号不闭合
            https://example.com/ok.m3u8
        """.trimIndent()

        val categories = M3uPlaylistParser.parse(playlist)

        // 不崩溃即可：具体取值取决于容错策略，但必须产出一个合法频道
        assertEquals(1, categories.size)
        assertEquals(1, categories[0].channels.size)
        assertEquals("https://example.com/ok.m3u8", categories[0].channels[0].streamUrl)
    }
}
