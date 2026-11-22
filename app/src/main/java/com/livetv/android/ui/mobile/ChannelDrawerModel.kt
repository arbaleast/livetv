package com.livetv.android.ui.mobile

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory

/**
 * 播放页"频道列表"抽屉的**纯数据模型**。
 *
 * 抽屉按分类分组列出全部频道，并标记当前正在播放的那一个。
 * 同样刻意**不引入任何 Android 类型**，便于纯 JVM 单元测试覆盖。
 */
sealed class ChannelDrawerRow {

    /** 分类标题。 */
    data class CategoryHeader(
        val categoryId: String,
        val categoryName: String,
    ) : ChannelDrawerRow()

    /** 一个可点击的频道条目。 */
    data class ChannelItem(
        val channel: Channel,
        val isCurrent: Boolean,
    ) : ChannelDrawerRow()
}

/**
 * 选台抽屉顶部的分组筛选胶囊项：`categoryId == null` 表示「全部」。
 */
data class ChannelDrawerFilterChip(
    val categoryId: String?,
    val label: String,
    val selected: Boolean,
)

/**
 * 抽屉行序列与分组筛选的组装逻辑（纯函数）。
 */
object ChannelDrawerModel {

    const val DEFAULT_ALL_LABEL = "全部"

    /**
     * 生成抽屉顶部的分组筛选胶囊列表：「全部」置顶，跳过空分类；
     * 若 `selectedCategoryId` 已不在有效分类中，自动回退选中「全部」。
     */
    fun buildFilterChips(
        categories: List<ChannelCategory>,
        selectedCategoryId: String?,
        allLabel: String = DEFAULT_ALL_LABEL,
    ): List<ChannelDrawerFilterChip> {
        val nonEmpty = categories.filter { it.channels.isNotEmpty() }
        val effectiveSelectedId = selectedCategoryId?.takeIf { id -> nonEmpty.any { it.id == id } }
        val chips = ArrayList<ChannelDrawerFilterChip>(nonEmpty.size + 1)
        chips += ChannelDrawerFilterChip(
            categoryId = null,
            label = allLabel,
            selected = effectiveSelectedId == null,
        )
        nonEmpty.forEach { category ->
            chips += ChannelDrawerFilterChip(
                categoryId = category.id,
                label = category.name,
                selected = category.id == effectiveSelectedId,
            )
        }
        return chips
    }

    /**
     * 按选中的分组过滤抽屉行：
     * - `selectedCategoryId == null` 或分组不存在时，返回全部带分类头的行；
     * - 命中单个分组时，直接返回该分组下的频道条目，并保留当前播放频道高亮。
     */
    fun buildFilteredRows(
        categories: List<ChannelCategory>,
        currentChannelId: String?,
        selectedCategoryId: String?,
    ): List<ChannelDrawerRow> {
        val target = selectedCategoryId?.let { id ->
            categories.firstOrNull { it.id == id && it.channels.isNotEmpty() }
        } ?: return buildRows(categories, currentChannelId)

        return target.channels.map { channel ->
            ChannelDrawerRow.ChannelItem(
                channel = channel,
                isCurrent = currentChannelId != null && channel.id == currentChannelId,
            )
        }
    }

    /**
     * 按分类顺序展开成抽屉的行序列。
     *
     * - 空分类直接跳过；
     * - `currentChannelId` 命中的频道标记 `isCurrent = true`；
     * - 其余频道 `isCurrent = false`。
     */
    fun buildRows(
        categories: List<ChannelCategory>,
        currentChannelId: String?,
    ): List<ChannelDrawerRow> {
        val rows = mutableListOf<ChannelDrawerRow>()
        categories.forEach { category ->
            if (category.channels.isEmpty()) return@forEach
            rows += ChannelDrawerRow.CategoryHeader(category.id, category.name)
            category.channels.forEach { channel ->
                rows += ChannelDrawerRow.ChannelItem(
                    channel = channel,
                    isCurrent = currentChannelId != null && channel.id == currentChannelId,
                )
            }
        }
        return rows
    }

    /**
     * 当前播放频道在抽屉中的行下标；没有命中时返回 -1。
     *
     * 用于打开抽屉后自动滚动定位到当前频道。
     */
    fun currentRowIndex(rows: List<ChannelDrawerRow>): Int =
        rows.indexOfFirst { it is ChannelDrawerRow.ChannelItem && it.isCurrent }

    /**
     * 当前播放频道在"仅频道条目"序列中的下标；没有命中时返回 -1。
     */
    fun currentChannelIndex(rows: List<ChannelDrawerRow>): Int =
        rows.filterIsInstance<ChannelDrawerRow.ChannelItem>()
            .indexOfFirst { it.isCurrent }
}
