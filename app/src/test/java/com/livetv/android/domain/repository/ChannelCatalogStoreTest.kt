package com.livetv.android.domain.repository

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChannelCatalogStore] 纯 JVM 单元测试：验证订阅目录与补充目录（如 MGTV）的聚合与隔离。
 */
class ChannelCatalogStoreTest {

    private fun channel(id: String, name: String = "频道$id", url: String = "https://example.com/$id.m3u8") =
        Channel(id = id, name = name, description = "", streamUrl = url)

    private fun category(id: String, name: String, vararg channels: Channel) =
        ChannelCategory(id = id, name = name, channels = channels.toList())

    @Test
    fun `initial store is empty and has no supplemental categories`() {
        val store = ChannelCatalogStore()

        assertEquals(emptyList<ChannelCategory>(), store.getAllCategories())
        assertFalse(store.hasSupplementalCategories())
    }

    @Test
    fun `combines subscription categories first followed by supplemental categories`() {
        val store = ChannelCatalogStore()
        val sub = listOf(category("cctv", "央视", channel("c1")))
        val mgtv = listOf(category("mgtv", "芒果 TV", channel("mgtv:287", "金鹰卡通", "")))

        store.updateSubscriptionCategories(sub)
        store.updateSupplementalCategories(mgtv)

        assertTrue(store.hasSupplementalCategories())
        assertEquals(listOf("cctv", "mgtv"), store.getAllCategories().map { it.id })
        assertEquals(listOf("c1", "mgtv:287"), store.getAllCategories().flatMap { it.channels }.map { it.id })
    }

    @Test
    fun `refreshing or clearing subscription preserves loaded supplemental categories`() {
        val store = ChannelCatalogStore()
        val subV1 = listOf(category("cctv", "央视", channel("c1")))
        val mgtv = listOf(category("mgtv", "芒果 TV", channel("mgtv:287", "金鹰卡通", "")))
        val subV2 = listOf(category("sports", "体育", channel("s1")))

        store.updateSubscriptionCategories(subV1)
        store.updateSupplementalCategories(mgtv)

        // 刷新订阅为新分组：MGTV 分组不得丢失
        store.updateSubscriptionCategories(subV2)
        assertEquals(listOf("sports", "mgtv"), store.getAllCategories().map { it.id })
        assertTrue(store.hasSupplementalCategories())

        // 清空订阅（恢复无自定义订阅态）：MGTV 分组仍应保留
        store.updateSubscriptionCategories(emptyList())
        assertEquals(listOf("mgtv"), store.getAllCategories().map { it.id })
        assertTrue(store.hasSupplementalCategories())
    }

    @Test
    fun `skips empty categories and prevents duplicate category ids across segments`() {
        val store = ChannelCatalogStore()
        store.updateSubscriptionCategories(
            listOf(
                category("empty_sub", "空分组"),
                category("shared", "订阅分组", channel("sub1")),
            ),
        )
        store.updateSupplementalCategories(
            listOf(
                category("empty_sup", "空补充"),
                category("shared", "重复分组", channel("sup_dup")),
                category("mgtv", "芒果 TV", channel("mgtv:1", "湖南卫视", "")),
            ),
        )

        val result = store.getAllCategories()
        assertEquals(listOf("shared", "mgtv"), result.map { it.id })
        assertEquals("订阅分组", result[0].name)
        assertEquals(listOf("sub1"), result[0].channels.map { it.id })
    }

    @Test
    fun `updating supplemental categories with empty list clears supplemental flag without touching subscription`() {
        val store = ChannelCatalogStore()
        store.updateSubscriptionCategories(listOf(category("cctv", "央视", channel("c1"))))
        store.updateSupplementalCategories(listOf(category("mgtv", "芒果 TV", channel("mgtv:1", "湖南卫视", ""))))

        store.updateSupplementalCategories(emptyList())

        assertFalse(store.hasSupplementalCategories())
        assertEquals(listOf("cctv"), store.getAllCategories().map { it.id })
    }
}
