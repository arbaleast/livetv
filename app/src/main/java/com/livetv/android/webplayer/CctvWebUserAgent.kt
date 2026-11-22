package com.livetv.android.webplayer

/**
 * WebView User-Agent 清洗与分流（纯 Kotlin）。
 *
 * - 移动直连台（14 路直播 + 4K 轮播）：保留系统 WebView 的移动 Chrome UA，仅剔除 `; wv` 标识；
 * - 桌面直播台（CCTV-3 / 6 / 8）：构造不含 `Android` / `Mobile` 标识的 Desktop Chrome UA，
 *   保持在 PC 直播页播放，避免 `/m/` 页面强跳 `m.yangshipin.cn`。
 */
object CctvWebUserAgent {

    private val WV_TOKEN = Regex(";\\s*wv\\b", RegexOption.IGNORE_CASE)
    private val EMPTY_PAREN_PREFIX = Regex("\\(\\s*;")
    private val EMPTY_PAREN_SUFFIX = Regex(";\\s*\\)")
    private val MULTI_SPACE = Regex("\\s{2,}")
    private val CHROME_VERSION = Regex("Chrome/([0-9.]+)")
    private const val FALLBACK_CHROME_VERSION = "126.0.0.0"

    fun sanitize(userAgent: String?): String {
        val raw = userAgent?.trim().orEmpty()
        if (raw.isEmpty()) return ""

        return WV_TOKEN.replace(raw, "")
            .let { EMPTY_PAREN_PREFIX.replace(it, "(") }
            .let { EMPTY_PAREN_SUFFIX.replace(it, ")") }
            .let { MULTI_SPACE.replace(it, " ") }
            .trim()
    }

    fun desktopChrome(baseUserAgent: String?): String {
        val version = CHROME_VERSION.find(baseUserAgent.orEmpty())?.groupValues?.getOrNull(1)
            ?: FALLBACK_CHROME_VERSION
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$version Safari/537.36"
    }

    fun resolveForChannel(baseUserAgent: String?, channelId: String?): String =
        if (CctvWebChannel.requiresDesktopUserAgent(channelId)) {
            desktopChrome(baseUserAgent)
        } else {
            sanitize(baseUserAgent)
        }
}
