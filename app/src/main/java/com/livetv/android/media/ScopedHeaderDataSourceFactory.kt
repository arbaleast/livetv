package com.livetv.android.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.livetv.android.domain.stream.HeaderHostPolicy
import java.io.IOException
import java.net.URI

/**
 * 带 Host 白名单隔离的 `DataSource.Factory`。
 *
 * 用途：为 MGTV 这类**动态会话地址**附加 `Origin` / `Referer` 等专用请求头，
 * 但只允许这些头出现在白名单域名（如 `*.mgtv.com`）上。
 *
 * 两层保护：
 * 1. 每次 `open()` 按目标 URL 重新过滤请求头；
 * 2. **自己跟随 3xx 跳转**，每一跳都重新过滤——这样跨域/跨协议跳转既不会被
 *    `DefaultHttpDataSource` 当成"禁止的跨协议跳转"报错，也不会把源专用头带过去。
 *
 * 为什么必须自己跟：MGTV 的 CDN 会按调度把请求 302 到别的边缘节点，
 * 而 Media3 在 `setAllowCrossProtocolRedirects(false)` 时遇到跨协议 302 会直接抛
 * `InvalidResponseCodeException(302)`；真机上表现为「服务器返回错误（HTTP 302）」。
 */
@UnstableApi
class ScopedHeaderDataSourceFactory(
    private val delegate: HttpDataSource.Factory,
    private val headers: Map<String, String>,
    private val allowedHostSuffixes: Set<String>,
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        ScopedHeaderDataSource(delegate.createDataSource(), headers, allowedHostSuffixes)
}

@UnstableApi
private class ScopedHeaderDataSource(
    private val delegate: HttpDataSource,
    private val headers: Map<String, String>,
    private val allowedHostSuffixes: Set<String>,
) : DataSource by delegate {

    override fun open(dataSpec: DataSpec): Long {
        var spec = dataSpec
        var hops = 0
        while (true) {
            val scoped = HeaderHostPolicy.filterHeadersForUrl(
                url = spec.uri.toString(),
                headers = headers,
                allowedHostSuffixes = allowedHostSuffixes,
            )
            val requestSpec = if (scoped.isEmpty()) spec else spec.withAdditionalHeaders(scoped)
            try {
                return delegate.open(requestSpec)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                val next = nextRedirectUrl(spec, e)
                if (next == null || hops >= MAX_REDIRECTS) throw e
                // 用"干净"的 DataSpec 重新开始：旧请求头不跟随跳转，逐跳重新过滤
                spec = spec.buildUpon()
                    .setUri(next)
                    .setHttpRequestHeaders(emptyMap())
                    .build()
                hops++
            }
        }
    }

    /**
     * 从 3xx 响应里解析下一跳地址。
     *
     * 只接受 **https** 目标：明文跳转在 Release 包里本来就会被系统拦截，
     * 提前拒绝可以避免把源专用头带到明文链路上。
     */
    private fun nextRedirectUrl(spec: DataSpec, error: HttpDataSource.InvalidResponseCodeException): Uri? {
        if (error.responseCode !in 300..399) return null
        val location = error.headerFields?.entries
            ?.firstOrNull { it.key.equals("Location", ignoreCase = true) }
            ?.value?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (location.isEmpty()) return null
        val resolved = try {
            URI(spec.uri.toString()).resolve(location).toString()
        } catch (e: Exception) {
            return null
        }
        if (!resolved.startsWith("https://", ignoreCase = true)) return null
        return Uri.parse(resolved)
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
