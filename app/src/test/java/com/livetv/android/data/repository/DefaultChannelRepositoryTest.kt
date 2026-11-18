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
}
