package com.livetv.android.data.mgtv

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.stream.StreamResolveError
import com.livetv.android.domain.stream.StreamResolveResult
import com.livetv.android.domain.stream.StreamTypeDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MgtvStreamResolver] 的纯 JVM 测试（基于**脱敏** fixture）。
 */
class MgtvStreamResolverTest {

    private val channel = Channel(
        id = "mgtv:287",
        name = "金鹰卡通",
        description = "",
        streamUrl = "",
    )

    private fun resolverWith(
        result: MgtvSourceFetchResult,
        nowMs: Long = 1_000_000L,
        allowedHosts: Set<String> = MgtvStreamResolver.DEFAULT_ALLOWED_HEADER_HOSTS,
    ) = MgtvStreamResolver(
        sourceFetcher = { _, _, _ -> result },
        clock = { nowMs },
        allowedHeaderHosts = allowedHosts,
    )

    @Test
    fun `resolves an eligible source into a scoped hls stream`() {
        val result = resolverWith(MgtvSourceFetchResult.RawJson(MgtvSanitizedFixtures.SOURCE_OK))
            .resolve(channel, null)

        assertTrue("expected success but was $result", result is StreamResolveResult.Success)
        val stream = (result as StreamResolveResult.Success).stream
        assertEquals("https://cdn.example.invalid/nn_live/nn_x64/SANITIZED/index.m3u8?es=1700000000", stream.url)
        assertEquals(StreamTypeDetector.MIME_HLS, stream.mimeType)
        assertEquals("360P", stream.definitionLabel)
        assertEquals("https://www.mgtv.com/", stream.headers["Referer"])
        // 按 HAR 实测与 RFC 6454 口径修订：Origin 不含尾斜杠
        assertEquals("https://www.mgtv.com", stream.headers["Origin"])
        assertEquals(setOf("mgtv.com"), stream.allowedHeaderHosts)
    }

    @Test
    fun `computes expiry from url duration and injected clock`() {
        val now = 5_000_000L
        val result = resolverWith(MgtvSourceFetchResult.RawJson(MgtvSanitizedFixtures.SOURCE_OK), now)
            .resolve(channel, null)

        val stream = (result as StreamResolveResult.Success).stream
        assertEquals(now + 28800L * 1000L, stream.expiresAtMs)
    }

    @Test
    fun `maps missing credentials to auth unavailable`() {
        val result = resolverWith(MgtvSourceFetchResult.AuthUnavailable("no compliant provider")).resolve(channel, null)

        assertEquals(StreamResolveError.AUTH_UNAVAILABLE, (result as StreamResolveResult.Failure).error)
    }

    @Test
    fun `maps http status codes to distinct errors`() {
        val forbidden = resolverWith(MgtvSourceFetchResult.HttpError(403)).resolve(channel, null)
        assertEquals(StreamResolveError.HTTP_FORBIDDEN, (forbidden as StreamResolveResult.Failure).error)

        val notFound = resolverWith(MgtvSourceFetchResult.HttpError(404)).resolve(channel, null)
        assertEquals(StreamResolveError.HTTP_NOT_FOUND, (notFound as StreamResolveResult.Failure).error)

        val limited = resolverWith(MgtvSourceFetchResult.HttpError(429)).resolve(channel, null)
        assertEquals(StreamResolveError.HTTP_RATE_LIMITED, (limited as StreamResolveResult.Failure).error)

        val other = resolverWith(MgtvSourceFetchResult.HttpError(500)).resolve(channel, null)
        assertEquals(StreamResolveError.NETWORK_ERROR, (other as StreamResolveResult.Failure).error)
    }

    @Test
    fun `maps network error and parse error`() {
        val network = resolverWith(MgtvSourceFetchResult.NetworkError("timeout")).resolve(channel, null)
        assertEquals(StreamResolveError.NETWORK_ERROR, (network as StreamResolveResult.Failure).error)

        val parse = resolverWith(MgtvSourceFetchResult.RawJson("<not json>")).resolve(channel, null)
        assertEquals(StreamResolveError.PARSE_ERROR, (parse as StreamResolveResult.Failure).error)
    }

