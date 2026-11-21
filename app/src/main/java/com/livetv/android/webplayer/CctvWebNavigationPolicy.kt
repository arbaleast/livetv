package com.livetv.android.webplayer

import java.net.URI

/**
 * CCTV WebView 原型の顶层导航白名单（纯 Kotlin，Fail-Closed）。
 *
 * 只放行央视官方直播域：精确 `tv.cctv.com` 与其**严格子域**（`*.tv.cctv.com`）。
 * 其余协议/域名一律拦截，包括 `eviltv.cctv.com`、`tv.cctv.com.evil.invalid` 与
 * `tv.cctv.com@evil.invalid` 这类同后缀/UserInfo 欺骗。
 */
object CctvWebNavigationPolicy {

    private const val OFFICIAL_HOST = "tv.cctv.com"

    private val ALLOWED_EXACT = setOf(OFFICIAL_HOST)
    private val ALLOWED_SUFFIXES = listOf(".$OFFICIAL_HOST")

    /** 是否允许 WebView 顶层导航到 [url]。 */
    fun isAllowed(url: String?): Boolean {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return false

        val uri = runCatching { URI(text) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        // 拒绝 userinfo@host 形式的欺骗
        if (uri.rawUserInfo != null) return false

        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (host.isEmpty()) return false

        return ALLOWED_EXACT.any { host == it } || ALLOWED_SUFFIXES.any { host.endsWith(it) }
    }

    /** 拦截日志：只暴露 scheme 与 host，绝不输出路径/查询串（可能含令牌）。 */
    fun describeForLog(url: String?): String {
        val text = url?.trim().orEmpty()
        val uri = runCatching { URI(text) }.getOrNull()
        val scheme = uri?.scheme?.lowercase() ?: "invalid"
        val host = uri?.host?.lowercase() ?: "-"
        return "blocked navigation: $scheme://$host"
    }
}
