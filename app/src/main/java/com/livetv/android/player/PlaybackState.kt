package com.livetv.android.player

/**
 * 播放器状态（纯 Kotlin 密封接口）。
 *
 * 本文件所属的 `player` 包分为两层：
 * - **纯策略层**：`PlaybackState` / `PlaybackEvent` / `PlaybackReducer` /
 *   `PlaybackError` / `PlaybackErrorMapper` / `RetryPolicy` / `ChannelNavigator`，
 *   **禁止**引用 `android.*` 与 `androidx.media3.*`，可在纯 JVM 单测中直接执行；
 * - **桥接层**：`PlayerManager`，负责把 Media3 事件翻译成纯策略层的事件。
 */
sealed interface PlaybackState {

    /** 尚未开始准备播放（播放器未创建或已释放）。 */
    data object Idle : PlaybackState

    /** 已发起准备，等待媒体信息。 */
    data object Loading : PlaybackState

    /** 缓冲中（数据不足，无法继续播放）。 */
    data object Buffering : PlaybackState

    /** 正在播放。 */
    data object Playing : PlaybackState

    /** 已暂停。 */
    data object Paused : PlaybackState

    /**
     * 播放出错。
     *
     * @param error 错误分类（网络 / HTTP 状态码 / 源解析 / 超时 / 未知）。
     * @param fatal 是否为致命错误——所有候选源都已耗尽，只能由用户手动重置。
     */
    data class Error(
        val error: PlaybackError,
        val fatal: Boolean = false,
    ) : PlaybackState
}
