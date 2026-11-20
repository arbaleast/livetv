package com.livetv.android

import android.app.Application
import com.livetv.android.data.SubscriptionCoordinator
import com.livetv.android.data.mgtv.HttpMgtvSourceFetcher
import com.livetv.android.data.mgtv.MgtvCatalogCoordinator
import com.livetv.android.data.mgtv.MgtvCatalogFetcher
import com.livetv.android.data.mgtv.MgtvProvider
import com.livetv.android.data.mgtv.MgtvStreamResolver
import com.livetv.android.data.mgtv.SharedPrefsMgtvDeviceId
import com.livetv.android.data.remote.RemoteM3uFetcher
import com.livetv.android.data.repository.SubscriptionChannelRepository
import com.livetv.android.data.subscription.FileSubscriptionStore
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.domain.repository.ChannelRepository
import com.livetv.android.domain.repository.SubscriptionRepository
import com.livetv.android.domain.stream.RoutingStreamResolver
import com.livetv.android.domain.stream.StaticStreamResolver
import com.livetv.android.domain.stream.StreamResolver

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
     * 内置源已清空（原来的 `assets/channels.json` 与 `assets/sample.m3u` 全是不可达占位频道，
     * 已删除）：现在的频道来源只有 **远程 M3U 订阅** 与 **MGTV 目录**（手机端独立分组）。
     */
    val subscriptionRepository: SubscriptionRepository by lazy {
        SubscriptionChannelRepository(
            builtinSources = emptyList(),
            fetcher = RemoteM3uFetcher(),
            store = FileSubscriptionStore(this),
        )
    }

    /**
     * 全局频道仓库。TV 与手机端共用同一个实例，避免重复解析。
     */
    val channelRepository: ChannelRepository by lazy { subscriptionRepository }

    /**
     * MGTV 频道目录协调器（**独立分组**，不并入内置频道仓库）。
     *
     * 目录是公开接口（无需 Cookie/签名），拉取失败只会导致「芒果 TV」分组不出现，
     * 不会影响内置频道与订阅源的任何行为。
     */
    val mgtvCatalogCoordinator: MgtvCatalogCoordinator by lazy {
        MgtvCatalogCoordinator(
            provider = MgtvProvider(
                catalogLoader = {
                    when (val result = MgtvCatalogFetcher().fetchCatalog()) {
                        is MgtvCatalogFetcher.Result.Success -> result.rawBody
                        else -> null
                    }
                },
            ),
        )
    }

    /** 订阅刷新协调器：后台拉取、主线程回传。 */
    val subscriptionCoordinator: SubscriptionCoordinator by lazy {
        SubscriptionCoordinator(subscriptionRepository)
    }

    /**
     * 全局播放会话解析器。
     *
     * - 普通 IPTV / M3U 订阅 / JSON 频道：走 [StaticStreamResolver]，行为与改造前一致；
     * - `mgtv:` 前缀频道：走 [MgtvStreamResolver]。
     *
     * MGTV 播放源接口要求 `sign` 与设备标识：签名由 `MgtvSigner` 在本地按网页播放器
     * 相同规则计算，设备 UUID 由 `SharedPrefsMgtvDeviceId` 首次生成后持久化复用；
     * 请求不携带 Cookie，也不冒用任何他人会话。
     */
    val streamResolver: StreamResolver by lazy {
        RoutingStreamResolver(
            mgtvResolver = MgtvStreamResolver(HttpMgtvSourceFetcher(SharedPrefsMgtvDeviceId(this))),
            fallbackResolver = StaticStreamResolver,
        )
    }

    /** 当前生效的频道快照（远程订阅 → 缓存 → 内置）。 */
    fun refreshChannels(): List<ChannelCategory> = channelRepository.getCategories()
}
