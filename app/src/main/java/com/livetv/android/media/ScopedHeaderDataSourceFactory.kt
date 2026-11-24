package com.livetv.android.media

import android.net.Uri
import android.util.Log
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
                val location = locationOf(e)
                val next = nextRedirectUrl(spec, e)
                if (next == null) {
                    Log.w(
                        TAG,
                        "HTTP ${e.responseCode} 不可跟随 hop=$hops url=${describeUri(spec.uri)} " +
                            "location=${location ?: "<none>"}",
                    )
                    throw e
                }
                if (hops >= MAX_REDIRECTS) {
                    Log.w(TAG, "HTTP ${e.responseCode} 跳转超限($MAX_REDIRECTS) url=${describeUri(spec.uri)}")
                    throw e
                }
                Log.i(
                    TAG,
                    "HTTP ${e.responseCode} 跟随跳转 hop=${hops + 1} " +
                        "${describeUri(spec.uri)} -> ${describeUri(next)}",
                )
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
     * 芒果 CDN 的边缘节点会把 https 播放请求 302 到 **明文**
     * `http://hlsal-*.qing.mgtv.com/...`（同一组签名路径）。实测把 scheme 换成 https 再跟会得到
     * 403，说明该回源 Host 只认 http；因此这里**原样跟随**（含明文跳转），由 `network_security_config.xml`
     * 仅对 `*.qing.mgtv.com` 放行明文（其余域名仍默认禁止明文 HTTP）。
     *
     * 仍然强制 **Host 白名单**：跳转目标必须是 `allowedHostSuffixes` 内的域名，
     * 避免把源专用请求头带到第三方。
     */
    private fun nextRedirectUrl(spec: DataSpec, error: HttpDataSource.InvalidResponseCodeException): Uri? {
        if (error.responseCode !in 300..399) return null
        val location = locationOf(error).orEmpty()
        if (location.isEmpty()) return null
        val target = try {
            URI(spec.uri.toString()).resolve(location).toString()
        } catch (e: Exception) {
            return null
        }
        if (!target.startsWith("https://", ignoreCase = true) &&
            !target.startsWith("http://", ignoreCase = true)
        ) {
            return null
        }
        if (!HeaderHostPolicy.isHostAllowed(target, allowedHostSuffixes, requireHttps = false)) {
            return null
        }
        return Uri.parse(target)
    }

    /** 302 的 Location 头（大小写不敏感）。 */
    private fun locationOf(error: HttpDataSource.InvalidResponseCodeException): String? =
        error.headerFields.entries
            .firstOrNull { it.key.equals("Location", ignoreCase = true) }
            ?.value?.firstOrNull()
            ?.trim()

    /** 日志里只打 scheme+host+path，丢掉查询串（可能含签名）。 */
    private fun describeUri(uri: Uri): String =
        "${uri.scheme}://${uri.host}${uri.path.orEmpty()}"

    private companion object {
        const val MAX_REDIRECTS = 5

        /** logcat tag：重定向相关诊断。 */
        const val TAG = "LiveTvHttp"
    }
}
