package com.livetv.android.player

/**
 * 播放事件（纯 Kotlin）。
 *
 * 由桥接层（`PlayerManager`）把 Media3 的回调翻译成这里的事件，
 * 再交给 [PlaybackReducer] 得到新的 [PlaybackState]。
 */
sealed interface PlaybackEvent {

    /** 已调用 prepare，开始加载。 */
    data object Prepare : PlaybackEvent

    /** 播放器报告缓冲中。 */
    data object Buffering : PlaybackEvent

    /** 播放器报告可播放（ready）。 */
    data object Ready : PlaybackEvent

    /** 播放位置前进中。 */
    data object Playing : PlaybackEvent

    /** 播放被暂停（用户暂停 / 播放中状态被撤销）。 */
    data object Paused : PlaybackEvent

    /**
     * 播放出错。
     *
     * @param error 错误分类。
     */
    data class Failed(val error: PlaybackError) : PlaybackEvent

    /**
     * 发生致命错误：所有候选源均已耗尽，只能由用户手动重置。
     */
    data class Fatal(val error: PlaybackError) : PlaybackEvent

    /** 用户手动重置（例如在 Error 态按下确认键），回到主源重新开始。 */
    data object ManualReset : PlaybackEvent

    /** 切换频道：无论当前处于何种状态（含致命错误态），都重置进入新频道的加载流程。 */
    data object SwitchChannel : PlaybackEvent

    /** 自动重试或切备用源发起新一轮加载（仅解锁非致命错误态）。 */
    data object Retry : PlaybackEvent

    /** 播放器已释放，回到空闲态。 */
    data object Released : PlaybackEvent
}
