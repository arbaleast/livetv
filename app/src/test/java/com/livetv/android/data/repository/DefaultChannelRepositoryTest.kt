package com.livetv.android.data.repository

import com.livetv.android.data.source.ChannelDataSource
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `DefaultChannelRepository` 的纯 JVM 单元测试。
 *
 * 用 fake 数据源替代真实 assets（mock 只挡外部依赖，被测的仓库逻辑不 mock）。
 * 覆盖：单源透传、空结果、异常吞噬，以及**多数据源合并**（同名分类聚合）。
 */
class DefaultChannelRepositoryTest {

    private class FakeDataSource(
        private val result: List<ChannelCategory>,
        private val error: Throwable? = null,
    ) : ChannelDataSource {
        var callCount = 0
            private set

        override fun loadCategories(): List<ChannelCategory> {
            callCount++
            error?.let { throw it }
            return result
        }
    }

    private class ThrowingDataSource : ChannelDataSource {
        override fun loadCategories(): List<ChannelCategory> = throw IllegalStateException("boom")
    }

    @Test
    fun `returns categories provided by the data source`() {
        val expected = listOf(
            ChannelCategory(
                id = "news",
                name = "新闻",
                channels = listOf(
                    Channel(id = "n1", name = "综合新闻", description = "24 小时滚动", streamUrl = "https://example.com/n1.m3u8"),
                ),
            ),
        )
        val source = FakeDataSource(expected)

        val repository = DefaultChannelRepository(source)

        assertEquals(expected, repository.getCategories())
        assertEquals(1, source.callCount)
    }

    @Test
    fun `returns empty list when data source yields nothing`() {
        val repository = DefaultChannelRepository(FakeDataSource(emptyList()))

        assertEquals(0, repository.getCategories().size)
    }

    @Test
    fun `swallows data source exceptions and returns empty list`() {
        val repository = DefaultChannelRepository(ThrowingDataSource())

        assertEquals(0, repository.getCategories().size)
    }

    // ---------- 多数据源合并 ----------

    @Test
    fun `merges categories with the same name into a single category`() {
        val jsonSource = FakeDataSource(
            listOf(
                ChannelCategory(
                    id = "news",
                    name = "新闻",
                    channels = listOf(
                        Channel(id = "n1", name = "综合新闻", description = "24 小时滚动", streamUrl = "https://example.com/n1.m3u8"),
                    ),
                ),
            ),
        )
        val m3uSource = FakeDataSource(
            listOf(
                ChannelCategory(
                    id = "新闻",
                    name = "新闻",
                    channels = listOf(
                        Channel(id = "cctv1", name = "CCTV-1 综合", description = "", streamUrl = "https://example.com/cctv1.m3u8"),
                    ),
                ),
            ),
        )

        val repository = DefaultChannelRepository(listOf(jsonSource, m3uSource))
        val categories = repository.getCategories()

        assertEquals(1, categories.size)
        assertEquals("新闻", categories[0].name)
        // 合并后的分类沿用第一个源的 id
        assertEquals("news", categories[0].id)
        assertEquals(2, categories[0].channels.size)
        assertEquals("n1", categories[0].channels[0].id)
        assertEquals("cctv1", categories[0].channels[1].id)
    }

    @Test
    fun `keeps categories with different names apart`() {
        val jsonSource = FakeDataSource(
            listOf(
                ChannelCategory(
                    id = "news",
                    name = "新闻",
                    channels = listOf(Channel("n1", "综合新闻", "", "https://example.com/n1.m3u8")),
                ),
            ),
        )
        val m3uSource = FakeDataSource(
            listOf(
                ChannelCategory(
                    id = "sports",
                    name = "体育",
                    channels = listOf(Channel("s1", "体育赛事", "", "https://example.com/s1.m3u8")),
                ),
            ),
        )

        val repository = DefaultChannelRepository(listOf(jsonSource, m3uSource))
        val categories = repository.getCategories()

        assertEquals(2, categories.size)
        assertEquals("新闻", categories[0].name)
        assertEquals("体育", categories[1].name)
        assertEquals(1, categories[0].channels.size)
        assertEquals(1, categories[1].channels.size)
    }

    @Test
    fun `does not duplicate identical channels inside a merged category`() {
        val shared = Channel("n1", "综合新闻", "", "https://example.com/n1.m3u8")
        val jsonSource = FakeDataSource(
            listOf(ChannelCategory("news", "新闻", listOf(shared))),
        )
        val m3uSource = FakeDataSource(
            listOf(ChannelCategory("新闻", "新闻", listOf(shared))),
        )

        val repository = DefaultChannelRepository(listOf(jsonSource, m3uSource))
        val categories = repository.getCategories()

        assertEquals(1, categories.size)
        assertEquals(1, categories[0].channels.size)
        assertEquals("n1", categories[0].channels[0].id)
    }

    @Test
    fun `a failing source does not prevent other sources from contributing`() {
        val brokenSource = ThrowingDataSource()
        val workingSource = FakeDataSource(
            listOf(
                ChannelCategory(
                    id = "news",
                    name = "新闻",
                    channels = listOf(Channel("n1", "综合新闻", "", "https://example.com/n1.m3u8")),
                ),
            ),
        )

        val repository = DefaultChannelRepository(listOf(brokenSource, workingSource))
        val categories = repository.getCategories()

        assertEquals(1, categories.size)
        assertEquals("新闻", categories[0].name)
        assertEquals(1, categories[0].channels.size)
    }

    @Test
    fun `queries every source exactly once`() {
        val first = FakeDataSource(emptyList())
        val second = FakeDataSource(emptyList())

        DefaultChannelRepository(listOf(first, second)).getCategories()

        assertEquals(1, first.callCount)
        assertEquals(1, second.callCount)
    }
}
