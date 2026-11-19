package com.livetv.android.ui.mobile

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
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
    fun `phone width uses two columns`() {
        assertEquals(2, MobileChannelList.gridSpanCount(360))
        assertEquals(2, MobileChannelList.gridSpanCount(411))
    }

    @Test
    fun `tablet width uses three columns`() {
        assertEquals(3, MobileChannelList.gridSpanCount(600))
        assertEquals(3, MobileChannelList.gridSpanCount(839))
    }

    @Test
    fun `large tablet width uses four columns`() {
        assertEquals(4, MobileChannelList.gridSpanCount(840))
        assertEquals(4, MobileChannelList.gridSpanCount(1280))
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
    fun `landscape phone display density yields three columns after conversion`() {
        // 2780px @ 3.5x = 794dp -> 横屏三列（不是四列）
        val widthDp = MobileChannelList.pxToDp(2780, 3.5f)
        assertEquals(3, MobileChannelList.gridSpanCount(widthDp))
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

    // ---------------- 副标题兜底 ----------------

    @Test
    fun `subtitle prefers the description`() {
        assertEquals("描述n1", MobileChannelList.formatChannelSubtitle(channel("n1")))
    }

    @Test
    fun `subtitle falls back to the stream url stem when description is blank`() {
        val blank = Channel(id = "cctv1", name = "CCTV-1 综合", description = "  ", streamUrl = "https://example.com/live/cctv1.m3u8")
        assertEquals("cctv1", MobileChannelList.formatChannelSubtitle(blank))
    }

    @Test
    fun `subtitle falls back to the channel id when there is neither description nor url`() {
        val bare = Channel(id = "x1", name = "频道", description = "", streamUrl = "")
        assertEquals("x1", MobileChannelList.formatChannelSubtitle(bare))
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
}
