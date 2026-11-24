package com.livetv.android.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.stream.SessionGenerationGate
import com.livetv.android.domain.stream.StreamResolveError
import com.livetv.android.domain.stream.Clock
import com.livetv.android.domain.stream.MonotonicClock
import com.livetv.android.domain.stream.StaticStreamResolver
import com.livetv.android.domain.stream.StreamRefreshCoordinator
import com.livetv.android.domain.stream.StreamResolver
import com.livetv.android.domain.stream.StreamTypeDetector
import com.livetv.android.media.Anime4kCnnGlEffect
import com.livetv.android.media.NoRetryOnClientErrorPolicy
import com.livetv.android.media.ScopedHeaderDataSourceFactory
import com.livetv.android.util.VideoEnhanceMode
import com.livetv.android.util.VideoEnhancePolicy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    /**
     * 播放会话解析器：普通 IPTV 用 [StaticStreamResolver]（行为与改造前一致），
     * 动态签名地址（如 MGTV）由具体实现解析出带有效期的会话。
     */
    private val streamResolver: StreamResolver = StaticStreamResolver,
    clock: Clock = MonotonicClock,
) {

    companion object {
        /** 连接超时（毫秒）。 */
        const val CONNECT_TIMEOUT_MS = 10_000

        /** 读取超时（毫秒）。 */
        const val READ_TIMEOUT_MS = 10_000

        /** 切源后的等待时间（毫秒），给播放器一点缓冲再加载下一条源。 */
        const val SWITCH_SOURCE_DELAY_MS = 500L

        /**
         * 快速失败（401 / 403 / 429）后切备用源的等待时间（毫秒）。
         *
         * 这类错误重试也不会变好，因此不再走 1500ms / 3000ms 退避，直接换源。
         */
        const val FAST_FAIL_SWITCH_DELAY_MS = 0L

        /**
         * Media3 中唯一代表"服务端返回了错误状态码"的错误码
         * （`PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS`）。
         *
         * 注意：不能把 `2000..2999` 整段当成 HTTP 状态码——该区间是 Media3 的 IO 错误段，
         * 其中 2001 是"网络连接失败"、2002 是"连接超时"，都不是 HTTP 响应码。
         * 阶段 5.5 真机实测发现过"网络故障被显示成 HTTP 2001"的错误提示，故收窄。
         */
        const val ERROR_CODE_IO_BAD_HTTP_STATUS = 2004

        /** 遍历 cause 链的最大深度，防止异常成环时死循环。 */
        const val MAX_CAUSE_DEPTH = 10

        /** logcat tag：播放/会话诊断。 */
        private const val TAG = "LiveTvPlayer"

        /** 向动态源（如 MGTV）请求首选清晰度编号（3=720P），服务端未开放时自动回退至最高可用清晰度。 */
        private const val DEFAULT_PREFERRED_DEFINITION = "3"

    }

    private val appContext: Context = context.applicationContext

    /** 独立的重试调度器（不复用主线程默认 Handler）。 */
    private val retryHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var videoEnhanceMode: VideoEnhanceMode = VideoEnhanceMode.OFF
    private var hasAppliedVideoEffectsOnce: Boolean = false

    /** 当前播放的频道。 */
    var currentChannel: Channel? = null
        private set

    /** 当前重试状态（纯策略层数据）。 */
    var retryState: RetryPolicy.RetryState = RetryPolicy.RetryState()
        private set

    /** 当前播放状态。 */
    var playbackState: PlaybackState = PlaybackState.Idle
        private set

    /** 动态会话协调器：负责地址到期刷新与 HTTP 403 单次重解析。 */
    private val streamCoordinator = StreamRefreshCoordinator(streamResolver, clock)

    /**
     * 会话解析后台线程（单线程，保证解析与协调器状态串行访问）。
     *
     * 之所以必须有它：`StreamResolver.resolve()` 对动态源（MGTV）会发起真实 HTTP 请求，
     * 在主线程调用会直接抛 `NetworkOnMainThreadException`。
     */
    private val resolutionExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "livetv-session-resolve").apply { isDaemon = true }
        }

    /** 会话代际号：切台时递增，用于丢弃过期的解析结果。 */
    private val generationGate = SessionGenerationGate()

    /** 当前生效的播放会话（静态频道为 null，动态频道为解析结果）。 */
    private var activeSession: PlaybackSession? = null

    /**
     * 解析后的播放会话快照。
     *
     * @param url 实际播放地址
     * @param headers 该会话专用的请求头
     * @param mimeType 显式 MIME（用于无 `.m3u8` 后缀的 HLS 识别）；`null` 表示交给 Media3 自动识别
     * @param allowedHeaderHosts 请求头允许出现的域名白名单（空集合表示不限制）
     * @param expiresAtMs 地址过期时间戳；`null` 表示静态地址
     * @param dynamic 是否为动态会话（动态会话不走主备源切换，只按会话重新解析）
     */
    private data class PlaybackSession(
        val url: String,
        val headers: Map<String, String>,
        val mimeType: String?,
        val allowedHeaderHosts: Set<String>,
        val expiresAtMs: Long?,
        val dynamic: Boolean,
    )

    /** 频道切换回调（用于更新 UI 提示）。 */
    var onChannelSwitched: ((Channel) -> Unit)? = null

    /** 自动重试提示回调（已重试次数, 最大重试次数）。 */
    var onRetrying: ((Int, Int) -> Unit)? = null

    /** 切换到备用线路的提示回调（备用源下标）。 */
    var onSwitchingSource: ((Int) -> Unit)? = null

    /** ExoPlayer 实例创建或释放时的回调，供 UI 绑定/解绑 `PlayerView.player`。 */
    var onPlayerChanged: ((ExoPlayer?) -> Unit)? = null

    /** 视频流格式/尺寸变更回调，供信息浮层即时刷新。 */
    var onVideoMetadataChanged: (() -> Unit)? = null

    data class VideoInfoSnapshot(
        val inputWidth: Int,
        val inputHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val formatFps: Float,
        val codecMime: String?,
        val videoCodecs: String?,
        val bitrateBps: Int,
        val audioMime: String?,
        val audioCodecs: String?,
        val audioBitrateBps: Int,
        val renderedFrames: Int,
        val droppedFrames: Int,
        val bufferedMs: Long,
        val streamHost: String?,
        val streamType: String,
        val enhanceMode: VideoEnhanceMode,
        val glPipelineActive: Boolean,
    )

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            onVideoMetadataChanged?.invoke()
            when (playbackState) {
                Player.STATE_BUFFERING -> dispatch(PlaybackEvent.Buffering)
                Player.STATE_READY -> dispatch(PlaybackEvent.Ready)
                Player.STATE_ENDED -> {
                    handlePlaybackError(
                        PlaybackException(
                            "Live stream ended unexpectedly",
                            null,
                            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        ),
                    )
                }
                else -> return
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                markPlaying()
            }
            onVideoMetadataChanged?.invoke()
            dispatch(if (isPlaying) PlaybackEvent.Playing else PlaybackEvent.Paused)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            onVideoMetadataChanged?.invoke()
        }

        override fun onTracksChanged(tracks: Tracks) {
            onVideoMetadataChanged?.invoke()
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
            hasAppliedVideoEffectsOnce = false
            player = ExoPlayer.Builder(appContext).build().apply {
                playWhenReady = true
                addListener(playerListener)
                if (VideoEnhancePolicy.shouldApplyOnPlayerStart(videoEnhanceMode)) {
                    applyEffectsToPlayer(this, videoEnhanceMode)
                }
            }
        }
        onPlayerChanged?.invoke(player)
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
        generationGate.next()
        onPlayerChanged?.invoke(null)
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
        currentChannel = null
        retryState = RetryPolicy.RetryState()
        activeSession = null
        if (!resolutionExecutor.isShutdown) {
            resolutionExecutor.execute { streamCoordinator.resetSession() }
        }
        dispatch(PlaybackEvent.Released)
    }

    /** 销毁后台解析线程池，应在 `Activity.onDestroy()` 调用。 */
    fun release() {
        stop()
        resolutionExecutor.shutdownNow()
    }

    // ---------------- 频道 / 源操作 ----------------

    fun getVideoInfoSnapshot(): VideoInfoSnapshot? {
        val activePlayer = player ?: return null
        val format = activePlayer.videoFormat
        val audioFormat = activePlayer.audioFormat
        val size = activePlayer.videoSize
        val inputW = format?.width?.takeIf { it > 0 } ?: size.width
        val inputH = format?.height?.takeIf { it > 0 } ?: size.height
        val counters = activePlayer.videoDecoderCounters
        val activeUrl = activeSession?.url ?: currentChannel?.streamUrl
        val host = activeUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        val isHls = activeUrl != null &&
            (activeSession?.mimeType == StreamTypeDetector.MIME_HLS || StreamTypeDetector.isLikelyHlsUrl(activeUrl))
        return VideoInfoSnapshot(
            inputWidth = inputW,
            inputHeight = inputH,
            outputWidth = size.width,
            outputHeight = size.height,
            formatFps = format?.frameRate ?: -1f,
            codecMime = format?.sampleMimeType,
            videoCodecs = format?.codecs,
            bitrateBps = format?.bitrate ?: -1,
            audioMime = audioFormat?.sampleMimeType,
            audioCodecs = audioFormat?.codecs,
            audioBitrateBps = audioFormat?.bitrate ?: -1,
            renderedFrames = counters?.renderedOutputBufferCount ?: 0,
            droppedFrames = counters?.droppedBufferCount ?: 0,
            bufferedMs = activePlayer.totalBufferedDuration.coerceAtLeast(0L),
            streamHost = host,
            streamType = if (isHls) "HLS" else "Progressive",
            enhanceMode = videoEnhanceMode,
            glPipelineActive = hasAppliedVideoEffectsOnce,
        )
    }

    fun setVideoEnhanceMode(mode: VideoEnhanceMode) {
        videoEnhanceMode = mode
        val activePlayer = player ?: return
        if (mode == VideoEnhanceMode.OFF && !hasAppliedVideoEffectsOnce) {
            return
        }
        applyEffectsToPlayer(activePlayer, mode)
    }

    private fun applyEffectsToPlayer(targetPlayer: ExoPlayer, mode: VideoEnhanceMode) {
        if (mode == VideoEnhanceMode.OFF) {
            targetPlayer.setVideoEffects(emptyList())
        } else {
            hasAppliedVideoEffectsOnce = true
            targetPlayer.setVideoEffects(listOf(Anime4kCnnGlEffect(mode)))
        }
    }

    /**
     * 切换到指定频道：**丢弃旧频道的重试状态**，从主源重新开始。
     *
     * 会话解析（可能包含网络请求）在后台线程完成，回到主线程前用代际号校验，
     * 保证快速连点时旧频道的解析结果不会覆盖新频道。
     */
    fun switchChannel(channel: Channel) {
        retryHandler.removeCallbacksAndMessages(null)
        currentChannel = channel
        activeSession = null
        val generation = generationGate.next()
        // 切台即刻停止旧频道并切入 Loading，避免后台解析动态源（1~3 秒）期间
        // 画面仍停留在旧频道或旧错误提示上导致“切台无响应”的错觉。
        player?.stop()
        player?.clearMediaItems()
        onChannelChanged(channel)
        onChannelSwitched?.invoke(channel)
        dispatch(PlaybackEvent.SwitchChannel)
        resolutionExecutor.execute {
            streamCoordinator.resetSession()
            val resolution = resolveSession(channel)
            retryHandler.post {
                if (!generationGate.isCurrent(generation) || currentChannel !== channel) return@post
                applySessionResolution(channel, resolution, PlaybackEvent.SwitchChannel)
            }
        }
    }

    /**
     * 手动重置（用户按 OK）：回到主源，计数归零，重新开始。
     */
    fun manualReset() {
        val channel = currentChannel ?: return
        retryHandler.removeCallbacksAndMessages(null)
        retryState = RetryPolicy.onManualReset(retryState)
        val generation = generationGate.next()
        val previousSession = activeSession
        dispatch(PlaybackEvent.ManualReset)
        resolutionExecutor.execute {
            val session = if (previousSession?.dynamic == true) {
                // 动态地址可能已过期：重新解析一次再重连
                streamCoordinator.resetSession()
                resolveSession(channel)
            } else {
                SessionResolution.Ready(previousSession)
            }
            retryHandler.post {
                if (!generationGate.isCurrent(generation) || currentChannel !== channel) return@post
                applySessionResolution(channel, session, PlaybackEvent.Retry)
            }
        }
    }

    /** 把一次会话解析结果应用到播放器（**必须在主线程**）。 */
    private fun applySessionResolution(
        channel: Channel,
        resolution: SessionResolution,
        event: PlaybackEvent,
    ) {
        when (resolution) {
            is SessionResolution.Failed -> {
                Log.w(TAG, "会话解析失败：channel=${channel.id} error=${resolution.error}")
                // 动态源解析失败：直接给出精确错误，不再回退到空地址
                dispatch(PlaybackEvent.Fatal(resolution.error))
            }

            is SessionResolution.Ready -> {
                resolution.session?.let { s ->
                    Log.i(
                        TAG,
                        "会话就绪：channel=${channel.id} host=${hostOf(s.url)} " +
                            "mime=${s.mimeType} dynamic=${s.dynamic} def=${s.headers.size} headers",
                    )
                }
                activeSession = resolution.session
                val candidates = candidatesFor(resolution.session, channel)
                retryState = RetryPolicy.onChannelSwitched(candidates.size)
                val url = candidates.getOrNull(retryState.sourceIndex)
                if (url.isNullOrEmpty()) {
                    dispatch(PlaybackEvent.Fatal(PlaybackError.SourceParsing))
                    return
                }
                prepare(url, channel, event)
            }
        }
    }

    /**
     * 解析当前频道的播放会话（**必须在解析线程调用**）。
     *
     * - 解析成功：返回会话快照（静态频道同样走这里，只是 `dynamic=false`）；
     * - 静态频道解析失败：返回 `Ready(null)`，调用方回退到频道自带的静态地址；
     * - 动态频道（`mgtv:` 等没有静态地址的频道）解析失败：返回 `Failed`，由调用方直接 Fatal。
     */
    private fun resolveSession(channel: Channel): SessionResolution {
        val result = streamCoordinator.prepareForPlayback(
            channel,
            preferredDefinition = DEFAULT_PREFERRED_DEFINITION,
        )
        if (result is com.livetv.android.domain.stream.StreamResolveResult.Success) {
            val stream = result.stream
            return SessionResolution.Ready(
                PlaybackSession(
                    url = stream.url,
                    headers = stream.headers,
                    mimeType = stream.mimeType,
                    allowedHeaderHosts = stream.allowedHeaderHosts,
                    expiresAtMs = stream.expiresAtMs,
                    dynamic = stream.expiresAtMs != null || channel.streamUrl.isBlank(),
                ),
            )
        }
        val failure = result as com.livetv.android.domain.stream.StreamResolveResult.Failure
        if (channel.streamUrl.isNotBlank()) {
            // 静态频道：保留原有回退行为
            return SessionResolution.Ready(null)
        }
        return SessionResolution.Failed(mapResolveError(failure))
    }

    /** 把会话解析错误映射成播放错误（用于精确文案）。 */
    /** 只取 host 打日志，避免把带签名的完整 URL 写进 logcat。 */
    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull() ?: "?"

    private fun mapResolveError(
        failure: com.livetv.android.domain.stream.StreamResolveResult.Failure,
    ): PlaybackError = when (failure.error) {
        StreamResolveError.HTTP_FORBIDDEN -> PlaybackError.HttpStatus(403)
        StreamResolveError.HTTP_NOT_FOUND -> PlaybackError.HttpStatus(404)
        StreamResolveError.HTTP_RATE_LIMITED -> PlaybackError.HttpStatus(429)
        StreamResolveError.NETWORK_ERROR -> PlaybackError.Network
        StreamResolveError.PARSE_ERROR -> PlaybackError.SourceParsing
        StreamResolveError.UNSUPPORTED_SOURCE -> PlaybackError.SourceParsing
        StreamResolveError.AUTH_UNAVAILABLE -> PlaybackError.Unknown(failure.message)
    }

    /** 会话解析结果。 */
    private sealed interface SessionResolution {
        data class Ready(val session: PlaybackSession?) : SessionResolution
        data class Failed(val error: PlaybackError) : SessionResolution
    }

    /**
     * 候选地址列表：
     * - 动态会话只有解析出的那一个地址（过期由会话刷新处理，不走主备源切换）；
     * - 静态频道保持原有"主源 + 备用源"顺序。
     */
    private fun candidatesFor(session: PlaybackSession?, channel: Channel): List<String> {
        if (session != null && session.dynamic) return listOf(session.url)
        return RetryPolicy.candidateUrls(channel)
    }

    /**
     * 用指定地址与频道的请求头准备播放。
     *
     * 只重建 `MediaSource`/`DataSource`，**不重建 ExoPlayer**。
     */
    private fun prepare(
        url: String,
        channel: Channel,
        event: PlaybackEvent = PlaybackEvent.Prepare,
    ) {
        val exoPlayer = player ?: return
        val mediaSource = buildMediaSource(url, channel)
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
        dispatch(event)
    }

    /**
     * 当前实际使用的播放地址（主源或备用源）。
     */
    fun currentUrl(): String? {
        val session = activeSession
        if (session != null && session.dynamic) return session.url
        val channel = currentChannel ?: return null
        return RetryPolicy.candidateUrls(channel).getOrNull(retryState.sourceIndex)
    }

    /** 切换播放/暂停，返回切换后的 `playWhenReady` 状态。 */
    fun togglePlayPause(): Boolean {
        val exoPlayer = player ?: return false
        val target = !exoPlayer.playWhenReady
        exoPlayer.playWhenReady = target
        return target
    }

    /** 当前是否处于计划播放状态（用于刷新播放/暂停按钮文案）。 */
    fun isPlayWhenReady(): Boolean = player?.playWhenReady == true

    /**
     * 构建 MediaSource：请求头与超时都配置在 `DataSource` 层。
     *
     * - 显式 MIME：来自会话解析结果（`application/x-mpegURL`），
     *   保证**无 `.m3u8` 后缀且服务端 Content-Type 不规范**的 HLS 也能进入 HLS 解析器；
     * - Host 白名单：动态会话的专用请求头只允许出现在白名单域名上，
     *   并阻断跨协议跳转；静态频道保持原有"工厂默认属性"行为，零回归。
     */
    private fun buildMediaSource(url: String, channel: Channel): MediaSource {
        val session = activeSession
        val headers = session?.headers ?: channel.headers
        val allowedHosts = session?.allowedHeaderHosts ?: emptySet()
        val mimeType = session?.mimeType
        val baseFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
        val dataSourceFactory: DataSource.Factory = if (allowedHosts.isEmpty()) {
            baseFactory
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)
        } else {
            ScopedHeaderDataSourceFactory(
                delegate = baseFactory.setAllowCrossProtocolRedirects(false),
                headers = headers,
                allowedHostSuffixes = allowedHosts,
            )
        }
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .apply { mimeType?.let { setMimeType(it) } }
            .build()
        return ProgressiveMediaSourceFactoryHolder.create(dataSourceFactory, mediaItem)
    }

    // ---------------- 错误与重试调度 ----------------

    /**
     * 处理播放错误：
     * - 动态会话（带有效期的签名地址）先交给 [StreamRefreshCoordinator]：
     *   403 最多重新解析 1 次、429 立即快速失败，杜绝无限重试；
     * - 其余情况沿用既有 [RetryPolicy] 重试 / 主备源切换策略。
     */
    private fun handlePlaybackError(error: PlaybackException) {
        val mapped = mapException(error)
        if (mapped is PlaybackError.HttpStatus && activeSession?.dynamic == true) {
            val channel = currentChannel
            if (channel != null) {
                val generation = generationGate.current()
                resolutionExecutor.execute {
                    val action = streamCoordinator.onPlaybackHttpError(
                        channel,
                        mapped.code,
                        preferredDefinition = DEFAULT_PREFERRED_DEFINITION,
                    )
                    retryHandler.post {
                        if (!generationGate.isCurrent(generation) || currentChannel !== channel) return@post
                        when (action) {
                            is StreamRefreshCoordinator.RefreshAction.Reconnect ->
                                applyResolvedStream(action.stream)

                            is StreamRefreshCoordinator.RefreshAction.FastFail ->
                                dispatch(PlaybackEvent.Fatal(mapped))

                            is StreamRefreshCoordinator.RefreshAction.DelegateToRetryPolicy ->
                                applyRetryPolicy(mapped)
                        }
                    }
                }
                return
            }
        }
        applyRetryPolicy(mapped)
    }

    /**
     * 使用重新解析出的会话重连（动态地址过期或 403 刷新后拿到新地址）。
     */
    private fun applyResolvedStream(stream: com.livetv.android.domain.stream.ResolvedStream) {
        val channel = currentChannel ?: return
        Log.i(TAG, "403 重解析就绪：channel=${channel.id} host=${Uri.parse(stream.url).host.orEmpty()}")
        activeSession = PlaybackSession(
            url = stream.url,
            headers = stream.headers,
            mimeType = stream.mimeType,
            allowedHeaderHosts = stream.allowedHeaderHosts,
            expiresAtMs = stream.expiresAtMs,
            dynamic = true,
        )
        retryState = retryState.copy(sourceIndex = 0, retryCount = 0)
        prepare(stream.url, channel, PlaybackEvent.Retry)
    }

    /**
     * 既有重试策略：每源退避重试 → 切换备用源 → 致命错误；401/403/429 走快速失败。
     */
    private fun applyRetryPolicy(mapped: PlaybackError) {
        if (RetryPolicy.isFastFail(mapped)) {
            handleFastFail(mapped)
            return
        }
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
     * 快速失败：HTTP 401 / 403 / 429。
     *
     * 这类结论重试也不会变好（鉴权失败、防盗链拦截、风控限流），因此：
     * - 取消当前源所有待执行的退避重试；
     * - 还有备用源 → **立即**切到下一条源（不再等 1500ms / 3000ms）；
     * - 没有备用源 → `Fatal`，进入 `PlaybackState.Error(fatal = true)` 等用户手动重置。
     */
    private fun handleFastFail(mapped: PlaybackError) {
        retryHandler.removeCallbacksAndMessages(null)
        when (val outcome = RetryPolicy.onFailure(retryState, mapped)) {
            is RetryPolicy.RetryOutcome.SwitchSource -> {
                retryState = retryState.copy(sourceIndex = outcome.index, retryCount = 0)
                onSwitchingSource?.invoke(outcome.index)
                scheduleReload(FAST_FAIL_SWITCH_DELAY_MS)
            }

            else -> dispatch(PlaybackEvent.Fatal(mapped))
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
        val expectedGeneration = generationGate.current()
        val url = candidatesFor(activeSession, expectedChannel).getOrNull(retryState.sourceIndex)
        if (url.isNullOrEmpty()) {
            dispatch(PlaybackEvent.Fatal(PlaybackError.SourceParsing))
            return
        }
        retryHandler.postDelayed({
            if (generationGate.isCurrent(expectedGeneration) && currentChannel === expectedChannel) {
                prepare(url, expectedChannel, PlaybackEvent.Retry)
            }
        }, delayMs)
    }

    /**
     * 播放成功（由桥接层在收到可播放信号后调用）：重试计数清零，保持当前源。
     */
    fun markPlaying() {
        retryState = RetryPolicy.onSuccess(retryState)
        // 起播成功：重置动态会话的 403 刷新预算，长播数小时后再次到期仍可刷新一次
        resolutionExecutor.execute { streamCoordinator.onPlaybackRecovered() }
    }

    /**
     * 把 Media3 异常翻译成纯策略层的错误分类。
     *
     * 状态码只从 **cause 链**里取 `HttpDataSource.InvalidResponseCodeException.responseCode`——
     * 那才是真正的 HTTP 响应码。
     *
     * 不使用 `PlaybackException.errorCode` 反推状态码：该错误码属于 Media3 自己的 IO 语义
     * （2001 网络连接失败、2002 连接超时、2004 坏 HTTP 状态…），把它当 HTTP 状态码展示会
     * 产生"HTTP 2001"这类误导性文案。
     */
    private fun mapException(error: PlaybackException): PlaybackError {
        val responseCode = findInvalidResponseCode(error)
        if (responseCode != null) {
            return PlaybackErrorMapper.map(responseCode, PlaybackErrorMapper.CauseKind.HTTP_STATUS)
        }
        return PlaybackErrorMapper.map(null, classifyCause(error))
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

    /**
     * 沿 cause 链判断失败原因。
     *
     * 逐层遍历而不是只看第一层：`HttpDataSourceException` 会包着真正的
     * `UnknownHostException` / `SocketTimeoutException`，只看第一层会一律归为网络错误。
     */
    private fun classifyCause(error: PlaybackException): PlaybackErrorMapper.CauseKind {
        var cause: Throwable? = error.cause
        var depth = 0
        var sawIoException = false
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            when (cause) {
                is java.net.SocketTimeoutException -> return PlaybackErrorMapper.CauseKind.TIMEOUT
                is java.net.UnknownHostException -> return PlaybackErrorMapper.CauseKind.NETWORK
                is java.net.ConnectException -> return PlaybackErrorMapper.CauseKind.NETWORK
                is java.io.IOException -> sawIoException = true
            }
            cause = cause.cause
            depth++
        }
        // 兜底：链上出现过 IOException 但没匹配到更具体类型时仍算网络问题
        if (sawIoException) return PlaybackErrorMapper.CauseKind.NETWORK
        // Media3 明确判定为"坏 HTTP 状态"但链上却没有响应码 -> 源解析/未知
        return if (error.errorCode == ERROR_CODE_IO_BAD_HTTP_STATUS) {
            PlaybackErrorMapper.CauseKind.SOURCE_PARSING
        } else {
            PlaybackErrorMapper.CauseKind.UNKNOWN
        }
    }

    /** 派发事件并更新状态。 */
    private fun dispatch(event: PlaybackEvent) {
        playbackState = PlaybackReducer.reduce(playbackState, event)
        onStateChanged(playbackState)
    }

    /** MediaSource 构建的薄封装：使用 DefaultMediaSourceFactory 自动支持 HLS (.m3u8) 与渐进式流。 */
    /**
     * MediaSource 构建的薄封装：使用 `DefaultMediaSourceFactory` 自动支持 HLS（`.m3u8`）与渐进式流，
     * 并挂载 [NoRetryOnClientErrorPolicy]，避免 Media3 底层把 401/403/429 再重试 3 次
     * （应用层 `RetryPolicy` 已经判定这三类不可重试，两层叠加会把一次 403 放大成 4 次请求）。
     */
    private object ProgressiveMediaSourceFactoryHolder {
        fun create(
            dataSourceFactory: DataSource.Factory,
            mediaItem: MediaItem,
        ): MediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(NoRetryOnClientErrorPolicy())
            .createMediaSource(mediaItem)
    }
}
