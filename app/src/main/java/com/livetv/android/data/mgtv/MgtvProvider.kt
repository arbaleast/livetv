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
     * **跳过聚合分类「热门」**（`category_id=2100001`）：它基本是把「卫视台 / 地方台 / 特色频道」
     * 里的频道再列一遍，直接展示会产生重复卡片。
     *
     * 但**不能整段丢弃**：实测目录 21 条对应 13 个唯一频道，其中 `344 湖南娱乐` 只出现在「热门」里，
     * 整段过滤会把它永久丢掉（曾导致"只有 12 台"）。这里只把「热门独有」的频道捞回来，
     * 追加成一个只含这些频道的「热门」分类，既没有重复也不丢台。
     */
    fun toDomainCategories(): List<ChannelCategory> {
        val keptCategories = cachedCategories
            .filterNot { it.id in AGGREGATOR_CATEGORY_IDS || it.name in AGGREGATOR_CATEGORY_NAMES }
        val keptChannelIds = keptCategories
            .flatMap { it.channels }
            .map { it.cameraId }
            .toSet()

        val categories = keptCategories
            .map { cat ->
                ChannelCategory(
                    id = "mgtv:${cat.id}",
                    name = cat.name,
                    channels = cat.channels
                        .filterNot { it.cameraId in DENYLIST_CAMERA_IDS }
                        .map { it.toDomainChannel() },
                )
            }
            // 黑名单过滤后可能出现空分类，直接丢弃，避免出现没有卡片的空分组
            .filter { it.channels.isNotEmpty() }

        val orphanChannels = cachedCategories
            .filter { it.id in AGGREGATOR_CATEGORY_IDS || it.name in AGGREGATOR_CATEGORY_NAMES }
            .flatMap { it.channels }
            .filter { it.cameraId !in keptChannelIds }
            .filterNot { it.cameraId in DENYLIST_CAMERA_IDS }
            .distinctBy { it.cameraId }
            .map { it.toDomainChannel() }

        if (orphanChannels.isEmpty()) return categories
        return categories + ChannelCategory(
            id = AGGREGATOR_ORPHAN_CATEGORY_ID,
            name = AGGREGATOR_ORPHAN_CATEGORY_NAME,
            channels = orphanChannels,
        )
    }

    private fun MgtvChannel.toDomainChannel(): Channel = Channel(
        id = "${MgtvStreamResolver.CHANNEL_ID_PREFIX}$cameraId",
        name = name,
        description = currentProgram.trim().ifEmpty { "芒果TV" },
        streamUrl = "",
        logo = logoUrl,
        epgId = cameraId,
        enabled = true,
    )

    companion object {
        /** 聚合分类（内容基本是其它分类的重复集合），不整段展示。 */
        private val AGGREGATOR_CATEGORY_IDS = setOf("2100001")

        /**
         * 上游 CDN 已失效的频道黑名单（cameraId）。
         *
         * `229 国际频道`：取流接口正常返回 padal 节点，但该节点 302 错跳到
         * `http://hlsal-ldvt.../KLGXHMPP360.m3u8`（频道 id 都不对）并返回 403；
         * npuk「换节点重试」也不换节点。上游修复后把这里清空即可恢复。
         */
        private val DENYLIST_CAMERA_IDS = setOf("229")

        /** 兜底：若服务端换了分类 id，按名称再挡一次。 */
        private val AGGREGATOR_CATEGORY_NAMES = setOf("热门")

        /** 「热门独有」频道的归组 id（不与服务端数值分类 id 冲突）。 */
        private const val AGGREGATOR_ORPHAN_CATEGORY_ID = "mgtv:hot"

        /** 「热门独有」频道的归组名称。 */
        private const val AGGREGATOR_ORPHAN_CATEGORY_NAME = "热门"
    }


}
