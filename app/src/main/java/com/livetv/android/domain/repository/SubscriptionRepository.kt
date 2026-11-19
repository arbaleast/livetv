package com.livetv.android.domain.repository

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.RefreshResult

/**
 * 支持远程订阅的频道仓库。
 *
 * 在 [ChannelRepository] 的同步读取语义之上补充：
 * - [findChannelById]：按频道 id 定位完整频道（供播放页解析备用源与请求头）；
 * - [refreshSubscription]：拉取远程列表并按「远程 → 缓存 → 内置」三级兜底；
 * - [updateSubscriptionUrl]：写入 / 清除订阅 URL。
 */
interface SubscriptionRepository : ChannelRepository {

    /** 按频道 id 查找频道；找不到返回 `null`。同步且线程安全。 */
    fun findChannelById(channelId: String): Channel?

    /** 拉取远程订阅并返回本次刷新结果。同步且线程安全。 */
    fun refreshSubscription(): RefreshResult

    /**
     * 更新订阅 URL。
     *
     * @param url `null` 或空白字符串表示清除订阅（清缓存并切回内置频道）。
     */
    fun updateSubscriptionUrl(url: String?)

    /** 当前已配置的订阅 URL；未配置返回 `null`。 */
    fun getSubscriptionUrl(): String?
}
