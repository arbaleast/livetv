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
}
