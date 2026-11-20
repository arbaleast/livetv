package com.livetv.android.domain.stream

import com.livetv.android.domain.model.Channel

/**
 * 动态播放地址的有效期刷新与 HTTP 403 单次重解析状态机（纯 Kotlin）。
 *
 * 规则：
 * 1. 起播前若缓存流为空或剩余有效期不足 [refreshLeadMs]（默认 60 秒），主动调用 [StreamResolver] 刷新；
 * 2. 播放中遇到 HTTP 403 时，同一会话**最多触发 1 次**重新解析并重连；
 * 3. 若重解析失败、或重连后再次遇到 HTTP 403 / 429，立即返回 [RefreshAction.FastFail]，严禁陷入无限重试。
 */
class StreamRefreshCoordinator(
    private val resolver: StreamResolver,
    private val clock: Clock = MonotonicClock,
    private val refreshLeadMs: Long = ResolvedStream.DEFAULT_REFRESH_LEAD_MS,
) {

    sealed interface RefreshAction {
        /** 已重新解析出新鲜流，调用方应使用新流重连。 */
        data class Reconnect(val stream: ResolvedStream) : RefreshAction

        /** 不允许继续刷新/重试，立即快速失败并上报错误。 */
        data class FastFail(val statusCode: Int, val error: StreamResolveError) : RefreshAction

        /** 非 403/429 类错误，交回播放器既有重试/备源策略处理。 */
        data class DelegateToRetryPolicy(val statusCode: Int) : RefreshAction
    }

    private var cachedChannelId: String? = null
    private var cachedStream: ResolvedStream? = null
    private var forbiddenRefreshCount: Int = 0

    /**
     * 起播前获取可用流：若命中同频道缓存且未临期则直接复用，否则重新解析。
     */
    @Synchronized
    fun prepareForPlayback(
        channel: Channel,
        preferredDefinition: String? = null,
    ): StreamResolveResult {
        val now = clock.nowMs()
        val existing = cachedStream
        if (cachedChannelId == channel.id && existing != null && !existing.isExpiringSoon(now, refreshLeadMs)) {
            return StreamResolveResult.Success(existing)
        }
        return resolveAndCache(channel, preferredDefinition, resetRefreshBudget = true)
    }

    /**
     * 播放中收到 HTTP 状态码错误时的协调决策。
     */
    @Synchronized
    fun onPlaybackHttpError(
        channel: Channel,
        statusCode: Int,
        preferredDefinition: String? = null,
    ): RefreshAction {
        if (cachedChannelId != channel.id) {
            cachedChannelId = channel.id
            cachedStream = null
            forbiddenRefreshCount = 0
        }

        return when (statusCode) {
            429 -> RefreshAction.FastFail(429, StreamResolveError.HTTP_RATE_LIMITED)
            403 -> {
                if (forbiddenRefreshCount >= MAX_FORBIDDEN_REFRESH_ATTEMPTS) {
                    RefreshAction.FastFail(403, StreamResolveError.HTTP_FORBIDDEN)
                } else {
                    forbiddenRefreshCount += 1
                    when (val result = resolveAndCache(channel, preferredDefinition, resetRefreshBudget = false)) {
                        is StreamResolveResult.Success -> RefreshAction.Reconnect(result.stream)
                        is StreamResolveResult.Failure -> RefreshAction.FastFail(403, result.error)
                    }
                }
            }
            else -> RefreshAction.DelegateToRetryPolicy(statusCode)
        }
    }

    /** 播放成功起播后重置单次 403 刷新预算，以便长播数小时后再次到期仍可刷新一次。 */
    @Synchronized
    fun onPlaybackRecovered() {
        forbiddenRefreshCount = 0
    }

    /** 切台或停止播放时清空会话缓存。 */
    @Synchronized
    fun resetSession() {
        cachedChannelId = null
        cachedStream = null
        forbiddenRefreshCount = 0
    }

    private fun resolveAndCache(
        channel: Channel,
        preferredDefinition: String?,
        resetRefreshBudget: Boolean,
    ): StreamResolveResult {
        if (resetRefreshBudget) {
            forbiddenRefreshCount = 0
        }
        val result = resolver.resolve(channel, preferredDefinition)
        if (result is StreamResolveResult.Success) {
            cachedChannelId = channel.id
            cachedStream = result.stream
        } else {
            cachedChannelId = channel.id
            cachedStream = null
        }
        return result
    }

    companion object {
        const val MAX_FORBIDDEN_REFRESH_ATTEMPTS = 1
    }
}
