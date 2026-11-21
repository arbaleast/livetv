package com.livetv.android.domain.repository

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChannelCatalogStore] 纯 JVM 单元测试：目录快照的替换、空分类过滤与顺序保持。
 */
class ChannelCatalogStoreTest {

    private fun channel(id: String, name: String = "频道$id", url: String = "https://example.com/$id.m3u8") =
        Channel(id = id, name = name, description = "", streamUrl = url)

    private fun category(id: String, name: String, vararg channels: Channel) =
        ChannelCategory(id = id, name = name, channels = channels.toList())

    @Test
    fun `initial store is empty`() {
        val store = ChannelCatalogStore()

        assertTrue(store.isEmpty())
        assertEquals(emptyList<ChannelCategory>(), store.getAllCategories())
    }

    @Test
    fun `update replaces the whole snapshot and keeps category order`() {
        val store = ChannelCatalogStore()
        store.update(
            listOf(
                category("sat", "卫视台", channel("287"), channel("229")),
                category("local", "地方台", channel("221")),
            ),
        )

        assertFalse(store.isEmpty())
        assertEquals(listOf("sat", "local"), store.getAllCategories().map { it.id })
        assertEquals(listOf("287", "229", "221"), store.getAllCategories().flatMap { it.channels }.map { it.id })
    }

    @Test
    fun `update drops empty categories so the ui never renders a bare header`() {
        val store = ChannelCatalogStore()
        store.update(
            listOf(
                category("empty", "空分类"),
                category("sat", "卫视台", channel("287")),
            ),
        )

        assertEquals(listOf("sat"), store.getAllCategories().map { it.id })
    }

    @Test
    fun `second update fully replaces the previous snapshot`() {
        val store = ChannelCatalogStore()
        store.update(listOf(category("old", "旧分组", channel("1"))))
        store.update(listOf(category("new", "新分组", channel("2"))))

        assertEquals(listOf("new"), store.getAllCategories().map { it.id })
        assertEquals(listOf("2"), store.getAllCategories().flatMap { it.channels }.map { it.id })
    }

    @Test
    fun `empty update clears the catalog`() {
        val store = ChannelCatalogStore()
        store.update(listOf(category("sat", "卫视台", channel("287"))))
        store.update(emptyList())

        assertTrue(store.isEmpty())
        assertEquals(emptyList<ChannelCategory>(), store.getAllCategories())
    }
}
