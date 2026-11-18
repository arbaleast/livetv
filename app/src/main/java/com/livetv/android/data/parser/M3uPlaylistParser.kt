package com.livetv.android.data.parser

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory

/**
 * M3U / 扩展 M3U（EXTM3U）播放列表解析器（纯 Kotlin）。
 *
 * 设计要点：
 * - **纯函数、无 Android 依赖**：输入 `String`，输出 `List<ChannelCategory>`，可在纯 JVM 单测中直接执行。
 * - **永不抛出异常**：任何非法输入都返回空列表或跳过坏条目。
 * - **URL 安全校验**：复用阶段二的规则，只接受 `http://` / `https://`（忽略大小写）。
 *
 * 解析策略：
 * 1. 逐行扫描，只有 `#EXTINF` 开头的行才代表一条频道的开始；
 * 2. `#EXTINF` 行的属性区（逗号之前）与显示名区（逗号之后）分开处理，
 *    **切分时跳过引号内部的逗号**（属性值本身可以含逗号）；
 * 3. 频道 URL 取该 `#EXTINF` 之后**向下第一个**非空、非注释的合法 http/https 行；
 * 4. `#EXTINF` 与 URL 之间出现的 `#EXTVLCOPT:` / `#KODIPROP:` 指令会被解析成 HTTP 请求头，
 *    注入 [Channel.headers]；
 * 5. `group-title` 决定分类名，缺失时归入兜底分类 [DEFAULT_CATEGORY_NAME]。
 */
object M3uPlaylistParser {

    /** 无 `group-title` 的频道归入的兜底分类名。 */
    const val DEFAULT_CATEGORY_NAME = "未分类"

    /** 兜底分类的 id。 */
    const val DEFAULT_CATEGORY_ID = "uncategorized"

    /** 标准 HTTP 请求头键名。 */
    const val HEADER_USER_AGENT = "User-Agent"
    const val HEADER_REFERER = "Referer"
    const val HEADER_ORIGIN = "Origin"

