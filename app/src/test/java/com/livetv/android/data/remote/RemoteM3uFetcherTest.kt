package com.livetv.android.data.remote

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RemoteM3uFetcher] 的纯 JVM 测试。
 *
 * 本地起一个最小 HTTP 服务（`java.net.ServerSocket` 手写实现）监听 `127.0.0.1` 的随机端口，
 * 100% 离线可复现，不访问任何外网地址。
 *
 * 说明：AGP 的单元测试编译类路径用 `android.jar` 取代了 JDK bootclasspath，
 * 因此 `com.sun.net.httpserver` 不可用，改用 `java.net.ServerSocket` 自实现 HTTP/1.1 响应。
 */
class RemoteM3uFetcherTest {

    private val servers = mutableListOf<TinyHttpServer>()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        servers.clear()
    }

    @Test
    fun `fetches playlist content over http`() {
        val body = """
            #EXTM3U
            #EXTINF:-1 tvg-id="demo" group-title="安全实验",Demo Channel
            #EXTVLCOPT:http-referrer=https://lab.local/
            http://127.0.0.1:18080/l2/seg000.ts
        """.trimIndent()
        val server = startServer { 200 to body }

        val result = RemoteM3uFetcher().fetch("${server.baseUrl}/playlist.m3u")

        assertTrue("expected success but was $result", result is FetchResult.Success)
        val content = (result as FetchResult.Success).content
        assertEquals(body, content)
        assertTrue(content.contains("#EXTM3U"))
        assertTrue(content.contains("#EXTVLCOPT:http-referrer=https://lab.local/"))
    }

    @Test
    fun `decodes utf-8 content`() {
        val body = "#EXTM3U\n#EXTINF:-1 group-title=\"分类\",频道一\nhttp://example.com/a.ts\n"
        val server = startServer { 200 to body }

        val result = RemoteM3uFetcher().fetch("${server.baseUrl}/playlist.m3u")

        assertTrue(result is FetchResult.Success)
        val success = result as FetchResult.Success
        assertEquals(body, success.content)
        assertTrue(success.content.contains("频道一"))
    }

    @Test
    fun `http 403 is reported as http error`() {
        val server = startServer { 403 to "forbidden" }

        val result = RemoteM3uFetcher().fetch("${server.baseUrl}/playlist.m3u")

        assertEquals(FetchResult.HttpError(403), result)
    }

    @Test
    fun `http 404 is reported as http error`() {
        val server = startServer { 404 to "not found" }

        val result = RemoteM3uFetcher().fetch("${server.baseUrl}/playlist.m3u")

        assertEquals(FetchResult.HttpError(404), result)
    }

    @Test
    fun `http 500 is reported as http error`() {
        val server = startServer { 500 to "server error" }

        val result = RemoteM3uFetcher().fetch("${server.baseUrl}/playlist.m3u")

        assertEquals(FetchResult.HttpError(500), result)
    }

    @Test
    fun `blank url is invalid and sends no request`() {
        val requestCount = AtomicInteger(0)
        val server = startServer { 200 to "#EXTM3U" }
        server.onRequest = { requestCount.incrementAndGet() }

        val result = RemoteM3uFetcher().fetch("   ")

        assertTrue("expected invalid url but was $result", result is FetchResult.InvalidUrl)
        assertEquals(0, requestCount.get())
    }

    @Test
    fun `file scheme is invalid and sends no request`() {
        val requestCount = AtomicInteger(0)
        val server = startServer { 200 to "#EXTM3U" }
        server.onRequest = { requestCount.incrementAndGet() }

        val result = RemoteM3uFetcher().fetch("file:///etc/passwd")

        assertTrue("expected invalid url but was $result", result is FetchResult.InvalidUrl)
        assertEquals(0, requestCount.get())
    }

    @Test
    fun `ftp scheme is invalid and sends no request`() {
        val requestCount = AtomicInteger(0)
        val server = startServer { 200 to "#EXTM3U" }
        server.onRequest = { requestCount.incrementAndGet() }

        val result = RemoteM3uFetcher().fetch("ftp://example.com/list.m3u")

        assertTrue("expected invalid url but was $result", result is FetchResult.InvalidUrl)
        assertEquals(0, requestCount.get())
    }

    @Test
    fun `response larger than limit is rejected`() {
        val server = startServer { 200 to "x".repeat(4096) }

        val result = RemoteM3uFetcher(maxResponseBytes = 32).fetch("${server.baseUrl}/playlist.m3u")

        assertEquals(FetchResult.SizeExceeded, result)
    }

    @Test
    fun `connection failure is reported as network error`() {
        // 先绑定再关闭，得到一个几乎肯定无人监听的端口
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        socket.close()

        val result = RemoteM3uFetcher(connectTimeoutMs = 500, readTimeoutMs = 500)
            .fetch("http://127.0.0.1:$port/playlist.m3u")

        assertTrue("expected network error but was $result", result is FetchResult.NetworkError)
    }

    private fun startServer(handler: () -> Pair<Int, String>): TinyHttpServer {
        val server = TinyHttpServer { handler() }
        servers += server
        return server
    }

    /**
     * 最小 HTTP/1.1 服务：只支持「读请求 → 回一次响应 → 关闭连接」。
     */
    private class TinyHttpServer(
        private val responder: () -> Pair<Int, String>,
    ) : AutoCloseable {

        /** 收到请求时回调（用于统计请求次数）。 */
        var onRequest: (() -> Unit)? = null

        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

        val baseUrl: String get() = "http://127.0.0.1:${serverSocket.localPort}"

        private val acceptor = Thread {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    break
                }
                Thread { serve(socket) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }

        init {
            acceptor.start()
        }

        private fun serve(socket: Socket) {
            try {
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                    val requestLine = reader.readLine() ?: return
                    if (!requestLine.startsWith("GET ")) return
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    onRequest?.invoke()
                    val (code, body) = responder()
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val head = buildString {
                        append("HTTP/1.1 ").append(code).append(' ').append(reasonOf(code)).append("\r\n")
                        append("Content-Type: audio/x-mpegurl; charset=utf-8\r\n")
                        append("Content-Length: ").append(bytes.size).append("\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    val out = s.getOutputStream()
                    out.write(head.toByteArray(StandardCharsets.ISO_8859_1))
                    out.write(bytes)
                    out.flush()
                }
            } catch (e: IOException) {
                // 客户端提前断开，忽略
            }
        }

        override fun close() {
            try {
                serverSocket.close()
            } catch (e: IOException) {
                // ignore
            }
        }

        private fun reasonOf(code: Int): String = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Status"
        }
    }
}
