package com.livetv.android.ui.mobile

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机端列表组装与网格列数的纯 JVM 单元测试（无 Android 依赖）。
 */
class MobileChannelListTest {

    private fun channel(id: String, name: String = "频道$id") =
        Channel(id = id, name = name, description = "描述$id", streamUrl = "https://example.com/$id.m3u8")

    private fun category(id: String, name: String, channels: List<Channel>) =
        ChannelCategory(id = id, name = name, channels = channels)

    @Test
    fun `each category contributes a header followed by its channels`() {
        val rows = MobileChannelList.buildRows(
            listOf(
                category("news", "新闻", listOf(channel("n1"), channel("n2"))),
                category("sports", "体育", listOf(channel("s1"))),
            ),
        )

        assertEquals(5, rows.size)
        assertEquals(
            MobileRow.CategoryHeader(categoryId = "news", categoryName = "新闻", channelCount = 2),
            rows[0],
        )
        assertEquals(MobileRow.ChannelCard(channel("n1"), "news", "新闻"), rows[1])
        assertEquals(MobileRow.ChannelCard(channel("n2"), "news", "新闻"), rows[2])
        assertEquals(
            MobileRow.CategoryHeader(categoryId = "sports", categoryName = "体育", channelCount = 1),
            rows[3],
        )
        assertEquals(MobileRow.ChannelCard(channel("s1"), "sports", "体育"), rows[4])
    }

    @Test
    fun `empty categories are skipped entirely`() {
        val rows = MobileChannelList.buildRows(
            listOf(
                category("empty", "空分类", emptyList()),
                category("news", "新闻", listOf(channel("n1"))),
            ),
        )

        assertEquals(2, rows.size)
        assertTrue(rows[0] is MobileRow.CategoryHeader)
        assertEquals("news", (rows[0] as MobileRow.CategoryHeader).categoryId)
    }

    @Test
    fun `no categories produce no rows`() {
        assertEquals(emptyList<MobileRow>(), MobileChannelList.buildRows(emptyList()))
    }

    @Test
    fun `category order and channel order are preserved`() {
        val rows = MobileChannelList.buildRows(
            listOf(
                category("c1", "一", listOf(channel("a"), channel("b"))),
                category("c2", "二", listOf(channel("c"))),
            ),
        )

        val channelIds = rows.filterIsInstance<MobileRow.ChannelCard>().map { it.channel.id }
        assertEquals(listOf("a", "b", "c"), channelIds)
    }

    @Test
    fun `normal portrait width uses two readable columns`() {
        // 360dp 是仍能容纳两张可读卡片的下界：360/190=1 → 下限 2 列
        assertEquals(2, MobileChannelList.gridSpanCount(360))
        assertEquals(2, MobileChannelList.gridSpanCount(411))
    }

    @Test
    fun `narrow screens below 360dp fall back to a single column`() {
        // 320dp 竖屏 + 左右 padding 后已放不下两张可读卡片，降为 1 列避免频道名被压成十几个像素
        assertEquals(1, MobileChannelList.gridSpanCount(320))
        assertEquals(1, MobileChannelList.gridSpanCount(359))
        // 尚未测量完成（<= 0）时同样按最保守的单列处理，避免先渲染出挤压的 2 列再抖动
        assertEquals(1, MobileChannelList.gridSpanCount(1))
        assertEquals(1, MobileChannelList.gridSpanCount(0))
        assertEquals(1, MobileChannelList.gridSpanCount(-1))
    }

    @Test
    fun `column count boundaries around narrow and wide breakpoints`() {
        // 359→1 列、360→2 列是硬边界；600/900 为横屏常见宽度；1280 为超宽屏封顶
        assertEquals(1, MobileChannelList.gridSpanCount(359))
        assertEquals(2, MobileChannelList.gridSpanCount(360))
        assertEquals(3, MobileChannelList.gridSpanCount(600))
        assertEquals(4, MobileChannelList.gridSpanCount(900))
        assertEquals(5, MobileChannelList.gridSpanCount(1280))
        assertEquals(5, MobileChannelList.gridSpanCount(2000))
    }

    @Test
    fun `columns grow with the available width instead of fixed breakpoints`() {
        // 190dp 单卡最小宽度：600/190=3、839/190=4、1280/190=6 → 上限 5
        assertEquals(3, MobileChannelList.gridSpanCount(600))
        assertEquals(4, MobileChannelList.gridSpanCount(839))
        assertEquals(4, MobileChannelList.gridSpanCount(840))
        assertEquals(5, MobileChannelList.gridSpanCount(1280))
    }

