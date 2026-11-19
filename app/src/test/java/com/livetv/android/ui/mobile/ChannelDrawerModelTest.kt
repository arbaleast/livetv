package com.livetv.android.ui.mobile

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页"频道列表"抽屉模型的纯 JVM 单元测试（无 Android 依赖）。
 */
class ChannelDrawerModelTest {

    private fun channel(id: String, name: String = "频道$id") =
        Channel(id = id, name = name, description = "描述$id", streamUrl = "https://example.com/$id.m3u8")

    private fun category(id: String, name: String, channels: List<Channel>) =
        ChannelCategory(id = id, name = name, channels = channels)

    private val categories = listOf(
        category("news", "新闻", listOf(channel("n1"), channel("n2"))),
        category("sports", "体育", listOf(channel("s1"))),
    )

    @Test
    fun `each category contributes a header followed by its channels`() {
        val rows = ChannelDrawerModel.buildRows(categories, currentChannelId = null)

        assertEquals(5, rows.size)
        assertEquals(ChannelDrawerRow.CategoryHeader("news", "新闻"), rows[0])
        assertEquals(ChannelDrawerRow.ChannelItem(channel("n1"), isCurrent = false), rows[1])
        assertEquals(ChannelDrawerRow.ChannelItem(channel("n2"), isCurrent = false), rows[2])
        assertEquals(ChannelDrawerRow.CategoryHeader("sports", "体育"), rows[3])
        assertEquals(ChannelDrawerRow.ChannelItem(channel("s1"), isCurrent = false), rows[4])
    }

    @Test
    fun `empty categories are skipped entirely`() {
        val rows = ChannelDrawerModel.buildRows(
            listOf(
                category("empty", "空分类", emptyList()),
                category("news", "新闻", listOf(channel("n1"))),
            ),
            currentChannelId = null,
        )

        assertEquals(2, rows.size)
        assertEquals(ChannelDrawerRow.CategoryHeader("news", "新闻"), rows[0])
        assertEquals(ChannelDrawerRow.ChannelItem(channel("n1"), isCurrent = false), rows[1])
    }

    @Test
    fun `the current channel is marked`() {
        val rows = ChannelDrawerModel.buildRows(categories, currentChannelId = "n2")

        val marked = rows.filterIsInstance<ChannelDrawerRow.ChannelItem>().filter { it.isCurrent }
        assertEquals(1, marked.size)
        assertEquals("n2", marked[0].channel.id)
    }

    @Test
    fun `no channel is marked when the current id is unknown`() {
        val rows = ChannelDrawerModel.buildRows(categories, currentChannelId = "missing")

        assertTrue(rows.filterIsInstance<ChannelDrawerRow.ChannelItem>().none { it.isCurrent })
    }

    @Test
    fun `current row index points at the marked channel`() {
        val rows = ChannelDrawerModel.buildRows(categories, currentChannelId = "s1")

        assertEquals(4, ChannelDrawerModel.currentRowIndex(rows))
    }

    @Test
    fun `current channel index counts only channel entries`() {
        val rows = ChannelDrawerModel.buildRows(categories, currentChannelId = "s1")

        // n1、n2、s1 三个频道条目中的第 3 个
        assertEquals(2, ChannelDrawerModel.currentChannelIndex(rows))
    }

    @Test
    fun `index helpers return minus one when nothing matches`() {
        val rows = ChannelDrawerModel.buildRows(categories, currentChannelId = "missing")

        assertEquals(-1, ChannelDrawerModel.currentRowIndex(rows))
        assertEquals(-1, ChannelDrawerModel.currentChannelIndex(rows))
    }
}
