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

    /** 频道名里提取不出任何有效字符时的徽章兜底。 */
    const val FALLBACK_BADGE = "TV"

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
     * 校验当前选中的分类 id 在新的分类列表里是否仍然有效。
     *
     * 刷新或切换订阅源后，旧分类可能不再存在或变空；若直接保留旧 id 会落入“总频道非空但列表为空”的假空态。
     * 此时自动回退到 `null`（全部）。
     */
    fun resolveSelectedCategoryId(
        categories: List<ChannelCategory>,
        selectedCategoryId: String?,
    ): String? {
        if (selectedCategoryId == null) return null
        val exists = categories.any { it.id == selectedCategoryId && it.channels.isNotEmpty() }
        return if (exists) selectedCategoryId else null
    }

    /**
     * 根据可用宽度（dp）计算网格列数：**不再写死阈值**，而是按"单卡最小期望宽度"除出来。
     *
     * - 单卡最小宽度 [MIN_CARD_WIDTH_DP] = 190dp（横屏常见 780–900dp 可用宽度 → 4 列）
     * - 下限 2 列（手机竖屏 1 列会让卡片过宽）、上限 5 列（避免超宽屏出现"细条"卡片）
     */
    fun gridSpanCount(screenWidthDp: Int): Int {
        if (screenWidthDp <= 0) return 2
        return (screenWidthDp / MIN_CARD_WIDTH_DP).coerceIn(2, MAX_SPAN_COUNT)
    }

    /** 单卡最小期望宽度（dp），用于列数计算。 */
    const val MIN_CARD_WIDTH_DP = 190

    /** 网格列数上限。 */
    const val MAX_SPAN_COUNT = 5

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
     * - 取不出任何有效字符 → `TV`（兜底，避免卡片上出现空方块）
     */
    fun extractChannelBadge(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return FALLBACK_BADGE

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

        return FALLBACK_BADGE
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
     * 频道副标题（**绝不暴露技术字段**）。
     *
     * 返回 `null` 表示"没有值得展示的辅助信息"，调用方应隐藏副标题行并让主标题垂直居中。
     *
     * 展示优先级：
     * 1. 有效的节目简介 / EPG 文案；
     * 2. 否则隐藏（**不再回退到 URL 文件名或频道 ID**）。
     *
     * 判定为"技术字段"的情形：纯 ASCII 的 slug/ID（`cctv13`、`mgtv:287`、`news_1`）、
     * 含 `://` 的地址、纯数字编号。
     */
    fun sanitizeSubtitle(channel: Channel): String? {
        val description = channel.description.trim()
        if (description.isEmpty()) return null
        // 副标题与频道名完全相同属于冗余信息（MGTV 目录里 currentProgram 常等于频道名）
        if (description.equals(channel.name.trim(), ignoreCase = true)) return null
        return description.takeIf { !isTechnicalText(it) }
    }

    /** 判断一段文本是否是用户不该看到的内部技术标识。 */
    fun isTechnicalText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.contains("://")) return true
        if (trimmed.startsWith("mgtv:")) return true
        // 纯 ASCII 的 slug / ID：字母数字加 _ - . : 组合，且不含空格
        if (ASCII_SLUG.matches(trimmed)) return true
        // 纯数字（频道编号之类）
        if (trimmed.all { it.isDigit() }) return true
        return false
    }

    /** 纯 ASCII slug/ID 形态（允许下划线、连字符、点、冒号）。 */
    private val ASCII_SLUG = Regex("^[A-Za-z0-9_.:\\-]+$")                
    /** 左侧分类导航栏的一行：`id == null` 表示"全部"。 */
    data class RailItem(
        val id: String?,
        val name: String,
        val count: Int,
        val selected: Boolean,
    )

    /**
     * 组装左侧分类导航项：第一项固定为"全部"（总数），之后每个**非空**分类一项。
     * 与右侧内容使用同一份分类数据，保证角标数量与网格一致。
     */
    fun buildRailItems(
        categories: List<ChannelCategory>,
        selectedCategoryId: String?,
    ): List<RailItem> {
        val items = mutableListOf<RailItem>()
        val total = categories.sumOf { it.channels.size }
        items += RailItem(
            id = null,
            name = ALL_CATEGORY_NAME,
            count = total,
            selected = selectedCategoryId == null,
        )
        categories.forEach { category ->
            if (category.channels.isEmpty()) return@forEach
            items += RailItem(
                id = category.id,
                name = category.name,
                count = category.channels.size,
                selected = category.id == selectedCategoryId,
            )
        }
        return items
    }

    /** "全部"导航项的名称。 */
    const val ALL_CATEGORY_NAME = "全部"
}
