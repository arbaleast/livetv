package com.livetv.android.player

/**
 * 播放状态归约器（纯 Kotlin）。
 *
 * 承载全部状态转换规则，使状态流转可以在纯 JVM 单测中验证：
 * - 合法路径：`Idle → Loading → Buffering → Playing`（或 `Paused`）；
 * - `Error` 态下**忽略** `Buffering` / `Playing` 等事件（避免错误后又被旧回调"救活"）；
 * - 非法转换（如 `Idle` 直接 `Playing`）保持原状态不变。
 */
object PlaybackReducer {

    /**
     * 归约一次事件。
     *
     * @return 新的状态；非法转换时返回与入参相同的状态。
     */
    fun reduce(current: PlaybackState, event: PlaybackEvent): PlaybackState = when (event) {
        PlaybackEvent.Prepare -> when (current) {
            is PlaybackState.Error -> current
            PlaybackState.Idle -> PlaybackState.Loading
            else -> current
        }

        PlaybackEvent.Buffering -> when (current) {
            is PlaybackState.Error -> current
            PlaybackState.Idle -> current
            PlaybackState.Loading, PlaybackState.Buffering, PlaybackState.Playing, PlaybackState.Paused ->
                PlaybackState.Buffering
        }

        PlaybackEvent.Ready -> when (current) {
            is PlaybackState.Error -> current
            PlaybackState.Idle -> current
            PlaybackState.Loading, PlaybackState.Buffering -> PlaybackState.Buffering
            PlaybackState.Playing, PlaybackState.Paused -> current
        }

        PlaybackEvent.Playing -> when (current) {
            is PlaybackState.Error -> current
            PlaybackState.Idle -> current
            PlaybackState.Loading -> PlaybackState.Loading
            PlaybackState.Buffering, PlaybackState.Playing, PlaybackState.Paused -> PlaybackState.Playing
        }

        PlaybackEvent.Paused -> when (current) {
            is PlaybackState.Error -> current
            PlaybackState.Idle -> current
            PlaybackState.Loading, PlaybackState.Buffering -> current
            PlaybackState.Playing, PlaybackState.Paused -> PlaybackState.Paused
        }

        is PlaybackEvent.Failed -> when (current) {
            // 已经在错误态时保持首个错误，避免被后续回调覆盖成别的分类
            is PlaybackState.Error -> current
            else -> PlaybackState.Error(event.error, fatal = false)
        }

        is PlaybackEvent.Fatal -> PlaybackState.Error(event.error, fatal = true)

        PlaybackEvent.ManualReset -> when (current) {
            is PlaybackState.Error -> PlaybackState.Loading
            else -> current
        }

        PlaybackEvent.SwitchChannel -> PlaybackState.Loading

        PlaybackEvent.Retry -> when (current) {
            is PlaybackState.Error -> if (current.fatal) current else PlaybackState.Loading
            PlaybackState.Idle -> current
            PlaybackState.Loading, PlaybackState.Buffering, PlaybackState.Playing, PlaybackState.Paused ->
                PlaybackState.Loading
        }

        PlaybackEvent.Released -> PlaybackState.Idle
    }
}
