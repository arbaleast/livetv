package com.livetv.android

import android.app.Application
import com.livetv.android.data.SubscriptionCoordinator
import com.livetv.android.data.remote.RemoteM3uFetcher
import com.livetv.android.data.repository.SubscriptionChannelRepository
import com.livetv.android.data.source.LocalAssetChannelSource
import com.livetv.android.data.source.LocalM3uChannelSource
import com.livetv.android.data.subscription.FileSubscriptionStore
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.domain.repository.ChannelRepository
import com.livetv.android.domain.repository.SubscriptionRepository

/**
 * 应用入口：持有全局唯一的频道仓库与订阅协调器。
 *
 * 阶段五引入 [ChannelRepository] 的目的：
 * - 消除 `MainBrowseFragment` 与 `PlayerActivity` 各自重复装配数据源、重复解析
 *   `assets/channels.json` 与 `assets/sample.m3u` 的冗余开销；
 * - 仓库自带内存缓存，首次聚合后复用结果。
 *
 * 阶段八把仓库升级为 [SubscriptionRepository]：数据来源按
 * **远程订阅 → 本地有效缓存 → 内置 assets** 三级兜底，
 * 并由 [SubscriptionCoordinator] 负责后台拉取与主线程回传。
 *
 * 装配在 `by lazy` 中完成：**首次访问**时才创建数据源，
 * 避免 Application 启动阶段就去读 assets 或磁盘。
 */
class LiveTvApplication : Application() {

    /**
     * 支持远程订阅的全局仓库。
     *
     * 内置源仍是 JSON（`assets/channels.json`）+ M3U（`assets/sample.m3u`），
     * 两者按分类名合并；远程订阅成功后以整体快照替换内置快照。
     */
    val subscriptionRepository: SubscriptionRepository by lazy {
        SubscriptionChannelRepository(
            builtinSources = listOf(
                LocalAssetChannelSource(assets),
                LocalM3uChannelSource(assets),
            ),
            fetcher = RemoteM3uFetcher(),
            store = FileSubscriptionStore(this),
        )
    }

    /**
     * 全局频道仓库。TV 与手机端共用同一个实例，避免重复解析。
     */
    val channelRepository: ChannelRepository by lazy { subscriptionRepository }

    /** 订阅刷新协调器：后台拉取、主线程回传。 */
    val subscriptionCoordinator: SubscriptionCoordinator by lazy {
        SubscriptionCoordinator(subscriptionRepository)
    }

    /** 当前生效的频道快照（远程订阅 → 缓存 → 内置）。 */
    fun refreshChannels(): List<ChannelCategory> = channelRepository.getCategories()
}