    /**
     * `#EXTINF` 属性区里的 `key="value"`。
     *
     * - 属性名只允许字母、数字与 `-`，避免贪婪匹配把后面的属性名吃进 key；
     * - 引号内允许出现任意字符（含 `=`、空格、逗号、中文），非引号值只允许不含空白与引号的字符；
     * - 属性之间可以有任意空白，因此用 `\s*` 而不是单个空格。
     */
    private val ATTRIBUTE_REGEX = Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"']+))""")

    /** `#EXTVLCOPT:` / `#KODIPROP:` 指令行。 */
    private val VLC_OPT_PREFIXES = listOf("#EXTVLCOPT:", "#KODIPROP:")

    /**
     * 指令键 -> 标准 Header 键。
     *
     * 键名比较**忽略大小写**，并接受 `http-` 前缀变体
     * （`http-referrer` 与 `http-referer` 都映射到 `Referer`）。
     */
    private val HEADER_KEY_MAP: Map<String, String> = mapOf(
        "user-agent" to HEADER_USER_AGENT,
        "http-user-agent" to HEADER_USER_AGENT,
        "referer" to HEADER_REFERER,
        "referrer" to HEADER_REFERER,
        "http-referer" to HEADER_REFERER,
        "http-referrer" to HEADER_REFERER,
        "origin" to HEADER_ORIGIN,
        "http-origin" to HEADER_ORIGIN,
    )

    /**
     * 解析 M3U 文本为分类领域模型。
     *
     * @return 解析成功且至少有一个有效频道时返回列表，否则返回空列表（不抛异常）。
     */
    fun parse(playlist: String): List<ChannelCategory> {
        if (playlist.isBlank()) return emptyList()

        val categories = linkedMapOf<String, MutableList<Channel>>()
        val categoryIds = linkedMapOf<String, String>()

        var pending: PendingEntry? = null

        for (rawLine in playlist.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith(EXTINF_PREFIX, ignoreCase = true)) {
                // 新的 EXTINF 会**作废**上一个还没闭合的条目（含其待定请求头）
                pending = parseExtInf(line)
                continue
            }

            // `#EXTVLCOPT:` / `#KODIPROP:` 指令：并入当前待定条目的请求头
            val optPrefix = VLC_OPT_PREFIXES.firstOrNull { line.startsWith(it, ignoreCase = true) }
            if (optPrefix != null) {
                val entry = pending ?: continue
                applyDirective(entry.headers, line.substring(optPrefix.length))
                continue
            }

            // 其它注释行（#EXTM3U / #EXTGRP 等）直接跳过，
            // 但**不**放弃 pending：EXTINF 与 URL 之间允许夹杂注释行。
            if (line.startsWith("#")) continue

            val entry = pending ?: continue
            pending = null

            val url = line.trim()
            if (!ChannelJsonParser.isValidStreamUrl(url)) continue

            val channel = entry.toChannel(url) ?: continue
            val categoryName = entry.groupTitle.ifBlank { DEFAULT_CATEGORY_NAME }
            // 同名分类共用同一个 id（取首次出现时的 id），保证 UI 层不会重复渲染同名分类
            val categoryId = categoryIds.getOrPut(categoryName) { categoryName }
            categories.getOrPut(categoryName) { mutableListOf() }.add(channel)
        }

        return categories.map { (name, channels) ->
            ChannelCategory(
                id = categoryIds[name] ?: name,
                name = name,
                channels = channels.toList(),
            )
        }
    }

    private const val EXTINF_PREFIX = "#EXTINF"

    /** `#EXTINF` 解析出来的中间结构。 */
    private data class PendingEntry(
        val displayName: String,
        val tvgId: String,
        val tvgLogo: String,
        val groupTitle: String,
        val headers: MutableMap<String, String>,
    ) {
        fun toChannel(url: String): Channel? {
            val name = displayName.trim()
            if (name.isEmpty()) return null
            return Channel(
                // M3U 未必提供 tvg-id，退化用名称做稳定 id
                id = tvgId.ifBlank { name },
                name = name,
                description = "",
                streamUrl = url,
                headers = headers.toMap(),
                logo = tvgLogo,
                epgId = tvgId,
                enabled = true,
            )
        }
    }

    /**
     * 解析 `#EXTINF` 行。
     *
     * 行格式：`#EXTINF:-1 tvg-id="x" tvg-logo="y" group-title="z",显示名称`
     * - 第一个逗号之前是属性区，之后是显示名（显示名本身可能含逗号）；
     * - 属性区里第一个逗号之后的内容不会被误当成属性（已按第一个逗号切分）；
     * - **切分时跳过引号内部的逗号**：`group-title="新闻,综合",CCTV-1` 中的逗号在引号内，不算分隔符。
     */
    private fun parseExtInf(line: String): PendingEntry {
        val body = line.substring(EXTINF_PREFIX.length)
        // 去掉 ":时长" 前缀（形如 ":-1" 或 ":123 tvg-id=..."）
        val afterColon = body.substringAfter(':', "")
        val separatorIndex = indexOfTopLevelComma(afterColon)
        val attributePart = if (separatorIndex >= 0) afterColon.substring(0, separatorIndex) else afterColon
        val displayName = if (separatorIndex >= 0) afterColon.substring(separatorIndex + 1) else ""

        val attributes = parseAttributes(attributePart)
        return PendingEntry(
            displayName = displayName,
            tvgId = attributes["tvg-id"].orEmpty(),
            tvgLogo = attributes["tvg-logo"].orEmpty(),
            groupTitle = attributes["group-title"].orEmpty(),
            headers = extractInlineHeaders(attributes),
        )
    }

    /**
     * 查找**引号之外**的第一个逗号。
     *
     * 双引号与单引号内部的逗号不算分隔符；引号未闭合时按"到行尾都是引号内"处理，
     * 保证不越界、不抛异常。
     *
     * @return 逗号下标；没有找到时返回 -1。
     */
    private fun indexOfTopLevelComma(text: String): Int {
        var inDoubleQuote = false
        var inSingleQuote = false
        for (i in text.indices) {
            when (text[i]) {
                '"' -> if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
                '\'' -> if (!inDoubleQuote) inSingleQuote = !inSingleQuote
                ',' -> if (!inDoubleQuote && !inSingleQuote) return i
            }
        }
        return -1
    }

    /**
     * 从属性区提取 `key="value"`。
     *
     * 用正则逐个扫描而不是简单 split，因此能容忍：
     * - 属性顺序任意；
     * - 属性之间有多个空格或制表符；
     * - 单引号与双引号混用（`key='value'` 与 `key="value"` 都支持）。
     */
    private fun parseAttributes(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (match in ATTRIBUTE_REGEX.findAll(text)) {
            val key = match.groupValues[1]
            // group2 = 双引号值，group3 = 单引号值，group4 = 无引号值
            val value = match.groupValues[2]
                .ifEmpty { match.groupValues[3] }
                .ifEmpty { match.groupValues[4] }
            if (key !in result) {
                result[key] = value.trim()
            }
        }
        return result
    }

    /**
     * 从 `#EXTINF` 行内属性里提取请求头。
     *
     * 识别 `user-agent` / `http-user-agent` / `referer` / `http-referer` / `http-referrer` 等键，
     * 统一映射为标准 Header 键。
     */
    private fun extractInlineHeaders(attributes: Map<String, String>): MutableMap<String, String> {
        val headers = mutableMapOf<String, String>()
        for ((key, value) in attributes) {
            val headerKey = HEADER_KEY_MAP[key.lowercase()] ?: continue
            if (value.isNotEmpty() && headerKey !in headers) {
                headers[headerKey] = value
            }
        }
        return headers
    }

    /**
     * 解析一条 `#EXTVLCOPT:` / `#KODIPROP:` 指令并写入请求头。
     *
     * 指令形如 `http-user-agent=Mozilla/5.0`；值可能被双引号或单引号包裹，需剥离外层引号；
     * 空值忽略；键名大小写不敏感。
     *
     * `#KODIPROP` 的键名带命名空间前缀（如 `inputstream.adaptive.stream_headers`），
     * 其值形如 `User-Agent=Kodi`，因此这里**再往后找一层 `=`**：
     * 前缀本身不是请求头键，真正的请求头键在最后一个 `=` 之前。
     */
    private fun applyDirective(headers: MutableMap<String, String>, directive: String) {
        val separatorIndex = directive.indexOf('=')
        if (separatorIndex <= 0) return
        val rawKey = directive.substring(0, separatorIndex).trim()
        var rawValue = directive.substring(separatorIndex + 1).trim()

        // KODIPROP 风格：`<namespace>.<Key>=<Value>`，再拆一层
        if (rawKey.contains('.')) {
            val nestedSeparator = rawValue.indexOf('=')
            if (nestedSeparator <= 0) return
            val nestedKey = rawValue.substring(0, nestedSeparator).trim()
            rawValue = rawValue.substring(nestedSeparator + 1).trim()
            val nestedHeaderKey = HEADER_KEY_MAP[nestedKey.lowercase()] ?: return
            val nestedValue = stripOuterQuotes(rawValue)
            if (nestedValue.isEmpty()) return
            headers[nestedHeaderKey] = nestedValue
            return
        }

        if (rawKey.isEmpty() || rawValue.isEmpty()) return
        val headerKey = HEADER_KEY_MAP[rawKey.lowercase()] ?: return
        val value = stripOuterQuotes(rawValue)
        if (value.isEmpty()) return
        headers[headerKey] = value
    }

    /** 剥离值外层的成对引号（双引号或单引号）。 */
    private fun stripOuterQuotes(value: String): String {
        if (value.length >= 2) {
            val first = value.first()
            val last = value.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length - 1).trim()
            }
        }
        return value
    }
}
