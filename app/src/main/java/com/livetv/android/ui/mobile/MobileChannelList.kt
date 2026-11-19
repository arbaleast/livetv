package com.livetv.android.ui.mobile

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory

/**
 * 手机端浏览列表的**纯数据模型**。
 *
 * 手机端用 `RecyclerView` + `GridLayoutManager` 展示频道：分类标题占满整行，
 * 频道卡片按网格排列。为了让这部分逻辑可被纯 JVM 单元测试覆盖，
 * 这里刻意**不引入任何 Android 类型**（无 `Context`、无 View）。
 */
sealed class MobileRow {

    /** 分类标题行：跨满整行宽度。 */
    data class CategoryHeader(
        val categoryId: String,
        val categoryName: String,
        val channelCount: Int,
    ) : MobileRow()

    /** 频道卡片：占一格。 */
    data class ChannelCard(val channel: Channel) : MobileRow()
}

/**
 * 手机端列表的组装与网格列数计算（纯函数，无 Android 依赖）。
 */
object MobileChannelList {

    /**
     * 把分类列表展开成"标题 + 卡片"的行序列。
     *
     * 保留分类顺序与分类内频道顺序；**空分类直接跳过**（不产生孤零零的标题）。
     */
    fun buildRows(categories: List<ChannelCategory>): List<MobileRow> {
        val rows = mutableListOf<MobileRow>()
        categories.forEach { category ->
            if (category.channels.isEmpty()) return@forEach
            rows += MobileRow.CategoryHeader(
                categoryId = category.id,
                categoryName = category.name,
                channelCount = category.channels.size,
            )
            category.channels.forEach { channel ->
                rows += MobileRow.ChannelCard(channel)
            }
        }
        return rows
    }

    /**
     * 根据可用宽度（dp）决定网格列数。
     *
     * - `>= 840dp`（大平板横屏）：4 列
     * - `>= 600dp`（平板）：3 列
     * - 其余（手机竖屏 / 横屏）：2 列
     *
     * 列数下限为 2：单列在手机上会让每张卡片拉得过宽、滚动过长。
     */
    fun gridSpanCount(screenWidthDp: Int): Int = when {
        screenWidthDp >= 840 -> 4
        screenWidthDp >= 600 -> 3
        else -> 2
    }

    /**
     * 像素转 dp（纯函数，可单测）。
     *
     * 注意：**不要**用 `TypedValue.applyDimension(COMPLEX_UNIT_DIP, px, metrics)` 做这件事——
     * 那个 API 的方向是 dp → px，用它处理 px 会再乘一次 density，把宽度放大。
     *
     * @param px 像素宽度
     * @param density 屏幕密度（`densityDpi / 160f`），非正数时按 1 处理
     */
    fun pxToDp(px: Int, density: Float): Int {
        if (density <= 0f) return px
        return (px / density).toInt()
    }
}
