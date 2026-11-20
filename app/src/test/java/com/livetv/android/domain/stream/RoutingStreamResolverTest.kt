package com.livetv.android.domain.stream

import com.livetv.android.domain.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoutingStreamResolver] 的路由测试：`mgtv:` 前缀走 MGTV，其余走静态解析器。
 */
class RoutingStreamResolverTest {

    private val mgtvResolverCalls = mutableListOf<Channel>()
    private val fallbackResolverCalls = mutableListOf<Channel>()

    private val mgtvResolver = object : StreamResolver {
        override fun resolve(channel: Channel, preferredDefinition: String?): StreamResolveResult {
            mgtvResolverCalls += channel
            return StreamResolveResult.Success(
                ResolvedStream(
                    url = "https://cdn.example.invalid/mgtv/index.m3u8",
                    headers = mapOf("Referer" to "https://www.mgtv.com/"),
                    expiresAtMs = 1_000L,
                    mimeType = StreamTypeDetector.MIME_HLS,
                    allowedHeaderHosts = setOf("mgtv.com"),
                ),
            )
        }
    }

    private val fallbackResolver = object : StreamResolver {
        override fun resolve(channel: Channel, preferredDefinition: String?): StreamResolveResult {
            fallbackResolverCalls += channel
            return StaticStreamResolver.resolve(channel, preferredDefinition)
        }
    }

    private val router = RoutingStreamResolver(mgtvResolver, fallbackResolver)

    @Test
    fun `routes mgtv prefixed channel to mgtv resolver`() {
        val channel = Channel("mgtv:287", "金鹰卡通", "", "")

        val result = router.resolve(channel, null)

        assertEquals(1, mgtvResolverCalls.size)
        assertTrue(fallbackResolverCalls.isEmpty())
        assertEquals(setOf("mgtv.com"), (result as StreamResolveResult.Success).stream.allowedHeaderHosts)
    }

    @Test
    fun `routes ordinary channel to static resolver`() {
        val channel = Channel("news", "新闻", "", "https://iptv.example.invalid/live/news.m3u8")

        val result = router.resolve(channel, null)

        assertTrue(mgtvResolverCalls.isEmpty())
        assertEquals(1, fallbackResolverCalls.size)
        assertEquals("https://iptv.example.invalid/live/news.m3u8", (result as StreamResolveResult.Success).stream.url)
    }

    @Test
    fun `identifies mgtv channels by prefix`() {
        assertTrue(router.isMgtvChannel(Channel("mgtv:287", "x", "")))
        assertFalse(router.isMgtvChannel(Channel("287", "x", "")))
        assertFalse(router.isMgtvChannel(Channel("mgtv2:287", "x", "")))
    }
}
