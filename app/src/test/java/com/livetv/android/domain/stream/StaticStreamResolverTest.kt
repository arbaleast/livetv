package com.livetv.android.domain.stream

import com.livetv.android.domain.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StaticStreamResolver] 的普通 IPTV 无回归测试。
 */
class StaticStreamResolverTest {

    @Test
    fun `keeps url headers and mime type of ordinary channel`() {
        val channel = Channel(
            id = "news",
            name = "新闻",
            description = "",
            streamUrl = "https://iptv.example.invalid/live/news.m3u8",
            headers = mapOf("Referer" to "https://iptv.example.invalid/"),
        )

        val result = StaticStreamResolver.resolve(channel, null)

        assertTrue(result is StreamResolveResult.Success)
        val stream = (result as StreamResolveResult.Success).stream
        assertEquals("https://iptv.example.invalid/live/news.m3u8", stream.url)
        assertEquals(channel.headers, stream.headers)
        assertEquals(StreamTypeDetector.MIME_HLS, stream.mimeType)
        assertNull("静态频道没有过期时间", stream.expiresAtMs)
        assertTrue("静态频道不启用 Host 限制", stream.allowedHeaderHosts.isEmpty())
    }

    @Test
    fun `keeps progressive stream mime unset`() {
        val channel = Channel("movie", "电影", "", "https://cdn.example.invalid/movie.mp4")

        val stream = (StaticStreamResolver.resolve(channel, null) as StreamResolveResult.Success).stream

        assertNull(stream.mimeType)
    }

    @Test
    fun `trims url`() {
        val channel = Channel("news", "新闻", "", "  https://iptv.example.invalid/live/news.m3u8  ")

        val stream = (StaticStreamResolver.resolve(channel, null) as StreamResolveResult.Success).stream

        assertEquals("https://iptv.example.invalid/live/news.m3u8", stream.url)
    }

    @Test
    fun `rejects empty url`() {
        val result = StaticStreamResolver.resolve(Channel("x", "x", "", ""), null)

        assertEquals(StreamResolveError.UNSUPPORTED_SOURCE, (result as StreamResolveResult.Failure).error)
    }

    @Test
    fun `rejects non http scheme`() {
        val result = StaticStreamResolver.resolve(Channel("x", "x", "", "file:///etc/passwd"), null)

        assertEquals(StreamResolveError.UNSUPPORTED_SOURCE, (result as StreamResolveResult.Failure).error)
    }
}
