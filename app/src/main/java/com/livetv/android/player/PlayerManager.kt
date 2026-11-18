package com.livetv.android.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.livetv.android.domain.model.Channel

/**
 * Media3 桥接层（Android / Media3 专用）。
 *
 * 职责：
 * - 持有并复用**同一个** `ExoPlayer` 实例（`onStart` → `onStop` 期间切台/切源/重试**不重建**）；
 * - 按频道的 `headers` 构建 `DataSource`（请求头只走 DataSource 层，不走 `setMediaItem`）；
 * - 把 Media3 回调翻译成纯策略层事件，交给 [PlaybackReducer]；
 * - 用独立的 `retryHandler` 调度重试延时，切台 / 手动重连 / `onStop` 时清理待执行任务。
 *
 * 纯策略逻辑（重试与主备源切换）全部在 [RetryPolicy] 中，本类只负责调度。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerManager(
    context: Context,
    private val onStateChanged: (PlaybackState) -> Unit,
    private val onChannelChanged: (Channel) -> Unit,
) {

    companion object {
        /** 连接超时（毫秒）。 */
        const val CONNECT_TIMEOUT_MS = 10_000

        /** 读取超时（毫秒）。 */
        const val READ_TIMEOUT_MS = 10_000

        /** 切源后的等待时间（毫秒），给播放器一点缓冲再加载下一条源。 */
        const val SWITCH_SOURCE_DELAY_MS = 500L

        /** Media3 中被视作 HTTP 状态码的错误码区间（兜底防线）。 */
        val HTTP_STATUS_RANGE = 2000..2999

        /** 遍历 cause 链的最大深度，防止异常成环时死循环。 */
        const val MAX_CAUSE_DEPTH = 10
    }

    private val appContext: Context = context.applicationContext

    /** 独立的重试调度器（不复用主线程默认 Handler）。 */
    private val retryHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null

    /** 当前播放的频道。 */
    var currentChannel: Channel? = null
        private set

    /** 当前重试状态（纯策略层数据）。 */
    var retryState: RetryPolicy.RetryState = RetryPolicy.RetryState()
        private set

    /** 当前播放状态。 */
    var playbackState: PlaybackState = PlaybackState.Idle
        private set

    /** 频道切换回调（用于更新 UI 提示）。 */
    var onChannelSwitched: ((Channel) -> Unit)? = null

    /** 自动重试提示回调（已重试次数, 最大重试次数）。 */
    var onRetrying: ((Int, Int) -> Unit)? = null

    /** 切换到备用线路的提示回调（备用源下标）。 */
    var onSwitchingSource: ((Int) -> Unit)? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            dispatch(
                when (playbackState) {
                    Player.STATE_BUFFERING -> PlaybackEvent.Buffering
                    Player.STATE_READY -> PlaybackEvent.Ready
                    Player.STATE_ENDED -> PlaybackEvent.Released
                    else -> return
                }
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                markPlaying()
            }
            dispatch(if (isPlaying) PlaybackEvent.Playing else PlaybackEvent.Paused)
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError(error)
        }
    }

    // ---------------- 生命周期 ----------------

    /**
     * 创建播放器（若尚未创建）并准备播放指定频道。
     *
     * @param channel 要播放的频道；为 null 时仅创建播放器不加载媒体。
     */
    fun start(channel: Channel?) {
        if (player == null) {
            player = ExoPlayer.Builder(appContext).build().apply {
                addListener(playerListener)
            }
        }
        if (channel != null) {
            switchChannel(channel)
        }
    }

    /**
     * 释放播放器与待执行的重试任务。
     *
     * `onStop` 之后再次 `onStart` 会重新创建播放器实例（符合 Android 生命周期预期）。
     */
    fun stop() {
        retryHandler.removeCallbacksAndMessages(null)
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
        currentChannel = null
        retryState = RetryPolicy.RetryState()
        dispatch(PlaybackEvent.Released)
    }

    // ---------------- 频道 / 源操作 ----------------

    /**
     * 切换到指定频道：**丢弃旧频道的重试状态**，从主源重新开始。
     */
    fun switchChannel(channel: Channel) {
        retryHandler.removeCallbacksAndMessages(null)
        currentChannel = channel
        val candidates = RetryPolicy.candidateUrls(channel)
        retryState = RetryPolicy.onChannelSwitched(candidates.size)
        onChannelChanged(channel)
        onChannelSwitched?.invoke(channel)
        val url = candidates.getOrNull(retryState.sourceIndex)
        if (url.isNullOrEmpty()) {
            dispatch(PlaybackEvent.Fatal(PlaybackError.SourceParsing))
            return
        }
        prepare(url, channel)
    }

    /**
     * 手动重置（用户按 OK）：回到主源，计数归零，重新开始。
     */
    fun manualReset() {
        val channel = currentChannel ?: return
        retryHandler.removeCallbacksAndMessages(null)
        retryState = RetryPolicy.onManualReset(retryState)
        val candidates = RetryPolicy.candidateUrls(channel)
        val url = candidates.getOrNull(retryState.sourceIndex)
        if (url.isNullOrEmpty()) {
            dispatch(PlaybackEvent.Fatal(PlaybackError.SourceParsing))
            return
        }
        dispatch(PlaybackEvent.ManualReset)
        prepare(url, channel)
    }

    /**
     * 用指定地址与频道的请求头准备播放。
     *
     * 只重建 `MediaSource`/`DataSource`，**不重建 ExoPlayer**。
     */
    private fun prepare(url: String, channel: Channel) {
        val exoPlayer = player ?: return
        val mediaSource = buildMediaSource(url, channel)
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        dispatch(PlaybackEvent.Prepare)
    }

    /**
     * 当前实际使用的播放地址（主源或备用源）。
     */
    fun currentUrl(): String? {
        val channel = currentChannel ?: return null
        return RetryPolicy.candidateUrls(channel).getOrNull(retryState.sourceIndex)
    }

    /**
     * 构建 MediaSource：请求头与超时都配置在 `DataSource` 层。
     */
    private fun buildMediaSource(url: String, channel: Channel): MediaSource {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(channel.headers)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
        return ProgressiveMediaSourceFactoryHolder.create(dataSourceFactory, url)
    }

    // ---------------- 错误与重试调度 ----------------

    /**
     * 处理播放错误：交给纯策略层决策，再按结果调度重试 / 切源 / 致命错误。
     */
    private fun handlePlaybackError(error: PlaybackException) {
        val mapped = mapException(error)
        val outcome = RetryPolicy.onFailure(retryState, mapped)
        when (outcome) {
            is RetryPolicy.RetryOutcome.Retry -> {
                retryState = retryState.copy(retryCount = retryState.retryCount + 1)
                dispatch(PlaybackEvent.Failed(mapped))
                onRetrying?.invoke(retryState.retryCount, RetryPolicy.MAX_RETRIES_PER_SOURCE)
                scheduleReload(outcome.delayMs)
            }

            is RetryPolicy.RetryOutcome.SwitchSource -> {
                dispatch(PlaybackEvent.Failed(mapped))
                retryState = retryState.copy(sourceIndex = outcome.index, retryCount = 0)
                onSwitchingSource?.invoke(outcome.index)
                scheduleReload(SWITCH_SOURCE_DELAY_MS)
            }

            RetryPolicy.RetryOutcome.Fatal -> {
                dispatch(PlaybackEvent.Fatal(mapped))
            }
        }
    }

    /**
     * 调度一次重新加载当前地址。
     *
     * 回调执行时会再次校验"频道没变"，避免切台后旧的重试任务误伤新频道。
     */
    private fun scheduleReload(delayMs: Long) {
        val channel = currentChannel ?: return
        val expectedChannel = channel
        val url = RetryPolicy.candidateUrls(expectedChannel).getOrNull(retryState.sourceIndex)
        if (url.isNullOrEmpty()) {
            dispatch(PlaybackEvent.Fatal(PlaybackError.SourceParsing))
            return
        }
        retryHandler.postDelayed({
            if (currentChannel === expectedChannel) {
                prepare(url, expectedChannel)
            }
        }, delayMs)
    }

    /**
     * 播放成功（由桥接层在收到可播放信号后调用）：重试计数清零，保持当前源。
     */
    fun markPlaying() {
        retryState = RetryPolicy.onSuccess(retryState)
    }

    /**
     * 把 Media3 异常翻译成纯策略层的错误分类。
     *
     * 状态码提取优先级：
     * 1. **遍历 cause 链**找 `HttpDataSource.InvalidResponseCodeException`——这才是真正的 HTTP 响应码；
     * 2. 兜底：`PlaybackException.errorCode` 落在 Media3 的"响应码类"区间时也当作 HTTP 状态码。
     */
    private fun mapException(error: PlaybackException): PlaybackError {
        // 1) 优先从 cause 链里取真实响应码
        val responseCode = findInvalidResponseCode(error)
        if (responseCode != null) {
            return PlaybackErrorMapper.map(responseCode, PlaybackErrorMapper.CauseKind.HTTP_STATUS)
        }

        // 2) 兜底：Media3 自身的错误码区间
        val fallbackCode = error.errorCode.takeIf { it in HTTP_STATUS_RANGE }

        val kind = classifyCause(error.cause)
        return if (fallbackCode != null) {
            PlaybackErrorMapper.map(fallbackCode, PlaybackErrorMapper.CauseKind.HTTP_STATUS)
        } else {
            PlaybackErrorMapper.map(null, kind)
        }
    }

    /**
     * 沿 cause 链向下查找 `InvalidResponseCodeException`，返回其 HTTP 响应码。
     *
     * 链上可能有多个嵌套异常（`PlaybackException` → `HttpDataSourceException` → `InvalidResponseCodeException`），
     * 因此必须逐层遍历而不是只看第一层 cause。
     */
    private fun findInvalidResponseCode(error: PlaybackException): Int? {
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode
            }
            cause = cause.cause
            depth++
        }
        return null
    }

    /** 按 cause 的具体类型归类失败原因。 */
    private fun classifyCause(cause: Throwable?): PlaybackErrorMapper.CauseKind = when (cause) {
        is java.net.SocketTimeoutException -> PlaybackErrorMapper.CauseKind.TIMEOUT
        is java.net.UnknownHostException -> PlaybackErrorMapper.CauseKind.NETWORK
        is java.net.ConnectException -> PlaybackErrorMapper.CauseKind.NETWORK
        is java.io.IOException -> PlaybackErrorMapper.CauseKind.NETWORK
        else -> PlaybackErrorMapper.CauseKind.UNKNOWN
    }

    /** 派发事件并更新状态。 */
    private fun dispatch(event: PlaybackEvent) {
        playbackState = PlaybackReducer.reduce(playbackState, event)
        onStateChanged(playbackState)
    }

    /** MediaSource 构建的薄封装，便于在测试中替换。 */
    private object ProgressiveMediaSourceFactoryHolder {
        fun create(
            dataSourceFactory: DefaultHttpDataSource.Factory,
            url: String,
        ): MediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
    }
}
