package com.livetv.android.player

/**
 * 播放错误分类（纯 Kotlin）。
 *
 * 媒体库异常类型的识别留给桥接层 `PlayerManager`，
 * 纯策略层只处理这里定义的分类。
 */
sealed interface PlaybackError {

    /** 网络不可达 / 连接被拒 / DNS 失败等。 */
    data object Network : PlaybackError

    /**
     * HTTP 状态码错误。
     *
     * @param code HTTP 状态码（如 403、404、500）。
     */
    data class HttpStatus(val code: Int) : PlaybackError

    /** 直播源本身无法解析（清单损坏、格式不支持等）。 */
    data object SourceParsing : PlaybackError

    /** 连接或读取超时。 */
    data object Timeout : PlaybackError

    /** 无法归类的其它错误。 */
    data class Unknown(val message: String = "") : PlaybackError
}
