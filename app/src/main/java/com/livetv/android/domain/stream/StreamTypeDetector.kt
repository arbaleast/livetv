package com.livetv.android.domain.stream

import java.net.URI
import java.util.Locale

/**
 * HLS / 渐进式流类型识别辅助（纯 Kotlin）。
 *
 * 用于确保：
 * 1. 标准 `.m3u8` 走 HLS；
 * 2. 无 `.m3u8` 后缀且服务端 `Content-Type` 不规范的普通 IPTV HLS 地址，仍可通过显式 MIME 提示正确进入 `HlsMediaSource`；
 * 3. `.mp4` / `.flv` / `.ts` 等明确非清单后缀不被误判为 HLS，避免回归。
 */
object StreamTypeDetector {

    const val MIME_HLS = "application/x-mpegURL"

    private val NON_HLS_EXTENSIONS = setOf(
        ".mp4", ".mkv", ".flv", ".ts", ".mp3", ".aac", ".webm",
        ".mpd", ".json", ".xml", ".jpg", ".jpeg", ".png", ".webp",
    )

    /**
     * 判断地址是否明显为 HLS 清单地址（含无 `.m3u8` 后缀的常见 IPTV 路由/参数特征）。
     */
    fun isLikelyHlsUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val uri = runCatching { URI(trimmed) }.getOrNull()
        val path = (uri?.path ?: extractFallbackPath(trimmed)).lowercase(Locale.US)
        val query = (uri?.rawQuery ?: trimmed.substringAfter('?', "").substringBefore('#')).lowercase(Locale.US)

        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true
        if (NON_HLS_EXTENSIONS.any { path.endsWith(it) }) return false

        if (path.contains("/hls/") || path.endsWith("/hls") || path.contains("hls.php") || path.contains("/m3u8/")) {
            return true
        }
        if (query.isNotEmpty()) {
            val pairs = query.split('&')
            for (pair in pairs) {
                val key = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if ((key == "format" || key == "type" || key == "protocol" || key == "fmt") &&
                    (value == "m3u8" || value == "hls")
                ) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 推断显式 MIME 类型。
     *
     * 返回 `null` 表示交给 Media3 `DefaultMediaSourceFactory` 按默认规则自动识别。
     */
    fun inferMimeType(url: String, contentTypeHeader: String? = null): String? {
        val ct = contentTypeHeader?.lowercase(Locale.US).orEmpty()
        if (ct.contains("mpegurl")) return MIME_HLS
        return if (isLikelyHlsUrl(url)) MIME_HLS else null
    }

    /** `URI` 解析失败时的兜底路径提取：先剥离 `scheme://authority`，避免把主机名误判成路径。 */
    private fun extractFallbackPath(rawUrl: String): String {
        val withoutQuery = rawUrl.substringBefore('?').substringBefore('#')
        val schemeEnd = withoutQuery.indexOf("://")
        val afterAuthority = if (schemeEnd >= 0) {
            val slashIdx = withoutQuery.indexOf('/', startIndex = schemeEnd + 3)
            if (slashIdx >= 0) withoutQuery.substring(slashIdx) else ""
        } else {
            withoutQuery
        }
        return afterAuthority
    }
}
