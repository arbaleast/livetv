package com.livetv.android

import android.app.Application
import com.livetv.android.data.repository.DefaultChannelRepository
import com.livetv.android.data.source.LocalAssetChannelSource
import com.livetv.android.data.source.LocalM3uChannelSource
import com.livetv.android.domain.repository.ChannelRepository

/**
 * 应用入口：持有全局唯一的 [ChannelRepository]。
 *
 * 阶段五引入的目的：
 * - 消除 `MainBrowseFragment` 与 `PlayerActivity` 各自重复装配数据源、重复解析
 *   `assets/channels.json` 与 `assets/sample.m3u` 的冗余开销；
 * - 仓库自带内存缓存，首次聚合后复用结果（需要刷新时调 [DefaultChannelRepository.refresh]）。
 *
 * 装配在 `by lazy` 中完成：**首次访问** `channelRepository` 时才创建数据源，
 * 避免 Application 启动阶段就去读 assets。
 */
class LiveTvApplication : Application() {

    /**
     * 全局频道仓库：JSON 源（`assets/channels.json`）+ M3U 源（`assets/sample.m3u`）。
     *
     * 仓库内部按分类名合并同名分类，UI 层直接消费 [com.livetv.android.domain.model.ChannelCategory]。
     */
    val channelRepository: ChannelRepository by lazy {
        DefaultChannelRepository(
            listOf(
                LocalAssetChannelSource(assets),
                LocalM3uChannelSource(assets),
            ),
        )
    }

    /** 刷新仓库缓存（重新读取 assets 并合并）。 */
    fun refreshChannels(): List<com.livetv.android.domain.model.ChannelCategory> {
        val repository = channelRepository
        return if (repository is DefaultChannelRepository) {
            repository.refresh()
        } else {
            repository.getCategories()
        }
    }
}
