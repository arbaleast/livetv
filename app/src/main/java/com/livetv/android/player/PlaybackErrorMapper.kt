package com.livetv.android.player

/**
 * 错误分类映射（纯 Kotlin）。
 *
 * 只接收**原始字段**（HTTP 状态码 + 原因种类），不接触 Media3 的异常类型，
 * 因此可以在纯 JVM 单测中完整覆盖。
 */
object PlaybackErrorMapper {

    /**
     * 失败原因种类（纯 Kotlin 枚举）。
     *
     * 桥接层负责把 Media3 的异常翻译成这里的枚举。
     */
    enum class CauseKind {
        /** 网络层失败（连不上、DNS 失败、连接被重置等）。 */
        NETWORK,

        /** 请求超时。 */
        TIMEOUT,

        /** 直播源解析失败（清单损坏、格式不支持）。 */
        SOURCE_PARSING,

        /** 服务端返回了错误状态码。 */
        HTTP_STATUS,

        /** 其它未知原因。 */
        UNKNOWN,
    }

    /**
     * 把原始字段映射为 [PlaybackError]。
     *
     * 规则：
     * - [CauseKind.HTTP_STATUS] 且 `responseCode` 非空 → [PlaybackError.HttpStatus]；
     * - [CauseKind.HTTP_STATUS] 但 `responseCode` 为空 → [PlaybackError.Unknown]；
     * - [CauseKind.NETWORK] → [PlaybackError.Network]；
     * - [CauseKind.TIMEOUT] → [PlaybackError.Timeout]；
     * - [CauseKind.SOURCE_PARSING] → [PlaybackError.SourceParsing]；
     * - [CauseKind.UNKNOWN] → [PlaybackError.Unknown]。
     */
    fun map(responseCode: Int?, causeKind: CauseKind): PlaybackError = when (causeKind) {
        CauseKind.HTTP_STATUS -> responseCode?.let { PlaybackError.HttpStatus(it) } ?: PlaybackError.Unknown()
        CauseKind.NETWORK -> PlaybackError.Network
        CauseKind.TIMEOUT -> PlaybackError.Timeout
        CauseKind.SOURCE_PARSING -> PlaybackError.SourceParsing
        CauseKind.UNKNOWN -> PlaybackError.Unknown()
    }
}
