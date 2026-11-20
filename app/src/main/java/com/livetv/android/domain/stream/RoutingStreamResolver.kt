package com.livetv.android.domain.stream

import com.livetv.android.domain.model.Channel

/**
 * 按频道前缀把解析请求路由到不同的 [StreamResolver]（纯 Kotlin）。
 *
 * - `mgtv:` 前缀的频道走 MGTV 解析器（动态签名地址、会话有效期、Host 头隔离）；
 * - 其余频道走静态解析器，行为与改造前完全一致（`channel.streamUrl` + `channel.headers`）。
 */
class RoutingStreamResolver(
    private val mgtvResolver: StreamResolver,
    private val fallbackResolver: StreamResolver = StaticStreamResolver,
    private val mgtvChannelPrefix: String = MGTV_CHANNEL_PREFIX,
) : StreamResolver {

    override fun resolve(
        channel: Channel,
        preferredDefinition: String?,
    ): StreamResolveResult {
        val resolver = if (isMgtvChannel(channel)) mgtvResolver else fallbackResolver
        return resolver.resolve(channel, preferredDefinition)
    }

    fun isMgtvChannel(channel: Channel): Boolean = channel.id.startsWith(mgtvChannelPrefix)

    companion object {
        const val MGTV_CHANNEL_PREFIX: String = "mgtv:"
    }
}
