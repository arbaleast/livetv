package com.livetv.android

import android.app.Application
import com.livetv.android.data.cctv.CctvCatalogCoordinator
import com.livetv.android.data.cctv.CctvChannelCatalog
import com.livetv.android.data.mgtv.HttpMgtvSourceFetcher
import com.livetv.android.data.mgtv.MgtvCatalogCoordinator
import com.livetv.android.data.mgtv.MgtvCatalogFetcher
import com.livetv.android.data.mgtv.MgtvProvider
import com.livetv.android.data.mgtv.MgtvStreamResolver
import com.livetv.android.data.mgtv.SharedPrefsMgtvDeviceId
import com.livetv.android.domain.repository.ChannelCatalogStore
import com.livetv.android.domain.repository.CompositeCatalogCoordinator
import com.livetv.android.domain.stream.RoutingStreamResolver
import com.livetv.android.domain.stream.StaticStreamResolver
import com.livetv.android.domain.stream.StreamResolver

/**
 * 应用入口：持有全局唯一的频道目录、CCTV/MGTV 目录协调器与播放源解析器。
 *
 * 频道来源：**CCTV 静态目录（P1，仅元数据）+ MGTV 公开目录**。
 * 两个来源由 [CompositeCatalogCoordinator] 分槽合并后写入 [ChannelCatalogStore]，
 * 手机首页、TV 首页、播放页抽屉与切台共用同一份快照。
 * CCTV 的播放地址由 P2 的 VDN 解析提供，目录里的 `streamUrl` 恒为空。
 *
 * 装配在 `by lazy` 中完成：**首次访问**时才创建，避免 Application 启动阶段就发起网络请求。
 */
class LiveTvApplication : Application() {

    /**
     * 应用级频道目录：CCTV + MGTV 合并后的唯一快照。
     *
     * 首页（手机 / TV）与播放页（上下切台 / 抽屉）共用该快照。
     */
    val catalogStore: ChannelCatalogStore by lazy { ChannelCatalogStore() }


    /**
     * CCTV 频道目录协调器（P1：静态元数据，后台解析 + 主线程回传）。
     *
     * 数据来自 `assets/cctv_channels.json`，**不含任何播放地址**。
     */
    val cctvCatalogCoordinator: CctvCatalogCoordinator by lazy {
        CctvCatalogCoordinator(
            catalog = CctvChannelCatalog(catalogLoader = { readCctvCatalogAsset() }),
        )
    }

    /**
     * 多源分槽聚合协调器：CCTV 与 MGTV 的加载结果合并后统一写入 [catalogStore]。
     *
     * 注入的是两个协调器的 `load(callback)`，便于纯 JVM 测试替换为 fake。
     */
    val compositeCatalogCoordinator: CompositeCatalogCoordinator by lazy {
        CompositeCatalogCoordinator(
            store = catalogStore,
            cctvLoader = { callback -> cctvCatalogCoordinator.load(callback) },
            mgtvLoader = { callback -> mgtvCatalogCoordinator.load(callback) },
        )
    }

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

    /** 读取 CCTV 静态目录；文件缺失或读取失败返回 null（该来源为空，不影响 MGTV）。 */
    private fun readCctvCatalogAsset(): String? = runCatching {
        assets.open(CCTV_CATALOG_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    private companion object {
        const val CCTV_CATALOG_ASSET = "cctv_channels.json"
    }
}
