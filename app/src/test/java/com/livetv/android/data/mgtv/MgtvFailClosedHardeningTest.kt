package com.livetv.android.data.mgtv

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.stream.StreamResolveError
import com.livetv.android.domain.stream.StreamResolveResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * M4 / M5 回归与 fail-closed 边界测试（全脱敏、100% 离线）。
 */
class MgtvFailClosedHardeningTest {

    private val closeables = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        closeables.clear()
    }

    private val channel = Channel(
        id = "mgtv:287",
        name = "金鹰卡通",
        description = "",
        streamUrl = "",
    )

    private fun resolverOf(rawJson: String, nowMs: Long = 10_000L): MgtvStreamResolver =
        MgtvStreamResolver(
            sourceFetcher = { _, _, _ -> MgtvSourceFetchResult.RawJson(rawJson) },
            clock = { nowMs },
        )

    @Test
    fun `missing isp2p and hdcp flags fail closed instead of defaulting to allowed`() {
        val raw = """
            {"code":"0","msg":"","data":{
              "cameraId":"287","cameraName":"金鹰卡通","servertime":1700000000,
              "sources":[
                {"url":"https://cdn.example.invalid/missing-flags/index.m3u8","format":".m3u8","definition":"2","name":"480P","urlExpireDuration":28800}
              ]
            }}
        """.trimIndent()

        val result = resolverOf(raw).resolve(channel)

        assertEquals(StreamResolveError.UNSUPPORTED_SOURCE, (result as StreamResolveResult.Failure).error)
    }

    @Test
    fun `non-numeric isp2p or hdcp strings fail closed`() {
        val raw = """
            {"code":"0","msg":"","data":{
              "cameraId":"287","cameraName":"金鹰卡通","isp2p":"true","hdcp":"hdcp2.2","servertime":1700000000,
              "sources":[
                {"url":"https://cdn.example.invalid/enum/index.m3u8","format":".m3u8","definition":"2","name":"480P","urlExpireDuration":28800}
              ]
            }}
        """.trimIndent()

        val result = resolverOf(raw).resolve(channel)

        assertEquals(StreamResolveError.UNSUPPORTED_SOURCE, (result as StreamResolveResult.Failure).error)
    }

    @Test
    fun `per-source non-numeric isp2p override no longer blocks a source whose hdcp is zero`() {
        val raw = """
            {"code":"0","msg":"","data":{
              "cameraId":"287","cameraName":"金鹰卡通","isp2p":0,"hdcp":0,"servertime":1700000000,
              "sources":[
                {"url":"https://cdn.example.invalid/item-enum/index.m3u8","format":".m3u8","definition":"2","name":"480P","urlExpireDuration":28800,"isp2p":"p2p"}
              ]
            }}
        """.trimIndent()

        val result = resolverOf(raw).resolve(channel)

        assertTrue("hdcp=0 且为 https .m3u8 时应解析成功，实际 $result", result is StreamResolveResult.Success)
    }

    @Test
    fun `drm flagged source fails closed`() {
        val raw = """
            {"code":"0","msg":"","data":{
              "cameraId":"287","cameraName":"金鹰卡通","isp2p":0,"hdcp":0,"servertime":1700000000,
              "sources":[
                {"url":"https://cdn.example.invalid/drm/index.m3u8","format":".m3u8","definition":"2","name":"480P","urlExpireDuration":28800,"drm":"widevine"}
              ]
            }}
        """.trimIndent()

        val result = resolverOf(raw).resolve(channel)

        assertEquals(StreamResolveError.UNSUPPORTED_SOURCE, (result as StreamResolveResult.Failure).error)
    }

    @Test
    fun `missing top-level code fails closed`() {
        val raw = """
            {"msg":"","data":{
              "cameraId":"287","cameraName":"金鹰卡通","isp2p":0,"hdcp":0,"servertime":1700000000,
              "sources":[
                {"url":"https://cdn.example.invalid/nocode/index.m3u8","format":".m3u8","definition":"2","name":"480P","urlExpireDuration":28800}
              ]
            }}
        """.trimIndent()

        val result = resolverOf(raw).resolve(channel)

        assertEquals(StreamResolveError.HTTP_FORBIDDEN, (result as StreamResolveResult.Failure).error)
    }

    @Test
    fun `origin header has no trailing slash matching HAR and RFC 6454`() {
        val result = resolverOf(MgtvSanitizedFixtures.SOURCE_OK).resolve(channel)
        val stream = (result as StreamResolveResult.Success).stream

        assertEquals("https://www.mgtv.com", stream.headers["Origin"])
        assertEquals("https://www.mgtv.com/", stream.headers["Referer"])
    }

    @Test
    fun `userinfo in https source url is rejected`() {
        val raw = """
            {"code":"0","msg":"","data":{
              "cameraId":"287","cameraName":"金鹰卡通","isp2p":0,"hdcp":0,"servertime":1700000000,
              "sources":[
                {"url":"https://attacker@cdn.example.invalid/ok/index.m3u8","format":".m3u8","definition":"2","name":"480P","urlExpireDuration":28800}
              ]
            }}
        """.trimIndent()

        val parsed = MgtvJsonParser.parseSourceResponse(raw)!!
        assertNull(MgtvSourceSelector.selectBestSource(parsed))
    }

    @Test
    fun `zero negative or huge urlExpireDuration never becomes immortal or negative`() {
        val now = 100_000L
        val zeroRaw = """
            {"code":"0","msg":"","data":{
              "cameraId":"287","cameraName":"金鹰卡通","isp2p":0,"hdcp":0,"servertime":1700000000,
              "sources":[
                {"url":"https://cdn.example.invalid/z/index.m3u8","format":".m3u8","definition":"2","name":"480P","urlExpireDuration":0}
              ]
            }}
        """.trimIndent()
        val zeroStream = (resolverOf(zeroRaw, now).resolve(channel) as StreamResolveResult.Success).stream
        assertNotNull(zeroStream.expiresAtMs)
        assertEquals(now + 60_000L, zeroStream.expiresAtMs)

        val hugeRaw = """
            {"code":"0","msg":"","data":{
              "cameraId":"287","cameraName":"金鹰卡通","isp2p":0,"hdcp":0,"servertime":1700000000,
              "sources":[
                {"url":"https://cdn.example.invalid/huge/index.m3u8","format":".m3u8","definition":"2","name":"480P","urlExpireDuration":9223372036854775}
              ]
            }}
        """.trimIndent()
        val hugeStream = (resolverOf(hugeRaw, now).resolve(channel) as StreamResolveResult.Success).stream
        assertEquals(now + 7L * 24L * 3600L * 1000L, hugeStream.expiresAtMs)
    }

    @Test
    fun `mgtv provider maps sanitized catalog to prefixed domain channels`() {
        val provider = MgtvProvider(catalogLoader = { MgtvSanitizedFixtures.CATEGORY_JSONP })

        val categories = provider.loadMgtvCategories()
        assertEquals(2, categories.size)
        assertEquals("金鹰卡通", provider.findChannelName("287"))
        assertEquals("脱敏频道三", provider.findChannelName("289"))
        assertNull(provider.findChannelName("999999"))

        val domainCategories = provider.toDomainCategories()
        assertEquals(2, domainCategories.size)
        assertEquals("mgtv:cat_sanitized_1", domainCategories[0].id)
        assertEquals("mgtv:287", domainCategories[0].channels[0].id)
        assertEquals("", domainCategories[0].channels[0].streamUrl)
        assertEquals("287", domainCategories[0].channels[0].epgId)
    }

    @Test
    fun `http client reports http 403 even when error body is empty`() {
        val server = TinyHttpServer { 403 to "" }.also { closeables += it }

        val result = MgtvHttpClient().get("${server.baseUrl}/empty-error")

        assertEquals(MgtvHttpClient.Result.HttpError(403, ""), result)
    }

    @Test
    fun `http client does not auto-follow 302 redirects to third-party endpoints`() {
        val targetHits = AtomicInteger(0)
        val target = TinyHttpServer { 200 to """{"leaked":true}""" }.also {
            it.onRequest = { targetHits.incrementAndGet() }
            closeables += it
        }
        val redirectSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also { closeables += it }
        Thread {
            try {
                redirectSocket.accept().use { s ->
                    val reader = s.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val resp = "HTTP/1.1 302 Found\r\n" +
                        "Location: ${target.baseUrl}/stolen\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n"
                    s.getOutputStream().write(resp.toByteArray(StandardCharsets.ISO_8859_1))
                    s.getOutputStream().flush()
                }
            } catch (_: IOException) {
            }
        }.apply { isDaemon = true }.start()

        val result = MgtvHttpClient().get(
            "http://127.0.0.1:${redirectSocket.localPort}/v1/live/source",
            mapOf("Referer" to "https://www.mgtv.com/"),
        )

        assertTrue("expected HttpError(302) but was $result", result is MgtvHttpClient.Result.HttpError)
        assertEquals(302, (result as MgtvHttpClient.Result.HttpError).statusCode)
        assertEquals(0, targetHits.get())
    }
}
