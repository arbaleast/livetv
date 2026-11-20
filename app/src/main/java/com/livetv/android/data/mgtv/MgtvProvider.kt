package com.livetv.android.data.mgtv

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory

/**
 * MGTV 频道目录提供方（与普通 IPTV 仓库隔离管理）。
 *
 * 维护 `cameraId` 与频道名称/Logo 的映射，并可将 MGTV 目录转换为领域层 [ChannelCategory]。
 */
class MgtvProvider(
    private val catalogLoader: () -> String?,
    val streamResolver: MgtvStreamResolver = MgtvStreamResolver(),
) {

    private var cachedCategories: List<MgtvCategory> = emptyList()
    private var channelIndexById: Map<String, MgtvChannel> = emptyMap()

    /**
     * 加载并解析 MGTV 频道目录，刷新内部 `cameraId -> MgtvChannel` 映射。
     */
    fun loadMgtvCategories(): List<MgtvCategory> {
        val raw = runCatching { catalogLoader() }.getOrNull().orEmpty()
        val parsed = MgtvJsonParser.parseCategoryList(raw)
        cachedCategories = parsed
        val index = LinkedHashMap<String, MgtvChannel>()
        parsed.forEach { category ->
            category.channels.forEach { ch ->
                index.putIfAbsent(ch.cameraId, ch)
            }
        }
        channelIndexById = index
        return parsed
    }

    /** 按 `cameraId` 查询频道名称。 */
    fun findChannelName(cameraId: String): String? = channelIndexById[cameraId.trim()]?.name

    /** 按 `cameraId` 查询完整 MGTV 频道元数据。 */
    fun findChannel(cameraId: String): MgtvChannel? = channelIndexById[cameraId.trim()]

    /**
     * 映射为通用领域层 [ChannelCategory]（频道 ID 带 `mgtv:` 前缀，与普通 IPTV 源隔离）。
     */
    fun toDomainCategories(): List<ChannelCategory> =
        cachedCategories.map { cat ->
            ChannelCategory(
                id = "mgtv:${cat.id}",
                name = cat.name,
                channels = cat.channels.map { ch ->
                    Channel(
                        id = "${MgtvStreamResolver.CHANNEL_ID_PREFIX}${ch.cameraId}",
                        name = ch.name,
                        description = ch.currentProgram.trim().ifEmpty { "芒果TV" },
                        streamUrl = "",
                        logo = ch.logoUrl,
                        epgId = ch.cameraId,
                        enabled = true,
                    )
                },
            )
        }
}
