package com.livetv.android.data.repository

import com.livetv.android.data.source.ChannelDataSource
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.domain.repository.ChannelRepository

/**
 * 默认频道仓库：聚合多个 [ChannelDataSource] 的结果并合并同名分类。
 *
 * 合并策略：
 * - **按分类名归并**：JSON 源的分类 `name` 与 M3U 源的 `group-title` 同名时合并到同一个
 *   [ChannelCategory]，UI 层因此不会出现重复的分类行；
 * - **先到先得的分类 id**：合并后的分类沿用**第一个**提供该分类的数据源的 id；
 * - **频道顺序稳定**：同一分类内，先按数据源顺序（构造参数的顺序）排列，
 *   源内保持各自原有顺序；
 * - **同名分类内不重复**：若两个源提供了完全相同的频道（`id` + `streamUrl` 都相同），
 *   只保留先出现的那一个，避免 UI 出现重复卡片；
 * - **单个源失败不影响其它源**：某个数据源抛异常时只跳过该源，其余源照常合并。
 *
 * 内存缓存（阶段五）：
 * - [enableCache] 为 true 时，首次 [getCategories] 聚合结果并缓存，后续调用直接返回缓存；
 * - [refresh] 强制重新聚合并返回新结果，[invalidateCache] 只作废缓存；
 * - 关闭缓存时每次调用都重新聚合（单测用 `enableCache = false` 保持每次重算的语义）。
 */
class DefaultChannelRepository(
    private val dataSources: List<ChannelDataSource>,
    private val enableCache: Boolean = true,
) : ChannelRepository {

    /** 便捷构造：单个数据源。 */
    constructor(
        dataSource: ChannelDataSource,
        enableCache: Boolean = true,
    ) : this(listOf(dataSource), enableCache)

    /** 聚合结果的内存缓存；`null` 表示尚未缓存。 */
    @Volatile
    private var cachedCategories: List<ChannelCategory>? = null

    override fun getCategories(): List<ChannelCategory> {
        if (!enableCache) {
            return aggregate()
        }
        cachedCategories?.let { return it }
        return aggregate().also { cachedCategories = it }
    }

    /**
     * 强制重新聚合数据源并更新缓存。
     *
     * @return 重新聚合后的分类列表。
     */
    fun refresh(): List<ChannelCategory> {
        val fresh = aggregate()
        cachedCategories = fresh
        return fresh
    }

    /** 作废缓存；下一次 [getCategories] 会重新聚合。 */
    fun invalidateCache() {
        cachedCategories = null
    }

    private fun aggregate(): List<ChannelCategory> {
        val merged = linkedMapOf<String, MergedCategory>()

        for (source in dataSources) {
            val categories = try {
                source.loadCategories()
            } catch (e: Exception) {
                // 单个数据源失败只跳过该源，不影响其它源
                continue
            }
            for (category in categories) {
                val key = category.name
                val target = merged.getOrPut(key) { MergedCategory(id = category.id, name = category.name) }
                for (channel in category.channels) {
                    if (target.channels.none { it.id == channel.id && it.streamUrl == channel.streamUrl }) {
                        target.channels.add(channel)
                    }
                }
            }
        }

        return merged.values
            .filter { it.channels.isNotEmpty() }
            .map { ChannelCategory(id = it.id, name = it.name, channels = it.channels.toList()) }
    }

    /** 合并过程中的可变累加器。 */
    private class MergedCategory(val id: String, val name: String) {
        val channels: MutableList<Channel> = mutableListOf()
    }
}
