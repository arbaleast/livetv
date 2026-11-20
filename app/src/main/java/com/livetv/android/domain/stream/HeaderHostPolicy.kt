package com.livetv.android.domain.stream

import java.net.URI
import java.util.Locale

/**
 * 请求头 Host 白名单隔离策略（纯 Kotlin）。
 *
 * 防止在跨域名 302 重定向时把某个源（如 MGTV）的专用请求头泄漏给第三方域名，
 * 同时保证普通 IPTV（`allowedHostSuffixes` 为空）维持原有行为不受污染。
 */
object HeaderHostPolicy {

    /**
     * 提取 URL 中的小写主机名（去除端口与末尾点号）；解析失败返回 `null`。
     */
    fun extractHost(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        return host.trimEnd('.').lowercase(Locale.US).takeIf { it.isNotEmpty() }
    }

    /**
     * 校验 [url] 是否命中 [allowedHostSuffixes] 白名单。
     *
     * - 白名单为空：视为不限制（返回 `true`，兼容普通 IPTV）。
     * - 白名单非空：
     *   1. 当 [requireHttps] 为 `true`（默认）时仅放行 `https` 协议，阻断明文 `http`/`ftp` 降级泄漏；
     *   2. 严格拦截带 `userinfo@host` 混淆凭据的 URL；
     *   3. 仅精确匹配 `host == suffix` 或子域匹配 `host.endsWith(".$suffix")`，
     *      严格拦截 `evilmgtv.com` 这类后缀伪造攻击。
     */
    fun isHostAllowed(
        url: String,
        allowedHostSuffixes: Set<String>,
        requireHttps: Boolean = true,
    ): Boolean {
        if (allowedHostSuffixes.isEmpty()) return true
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null) return false
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (requireHttps) {
            if (scheme != "https") return false
        } else {
            if (scheme != "http" && scheme != "https") return false
        }
        val host = uri.host?.trimEnd('.')?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return false
        return allowedHostSuffixes.any { rawSuffix ->
            val suffix = rawSuffix.trim().trimStart('.').trimEnd('.').lowercase(Locale.US)
            suffix.isNotEmpty() && (host == suffix || host.endsWith(".$suffix"))
        }
    }

    /**
     * 按目标 URL 与 Host 白名单过滤请求头：
     * 命中白名单返回原请求头副本，未命中返回空 Map。
     */
    fun filterHeadersForUrl(
        url: String,
        headers: Map<String, String>,
        allowedHostSuffixes: Set<String>,
        requireHttps: Boolean = true,
    ): Map<String, String> {
        if (headers.isEmpty()) return emptyMap()
        return if (isHostAllowed(url, allowedHostSuffixes, requireHttps)) headers else emptyMap()
    }
}