    @Test
    fun `maps business error code to forbidden`() {
        val result = resolverWith(MgtvSourceFetchResult.RawJson(MgtvSanitizedFixtures.SOURCE_SIGN_ERROR))
            .resolve(channel, null)

        assertEquals(StreamResolveError.HTTP_FORBIDDEN, (result as StreamResolveResult.Failure).error)
    }

    @Test
    fun `fails when no eligible source exists`() {
        val result = resolverWith(MgtvSourceFetchResult.RawJson(MgtvSanitizedFixtures.SOURCE_EMPTY))
            .resolve(channel, null)

        assertEquals(StreamResolveError.UNSUPPORTED_SOURCE, (result as StreamResolveResult.Failure).error)
    }

    @Test
    fun `keeps header host allowlist configurable`() {
        val result = resolverWith(
            result = MgtvSourceFetchResult.RawJson(MgtvSanitizedFixtures.SOURCE_OK),
            allowedHosts = setOf("mgtv.com", "example.invalid"),
        ).resolve(channel, null)

        assertEquals(
            setOf("mgtv.com", "example.invalid"),
            (result as StreamResolveResult.Success).stream.allowedHeaderHosts,
        )
    }

    @Test
    fun `extracts camera id from prefixed and plain ids`() {
        assertEquals("287", MgtvStreamResolver.extractCameraId(channel))
        assertEquals("287", MgtvStreamResolver.extractCameraId(Channel("287", "n", "")))
        assertEquals("", MgtvStreamResolver.extractCameraId(Channel("mgtv:", "n", "")))
    }

    @Test
    fun `fails when camera id is missing`() {
        val result = resolverWith(MgtvSourceFetchResult.RawJson(MgtvSanitizedFixtures.SOURCE_OK))
            .resolve(Channel("mgtv:", "空 ID", ""), null)

        val failure = result as StreamResolveResult.Failure
        assertEquals(StreamResolveError.UNSUPPORTED_SOURCE, failure.error)
    }

    @Test
    fun `does not send stale npuk on fresh resolve and rotates past same failed host`() {
        val sentNpuks = mutableListOf<String?>()
        var call = 0
        val fetcher = MgtvSourceFetcher { _, _, npuk ->
            sentNpuks += npuk
            call++
            val (host, nextNpuk) = when (call) {
                1 -> "padal.qing.mgtv.com" to "npuk-1"
                2 -> "padal.qing.mgtv.com" to "npuk-2"
                3 -> "padal.qing.mgtv.com" to "npuk-3"
                else -> "padqq.qing.mgtv.com" to "npuk-4"
            }
            MgtvSourceFetchResult.RawJson(
                """{"code":"0","data":{"sources":[{"url":"https://$host/live.m3u8","format":".m3u8","hdcp":0,"isp2p":0,"name":"高清","definition":"2","npuk":"$nextNpuk"}]}}""",
            )
        }
        val resolver = MgtvStreamResolver(sourceFetcher = fetcher)

        val first = resolver.resolve(channel, null) as StreamResolveResult.Success
        assertTrue(first.stream.url.contains("padal.qing.mgtv.com"))

        // Fresh resolve must not send npuk-1
        resolver.resolve(channel, null)
        assertEquals(listOf(null, null), sentNpuks)

        // 403 rotation sends npuk-2, sees padal again, chases once with npuk-3 and lands on padqq
        sentNpuks.clear()
        val rotated = resolver.resolveWithOptions(channel, null, rotateNode = true) as StreamResolveResult.Success
        assertEquals(listOf("npuk-2", "npuk-3"), sentNpuks)
        assertTrue(rotated.stream.url.contains("padqq.qing.mgtv.com"))
    }
}
