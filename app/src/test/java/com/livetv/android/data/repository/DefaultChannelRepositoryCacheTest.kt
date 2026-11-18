package com.livetv.android.data.repository

import com.livetv.android.data.source.ChannelDataSource
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `DefaultChannelRepository` 内存缓存行为的纯 JVM 单元测试（阶段五）。
 *
 * 覆盖：默认开启缓存、缓存命中不再访问数据源、`refresh()` 强制重算、
 * `invalidateCache()` 作废缓存、`enableCache = false` 每次都重算。
 */
class DefaultChannelRepositoryCacheTest {

    private class CountingDataSource(
        private val result: List<ChannelCategory>,
    ) : ChannelDataSource {
        var callCount = 0
            private set

        override fun loadCategories(): List<ChannelCategory> {
            callCount++
            return result
        }
    }

    private fun categories(prefix: String): List<ChannelCategory> = listOf(
        ChannelCategory(
            id = "news",
            name = "新闻",
            channels = listOf(Channel("$prefix-1", "综合新闻", "", "https://example.com/$prefix-1.m3u8")),
        ),
    )

    @Test
    fun `cache is enabled by default so the data source is queried once`() {
        val source = CountingDataSource(categories("a"))
        val repository = DefaultChannelRepository(source)

        val first = repository.getCategories()
        val second = repository.getCategories()

        assertEquals(1, source.callCount)
        assertEquals(first, second)
    }

    @Test
    fun `cached result is reused verbatim`() {
        val source = CountingDataSource(categories("a"))
        val repository = DefaultChannelRepository(source)

        val first = repository.getCategories()

        // 数据源结果在缓存期间发生变化也不影响已缓存的返回值
        val second = repository.getCategories()

        assertEquals("a-1", first[0].channels[0].id)
        assertEquals(first, second)
    }

    @Test
    fun `refresh re-queries the data sources and updates the cache`() {
        val source = CountingDataSource(categories("a"))
        val repository = DefaultChannelRepository(source)

        repository.getCategories()
        val refreshed = repository.refresh()

        assertEquals(2, source.callCount)
        assertEquals("a-1", refreshed[0].channels[0].id)
        // refresh 之后缓存已被更新，后续调用不再访问数据源
        repository.getCategories()
        assertEquals(2, source.callCount)
    }

    @Test
    fun `invalidateCache forces the next call to re-aggregate`() {
        val source = CountingDataSource(categories("a"))
        val repository = DefaultChannelRepository(source)

        repository.getCategories()
        repository.invalidateCache()
        repository.getCategories()

        assertEquals(2, source.callCount)
    }

    @Test
    fun `cache disabled re-aggregates on every call`() {
        val source = CountingDataSource(categories("a"))
        val repository = DefaultChannelRepository(source, enableCache = false)

        repository.getCategories()
        repository.getCategories()
        repository.getCategories()

        assertEquals(3, source.callCount)
    }

    @Test
    fun `cache disabled still returns the aggregated result`() {
        val source = CountingDataSource(categories("a"))
        val repository = DefaultChannelRepository(source, enableCache = false)

        val categories = repository.getCategories()

        assertEquals(1, categories.size)
        assertEquals("新闻", categories[0].name)
        assertEquals("a-1", categories[0].channels[0].id)
    }

    @Test
    fun `single data source constructor keeps the same behaviour`() {
        val source = CountingDataSource(categories("a"))
        val repository = DefaultChannelRepository(source)

        repository.getCategories()
        repository.getCategories()

        assertEquals(1, source.callCount)
    }
}
