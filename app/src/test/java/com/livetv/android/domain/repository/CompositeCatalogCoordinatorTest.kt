package com.livetv.android.domain.repository

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CompositeCatalogCoordinator` 的纯 JVM 单元测试。
 *
 * 覆盖：两源乱序到达、单源失败不清空另一源、单源二次刷新保留另一槽位、
 * 合并结果写入 `ChannelCatalogStore` 且回调在两侧都完成后只触发一次。
 */
class CompositeCatalogCoordinatorTest {

    private class FakeLoader : CompositeCatalogCoordinator.CatalogLoader {
        private var pending: ((List<ChannelCategory>) -> Unit)? = null
        var loadCount = 0
            private set

        override fun load(callback: (List<ChannelCategory>) -> Unit) {
            loadCount++
            pending = callback
        }

        fun emit(categories: List<ChannelCategory>) {
            val callback = pending ?: error("loader 尚未被调用")
            pending = null
            callback(categories)
        }
    }

    private fun channel(id: String) =
        Channel(id = id, name = "频道$id", description = "", streamUrl = "")

    private fun category(id: String, name: String, vararg channelIds: String) =
        ChannelCategory(id = id, name = name, channels = channelIds.map { channel(it) })

    private val cctvList = listOf(category("cctv:news", "新闻", "cctv:cctv13"))
    private val mgtvList = listOf(category("mgtv:local", "地方台", "mgtv:221"))

    private fun build(): Triple<ChannelCatalogStore, FakeLoader, FakeLoader> {
        val store = ChannelCatalogStore()
        return Triple(store, FakeLoader(), FakeLoader())
    }

    @Test
    fun `cctv first then mgtv merges both without overwriting`() {
        val (store, cctv, mgtv) = build()
        val coordinator = CompositeCatalogCoordinator(store, cctv, mgtv)
        var result: List<ChannelCategory>? = null

        coordinator.refresh { result = it }
        cctv.emit(cctvList)
        mgtv.emit(mgtvList)

        assertEquals(listOf("cctv:news", "mgtv:local"), store.getAllCategories().map { it.id })
        assertEquals(store.getAllCategories(), result)
    }

    @Test
    fun `mgtv first then cctv still yields cctv before mgtv`() {
        val (store, cctv, mgtv) = build()
        val coordinator = CompositeCatalogCoordinator(store, cctv, mgtv)

        coordinator.refresh { }
        mgtv.emit(mgtvList)
        cctv.emit(cctvList)

        assertEquals(listOf("cctv:news", "mgtv:local"), store.getAllCategories().map { it.id })
    }

    @Test
    fun `mgtv failure keeps the previously loaded cctv slot`() {
        val (store, cctv, mgtv) = build()
        val coordinator = CompositeCatalogCoordinator(store, cctv, mgtv)

        coordinator.refresh { }
        cctv.emit(cctvList)
        mgtv.emit(emptyList())

        assertEquals(listOf("cctv:news"), store.getAllCategories().map { it.id })
    }

    @Test
    fun `cctv failure keeps the previously loaded mgtv slot`() {
        val (store, cctv, mgtv) = build()
        val coordinator = CompositeCatalogCoordinator(store, cctv, mgtv)

        coordinator.refresh { }
        mgtv.emit(mgtvList)
        cctv.emit(emptyList())

        assertEquals(listOf("mgtv:local"), store.getAllCategories().map { it.id })
    }

    @Test
    fun `second refresh of one source keeps the other slot`() {
        val (store, cctv, mgtv) = build()
        val coordinator = CompositeCatalogCoordinator(store, cctv, mgtv)

        coordinator.refresh { }
        cctv.emit(cctvList)
        mgtv.emit(mgtvList)

        val updatedCctv = listOf(category("cctv:sports", "体育", "cctv:cctv5"))
        coordinator.refreshCctv { }
        cctv.emit(updatedCctv)

        assertEquals(listOf("cctv:sports", "mgtv:local"), store.getAllCategories().map { it.id })
    }

    @Test
    fun `callback fires once after both sources complete and store is already updated`() {
        val (store, cctv, mgtv) = build()
        val coordinator = CompositeCatalogCoordinator(store, cctv, mgtv)
        var callbacks = 0
        var snapshotAtCallback: List<ChannelCategory> = emptyList()

        coordinator.refresh {
            callbacks++
            snapshotAtCallback = store.getAllCategories()
        }
        cctv.emit(cctvList)
        assertEquals(0, callbacks)
        mgtv.emit(mgtvList)

        assertEquals(1, callbacks)
        assertEquals(2, snapshotAtCallback.flatMap { it.channels }.size)
    }

    @Test
    fun `empty refresh with no prior data keeps the store empty`() {
        val (store, cctv, mgtv) = build()
        val coordinator = CompositeCatalogCoordinator(store, cctv, mgtv)

        coordinator.refresh { }
        cctv.emit(emptyList())
        mgtv.emit(emptyList())

        assertTrue(store.isEmpty())
    }
}
