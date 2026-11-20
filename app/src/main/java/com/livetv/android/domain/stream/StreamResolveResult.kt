package com.livetv.android.domain.stream

/**
 * 流地址解析与筛选的显式错误分类。
 */
enum class StreamResolveError {
    /** 无符合白盒条件的源（例如仅剩 P2P、HDCP 受限、非 HTTPS 或非 M3U8 源）。 */
    UNSUPPORTED_SOURCE,

    /** 服务端拒绝访问或签名失效（HTTP 403）。 */
    HTTP_FORBIDDEN,

    /** 频道或播放源不存在（HTTP 404）。 */
    HTTP_NOT_FOUND,

    /** 触发限流/风控（HTTP 429）。 */
    HTTP_RATE_LIMITED,

    /** JSON / 清单解析失败。 */
    PARSE_ERROR,

    /** 网络连接异常或超时。 */
    NETWORK_ERROR,

    /** 缺少合规授权凭据或签名能力（No-Go 阻断态）。 */
    AUTH_UNAVAILABLE,
}

/**
 * 通用流解析结果。
 */
sealed interface StreamResolveResult {
    data class Success(val stream: ResolvedStream) : StreamResolveResult
    data class Failure(
        val error: StreamResolveError,
        val message: String = "",
    ) : StreamResolveResult
}
