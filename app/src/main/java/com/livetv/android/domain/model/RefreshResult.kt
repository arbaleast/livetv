package com.livetv.android.domain.model

/**
 * 远程订阅刷新结果。
 *
 * 描述一次刷新之后「当前生效的频道列表来自哪里」，便于 UI 直接展示反馈。
 */
sealed interface RefreshResult {

    /** 当前生效的频道数量。 */
    val channelCount: Int

    /** 未配置订阅 URL，当前使用内置 assets 频道。 */
    data class BuiltinOnly(override val channelCount: Int) : RefreshResult

    /** 远程拉取成功，已切换到远程频道。 */
    data class RemoteLoaded(override val channelCount: Int) : RefreshResult

    /** 远程拉取失败，回退到本地缓存的远程列表。 */
    data class FallbackToCache(override val channelCount: Int, val reason: String) : RefreshResult

    /** 远程拉取失败且无有效缓存，回退到内置 assets 频道。 */
    data class FallbackToBuiltin(override val channelCount: Int, val reason: String) : RefreshResult
}
