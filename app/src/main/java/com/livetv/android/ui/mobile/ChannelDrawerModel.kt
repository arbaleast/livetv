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
 * 抽屉行序列的组装逻辑（纯函数）。
 */
object ChannelDrawerModel {

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
