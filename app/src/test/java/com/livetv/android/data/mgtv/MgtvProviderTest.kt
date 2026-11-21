package com.livetv.android.data.mgtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [MgtvProvider] 的目录映射测试。
 *
 * 关键约定：**聚合分类「热门」不展示**——它的频道是其它分类的重复集合，
 * 跳过它之后列表天然没有重复卡片，也不需要运行时去重。
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
}
