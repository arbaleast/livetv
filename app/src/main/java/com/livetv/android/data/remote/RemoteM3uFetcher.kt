package com.livetv.android.data.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 远程播放列表拉取结果。
 *
 * 全部为纯 Kotlin 数据类型，不依赖 Android / Media3，便于纯 JVM 单测断言。
 */
sealed interface FetchResult {

    /** 拉取成功，响应体已按 UTF-8 解码。 */
    data class Success(val content: String) : FetchResult

    /** 服务端返回了非 200 状态码。 */
    data class HttpError(val code: Int) : FetchResult

    /** 连接失败、超时、DNS 失败等网络层异常。 */
    data class NetworkError(val reason: String) : FetchResult

    /** URL 非法（空串、协议不支持、缺少主机名等）；此类请求不会真正发出。 */
    data class InvalidUrl(val reason: String) : FetchResult

    /** 响应体超过大小上限。 */
    data object SizeExceeded : FetchResult
}

/** 远程播放列表拉取器抽象，便于用假实现做确定性测试。 */
interface RemotePlaylistFetcher {
    fun fetch(url: String): FetchResult
}

/**
 * 基于 JDK [HttpURLConnection] 的 M3U 拉取实现。
 *
 * 约束：
 * - 只允许 `http` / `https`，其余协议（`file://`、`ftp://` 等）直接判定为 [FetchResult.InvalidUrl] 且不发起请求；
 * - 连接超时 [CONNECT_TIMEOUT_MS]、读取超时 [READ_TIMEOUT_MS]；
 * - 响应体硬上限 [MAX_RESPONSE_BYTES]，超限返回 [FetchResult.SizeExceeded]；
 * - 只接受 HTTP 200，其余状态码返回 [FetchResult.HttpError]；
 * - 响应体按 UTF-8 解码。
 *
 * 不引入任何第三方网络库。
 */
class RemoteM3uFetcher(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
) : RemotePlaylistFetcher {

    override fun fetch(url: String): FetchResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return FetchResult.InvalidUrl("url is blank")
        }

        val target = try {
            URI(trimmed)
        } catch (e: Exception) {
            return FetchResult.InvalidUrl("malformed url")
        }

        val scheme = target.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return FetchResult.InvalidUrl("unsupported scheme: ${target.scheme ?: "none"}")
        }
        if (target.host.isNullOrBlank()) {
            return FetchResult.InvalidUrl("missing host")
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(trimmed).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Accept", "*/*")
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                FetchResult.HttpError(code)
            } else {
                val bytes = connection.inputStream.use { readLimited(it, maxResponseBytes) }
                if (bytes == null) {
                    FetchResult.SizeExceeded
                } else {
                    FetchResult.Success(String(bytes, StandardCharsets.UTF_8))
                }
            }
        } catch (e: IOException) {
            FetchResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            FetchResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 读取响应体，最多读取 [limit] 字节。
     *
     * @return 正常读完返回字节数组；超过上限返回 `null`（调用方据此返回 [FetchResult.SizeExceeded]）。
     */
    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val sink = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > limit) return null
            sink.write(chunk, 0, read)
        }
        return sink.toByteArray()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
