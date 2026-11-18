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
 * 2. `#EXTINF` 行的属性区（逗号之前）与显示名区（逗号之后）分开处理；
 * 3. 频道 URL 取该 `#EXTINF` 之后**向下第一个**非空、非注释的合法 http/https 行；
 * 4. `group-title` 决定分类名，缺失时归入兜底分类 [DEFAULT_CATEGORY_NAME]。
 */
object M3uPlaylistParser {

    /** 无 `group-title` 的频道归入的兜底分类名。 */
    const val DEFAULT_CATEGORY_NAME = "未分类"

    /** 兜底分类的 id。 */
    const val DEFAULT_CATEGORY_ID = "uncategorized"

    /**
     * `#EXTINF` 属性区里的 `key="value"`。
     *
     * - 属性名只允许字母、数字与 `-`，避免贪婪匹配把后面的属性名吃进 key；
     * - 引号内允许出现任意字符（含 `=`、空格、中文），非引号值只允许不含空白与引号的字符；
     * - 属性之间可以有任意空白，因此用 `\s*` 而不是单个空格。
     */
    private val ATTRIBUTE_REGEX = Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"']+))""")

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
                // 上一条 EXTINF 还没等到 URL 就被新的 EXTINF 覆盖 -> 上一条作废
                pending = parseExtInf(line)
                continue
            }

            // 其它注释行（#EXTVLCOPT / #EXTM3U / #EXTGRP 等）直接跳过，
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
     * - 第一个逗号之前是属性区，之后是显示名（显示名本身可能含逗号，取第一个逗号即可）；
     * - 属性区里第一个逗号之后的内容不会被误当成属性（已按第一个逗号切分）。
     */
    private fun parseExtInf(line: String): PendingEntry {
        val body = line.substring(EXTINF_PREFIX.length)
        // 去掉 ":时长" 前缀（形如 ":-1" 或 ":123 tvg-id=..."）
        val afterColon = body.substringAfter(':', "")
        val commaIndex = afterColon.indexOf(',')
        val attributePart = if (commaIndex >= 0) afterColon.substring(0, commaIndex) else afterColon
        val displayName = if (commaIndex >= 0) afterColon.substring(commaIndex + 1) else ""

        val attributes = parseAttributes(attributePart)
        return PendingEntry(
            displayName = displayName,
            tvgId = attributes["tvg-id"].orEmpty(),
            tvgLogo = attributes["tvg-logo"].orEmpty(),
            groupTitle = attributes["group-title"].orEmpty(),
        )
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
}