    @Test
    fun `pixels are converted to dp by dividing by density`() {
        // 1080px / 3.0 = 360dp
        assertEquals(360, MobileChannelList.pxToDp(1080, 3.0f))
        // 2640px / 3.5 = 754dp
        assertEquals(754, MobileChannelList.pxToDp(2640, 3.5f))
        // 2780px / 3.5 = 794dp
        assertEquals(794, MobileChannelList.pxToDp(2780, 3.5f))
        // 1264px / 3.5 = 361dp
        assertEquals(361, MobileChannelList.pxToDp(1264, 3.5f))
    }

    @Test
    fun `non positive density falls back to raw pixels`() {
        assertEquals(1080, MobileChannelList.pxToDp(1080, 0f))
        assertEquals(1080, MobileChannelList.pxToDp(1080, -1f))
    }

    @Test
    fun `phone display density yields two columns after conversion`() {
        // 1264px @ 3.5x = 361dp -> 手机竖屏两列
        val widthDp = MobileChannelList.pxToDp(1264, 3.5f)
        assertEquals(2, MobileChannelList.gridSpanCount(widthDp))
    }

    @Test
    fun `landscape phone display density yields four columns after conversion`() {
        // 2780px @ 3.5x = 794dp -> 794/190=4 列（按单卡最小宽度动态算）
        val widthDp = MobileChannelList.pxToDp(2780, 3.5f)
        assertEquals(4, MobileChannelList.gridSpanCount(widthDp))
    }

    // ---------------- 徽章简标 ----------------

    @Test
    fun `badge uses first letter and digits for alphanumeric channel names`() {
        assertEquals("C1", MobileChannelList.extractChannelBadge("CCTV-1 综合"))
        assertEquals("C13", MobileChannelList.extractChannelBadge("CCTV-13 新闻"))
        assertEquals("C1", MobileChannelList.extractChannelBadge("CCTV1"))
        assertEquals("C5", MobileChannelList.extractChannelBadge("  CCTV-5  "))
    }

    @Test
    fun `badge uses first two chinese characters when there is no alphanumeric prefix`() {
        assertEquals("综合", MobileChannelList.extractChannelBadge("综合新闻"))
        assertEquals("湖南", MobileChannelList.extractChannelBadge("湖南卫视"))
    }

    @Test
    fun `badge uses first two latin letters for western channel names`() {
        assertEquals("ES", MobileChannelList.extractChannelBadge("ESPN HD"))
        assertEquals("SK", MobileChannelList.extractChannelBadge("Sky Sports"))
    }

    @Test
    fun `badge falls back to TV when nothing can be extracted`() {
        // 空名与纯符号名都取不出有效字符，必须兜底成 TV，避免卡片上出现空方块
        assertEquals("TV", MobileChannelList.extractChannelBadge(""))
        assertEquals("TV", MobileChannelList.extractChannelBadge("   "))
        assertEquals("TV", MobileChannelList.extractChannelBadge("★"))
        assertEquals(MobileChannelList.FALLBACK_BADGE, MobileChannelList.extractChannelBadge("—"))
    }

    // ---------------- 分类筛选 ----------------

    @Test
    fun `filtering by null keeps the grouped view with category headers`() {
        val categories = listOf(
            category("news", "新闻", listOf(channel("n1"), channel("n2"))),
            category("sports", "体育", listOf(channel("s1"))),
        )
        val rows = MobileChannelList.filterRows(categories, null)

        assertEquals(5, rows.size)
        assertTrue(rows[0] is MobileRow.CategoryHeader)
        assertEquals(
            MobileRow.CategoryHeader(categoryId = "news", categoryName = "新闻", channelCount = 2),
            rows[0],
        )
    }

    @Test
    fun `filtering by a category returns only its cards without a redundant header`() {
        val categories = listOf(
            category("news", "新闻", listOf(channel("n1"), channel("n2"))),
            category("sports", "体育", listOf(channel("s1"))),
        )
        val rows = MobileChannelList.filterRows(categories, "sports")

        assertEquals(1, rows.size)
        assertEquals(MobileRow.ChannelCard(channel("s1"), "sports", "体育"), rows[0])
        assertTrue(rows.none { it is MobileRow.CategoryHeader })
    }

    @Test
    fun `filtering by an unknown category returns nothing instead of falling back`() {
        val categories = listOf(category("news", "新闻", listOf(channel("n1"))))

        assertEquals(emptyList<MobileRow>(), MobileChannelList.filterRows(categories, "missing"))
    }

