package com.livetv.android.domain.repository

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChannelCatalogMerger` 的纯 JVM 单元测试。
 *
 * 固定契约：CCTV 段在前、MGTV 段在后；空分类丢弃；同分类 id 合并频道并按 `channel.id` 去重
 * （**不允许**整组丢弃后出现的分类，否则会吞频道）；单侧为空时完整保留另一侧。
 */
class ChannelCatalogMergerTest {

    private fun channel(id: String, name: String = "频道$id") =
        Channel(id = id, name = name, description = "", streamUrl = "")

    private fun category(id: String, name: String, vararg channels: Channel) =
        ChannelCategory(id = id, name = name, channels = channels.toList())

    @Test
    fun `cctv categories always come before mgtv`() {
        val merged = ChannelCatalogMerger.merge(
            cctv = listOf(category("cctv:news", "新闻", channel("cctv:cctv13"))),
            mgtv = listOf(category("mgtv:local", "地方台", channel("mgtv:221"))),
        )

        assertEquals(listOf("cctv:news", "mgtv:local"), merged.map { it.id })
    }

    @Test
    fun `empty categories are dropped from both sides`() {
        val merged = ChannelCatalogMerger.merge(
            cctv = listOf(category("cctv:empty", "空分组")),
            mgtv = listOf(category("mgtv:sat", "卫视台", channel("mgtv:287"))),
        )

        assertEquals(listOf("mgtv:sat"), merged.map { it.id })
    }

    @Test
    fun `same category id merges channels and never swallows the later group`() {
        val merged = ChannelCatalogMerger.merge(
            cctv = listOf(category("shared", "综合", channel("a"), channel("b"))),
            mgtv = listOf(category("shared", "综合", channel("b"), channel("c"))),
        )

        assertEquals(1, merged.size)
        val shared = merged.single()
        // 同名分类保留先出现（CCTV）的名称，频道按 id 去重后合并
        assertEquals("综合", shared.name)
        assertEquals(listOf("a", "b", "c"), shared.channels.map { it.id })
    }

    @Test
    fun `empty cctv keeps mgtv intact`() {
        val merged = ChannelCatalogMerger.merge(
            cctv = emptyList(),
            mgtv = listOf(category("mgtv:sat", "卫视台", channel("mgtv:287"))),
        )

        assertEquals(listOf("mgtv:sat"), merged.map { it.id })
        assertEquals(1, merged.single().channels.size)
    }

    @Test
    fun `empty mgtv keeps cctv intact`() {
        val merged = ChannelCatalogMerger.merge(
            cctv = listOf(category("cctv:news", "新闻", channel("cctv:cctv13"))),
            mgtv = emptyList(),
        )

        assertEquals(listOf("cctv:news"), merged.map { it.id })
        assertEquals(1, merged.single().channels.size)
    }

    @Test
    fun `both empty yields empty list`() {
        assertTrue(ChannelCatalogMerger.merge(emptyList(), emptyList()).isEmpty())
    }
}
