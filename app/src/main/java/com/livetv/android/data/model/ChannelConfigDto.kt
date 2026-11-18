package com.livetv.android.data.model

/**
 * 频道配置文件（assets/channels.json）的 JSON 映射 DTO。
 *
 * 这一层只描述**原始 JSON 形状**，不做任何业务校验；
 * 校验与过滤集中在 `com.livetv.android.data.parser.ChannelJsonParser`。
 */
data class ChannelConfigDto(
    val version: Int = DEFAULT_VERSION,
    val categories: List<CategoryDto> = emptyList(),
) {
    companion object {
        const val DEFAULT_VERSION = 1
    }
}

/** 分类 DTO。 */
data class CategoryDto(
    val id: String = "",
    val name: String = "",
    val channels: List<ChannelDto> = emptyList(),
)

/** 频道 DTO；所有可选字段均以默认值兜底。 */
data class ChannelDto(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val streamUrl: String = "",
    val backupUrls: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val logo: String = "",
    val epgId: String = "",
    val enabled: Boolean = true,
)