    @Test
    fun `resolveSelectedCategoryId keeps existing non empty category`() {
        val categories = listOf(
            category("news", "新闻", listOf(channel("n1"))),
            category("sports", "体育", listOf(channel("s1"))),
        )

        assertEquals("sports", MobileChannelList.resolveSelectedCategoryId(categories, "sports"))
        assertEquals(null, MobileChannelList.resolveSelectedCategoryId(categories, null))
    }

    @Test
    fun `resolveSelectedCategoryId resets to null when selected category is missing or empty`() {
        val categories = listOf(
            category("news", "新闻", listOf(channel("n1"))),
            category("empty", "空分类", emptyList()),
        )

        assertEquals(null, MobileChannelList.resolveSelectedCategoryId(categories, "missing"))
        assertEquals(null, MobileChannelList.resolveSelectedCategoryId(categories, "empty"))
    }

    // ---------------- 副标题净化（绝不暴露技术字段） ----------------

    @Test
    fun `subtitle prefers a human readable description`() {
        val withProgram = Channel(id = "cctv1", name = "CCTV-1 综合", description = "晚间新闻", streamUrl = "")
        assertEquals("晚间新闻", MobileChannelList.sanitizeSubtitle(withProgram))
    }

    @Test
    fun `subtitle is hidden when description is blank instead of exposing the url stem`() {
        val blank = Channel(
            id = "cctv1",
            name = "CCTV-1 综合",
            description = "  ",
            streamUrl = "https://example.com/live/cctv1.m3u8",
        )
        assertNull("没有有效简介时必须隐藏副标题，而不是回退到 URL", MobileChannelList.sanitizeSubtitle(blank))
    }

    @Test
    fun `subtitle is hidden when description is a technical slug`() {
        listOf("cctv13", "mgtv:287", "news_1", "cctv-1", "12345").forEach { technical ->
            val channel = Channel(id = "x", name = "频道", description = technical, streamUrl = "")
            assertNull("技术字段不应作为副标题：$technical", MobileChannelList.sanitizeSubtitle(channel))
        }
    }

    @Test
    fun `subtitle is hidden when it merely repeats the channel name`() {
        val channel = Channel(id = "mgtv:287", name = "金鹰卡通", description = "金鹰卡通", streamUrl = "")
        assertNull(MobileChannelList.sanitizeSubtitle(channel))
    }

    @Test
    fun `subtitle is hidden when description contains a url`() {
        val channel = Channel(id = "x", name = "频道", description = "https://cdn.example.invalid/a.m3u8", streamUrl = "")
        assertNull(MobileChannelList.sanitizeSubtitle(channel))
    }

    @Test
    fun `technical text detection keeps human readable strings`() {
        assertFalse(MobileChannelList.isTechnicalText("24 小时滚动"))
        assertFalse(MobileChannelList.isTechnicalText("市场与政策"))
        assertTrue(MobileChannelList.isTechnicalText("mgtv:344"))
        assertTrue(MobileChannelList.isTechnicalText("  "))
    }

    // ---------------- 序号与强调色 ----------------

    @Test
    fun `channel index is zero padded`() {
        assertEquals("01", MobileChannelList.formatChannelIndex(1))
        assertEquals("09", MobileChannelList.formatChannelIndex(9))
        assertEquals("10", MobileChannelList.formatChannelIndex(10))
        assertEquals("00", MobileChannelList.formatChannelIndex(0))
        assertEquals("00", MobileChannelList.formatChannelIndex(-5))
    }

    @Test
    fun `category accent index is stable and inside the palette range`() {
        val first = MobileChannelList.categoryAccentIndex("新闻")
        val second = MobileChannelList.categoryAccentIndex("新闻")
        assertEquals(first, second)
        assertTrue(first in 0 until MobileChannelList.BADGE_ACCENT_COUNT)
        assertTrue(MobileChannelList.categoryAccentIndex("卫视") in 0 until MobileChannelList.BADGE_ACCENT_COUNT)
        assertEquals(0, MobileChannelList.categoryAccentIndex(""))
    }

    @Test
    fun `the shipped categories get distinct accent colors`() {
        // 三个随包分类必须落到不同色号，否则徽章会全都一个颜色
        val news = MobileChannelList.categoryAccentIndex("新闻")
        val sports = MobileChannelList.categoryAccentIndex("体育")
        val satellite = MobileChannelList.categoryAccentIndex("卫视")

        assertEquals(0, news)
        assertEquals(3, sports)
        assertEquals(5, satellite)
    }
    // ---------------- 左侧分类导航栏 ----------------

