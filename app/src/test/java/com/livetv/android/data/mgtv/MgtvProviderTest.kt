package com.livetv.android.data.mgtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [MgtvProvider] 的目录映射测试。
 *
 * 关键约定：**聚合分类「热门」不整段展示**——它的频道大多与其它分类重复；
 * 但**只在「热门」出现的频道必须捞回来**（实测 `344 湖南娱乐`），否则会丢台。
 */
class MgtvProviderTest {

    private fun providerWith(raw: String) = MgtvProvider(catalogLoader = { raw })

    @Test
    fun `aggregator category is dropped entirely`() {
        val provider = providerWith(MgtvSanitizedFixtures.CATEGORY_WITH_AGGREGATOR)
        provider.loadMgtvCategories()

        val categories = provider.toDomainCategories()

        assertEquals(listOf("卫视台", "地方台"), categories.map { it.name })
        assertFalse("热门 不应出现在领域分类里", categories.any { it.name == "热门" })
    }

    @Test
    fun `remaining categories carry no duplicate channel ids`() {
        val provider = providerWith(MgtvSanitizedFixtures.CATEGORY_WITH_AGGREGATOR)
        provider.loadMgtvCategories()

        val ids = provider.toDomainCategories().flatMap { it.channels }.map { it.id }

        assertEquals(listOf("mgtv:287", "mgtv:999", "mgtv:344"), ids)
        assertEquals("去掉聚合分类后不应再有重复", ids.size, ids.toSet().size)
    }

    @Test
    fun `channel keeps camera id as epg id and empty stream url`() {
        val provider = providerWith(MgtvSanitizedFixtures.CATEGORY_JSONP)
        provider.loadMgtvCategories()

        val channel = provider.toDomainCategories().first().channels.first()

        assertEquals("mgtv:287", channel.id)
        assertEquals("287", channel.epgId)
        assertEquals("", channel.streamUrl)
    }

    @Test
    fun `aggregator only channel is rescued into its own hot group exactly once`() {
        val provider = providerWith(MgtvSanitizedFixtures.CATEGORY_WITH_AGGREGATOR_ORPHAN)
        provider.loadMgtvCategories()

        val categories = provider.toDomainCategories()

        assertEquals(listOf("卫视台", "地方台", "热门"), categories.map { it.name })
        val hot = categories.last()
        assertEquals("热门分组只应保留聚合独有频道", listOf("mgtv:344"), hot.channels.map { it.id })

        val allIds = categories.flatMap { it.channels }.map { it.id }
        assertEquals(listOf("mgtv:287", "mgtv:221", "mgtv:344"), allIds)
        assertEquals("不应产生重复频道", allIds.size, allIds.toSet().size)
    }

    @Test
    fun `denylisted camera id is dropped while other channels keep their grouping`() {
        val provider = providerWith(MgtvSanitizedFixtures.CATEGORY_WITH_DENYLISTED_CHANNEL)
        provider.loadMgtvCategories()

        val categories = provider.toDomainCategories()

        assertEquals(listOf("卫视台", "地方台"), categories.map { it.name })
        assertEquals("卫视台 应保留正常频道", listOf("mgtv:287"), categories[0].channels.map { it.id })
        assertEquals("地方台 不应受影响", listOf("mgtv:280"), categories[1].channels.map { it.id })
        assertEquals(
            "229 必须被过滤",
            false,
            categories.flatMap { it.channels }.any { it.id == "mgtv:229" },
        )
    }

    @Test
    fun `denylisted channel never leaks back through the aggregator rescue path`() {
        val provider = providerWith(MgtvSanitizedFixtures.CATEGORY_WITH_DENYLISTED_CHANNEL)
        provider.loadMgtvCategories()

        val ids = provider.toDomainCategories().flatMap { it.channels }.map { it.id }

        assertEquals(listOf("mgtv:287", "mgtv:280"), ids)
    }
}
