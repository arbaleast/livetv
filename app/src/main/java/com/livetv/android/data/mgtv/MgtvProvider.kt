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
     *
     * **跳过聚合分类「热门」**（`category_id=2100001`）：它只是把「卫视台 / 地方台 / 特色频道」
     * 里的频道再列一遍——实测目录 21 条只对应 12 个唯一频道，其中 9 条是「热门」的重复。
     * 直接不展示聚合分类，列表就没有重复卡片、总数也不再虚高，**不需要运行时去重**。
     */
    fun toDomainCategories(): List<ChannelCategory> =
        cachedCategories
            .filterNot { it.id in AGGREGATOR_CATEGORY_IDS || it.name in AGGREGATOR_CATEGORY_NAMES }
            .map { cat ->
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

    companion object {
        /** 聚合分类（内容是其它分类的重复集合），不单独展示。 */
        private val AGGREGATOR_CATEGORY_IDS = setOf("2100001")

        /** 兜底：若服务端换了分类 id，按名称再挡一次。 */
        private val AGGREGATOR_CATEGORY_NAMES = setOf("热门")
    }


}