    @Test
    fun `rail items start with an all entry carrying the total count`() {
        val categories = listOf(
            category("news", "新闻", listOf(channel("n1"), channel("n2"))),
            category("sports", "体育", listOf(channel("s1"))),
        )

        val rail = MobileChannelList.buildRailItems(categories, selectedCategoryId = null)

        assertEquals(3, rail.size)
        assertEquals(null, rail[0].id)
        assertEquals(MobileChannelList.ALL_CATEGORY_NAME, rail[0].name)
        assertEquals(3, rail[0].count)
        assertTrue(rail[0].selected)
    }

    @Test
    fun `rail skips empty categories and marks the selected one`() {
        val categories = listOf(
            category("news", "新闻", listOf(channel("n1"))),
            category("empty", "空分类", emptyList()),
            category("sports", "体育", listOf(channel("s1"), channel("s2"))),
        )

        val rail = MobileChannelList.buildRailItems(categories, selectedCategoryId = "sports")

        assertEquals(listOf(null, "news", "sports"), rail.map { it.id })
        assertEquals(2, rail.last().count)
        assertTrue(rail.last().selected)
        assertFalse(rail[0].selected)
    }

    // ---------------- 响应式导航形态（竖屏顶部条 vs 横屏左侧栏） ----------------

    @Test
    fun `narrow portrait widths use top bar navigation to preserve grid width`() {
        assertEquals(MobileChannelList.NavigationMode.TOP_BAR, MobileChannelList.resolveNavigationMode(0))
        assertEquals(MobileChannelList.NavigationMode.TOP_BAR, MobileChannelList.resolveNavigationMode(360))
        assertEquals(MobileChannelList.NavigationMode.TOP_BAR, MobileChannelList.resolveNavigationMode(411))
        assertEquals(MobileChannelList.NavigationMode.TOP_BAR, MobileChannelList.resolveNavigationMode(599))
    }

    @Test
    fun `wide landscape and tablet widths use left rail navigation`() {
        assertEquals(MobileChannelList.NavigationMode.LEFT_RAIL, MobileChannelList.resolveNavigationMode(600))
        assertEquals(MobileChannelList.NavigationMode.LEFT_RAIL, MobileChannelList.resolveNavigationMode(794))
        assertEquals(MobileChannelList.NavigationMode.LEFT_RAIL, MobileChannelList.resolveNavigationMode(1280))
    }

    // ---------------- D-Pad UP 首行边界判定 ----------------

    @Test
    fun `isInTopGridRow identifies only the first row in filtered flat grid`() {
        val rows = listOf(
            MobileRow.ChannelCard(channel("1"), "c", "分类"),
            MobileRow.ChannelCard(channel("2"), "c", "分类"),
            MobileRow.ChannelCard(channel("3"), "c", "分类"),
            MobileRow.ChannelCard(channel("4"), "c", "分类"),
            MobileRow.ChannelCard(channel("5"), "c", "分类"),
        )

        assertTrue(MobileChannelList.isInTopGridRow(rows, position = 0, spanCount = 3))
        assertTrue(MobileChannelList.isInTopGridRow(rows, position = 1, spanCount = 3))
        assertTrue(MobileChannelList.isInTopGridRow(rows, position = 2, spanCount = 3))
        assertFalse(MobileChannelList.isInTopGridRow(rows, position = 3, spanCount = 3))
        assertFalse(MobileChannelList.isInTopGridRow(rows, position = 4, spanCount = 3))
    }

    @Test
    fun `isInTopGridRow stops at category boundary in grouped view`() {
        val rows = MobileChannelList.buildRows(
            listOf(
                category("c1", "一", listOf(channel("1"), channel("2"))),
                category("c2", "二", listOf(channel("3"), channel("4"))),
            ),
        )
        // [H(c1), C(1), C(2), H(c2), C(3), C(4)]，4 列网格下 C(1)、C(2) 在首行，C(3) 属于第二分组不在首行
        assertFalse(MobileChannelList.isInTopGridRow(rows, position = 0, spanCount = 4))
        assertTrue(MobileChannelList.isInTopGridRow(rows, position = 1, spanCount = 4))
        assertTrue(MobileChannelList.isInTopGridRow(rows, position = 2, spanCount = 4))
        assertFalse(MobileChannelList.isInTopGridRow(rows, position = 3, spanCount = 4))
        assertFalse(MobileChannelList.isInTopGridRow(rows, position = 4, spanCount = 4))
        assertFalse(MobileChannelList.isInTopGridRow(rows, position = 5, spanCount = 4))
    }

