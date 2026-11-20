package com.livetv.android.data.mgtv

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * 最小 HTTP/1.1 测试服务（`java.net.ServerSocket` 手写实现）。
 *
 * 说明：AGP 的单元测试编译类路径用 `android.jar` 取代了 JDK bootclasspath，
 * 因此 `com.sun.net.httpserver` 不可用，改用 `java.net.ServerSocket` 自实现响应。
 *
 * 只监听 `127.0.0.1` 的随机端口，100% 离线可复现，不访问任何外网地址。
 */
internal class TinyHttpServer(
    private val responder: () -> Pair<Int, String>,
) : AutoCloseable {

    /** 收到请求时回调（用于统计请求次数、断言"未发出请求"）。 */
    var onRequest: (() -> Unit)? = null

    /** 最近一次收到的请求行（含路径与查询串）。 */
    @Volatile
    var lastRequestLine: String? = null
        private set

    /** 最近一次收到的请求头（小写键）。 */
    @Volatile
    var lastHeaders: Map<String, String> = emptyMap()
        private set

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
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                }
                lastRequestLine = requestLine
                lastHeaders = headers
                onRequest?.invoke()
                val (code, body) = responder()
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                val head = buildString {
                    append("HTTP/1.1 ").append(code).append(' ').append(reasonOf(code)).append("\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
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
        204 -> "No Content"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> "Status"
    }
}
