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
