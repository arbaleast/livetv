package com.livetv.android.webplayer

/**
 * WebView User-Agent 清洗（纯 Kotlin）。
 *
 * 默认策略：保留系统 WebView 的移动 Chrome UA，仅剔除 `; wv` 标识，
 * 避免官方播放器把 WebView 判为不支持；同时保持 UA 其余部分与分号格式完好。
 */
object CctvWebUserAgent {

    private val WV_TOKEN = Regex(";\\s*wv\\b", RegexOption.IGNORE_CASE)
    private val EMPTY_PAREN_PREFIX = Regex("\\(\\s*;")
    private val EMPTY_PAREN_SUFFIX = Regex(";\\s*\\)")
    private val MULTI_SPACE = Regex("\\s{2,}")

    fun sanitize(userAgent: String?): String {
        val raw = userAgent?.trim().orEmpty()
        if (raw.isEmpty()) return ""

        return WV_TOKEN.replace(raw, "")
            .let { EMPTY_PAREN_PREFIX.replace(it, "(") }
            .let { EMPTY_PAREN_SUFFIX.replace(it, ")") }
            .let { MULTI_SPACE.replace(it, " ") }
            .trim()
    }
}
