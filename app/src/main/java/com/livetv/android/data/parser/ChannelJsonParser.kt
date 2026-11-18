package com.livetv.android.data.parser

import com.livetv.android.data.model.CategoryDto
import com.livetv.android.data.model.ChannelConfigDto
import com.livetv.android.data.model.ChannelDto
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * 频道 JSON 解析与校验器。
 *
 * 设计要点：
 * - **纯函数、无 Android 依赖**：输入 `String`，输出领域模型，可在纯 JVM 单测中直接执行。
 * - **永不抛出异常**：任何非法输入（空串、残缺 JSON、结构不符）都返回空列表。
 * - **URL 安全校验**：只接受 `http://` / `https://`（忽略大小写）开头的非空地址，
 *   过滤 `file://`、`javascript:`、`rtmp://` 等其它协议。
 */
object ChannelJsonParser {

    /** 允许的协议前缀（小写比较）。 */
    private val ALLOWED_SCHEMES = listOf("http://", "https://")

    /**
     * 解析 JSON 文本为分类领域模型。
     *
     * @return 解析成功且至少有一个有效分类时返回列表，否则返回空列表（不抛异常）。
     */
    fun parse(json: String): List<ChannelCategory> {
        val dto = parseToDto(json) ?: return emptyList()
        return dto.categories
            .mapNotNull { toCategory(it) }
            .filter { it.channels.isNotEmpty() }
    }

    /**
     * 解析 JSON 文本为 DTO（不做业务校验）。
     *
     * @return 解析失败时返回 null。
     */
    fun parseToDto(json: String): ChannelConfigDto? {
        if (json.isBlank()) return null
        return try {
            val root = JSONObject(json)
            val version = if (root.has("version")) root.optInt("version", ChannelConfigDto.DEFAULT_VERSION) else ChannelConfigDto.DEFAULT_VERSION
            val categories = root.optJSONArray("categories")?.toCategoryDtos().orEmpty()
            ChannelConfigDto(version = version, categories = categories)
        } catch (e: Exception) {
            // 残缺 JSON / 结构不符 -> null（调用方转为空列表）
            null
        }
    }

    /**
     * 判断是否为合法的播放地址：非空且以 `http://` 或 `https://` 开头（忽略大小写）。
     */
    fun isValidStreamUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        return ALLOWED_SCHEMES.any { trimmed.startsWith(it, ignoreCase = true) }
    }

    private fun toCategory(dto: CategoryDto): ChannelCategory? {
        val id = dto.id.trim()
        if (id.isEmpty()) return null
        val name = dto.name.trim().ifEmpty { id }
        val channels = dto.channels.mapNotNull { toChannel(it) }
        if (channels.isEmpty()) return null
        return ChannelCategory(id = id, name = name, channels = channels)
    }

    private fun toChannel(dto: ChannelDto): Channel? {
        // enabled == false 的频道直接剔除
        if (!dto.enabled) return null
        val id = dto.id.trim()
        val name = dto.name.trim()
        // id / name 为必填语义：缺任一则丢弃该频道
        if (id.isEmpty() || name.isEmpty()) return null
        val streamUrl = dto.streamUrl.trim()
        // 主地址非法 -> 整条频道无效
        if (!isValidStreamUrl(streamUrl)) return null
        return Channel(
            id = id,
            name = name,
            description = dto.description,
            streamUrl = streamUrl,
            backupUrls = dto.backupUrls.map { it.trim() }.filter { isValidStreamUrl(it) },
            headers = dto.headers,
            logo = dto.logo,
            epgId = dto.epgId,
            enabled = true,
        )
    }

    private fun JSONArray.toCategoryDtos(): List<CategoryDto> {
        val result = mutableListOf<CategoryDto>()
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            result += CategoryDto(
                id = item.optString("id", ""),
                name = item.optString("name", ""),
                channels = item.optJSONArray("channels")?.toChannelDtos().orEmpty(),
            )
        }
        return result
    }

    private fun JSONArray.toChannelDtos(): List<ChannelDto> {
        val result = mutableListOf<ChannelDto>()
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            result += ChannelDto(
                id = item.optString("id", ""),
                name = item.optString("name", ""),
                description = item.optString("description", ""),
                streamUrl = item.optString("streamUrl", ""),
                backupUrls = item.optJSONArray("backupUrls")?.toStringList().orEmpty(),
                headers = item.optJSONObject("headers")?.toStringMap().orEmpty(),
                logo = item.optString("logo", ""),
                epgId = item.optString("epgId", ""),
                enabled = if (item.has("enabled")) item.optBoolean("enabled", true) else true,
            )
        }
        return result
    }

    private fun JSONArray.toStringList(): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until length()) {
            val value = optString(i, "")
            if (value.isNotEmpty()) result += value
        }
        return result
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = optString(key, "")
            if (value.isNotEmpty()) result[key] = value
        }
        return result
    }
}
