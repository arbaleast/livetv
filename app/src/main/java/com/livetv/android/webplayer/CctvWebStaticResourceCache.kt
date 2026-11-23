package com.livetv.android.webplayer

import android.util.Log
import android.util.LruCache
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * CCTV 静态核心脚本/WASM 内存缓存准入策略（纯 Kotlin，可纯 JVM 单测）。
 *
 * 严格遵循零核心侵入与合规边界：
 * - 仅缓存官方静态播放器脚本（`.js`）、WebAssembly（`.wasm`）与样式表（`.css`）；
 * - 绝不缓存 HTML 主文档、鉴权/时间/配置接口（`.json` / `time.tv.cctv.com` / `vdnx.live.cntv.cn`）或媒体流切片。
 */
internal object CctvWebStaticCachePolicy {

    private val ALLOWED_HOSTS = setOf(
        "js.player.cntv.cn",
        "player.cntv.cn",
        "r.img.cctvpic.com",
    )

    private val CORE_SCRIPT_MARKERS = listOf(
        "liveplayer.js",
        "liveplayer_controls.js",
        "live.worker.js",
        "swfobject.js",
        "zepto.min.js",
        "jquery-1.7.2.min.js",
        "configtool",
    )

    fun isCacheableStaticAsset(method: String?, host: String?, path: String?): Boolean {
        if (!method.equals("GET", ignoreCase = true)) return false
        val h = host?.trim()?.lowercase().orEmpty()
        if (h !in ALLOWED_HOSTS) return false
        val p = path?.trim()?.lowercase()?.substringBefore('?')?.substringBefore('#').orEmpty()
        if (p.isEmpty() || p.endsWith("/")) return false
        if (p.endsWith(".wasm")) return true
        if (!p.endsWith(".js")) return false
        return CORE_SCRIPT_MARKERS.any { marker -> p.contains(marker) }
    }

    fun mimeTypeForPath(path: String?): String {
        val p = path?.trim()?.lowercase()?.substringBefore('?')?.substringBefore('#').orEmpty()
        return when {
            p.endsWith(".wasm") -> "application/wasm"
            p.endsWith(".css") -> "text/css"
            else -> "application/javascript"
        }
    }
}

/**
 * 进程内 CCTV 静态核心资源（JS/WASM/CSS）LRU 内存缓存。
 *
 * 在 `shouldInterceptRequest` 命中时直接返回内存副本，跳过 WebView 磁盘缓存锁与 304 校验 RTT；
 * 任何网络异常、非 200 响应或超限文件均静默回退 `null`（fail-open），由 Chromium 原生链路接管。
 */
internal object CctvWebStaticResourceCache {

    private const val TAG = "CctvWebStaticCache"
    private const val MAX_CACHE_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
    private const val ENTRY_TTL_MS = 15 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 5_000

    private data class CachedEntry(
        val mimeType: String,
        val bytes: ByteArray,
        val fetchedAtMs: Long,
    )

    private val cache = object : LruCache<String, CachedEntry>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: CachedEntry): Int = maxOf(1, value.bytes.size)
    }

    fun getOrFetch(
        url: String,
        method: String?,
        host: String?,
        path: String?,
        requestHeaders: Map<String, String>?,
    ): WebResourceResponse? {
        if (!CctvWebStaticCachePolicy.isCacheableStaticAsset(method, host, path)) return null
        val mimeType = CctvWebStaticCachePolicy.mimeTypeForPath(path)
        val now = System.currentTimeMillis()
        val cached = synchronized(cache) { cache.get(url) }
        if (cached != null && now - cached.fetchedAtMs <= ENTRY_TTL_MS) {
            Log.i(TAG, "HIT ${path.orEmpty()} bytes=${cached.bytes.size}")
            return buildResponse(cached.mimeType, cached.bytes)
        }

        val fetched = fetchBytes(url, requestHeaders) ?: return null
        synchronized(cache) {
            cache.put(url, CachedEntry(mimeType = mimeType, bytes = fetched, fetchedAtMs = now))
        }
        Log.i(TAG, "FETCH ${path.orEmpty()} bytes=${fetched.size}")
        return buildResponse(mimeType, fetched)
    }

    fun clear() {
        synchronized(cache) {
            cache.evictAll()
        }
    }

    private fun buildResponse(mimeType: String, bytes: ByteArray): WebResourceResponse =
        WebResourceResponse(
            mimeType,
            "utf-8",
            200,
            "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "public, max-age=900",
            ),
            ByteArrayInputStream(bytes),
        )

    private fun fetchBytes(url: String, requestHeaders: Map<String, String>?): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as? HttpURLConnection) ?: return null
            conn.instanceFollowRedirects = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            requestHeaders?.forEach { (key, value) ->
                if (key.equals("User-Agent", ignoreCase = true) ||
                    key.equals("Referer", ignoreCase = true) ||
                    key.equals("Origin", ignoreCase = true)
                ) {
                    conn.setRequestProperty(key, value)
                }
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val contentLength = conn.contentLength
            if (contentLength > MAX_ENTRY_BYTES) return null

            conn.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_ENTRY_BYTES) return null
                    out.write(buffer, 0, read)
                }
                val bytes = out.toByteArray()
                bytes.takeIf { it.isNotEmpty() }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "static cache miss fallback url=$url err=${t.javaClass.simpleName}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
