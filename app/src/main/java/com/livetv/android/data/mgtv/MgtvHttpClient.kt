package com.livetv.android.data.mgtv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * MGTV 接口访问用的最小 HTTP 客户端（JDK `HttpURLConnection`，纯 JVM 可测）。
 *
 * 与 `RemoteM3uFetcher` 保持一致的失败语义，便于统一错误分类：
 * - 仅 `http` / `https`；
 * - 连接超时 5s、读取超时 8s；
 * - 响应体上限 2MB；
 * - UTF-8 解码。
 */
class MgtvHttpClient(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
) {

    sealed interface Result {
        data class Success(val statusCode: Int, val body: String) : Result
        data class HttpError(val statusCode: Int, val body: String = "") : Result
        data class NetworkError(val message: String) : Result
        data class InvalidUrl(val message: String) : Result
        data object TooLarge : Result
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Result {
        val parsed = runCatching { URL(url) }.getOrNull()
            ?: return Result.InvalidUrl("invalid url")
        val protocol = parsed.protocol?.lowercase()
        if (protocol != "http" && protocol != "https") {
            return Result.InvalidUrl("unsupported protocol: ${parsed.protocol}")
        }
        if (parsed.host.isNullOrBlank()) {
            return Result.InvalidUrl("missing host")
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                // 禁止自动跟随重定向，防止服务端 3xx 跳转将源专用请求头泄漏给非白名单第三方域
                instanceFollowRedirects = false
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = if (stream == null) "" else stream.use { readCapped(it) }
            if (body == null) {
                Result.TooLarge
            } else if (status in 200..299) {
                Result.Success(status, body)
            } else {
                Result.HttpError(status, body)
            }
        } catch (e: Exception) {
            Result.NetworkError(e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    /** 读取响应体；超过 [maxResponseBytes] 时返回 `null`（表示超限）。 */
    private fun readCapped(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read <= 0) break
            total += read
            if (total > maxResponseBytes) return null
            buffer.write(chunk, 0, read)
        }
        return String(buffer.toByteArray(), StandardCharsets.UTF_8)
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
