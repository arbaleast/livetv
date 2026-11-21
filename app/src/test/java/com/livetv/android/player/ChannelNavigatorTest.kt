package com.livetv.android.player

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChannelNavigator` 的纯 JVM 单元测试。
 *
 * 覆盖：按 id / streamUrl 定位、上下切台、首尾循环、单频道、空列表、分类扁平化。
 */
class ChannelNavigatorTest {

    private fun channel(id: String, url: String = "https://example.com/$id.m3u8") =
        Channel(id = id, name = "频道 $id", description = "", streamUrl = url)

    private val channels = listOf(
        channel("c1"),
        channel("c2"),
        channel("c3"),
    )

    @Test
    fun `locates channel by id`() {
        val navigator = ChannelNavigator(channels)

        val found = navigator.locate("c2", null)

        assertTrue(found)
        assertEquals(1, navigator.currentIndex)
        assertEquals("c2", navigator.current()?.id)
    }

    @Test
    fun `falls back to stream url when id is unknown`() {
        val navigator = ChannelNavigator(channels)

        val found = navigator.locate("unknown", "https://example.com/c3.m3u8")

        assertTrue(found)
        assertEquals("c3", navigator.current()?.id)
    }

    @Test
    fun `duplicate stream urls resolve to the first match`() {
        val duplicated = listOf(
            channel("first", "https://example.com/same.m3u8"),
            channel("second", "https://example.com/same.m3u8"),
        )
        val navigator = ChannelNavigator(duplicated)

        navigator.locate(null, "https://example.com/same.m3u8")

        assertEquals(0, navigator.currentIndex)
        assertEquals("first", navigator.current()?.id)
    }

    @Test
    fun `locate returns false when nothing matches`() {
        val navigator = ChannelNavigator(channels)

        val found = navigator.locate("nope", "https://nowhere.example.com/x.m3u8")

        assertFalse(found)
        assertEquals(-1, navigator.currentIndex)
        assertNull(navigator.current())
    }

    @Test
    fun `next walks forward through channels`() {
        val navigator = ChannelNavigator(channels)
        navigator.locate("c1", null)

        assertEquals("c2", navigator.next()?.id)
        assertEquals("c3", navigator.next()?.id)
    }

    @Test
    fun `previous walks backward through channels`() {
        val navigator = ChannelNavigator(channels)
        navigator.locate("c3", null)

        assertEquals("c2", navigator.previous()?.id)
        assertEquals("c1", navigator.previous()?.id)
    }

    @Test
    fun `next wraps from last to first`() {
        val navigator = ChannelNavigator(channels)
        navigator.locate("c3", null)

        assertEquals("c1", navigator.next()?.id)
    }

    @Test
    fun `previous wraps from first to last`() {
        val navigator = ChannelNavigator(channels)
        navigator.locate("c1", null)

        assertEquals("c3", navigator.previous()?.id)
    }

    @Test
    fun `single channel returns itself in both directions`() {
        val navigator = ChannelNavigator(listOf(channel("only")))
        navigator.locate("only", null)

        assertEquals("only", navigator.next()?.id)
        assertEquals("only", navigator.previous()?.id)
    }

    @Test
    fun `empty list returns null for navigation`() {
        val navigator = ChannelNavigator(emptyList())

        assertNull(navigator.next())
        assertNull(navigator.previous())
    }

    @Test
    fun `flatten keeps category and channel order`() {
        val categories = listOf(
            ChannelCategory("news", "新闻", listOf(channel("n1"), channel("n2"))),
            ChannelCategory("sports", "体育", listOf(channel("s1"))),
        )

        val flat = ChannelNavigator.flatten(categories)

        assertEquals(3, flat.size)
        assertEquals(listOf("n1", "n2", "s1"), flat.map { it.id })
    }
    @Test
    fun `blank stream url never matches, so dynamic channels cannot mis-locate`() {
        // 动态源（mgtv:*）streamUrl 全为空串：id 找不到时必须失败，而不是命中第一个空地址频道
        val dynamic1 = Channel(id = "mgtv:287", name = "金鹰卡通", description = "", streamUrl = "")
        val dynamic2 = Channel(id = "mgtv:344", name = "湖南娱乐", description = "", streamUrl = "")
        val navigator = ChannelNavigator(listOf(dynamic1, dynamic2))

        assertTrue(navigator.locate("mgtv:287", ""))
        assertEquals(0, navigator.currentIndex)

        // 目标 id 不存在 → 不允许退化成"第一个空 streamUrl 的频道"
        assertFalse(navigator.locate("mgtv:999", ""))
        assertEquals(-1, navigator.currentIndex)

        // null 同样不参与回退
        assertFalse(navigator.locate("mgtv:999", null))
        assertEquals(-1, navigator.currentIndex)
    }

    @Test
    fun `non blank stream url still falls back after id miss`() {
        val a = Channel(id = "a", name = "A", description = "", streamUrl = "https://x.invalid/a.m3u8")
        val b = Channel(id = "b", name = "B", description = "", streamUrl = "https://x.invalid/b.m3u8")
        val navigator = ChannelNavigator(listOf(a, b))

        assertTrue(navigator.locate("missing", "https://x.invalid/b.m3u8"))
        assertEquals(1, navigator.currentIndex)
    }

    @Test
    fun `aggregated catalog keeps subscription segment first and navigates across mgtv channels`() {
        // 播放页的切台序列来自 LiveTvApplication.catalogStore 的聚合目录：
        // 订阅分段在前、MGTV 分段在后；任意 MGTV 频道都要能定位并在 MGTV 分组内上下切台。
        val aggregated = listOf(
            ChannelCategory(
                id = "sub",
                name = "订阅",
                channels = listOf(channel("sub1"), channel("sub2")),
            ),
            ChannelCategory(
                id = "mgtv",
                name = "芒果 TV",
                channels = listOf(
                    Channel(id = "mgtv:1", name = "湖南卫视", description = "", streamUrl = ""),
                    Channel(id = "mgtv:2", name = "金鹰卡通", description = "", streamUrl = ""),
                ),
            ),
        )
        val navigator = ChannelNavigator(ChannelNavigator.flatten(aggregated))

        assertEquals(listOf("sub1", "sub2", "mgtv:1", "mgtv:2"), navigator.channels.map { it.id })
        assertTrue(navigator.locate("mgtv:1", null))
        assertEquals("mgtv:1", navigator.current()?.id)

        // 上下键在 MGTV 分组内正向/反向切换
        assertEquals("mgtv:2", navigator.next()?.id)
        assertEquals("mgtv:1", navigator.previous()?.id)
        // 反向循环跨回订阅分段（不越出聚合列表）
        assertEquals("sub2", navigator.previous()?.id)
        assertEquals("sub1", navigator.previous()?.id)
        assertEquals("sub2", navigator.next()?.id)
    }
}