    @Test
    fun `isInTopGridRow handles grouped category with more cards than spanCount and invalid inputs`() {
        val rows = MobileChannelList.buildRows(
            listOf(
                category("c1", "一", listOf(channel("1"), channel("2"), channel("3"), channel("4"), channel("5"))),
            ),
        )
        // [H(c1), C(1), C(2), C(3), C(4), C(5)]，spanCount = 4 -> 位置 1..4 为首行，位置 5 为第二行
        assertTrue(MobileChannelList.isInTopGridRow(rows, position = 1, spanCount = 4))
        assertTrue(MobileChannelList.isInTopGridRow(rows, position = 4, spanCount = 4))
        assertFalse(MobileChannelList.isInTopGridRow(rows, position = 5, spanCount = 4))
        assertFalse(MobileChannelList.isInTopGridRow(rows, position = -1, spanCount = 4))
        assertFalse(MobileChannelList.isInTopGridRow(rows, position = 6, spanCount = 4))
        assertFalse(MobileChannelList.isInTopGridRow(rows, position = 1, spanCount = 0))
        assertFalse(MobileChannelList.isInTopGridRow(emptyList(), position = 0, spanCount = 2))
    }

    // ---------------- 本地收藏虚拟分组 ----------------

    @Test
    fun `prependFavoritesCategory inserts deduplicated favorites category first`() {
        val ch1 = channel("mgtv:1", "金鹰卡通")
        val ch2 = channel("mgtv:2", "湖南卫视")
        val categories = listOf(
            category("hot", "热门", listOf(ch1, ch2)),
            category("sat", "卫视台", listOf(ch1)), // 与热门重复的频道 id
        )

        val result = MobileChannelList.prependFavoritesCategory(categories, setOf("mgtv:1"))

        assertEquals(listOf(MobileChannelList.FAVORITE_CATEGORY_ID, "hot", "sat"), result.map { it.id })
        assertEquals(MobileChannelList.FAVORITE_CATEGORY_NAME, result[0].name)
        assertEquals(listOf("mgtv:1"), result[0].channels.map { it.id })
    }

    @Test
    fun `prependFavoritesCategory keeps categories unchanged when no favorites match`() {
        val categories = listOf(category("hot", "热门", listOf(channel("c1"))))

        assertEquals(categories, MobileChannelList.prependFavoritesCategory(categories, emptySet()))
        assertEquals(categories, MobileChannelList.prependFavoritesCategory(categories, setOf("nonexistent")))
    }

    // ---------------- 实时搜索过滤 ----------------

    @Test
    fun `searchRows matches channel name, sanitized description, and category name case-insensitively and deduplicates`() {
        val cctv = Channel(id = "c1", name = "CCTV-5 体育", description = "欧冠精选", streamUrl = "https://a.m3u8")
        val cartoon = Channel(id = "m1", name = "金鹰卡通", description = "mgtv:287", streamUrl = "")
        val categories = listOf(
            category("hot", "热门", listOf(cartoon, cctv)),
            category("sat", "卫视台", listOf(cartoon)),
        )

        // 忽略大小写匹配频道名
        val byName = MobileChannelList.searchRows(categories, "cctv-5")
        assertEquals(1, byName.size)
        assertEquals("c1", (byName[0] as MobileRow.ChannelCard).channel.id)

        // 匹配重复出现的频道应按 id 去重
        val dedup = MobileChannelList.searchRows(categories, "金鹰")
        assertEquals(1, dedup.size)
        assertEquals("m1", (dedup[0] as MobileRow.ChannelCard).channel.id)

        // 匹配有效节目描述，但不匹配技术字段 mgtv:287
        assertEquals(1, MobileChannelList.searchRows(categories, "欧冠").size)
        assertEquals(emptyList<MobileRow>(), MobileChannelList.searchRows(categories, "mgtv:287"))

        // 匹配分类名
        val byCat = MobileChannelList.searchRows(categories, "卫视")
        assertEquals(1, byCat.size)
        assertEquals("m1", (byCat[0] as MobileRow.ChannelCard).channel.id)

        // 空白搜索词返回空列表
        assertEquals(emptyList<MobileRow>(), MobileChannelList.searchRows(categories, "   "))
    }

}
