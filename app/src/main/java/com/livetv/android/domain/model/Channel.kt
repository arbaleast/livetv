package com.livetv.android.domain.model

/**
 * 频道领域模型。
 *
 * 从阶段一的 `com.livetv.android.model.Channel` 升级而来，字段向后兼容：
 * 原有构造调用 `Channel(id, name, description, streamUrl)` 仍然有效（新增字段均有默认值）。
 *
 * 为后续能力预留的字段：
 * - [backupUrls]：主备源切换（Failover）用备用地址；
 * - [headers]：自定义 HTTP 请求头；
 * - [logo]：频道图标地址；
 * - [epgId]：节目单（EPG）匹配标识；
 * - [enabled]：是否在浏览页展示。
 */
data class Channel(
    val id: String,
    val name: String,
    val description: String,
    val streamUrl: String = "",
    val backupUrls: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val logo: String = "",
    val epgId: String = "",
    val enabled: Boolean = true,
)
