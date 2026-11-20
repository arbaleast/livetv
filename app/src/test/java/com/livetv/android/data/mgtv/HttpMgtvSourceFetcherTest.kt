package com.livetv.android.data.mgtv

import java.net.URLEncoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HttpMgtvSourceFetcher] 的纯 JVM 测试（本地回环服务，100% 离线）。
 *
 * 通过注入 `urlBuilder` 把请求指向 `127.0.0.1` 的 `TinyHttpServer`，
 * 参数拼接、签名计算与请求头逻辑都走真实实现。
 */
class HttpMgtvSourceFetcherTest {

    private val servers = mutableListOf<TinyHttpServer>()

    private val sanitizedDeviceId = "00000000-0000-0000-0000-000000000001"

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        servers.clear()
    }

    private fun startServer(handler: () -> Pair<Int, String>): TinyHttpServer {
        val server = TinyHttpServer { handler() }
        servers += server
        return server
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** 指向本地回环服务的抓取器：真实签名 + 真实参数，只把 host 换成本地。 */
    private fun fetcherAgainst(
        server: TinyHttpServer,
        timestampMs: Long = 1700000000000L,
        signer: (Map<String, String>) -> String = MgtvSigner::sign,
    ) = HttpMgtvSourceFetcher(
        deviceIdProvider = { sanitizedDeviceId },
        timestampProvider = { timestampMs },
        signer = signer,
        urlBuilder = { params, sign ->
            val query = LinkedHashMap<String, String>(params).apply {
                put("_support", MgtvEndpoints.SUPPORT)
                put("sign", sign)
            }.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
            "${server.baseUrl}/v1/live/source?$query"
        },
    )

    @Test
    fun `returns raw json on http 200`() {
        val server = startServer { 200 to MgtvSanitizedFixtures.SOURCE_OK }

        val result = fetcherAgainst(server).fetchSourceJson("287", null)

        assertTrue("expected RawJson but was $result", result is MgtvSourceFetchResult.RawJson)
        assertTrue((result as MgtvSourceFetchResult.RawJson).body.contains("\"code\":\"0\""))
    }

    @Test
    fun `request carries computed sign and generated device id`() {
        val server = startServer { 200 to "{}" }

        fetcherAgainst(server).fetchSourceJson("287", null)

        val line = server.lastRequestLine.orEmpty()
        assertTrue("请求行应包含 cameraId: $line", line.contains("cameraId=287"))
        assertTrue("请求行应包含 32 位大写签名: $line", Regex("sign=[0-9A-F]{32}").containsMatchIn(line))
        assertTrue("请求行应包含 _support", line.contains("_support=10000000"))
        assertTrue(
            "did 与 deviceId 必须一致",
            line.contains("did=$sanitizedDeviceId") && line.contains("deviceId=$sanitizedDeviceId"),
        )
    }

    @Test
    fun `signature matches the signer over the same params`() {
        val server = startServer { 200 to "{}" }
        val expected = MgtvSigner.sign(
            MgtvEndpoints.sourceParams("287", sanitizedDeviceId, 1700000000000L),
        )

        fetcherAgainst(server).fetchSourceJson("287", null)

        assertTrue(server.lastRequestLine.orEmpty().contains("sign=$expected"))
    }

    @Test
    fun `signature changes with timestamp`() {
        val a = MgtvSigner.sign(MgtvEndpoints.sourceParams("287", sanitizedDeviceId, 1700000000000L))
        val b = MgtvSigner.sign(MgtvEndpoints.sourceParams("287", sanitizedDeviceId, 1700000000001L))

        assertTrue("不同 _t 必须产生不同签名", a != b)
    }

    @Test
    fun `sends referer header on the wire`() {
        val server = startServer { 200 to "{}" }

        fetcherAgainst(server).fetchSourceJson("287", null)

        assertEquals("https://www.mgtv.com/", server.lastHeaders["referer"])
    }

    @Test
    fun `builds referer origin and user agent headers`() {
        val headers = HttpMgtvSourceFetcher.sourceRequestHeaders()

        assertEquals("https://www.mgtv.com/", headers["Referer"])
        assertEquals("https://www.mgtv.com", headers["Origin"])
        assertTrue(headers["User-Agent"].orEmpty().isNotEmpty())
    }

    @Test
    fun `maps http 403 to http error`() {
        val server = startServer { 403 to """{"code":"2040117","msg":"签名不正确"}""" }

        val result = fetcherAgainst(server).fetchSourceJson("287", null)

        assertTrue(result is MgtvSourceFetchResult.HttpError)
        assertEquals(403, (result as MgtvSourceFetchResult.HttpError).code)
    }

    @Test
    fun `maps network failure to network error`() {
        val server = TinyHttpServer { 200 to "{}" }
        val deadBaseUrl = server.baseUrl
        server.close()

        val fetcher = HttpMgtvSourceFetcher(
            deviceIdProvider = { sanitizedDeviceId },
            client = MgtvHttpClient(connectTimeoutMs = 500, readTimeoutMs = 500),
            timestampProvider = { 1L },
            signer = MgtvSigner::sign,
            urlBuilder = { _, _ -> "$deadBaseUrl/v1/live/source" },
        )

        val result = fetcher.fetchSourceJson("287", null)

        assertTrue("expected NetworkError but was $result", result is MgtvSourceFetchResult.NetworkError)
    }
}
