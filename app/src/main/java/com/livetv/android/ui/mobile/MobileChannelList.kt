package com.livetv.android.ui.mobile

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import java.util.Locale

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
    data class ChannelCard(
        val channel: Channel,
        val categoryId: String = "",
        val categoryName: String = "",
    ) : MobileRow()
}

/**
 * 手机端列表的组装与网格列数计算（纯函数，无 Android 依赖）。
 */
object MobileChannelList {

    /**
     * 分类徽章可用的强调色数量。
     *
     * 颜色本身定义在 `res/values/colors.xml` 的 `mobile_badge_accents` 数组里，
     * 这里只负责**稳定地**把一个分类映射到其中一个下标。
     */
    const val BADGE_ACCENT_COUNT = 6

    /** 形如 `CCTV-13`、`CCTV1` 的"字母+数字"前缀，用于生成 `C1` / `C13` 这类徽章。 */
    private val ALPHANUMERIC_PREFIX = Regex("^[A-Za-z]+[-]?\\d+")

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
                rows += MobileRow.ChannelCard(channel, category.id, category.name)
            }
        }
        return rows
    }

    /**
     * 按选中的分类过滤行序列。
     *
     * - `selectedCategoryId == null`：等价于 [buildRows]，返回**全部分组视图**（带分类标题）。
     * - 指定分类：只返回该分类的频道卡片，**不再重复渲染分类标题**（筛选态下标题已在顶部胶囊栏里）。
     *
     * 分类 id 匹配不到时返回空列表（而不是退回全部分组，避免"点了胶囊却看到别的内容"）。
     */
    fun filterRows(categories: List<ChannelCategory>, selectedCategoryId: String?): List<MobileRow> {
        if (selectedCategoryId == null) return buildRows(categories)
        val target = categories.firstOrNull { it.id == selectedCategoryId } ?: return emptyList()
        return target.channels.map { MobileRow.ChannelCard(it, target.id, target.name) }
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

    /** 像素 → dp。`density` 非正数时退回原始像素，避免除零。 */
    fun pxToDp(px: Int, density: Float): Int {
        if (density <= 0f) return px
        return (px / density).toInt()
    }

    /**
     * 从频道名生成 1～3 字的徽章简标。
     *
     * - `CCTV-1 综合` → `C1`
     * - `CCTV-13 新闻` → `C13`
     * - `综合新闻` → `综合`
     * - `湖南卫视` → `湖南`
     * - `ESPN HD` → `ES`
     * - 空串 → 空串（调用方负责隐藏徽章）
     */
    fun extractChannelBadge(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""

        ALPHANUMERIC_PREFIX.find(trimmed)?.let { match ->
            val token = match.value
            val letters = token.takeWhile { it.isLetter() }
            val digits = token.dropWhile { it.isLetter() }.filter { it.isDigit() }
            val badge = letters.take(1).uppercase(Locale.US) + digits
            if (badge.isNotEmpty()) return badge
        }

        val cjk = trimmed.filter { it.code in 0x4E00..0x9FFF }
        if (cjk.isNotEmpty()) return cjk.take(2)

        val latin = trimmed.filter { it.isLetter() }
        if (latin.isNotEmpty()) return latin.take(2).uppercase(Locale.US)

        return ""
    }

    /**
     * 把分类名稳定地映射到 [BADGE_ACCENT_COUNT] 个强调色之一。
     *
     * 同一分类**永远**得到同一个颜色（与它在列表里的位置、分类总数无关）。
     * 采用"位置上加权的码点和"，而不是 FNV/Long 散列：短的中文分类名在 64 位散列下
     * 极易撞到同一个桶（`新闻` 与 `体育` 实测撞成同一色），加权和能把它们区分开。
     */
    fun categoryAccentIndex(categoryName: String): Int {
        if (categoryName.isEmpty()) return 0
        var weighted = 0L
        categoryName.forEachIndexed { position, char ->
            weighted += char.code.toLong() * (position + 1)
        }
        val remainder = weighted % BADGE_ACCENT_COUNT
        return ((remainder + BADGE_ACCENT_COUNT) % BADGE_ACCENT_COUNT).toInt()
    }

    /**
     * 频道序号格式化为两位数（`01`、`02`…），超过 99 时自然变长。
     */
    fun formatChannelIndex(index: Int): String =
        String.format(Locale.US, "%02d", index.coerceAtLeast(0))

    /**
     * 频道副标题：有描述用描述；没有描述时退回**来源标签**（流地址的文件名主干），
     * 再不行退回频道 id——保证卡片副标题行永远有内容，行高一致、网格不参差。
     *
     * M3U 播放列表没有描述字段，`sample.m3u` 里的频道全部走这条兜底路径。
     */
    fun formatChannelSubtitle(channel: Channel): String {
        val description = channel.description.trim()
        if (description.isNotEmpty()) return description

        val segment = channel.streamUrl.substringAfterLast('/').substringBefore('?').trim()
        val stem = segment.substringBeforeLast('.', segment).trim()
        if (stem.isNotEmpty()) return stem

        return channel.id.trim()
    }
}
