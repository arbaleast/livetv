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

    @Test
    fun `filter chips merge fragmented categories prepend All and hide empty Other`() {
        val raw = listOf(
            category("empty", "空分组", emptyList()),
            category("cctv:general", "综合·新闻", listOf(channel("cctv:cctv1", "CCTV-1 综合"))),
            category("cctv:sports", "体育·少儿", listOf(channel("cctv:cctv5", "CCTV-5 体育"))),
            category("mgtv:sat", "卫视台", listOf(channel("mgtv:287", "金鹰卡通"))),
            category("mgtv:local", "地方台", listOf(channel("mgtv:280", "湖南经视"))),
        )

        val chips = ChannelDrawerModel.buildFilterChips(
            categories = raw,
            selectedCategoryId = null,
        )

        assertEquals(3, chips.size)
        assertEquals(ChannelDrawerFilterChip(null, "全部", selected = true), chips[0])
        assertEquals(
            ChannelDrawerFilterChip(MobileChannelCategoryPolicy.ID_CCTV, MobileChannelCategoryPolicy.NAME_CCTV, selected = false),
            chips[1],
        )
        assertEquals(
            ChannelDrawerFilterChip(MobileChannelCategoryPolicy.ID_SATELLITE, MobileChannelCategoryPolicy.NAME_SATELLITE, selected = false),
            chips[2],
        )
    }

    @Test
    fun `filter chips fall back to All when selected category disappears`() {
        val chips = ChannelDrawerModel.buildFilterChips(
            categories = categories,
            selectedCategoryId = "removed",
        )

        assertTrue(chips.first().selected)
        assertEquals(null, chips.first().categoryId)
        assertTrue(chips.drop(1).none { it.selected })
    }

    @Test
    fun `filtered rows show single merged category channels preserving current highlight`() {
        val raw = listOf(
            category("cctv:general", "综合·新闻", listOf(channel("cctv:cctv1", "CCTV-1 综合"))),
            category("mgtv:sat", "卫视台", listOf(channel("mgtv:287", "金鹰卡通"))),
            category("custom:other", "地方台", listOf(channel("custom:x1", "测试地方台"))),
        )

        val rows = ChannelDrawerModel.buildFilteredRows(
            categories = raw,
            currentChannelId = "mgtv:287",
            selectedCategoryId = MobileChannelCategoryPolicy.ID_SATELLITE,
        )

        assertEquals(1, rows.size)
        assertEquals(ChannelDrawerRow.ChannelItem(channel("mgtv:287", "金鹰卡通"), isCurrent = true), rows[0])
        assertEquals(0, ChannelDrawerModel.currentRowIndex(rows))
    }

    @Test
    fun `filtered rows fall back to all merged grouped rows when selection is null or unknown`() {
        val raw = listOf(
            category("cctv:general", "综合·新闻", listOf(channel("cctv:cctv1", "CCTV-1 综合"))),
            category("cctv:sports", "体育·少儿", listOf(channel("cctv:cctv5", "CCTV-5 体育"))),
            category("mgtv:sat", "卫视台", listOf(channel("mgtv:287", "金鹰卡通"))),
        )

        val allRows = ChannelDrawerModel.buildFilteredRows(
            categories = raw,
            currentChannelId = "cctv:cctv5",
            selectedCategoryId = null,
        )
        // 2 个合并分类头（央视 + 卫视·芒果）+ 3 个频道 = 5 行
        assertEquals(5, allRows.size)
        assertEquals(
            ChannelDrawerRow.CategoryHeader(MobileChannelCategoryPolicy.ID_CCTV, MobileChannelCategoryPolicy.NAME_CCTV),
            allRows[0],
        )
        assertEquals(2, ChannelDrawerModel.currentRowIndex(allRows))

        val fallbackRows = ChannelDrawerModel.buildFilteredRows(
            categories = raw,
            currentChannelId = "cctv:cctv5",
            selectedCategoryId = "nonexistent",
        )
        assertEquals(allRows, fallbackRows)
    }

    @Test
    fun `filtered rows and chips handle completely empty catalog safely`() {
        val chips = ChannelDrawerModel.buildFilterChips(emptyList(), selectedCategoryId = null)
        val rows = ChannelDrawerModel.buildFilteredRows(emptyList(), currentChannelId = "n1", selectedCategoryId = null)

        assertEquals(1, chips.size)
        assertEquals(ChannelDrawerFilterChip(null, "全部", selected = true), chips[0])
        assertTrue(rows.isEmpty())
    }
}
