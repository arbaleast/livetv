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
        assertEquals(MobileRow.ChannelCard(channel("n1")), rows[1])
        assertEquals(MobileRow.ChannelCard(channel("n2")), rows[2])
        assertEquals(
            MobileRow.CategoryHeader(categoryId = "sports", categoryName = "体育", channelCount = 1),
            rows[3],
        )
        assertEquals(MobileRow.ChannelCard(channel("s1")), rows[4])
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
}
