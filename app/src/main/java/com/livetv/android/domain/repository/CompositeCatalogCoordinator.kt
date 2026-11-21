package com.livetv.android.domain.repository

import com.livetv.android.domain.model.ChannelCategory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 多源频道目录的**分槽聚合协调器**（纯 Kotlin，可纯 JVM 单测）。
 *
 * 背景：`ChannelCatalogStore` 只有一份快照；CCTV 与 MGTV 由两个独立异步协调器加载，
 * 若各自直接 `update()` 会互相覆盖。这里按来源分槽持有最新快照
 * （`latestCctv` / `latestMgtv`），每次任一来源完成都用 [ChannelCatalogMerger] 合并后
 * 统一写入 [ChannelCatalogStore]。
 *
 * 容错约定：**空列表视为"本次没有新数据"**，保留该来源上一次的有效槽位，
 * 因此单源失败绝不会清空另一来源的频道；两槽都为空时目录才为空。
 *
 * 线程约定：本类不切换线程——调用方的加载器负责把回调切到主线程
 * （现有 `MgtvCatalogCoordinator` / `CctvCatalogCoordinator` 都已在主线程回调）。
 */
class CompositeCatalogCoordinator(
    private val store: ChannelCatalogStore,
    private val cctvLoader: CatalogLoader,
    private val mgtvLoader: CatalogLoader,
) {

    /** 频道目录加载器（生产环境包一层协调器；测试可注入 fake）。 */
    fun interface CatalogLoader {
        fun load(callback: (List<ChannelCategory>) -> Unit)
    }

    private val lock = Any()
    private var latestCctv: List<ChannelCategory> = emptyList()
    private var latestMgtv: List<ChannelCategory> = emptyList()

    /** 只刷新 CCTV 槽位（另一槽保持不变）。 */
    fun refreshCctv(callback: (List<ChannelCategory>) -> Unit) {
        cctvLoader.load { result -> callback(applySource(isCctv = true, categories = result)) }
    }

    /** 只刷新 MGTV 槽位（另一槽保持不变）。 */
    fun refreshMgtv(callback: (List<ChannelCategory>) -> Unit) {
        mgtvLoader.load { result -> callback(applySource(isCctv = false, categories = result)) }
    }

    /** 同时刷新两个来源；两侧都完成后回调一次，携带最终合并快照。 */
    fun refresh(callback: (List<ChannelCategory>) -> Unit) {
        val remaining = AtomicInteger(2)
        val lastMerged = AtomicReference<List<ChannelCategory>>(emptyList())
        val onSourceDone: (List<ChannelCategory>) -> Unit = { merged ->
            lastMerged.set(merged)
            if (remaining.decrementAndGet() == 0) callback(lastMerged.get())
        }
        cctvLoader.load { result -> onSourceDone(applySource(isCctv = true, categories = result)) }
        mgtvLoader.load { result -> onSourceDone(applySource(isCctv = false, categories = result)) }
    }

    private fun applySource(isCctv: Boolean, categories: List<ChannelCategory>): List<ChannelCategory> =
        synchronized(lock) {
            if (categories.isNotEmpty()) {
                if (isCctv) latestCctv = categories else latestMgtv = categories
            }
            val merged = ChannelCatalogMerger.merge(latestCctv, latestMgtv)
            store.update(merged)
            merged
        }
}
