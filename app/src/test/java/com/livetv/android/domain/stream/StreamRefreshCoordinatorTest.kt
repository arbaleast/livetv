package com.livetv.android.domain.stream

import com.livetv.android.domain.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamRefreshCoordinator] 的状态机测试：
 * - 到期前主动刷新（注入 [Clock] 模拟）；
 * - HTTP 403 只允许 1 次重解析，重解析失败或再次 403 立即快速失败（防死循环）；
 * - HTTP 429 立即快速失败；
 * - 其它状态码交回播放器既有重试策略。
 */
class StreamRefreshCoordinatorTest {

    private val channel = Channel("mgtv:287", "金鹰卡通", "", "")
    private var now = 1_000_000L
    private var resolveCount = 0
    private var nextResult: StreamResolveResult = success("https://cdn.example.invalid/a.m3u8", expiresAtMs = 2_000_000L)

    private val resolver = object : StreamResolver {
        override fun resolve(channel: Channel, preferredDefinition: String?): StreamResolveResult {
            resolveCount++
            return nextResult
        }
    }

    private fun success(url: String, expiresAtMs: Long?): StreamResolveResult.Success =
        StreamResolveResult.Success(
            ResolvedStream(
                url = url,
                headers = mapOf("Referer" to "https://www.mgtv.com/"),
                expiresAtMs = expiresAtMs,
                mimeType = StreamTypeDetector.MIME_HLS,
                allowedHeaderHosts = setOf("mgtv.com"),
            ),
        )

    private fun coordinator(refreshLeadMs: Long = ResolvedStream.DEFAULT_REFRESH_LEAD_MS) =
        StreamRefreshCoordinator(resolver, clock = { now }, refreshLeadMs = refreshLeadMs)

    @Test
    fun `resolves and caches stream on first prepare`() {
        val coordinator = coordinator()

        val result = coordinator.prepareForPlayback(channel)

        assertTrue(result is StreamResolveResult.Success)
        assertEquals(1, resolveCount)
    }

    @Test
    fun `reuses cached stream while it is not near expiry`() {
        val coordinator = coordinator()

        coordinator.prepareForPlayback(channel)
        coordinator.prepareForPlayback(channel)

        assertEquals("缓存未临期时不应重复解析", 1, resolveCount)
    }

    @Test
    fun `refreshes proactively when cached stream is near expiry`() {
        val coordinator = coordinator(refreshLeadMs = 60_000L)
        coordinator.prepareForPlayback(channel)
        assertEquals(1, resolveCount)

        // 距过期还有 500s，远超 60s lead → 复用缓存
        now = 1_500_000L
        coordinator.prepareForPlayback(channel)
        assertEquals(1, resolveCount)

        // 距过期只剩 50s（< 60s lead）→ 必须主动刷新
        now = 1_950_000L
        nextResult = success("https://cdn.example.invalid/fresh.m3u8", expiresAtMs = 2_100_000L)
        coordinator.prepareForPlayback(channel)

        assertEquals("临期时必须主动重新解析", 2, resolveCount)
    }

    @Test
    fun `first 403 triggers exactly one re-resolution and reconnect`() {
        val coordinator = coordinator()
        coordinator.prepareForPlayback(channel)
        nextResult = success("https://cdn.example.invalid/refreshed.m3u8", expiresAtMs = 3_000_000L)

        val action = coordinator.onPlaybackHttpError(channel, 403)

        assertTrue("应为 Reconnect，实际 $action", action is StreamRefreshCoordinator.RefreshAction.Reconnect)
        assertEquals(
            "https://cdn.example.invalid/refreshed.m3u8",
            (action as StreamRefreshCoordinator.RefreshAction.Reconnect).stream.url,
        )
        assertEquals(2, resolveCount)
    }

    @Test
    fun `second 403 fast fails without another resolution`() {
        val coordinator = coordinator()
        coordinator.prepareForPlayback(channel)
        coordinator.onPlaybackHttpError(channel, 403)
        val resolvesAfterFirst = resolveCount

        val action = coordinator.onPlaybackHttpError(channel, 403)

        assertTrue("应快速失败，实际 $action", action is StreamRefreshCoordinator.RefreshAction.FastFail)
        assertEquals(403, (action as StreamRefreshCoordinator.RefreshAction.FastFail).statusCode)
        assertEquals(StreamResolveError.HTTP_FORBIDDEN, action.error)
        assertEquals("第二次 403 不得再触发解析", resolvesAfterFirst, resolveCount)
    }

    @Test
    fun `403 fast fails when re-resolution fails`() {
        val coordinator = coordinator()
        coordinator.prepareForPlayback(channel)
        nextResult = StreamResolveResult.Failure(StreamResolveError.AUTH_UNAVAILABLE, "no credential")

        val action = coordinator.onPlaybackHttpError(channel, 403)

        assertTrue(action is StreamRefreshCoordinator.RefreshAction.FastFail)
        assertEquals(StreamResolveError.AUTH_UNAVAILABLE, (action as StreamRefreshCoordinator.RefreshAction.FastFail).error)
    }

    @Test
    fun `429 fast fails immediately without resolution`() {
        val coordinator = coordinator()
        coordinator.prepareForPlayback(channel)
        val resolvesBefore = resolveCount

        val action = coordinator.onPlaybackHttpError(channel, 429)

        assertTrue(action is StreamRefreshCoordinator.RefreshAction.FastFail)
        assertEquals(429, (action as StreamRefreshCoordinator.RefreshAction.FastFail).statusCode)
        assertEquals(StreamResolveError.HTTP_RATE_LIMITED, action.error)
        assertEquals("429 不得触发重新解析", resolvesBefore, resolveCount)
    }

    @Test
    fun `other statuses are delegated to retry policy`() {
        val coordinator = coordinator()
        coordinator.prepareForPlayback(channel)

        val action = coordinator.onPlaybackHttpError(channel, 500)

        assertTrue(action is StreamRefreshCoordinator.RefreshAction.DelegateToRetryPolicy)
        assertEquals(500, (action as StreamRefreshCoordinator.RefreshAction.DelegateToRetryPolicy).statusCode)
    }

    @Test
    fun `successful playback restores the single forbidden refresh budget`() {
        val coordinator = coordinator()
        coordinator.prepareForPlayback(channel)
        coordinator.onPlaybackHttpError(channel, 403)
        coordinator.onPlaybackHttpError(channel, 403)

        // 起播成功 → 预算重置
        coordinator.onPlaybackRecovered()
        nextResult = success("https://cdn.example.invalid/again.m3u8", expiresAtMs = 4_000_000L)

        val action = coordinator.onPlaybackHttpError(channel, 403)

        assertTrue("恢复后应允许再次刷新，实际 $action", action is StreamRefreshCoordinator.RefreshAction.Reconnect)
    }

    @Test
    fun `reset session clears cached stream and budget`() {
        val coordinator = coordinator()
        coordinator.prepareForPlayback(channel)
        coordinator.onPlaybackHttpError(channel, 403)

        coordinator.resetSession()
        nextResult = success("https://cdn.example.invalid/after-reset.m3u8", expiresAtMs = 5_000_000L)
        coordinator.prepareForPlayback(channel)

        assertEquals("重置会话后应重新解析", 3, resolveCount)
    }
}
