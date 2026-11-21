package com.livetv.android

import android.app.Application
import com.livetv.android.data.mgtv.HttpMgtvSourceFetcher
import com.livetv.android.data.mgtv.MgtvCatalogCoordinator
import com.livetv.android.data.mgtv.MgtvCatalogFetcher
import com.livetv.android.data.mgtv.MgtvProvider
import com.livetv.android.data.mgtv.MgtvStreamResolver
import com.livetv.android.data.mgtv.SharedPrefsMgtvDeviceId
import com.livetv.android.domain.repository.ChannelCatalogStore
import com.livetv.android.domain.stream.RoutingStreamResolver
import com.livetv.android.domain.stream.StaticStreamResolver
import com.livetv.android.domain.stream.StreamResolver

/**
 * 应用入口：持有全局唯一的频道目录、MGTV 目录协调器与播放源解析器。
 *
 * **频道来源只有 MGTV 公开目录**：内置 assets 与 M3U 订阅管线已移除
 * （占位频道不可达、订阅入口长期无内容，属于冗余数据源）。
 * 目录快照由 [ChannelCatalogStore] 持有，手机首页、TV 首页、播放页抽屉与切台共用。
 *
 * 装配在 `by lazy` 中完成：**首次访问**时才创建，避免 Application 启动阶段就发起网络请求。
 */
class LiveTvApplication : Application() {

    /**
     * 应用级频道目录：只有 MGTV 一个来源。
     *
     * 首页（手机 / TV）与播放页（上下切台 / 抽屉）共用该快照。
     */
    val catalogStore: ChannelCatalogStore by lazy { ChannelCatalogStore() }

    /**
     * MGTV 频道目录协调器（后台拉取 + 主线程回传）。
     *
     * 目录是公开接口（无需 Cookie/签名），拉取失败只会导致列表为空。
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

    /**
     * 全局播放会话解析器。
     *
     * - `mgtv:` 前缀频道：走 [MgtvStreamResolver]；
     * - 其余静态地址：走 [StaticStreamResolver]（保留给未来其它来源，当前目录不会产生此类频道）。
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
}
