package com.livetv.android.data.mgtv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MgtvHttpClient] 的纯 JVM 测试（本地回环服务，100% 离线）。
 */
class MgtvHttpClientTest {

    private val servers = mutableListOf<TinyHttpServer>()

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

    @Test
    fun `returns body on http 200`() {
        val server = startServer { 200 to """{"code":"0"}""" }

        val result = MgtvHttpClient().get("${server.baseUrl}/v1/live/source")

        assertTrue("expected success but was $result", result is MgtvHttpClient.Result.Success)
        assertEquals(200, (result as MgtvHttpClient.Result.Success).statusCode)
        assertEquals("""{"code":"0"}""", result.body)
    }

    @Test
    fun `sends request headers`() {
        val server = startServer { 200 to "{}" }

        MgtvHttpClient().get("${server.baseUrl}/x", mapOf("Referer" to "https://www.mgtv.com/"))

        assertEquals("https://www.mgtv.com/", server.lastHeaders["referer"])
    }

    @Test
    fun `reports http error status`() {
        val server = startServer { 403 to "forbidden" }

        val result = MgtvHttpClient().get("${server.baseUrl}/x")

        assertTrue(result is MgtvHttpClient.Result.HttpError)
        assertEquals(403, (result as MgtvHttpClient.Result.HttpError).statusCode)
        assertEquals("forbidden", result.body)
    }

    @Test
    fun `reports 404 as http error`() {
        val server = startServer { 404 to "not found" }

        val result = MgtvHttpClient().get("${server.baseUrl}/x")

        assertEquals(404, (result as MgtvHttpClient.Result.HttpError).statusCode)
    }

    @Test
    fun `rejects unsupported scheme before connecting`() {
        val result = MgtvHttpClient().get("file:///etc/passwd")

        assertTrue(result is MgtvHttpClient.Result.InvalidUrl)
    }

    @Test
    fun `rejects blank url`() {
        val result = MgtvHttpClient().get("")

        assertTrue(result is MgtvHttpClient.Result.InvalidUrl)
    }

    @Test
    fun `reports oversized body`() {
        val server = startServer { 200 to "x".repeat(64) }
        val client = MgtvHttpClient(maxResponseBytes = 16)

        val result = client.get("${server.baseUrl}/x")

        assertEquals(MgtvHttpClient.Result.TooLarge, result)
    }

    @Test
    fun `reports network error when nothing listens`() {
        val server = TinyHttpServer { 200 to "{}" }
        val url = server.baseUrl
        server.close()

        val result = MgtvHttpClient(connectTimeoutMs = 500, readTimeoutMs = 500).get("$url/x")

        assertTrue("expected network error but was $result", result is MgtvHttpClient.Result.NetworkError)
    }
}
