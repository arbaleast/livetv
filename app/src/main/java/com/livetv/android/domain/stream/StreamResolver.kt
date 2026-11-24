package com.livetv.android.domain.stream

import com.livetv.android.domain.model.Channel

/**
 * 通用播放会话解析抽象，解耦数据源特有协议与 `PlayerManager`。
 */
interface StreamResolver {
    fun resolve(
        channel: Channel,
        preferredDefinition: String? = null,
    ): StreamResolveResult

    /**
     * 带节点轮换标志的解析入口：仅在播放遇到 403/401 需要切换 CDN 节点时传 `rotateNode = true`，
     * 避免新会话携带旧缓存的 `npuk` 反而从正常节点被切走。
     */
    fun resolveWithOptions(
        channel: Channel,
        preferredDefinition: String? = null,
        rotateNode: Boolean = false,
    ): StreamResolveResult = resolve(channel, preferredDefinition)
}

/**
 * 普通静态 IPTV / M3U / JSON 频道的默认解析器。
 */
object StaticStreamResolver : StreamResolver {
    override fun resolve(
        channel: Channel,
        preferredDefinition: String?,
    ): StreamResolveResult {
        val url = channel.streamUrl.trim()
        if (url.isEmpty() || (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true))) {
            return StreamResolveResult.Failure(
                error = StreamResolveError.UNSUPPORTED_SOURCE,
                message = "empty or unsupported stream url",
            )
        }
        return StreamResolveResult.Success(
            ResolvedStream(
                url = url,
                headers = channel.headers,
                expiresAtMs = null,
                mimeType = StreamTypeDetector.inferMimeType(url),
                allowedHeaderHosts = emptySet(),
            ),
        )
    }
}
