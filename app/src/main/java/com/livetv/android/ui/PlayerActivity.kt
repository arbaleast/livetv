package com.livetv.android.ui

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.livetv.android.LiveTvApplication
import com.livetv.android.R
import com.livetv.android.databinding.ActivityPlayerBinding
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.webplayer.CctvWebChannel
import com.livetv.android.webplayer.CctvWebPlayerActivity
import com.livetv.android.data.mgtv.MgtvStreamResolver
import com.livetv.android.domain.stream.StaticStreamResolver
import com.livetv.android.player.ChannelNavigator
import com.livetv.android.player.PlaybackError
import com.livetv.android.player.PlaybackState
import com.livetv.android.player.PlayerManager
import com.livetv.android.ui.mobile.ChannelDrawerAdapter
import com.livetv.android.ui.mobile.ChannelDrawerModel
import com.livetv.android.ui.mobile.ChannelDrawerRow
import com.livetv.android.ui.mobile.ChromeBackAction
import com.livetv.android.ui.mobile.CrossEngineCctvPrecheck
import com.livetv.android.ui.mobile.MobilePlayerChromeController
import com.livetv.android.ui.mobile.MobilePlayerLoadingOverlay
import com.livetv.android.ui.mobile.MobilePlayerTransitionHelper
import com.livetv.android.ui.mobile.MobileUiPreferences
import com.livetv.android.ui.mobile.PlayerChromeCapabilities
import com.livetv.android.ui.mobile.PlayerTransitionAndPipPolicy
import com.livetv.android.util.DeviceUtils
import com.livetv.android.util.PlayerKeyPolicy
import com.livetv.android.util.PlayerOrientationPolicy
import com.livetv.android.util.PlayerTouchGestureMath
import com.livetv.android.util.TvChannelRouteAction
import com.livetv.android.util.TvChannelRoutePolicy
import com.livetv.android.util.VideoEnhanceMode
import com.livetv.android.util.VideoEnhancePolicy
import com.livetv.android.util.VideoEnhanceStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 播放页。
 *
 * 播放器逻辑已抽离到 `com.livetv.android.player`：
 * - 纯策略层（`PlaybackReducer` / `RetryPolicy` / `ChannelNavigator` / `PlaybackErrorMapper`）不依赖 Android；
 * - 桥接层 `PlayerManager` 负责 ExoPlayer 生命周期、请求头/超时、重试调度。
 *
 * 本 Activity 只负责：加载频道、绑定 PlayerView、渲染状态与提示、处理遥控器按键与触控浮层/手势。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_CHANNEL_ID = "channel_id"

        /** 切台提示自动隐藏的延迟（毫秒）。 */
        const val SWITCH_HINT_AUTO_HIDE_DELAY_MS = 3_000L

        /** TV 端左上角频道名自动隐藏的延迟（毫秒）。 */
        const val CHANNEL_NAME_AUTO_HIDE_DELAY_MS = 4_000L

        /** 触控端实时视频信息条刷新间隔（毫秒）。 */
        private const val VIDEO_INFO_REFRESH_INTERVAL_MS = 1_000L

        /** TV 端频道抽屉滑入滑出时长（毫秒）。 */
        private const val DRAWER_ANIM_MS = 220L

        /** 边缘防误触保留宽度（dp），避让系统侧滑返回手势。 */
        private const val EDGE_EXCLUSION_DP = 24

        /** 抽屉内容底部稳定安全边距（dp）：固定值，不随瞬时系统栏出现而抖动。 */
        private const val DRAWER_STABLE_BOTTOM_MARGIN_DP = 8

        private const val STATE_CURRENT_CHANNEL_ID = "current_channel_id"
        private const val TAG = "PlayerActivity"
    }

    private val app: LiveTvApplication
        get() = application as LiveTvApplication

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerManager: PlayerManager
    private lateinit var navigator: ChannelNavigator
    private var drawerAdapter: ChannelDrawerAdapter? = null
    private var mobileChrome: MobilePlayerChromeController? = null
    private var mobileLoadingOverlay: MobilePlayerLoadingOverlay? = null

    /** 当前播放的频道。 */
    private var currentChannel: Channel? = null

    /** 是否是触控设备（手机 / 平板）。TV 端所有触控视图保持 `GONE`。 */
    private var isTouchDevice = false

    /** 手机端设备是否支持系统画中画（TV 端恒为 `false`）。 */
    private var supportsPip = false

    /** 是否正在执行跨引擎无感切台（期间屏蔽自动进小窗）。 */
    private var isSwitchingEngine = false

    /** 聚合目录的全部分类，供“频道列表”抽屉使用。 */
    private var categories: List<ChannelCategory> = emptyList()

    /** 当前抽屉展示的行数据，供旋转后重新居中定位当前频道。 */
    private var currentDrawerRows: List<ChannelDrawerRow> = emptyList()

    /** 当前画面比例模式（触控端切换）。 */
    private var aspectMode = PlayerTouchGestureMath.AspectMode.FIT
    private var videoEnhanceMode = VideoEnhanceMode.OFF
    private var isVideoInfoVisible = true
    private var lastVideoInfoFrames = -1
    private var lastVideoInfoSampleMs = 0L
    private var lastRxBytes = -1L
    private var measuredFps = 0f
    private var measuredSpeedKbps = 0f

    private val videoInfoRefreshRunnable = object : Runnable {
        override fun run() {
            refreshVideoInfoOverlay()
            if (isTouchDevice && isVideoInfoVisible && !isFinishing && !isDestroyed) {
                binding.root.postDelayed(this, VIDEO_INFO_REFRESH_INTERVAL_MS)
            }
        }
    }

    /** 触控端防误触锁屏标记。 */
    private var isScreenLocked = false

    /** "频道列表"抽屉是否处于打开状态。 */
    private var isDrawerOpen = false

    /** TV 端左上角频道名自动隐藏回调。 */
    private val hideChannelNameRunnable = Runnable {
        if (!isTouchDevice && !isControllerOpenedByUser) {
            binding.channelName.visibility = View.GONE
        }
    }

    private var switchHintJob: Job? = null

    /** 控制器是否由用户主动呼出（按 OK 键）。 */
    private var isControllerOpenedByUser = false

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)

        isTouchDevice = !DeviceUtils.isTvDevice(this)
        supportsPip = isTouchDevice && MobilePlayerTransitionHelper.supportsPictureInPicture(this)
        if (isTouchDevice) {
            MobilePlayerTransitionHelper.applyIncomingNoTransitionIfNeeded(this, intent)
            MobilePlayerTransitionHelper.registerActiveMobilePlayer(this) { dismissForOtherEngine() }
        }
        requestedOrientation = MobilePlayerTransitionHelper.resolveInitialOrientation(
            isTvDevice = !isTouchDevice,
            intent = intent,
        )

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        binding.playerView.controllerAutoShow = false
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility != View.VISIBLE) {
                    isControllerOpenedByUser = false
                    scheduleChannelNameAutoHide()
                } else if (!isTouchDevice) {
                    showTvChannelName(autoHide = false)
                }
            },
        )
        setContentView(binding.root)

        playerManager = PlayerManager(
            context = this,
            onStateChanged = ::renderState,
            onChannelChanged = ::renderChannel,
            streamResolver = (application as? LiveTvApplication)?.streamResolver
                ?: StaticStreamResolver,
        ).also {
            it.onPlayerChanged = { exoPlayer -> binding.playerView.player = exoPlayer }
            it.onRetrying = { retryCount, maxRetries -> showRetryingHint(retryCount, maxRetries) }
            it.onSwitchingSource = { showSwitchingSourceHint() }
            it.onVideoMetadataChanged = {
                if (isTouchDevice) {
                    refreshVideoInfoOverlay()
                }
            }
            videoEnhanceMode = if (isTouchDevice) {
                MobileUiPreferences.getVideoEnhanceMode(this)
            } else {
                VideoEnhanceMode.OFF
            }
            isVideoInfoVisible = if (isTouchDevice) {
                MobileUiPreferences.isVideoInfoVisible(this)
            } else {
                false
            }
            it.setVideoEnhanceMode(videoEnhanceMode)
        }

        setupChannelDrawer()
        setupTouchUi()
        applySafeAreaInsets()
        resolveInitialChannel(savedInstanceState)
        if (isTouchDevice) {
            MobilePlayerTransitionHelper.consumeCrossEngineLastFrame(intent)?.let { crossBitmap ->
                mobileLoadingOverlay?.applyCrossEngineLastFrame(
                    bitmap = crossBitmap,
                    channelName = currentChannel?.name.orEmpty(),
                )
            }
        }
        ensureCatalogLoaded()
        updateOverlayChannelInfo()
        updateFavoriteButton()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentChannel?.id?.let { outState.putString(STATE_CURRENT_CHANNEL_ID, it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemUi()
        ViewCompat.requestApplyInsets(binding.playerRoot)
        if (isTouchDevice) {
            mobileChrome?.scrollDrawerToCurrentChannel()
        } else if (isDrawerOpen) {
            // 旋转后抽屉仍开着：重新居中并保持焦点在抽屉内
            scrollDrawerToCurrentChannel()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        isSwitchingEngine = false
        if (isTouchDevice) {
            MobilePlayerTransitionHelper.applyIncomingNoTransitionIfNeeded(this, intent)
            MobilePlayerTransitionHelper.registerActiveMobilePlayer(this) { dismissForOtherEngine() }
            if (intent.hasExtra(PlayerTransitionAndPipPolicy.EXTRA_PREFERRED_ORIENTATION)) {
                requestedOrientation = MobilePlayerTransitionHelper.resolveInitialOrientation(
                    isTvDevice = false,
                    intent = intent,
                )
            }
        }
        val latestCategories = app.catalogStore.getAllCategories()
        if (latestCategories.isNotEmpty()) {
            categories = latestCategories
        }
        val target = resolveChannelFromIntent(intent) ?: return
        if (isTouchDevice) {
            MobilePlayerTransitionHelper.consumeCrossEngineLastFrame(intent)?.let { crossBitmap ->
                mobileLoadingOverlay?.applyCrossEngineLastFrame(
                    bitmap = crossBitmap,
                    channelName = target.name,
                )
            }
        }
        rebuildNavigatorKeepingCurrent()
        navigator.locate(target.id, target.streamUrl)
        if (target.id == currentChannel?.id && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            updateOverlayChannelInfo()
            updateFavoriteButton()
            updateAutoPipParams()
            return
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            switchToChannel(target)
        } else {
            currentChannel = target
            updateOverlayChannelInfo()
            updateFavoriteButton()
        }
    }

    override fun onStart() {
        super.onStart()
        resetVideoInfoSampling()
        playerManager.start(currentChannel)
        if (isTouchDevice) {
            scheduleVideoInfoRefresh()
        }
    }

    override fun onResume() {
        super.onResume()
        isSwitchingEngine = false
        hideSystemUi()
        if (isTouchDevice) {
            updateAutoPipParams()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isTouchDevice && !isInPictureInPictureMode && canEnterPipNow()) {
            enterPipIfEligible(fromUserAction = false)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isTouchDevice) return
        if (isInPictureInPictureMode) {
            Log.i(TAG, "entered picture-in-picture mode")
            mobileChrome?.setPictureInPictureMode(true)
            mobileLoadingOverlay?.hide(animate = false)
            hideStatus()
        } else {
            Log.i(TAG, "exited picture-in-picture mode")
            mobileChrome?.setPictureInPictureMode(false)
            hideSystemUi()
            ViewCompat.requestApplyInsets(binding.playerRoot)
            updateAutoPipParams()
            scheduleVideoInfoRefresh()
        }
    }

    override fun onStop() {
        super.onStop()
        binding.root.removeCallbacks(videoInfoRefreshRunnable)
        mobileLoadingOverlay?.hide(animate = false)
        playerManager.stop()
    }

    override fun onDestroy() {
        if (isTouchDevice) {
            MobilePlayerTransitionHelper.unregisterActiveMobilePlayer(this)
        }
        switchHintJob?.cancel()
        switchHintJob = null
        binding.root.removeCallbacks(hideChannelNameRunnable)
        mobileLoadingOverlay?.release()
        mobileLoadingOverlay = null
        mobileChrome?.release()
        binding.channelDrawer.animate().cancel()
        binding.drawerScrim.animate().cancel()
        binding.playerView.player = null
        playerManager.release()
        super.onDestroy()
    }

    private fun dismissForOtherEngine() {
        isSwitchingEngine = true
        mobileLoadingOverlay?.release()
        runCatching {
            playerManager.stop()
            binding.playerView.player = null
        }
        // 严禁调用 finishAndRemoveTask()：同 affinity 下 singleTask 新播放页与旧 PiP 共享同一 Task，
        // 仅 finish() 当前旧实例即可释放并保留新拉起的播放页。
        if (!isFinishing && !isDestroyed) {
            finish()
        }
    }

    private fun resolveChannelFromIntent(sourceIntent: Intent): Channel? {
        val flat = ChannelNavigator.flatten(categories)
        val channelId = sourceIntent.getStringExtra(EXTRA_CHANNEL_ID)
        val streamUrl = sourceIntent.getStringExtra(EXTRA_STREAM_URL)
        val resolved = flat.firstOrNull { it.id == channelId }
            ?: streamUrl?.takeIf { it.isNotBlank() }?.let { url -> flat.firstOrNull { it.streamUrl == url } }
        if (resolved != null) return resolved
        val fallbackUrl = streamUrl ?: return null
        val fallbackName = sourceIntent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        val fallbackId = channelId ?: fallbackName
        return Channel(
            id = fallbackId,
            name = fallbackName,
            description = "",
            streamUrl = fallbackUrl,
        )
    }

    private fun canEnterPipNow(): Boolean {
        val state = if (::playerManager.isInitialized) playerManager.playbackState else PlaybackState.Idle
        val isPlaybackReady = state == PlaybackState.Playing || state == PlaybackState.Buffering
        return PlayerTransitionAndPipPolicy.canEnterPip(
            isTvDevice = !isTouchDevice,
            supportsPipFeature = supportsPip,
            isSwitchingEngine = isSwitchingEngine,
            isErrorState = state is PlaybackState.Error,
            isPlaybackReady = isPlaybackReady,
            isFinishingOrDestroyed = isFinishing || isDestroyed,
        )
    }

    private fun buildPipParams(): PictureInPictureParams {
        val videoSize = binding.playerView.player?.videoSize
        val aspect = PlayerTransitionAndPipPolicy.resolvePipAspectRatio(
            videoWidth = videoSize?.width ?: 0,
            videoHeight = videoSize?.height ?: 0,
        )
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(aspect.numerator, aspect.denominator))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
            builder.setAutoEnterEnabled(canEnterPipNow())
        }
        return builder.build()
    }

    private fun updateAutoPipParams() {
        if (!supportsPip || isFinishing || isDestroyed) return
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    private fun enterPipIfEligible(fromUserAction: Boolean) {
        if (!isTouchDevice) return
        if (!supportsPip) {
            if (fromUserAction) {
                Toast.makeText(this, R.string.player_pip_unsupported, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!canEnterPipNow()) {
            if (fromUserAction) {
                mobileChrome?.showHudText(getString(R.string.player_pip_not_ready))
            }
            return
        }
        mobileChrome?.setPictureInPictureMode(true)
        hideStatus()
        val entered = runCatching { enterPictureInPictureMode(buildPipParams()) }.getOrDefault(false)
        if (!entered) {
            mobileChrome?.setPictureInPictureMode(false)
        }
    }

    // ---------------- 频道解析 ----------------

    private fun resolveInitialChannel(savedInstanceState: Bundle?) {
        categories = app.catalogStore.getAllCategories()
        val flat = ChannelNavigator.flatten(categories)

        val channelId = PlayerOrientationPolicy.resolveChannelIdToRestore(
            savedChannelId = savedInstanceState?.getString(STATE_CURRENT_CHANNEL_ID),
            intentChannelId = intent.getStringExtra(EXTRA_CHANNEL_ID),
        )
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)

        // URL 回退只在 streamUrl 非空时启用：动态源（mgtv:*）的 streamUrl 全是空串，
        // 若允许空串匹配，任何 id 查找失败都会命中"第一个空地址频道"——即串台到别的台。
        val resolved = flat.firstOrNull { it.id == channelId }
            ?: streamUrl?.takeIf { it.isNotBlank() }?.let { url -> flat.firstOrNull { it.streamUrl == url } }

        currentChannel = resolved ?: buildFallbackChannel()
        val navigatorChannels = when {
            resolved != null -> flat
            currentChannel != null -> listOf(currentChannel!!) + flat
            else -> flat
        }
        navigator = ChannelNavigator(navigatorChannels.ifEmpty { listOfNotNull(currentChannel) })
        if (currentChannel != null) {
            navigator.locate(currentChannel?.id, currentChannel?.streamUrl)
        }
    }

    /**
     * 播放页兜底加载频道目录（CCTV + MGTV 分槽合并）。
     *
     * 正常链路下手机端首页 / TV 首页已经加载并写入
     * [com.livetv.android.domain.repository.ChannelCatalogStore]；这里只处理
     * "直接从外部/历史入口打开频道、首页尚未加载目录"的边角情况，
     * 保证抽屉里能看到当前频道所在分组并能在频道间上下切台。
     */
    private fun ensureCatalogLoaded() {
        if (!app.catalogStore.isEmpty()) return
        app.compositeCatalogCoordinator.refresh { categories ->
            if (isFinishing || isDestroyed) return@refresh
            this.categories = categories
            rebuildNavigatorKeepingCurrent()
            mobileChrome?.setCatalog(categories, currentChannel?.id)
            if (!isTouchDevice && isDrawerOpen) refreshDrawerRows()
        }
    }

    /** 目录变化后重建切台序列，并定位回当前播放频道（不跳台）。 */
    private fun rebuildNavigatorKeepingCurrent() {
        val flat = ChannelNavigator.flatten(categories)
        val current = currentChannel
        val channels = if (current != null && flat.none { it.id == current.id }) {
            listOf(current) + flat
        } else {
            flat
        }
        navigator = ChannelNavigator(channels.ifEmpty { listOfNotNull(current) })
        if (current != null) {
            navigator.locate(current.id, current.streamUrl)
        }
    }

    private fun buildFallbackChannel(): Channel? {
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: return null
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: channelName
        return Channel(
            id = channelId,
            name = channelName,
            description = "",
            streamUrl = streamUrl,
        )
    }

    // ---------------- UI 渲染 ----------------

    private fun renderChannel(channel: Channel) {
        currentChannel = channel
        MobileUiPreferences.setLastPlayedChannelId(this, channel.id)
        binding.channelName.text = channel.name
        if (!isTouchDevice) {
            showTvChannelName(autoHide = true)
        }
        updateOverlayChannelInfo()
        updateFavoriteButton()
    }

    private fun showTvChannelName(autoHide: Boolean) {
        if (isTouchDevice) return
        binding.root.removeCallbacks(hideChannelNameRunnable)
        if (binding.channelName.text.isNullOrBlank()) return
        binding.channelName.visibility = View.VISIBLE
        if (autoHide) {
            scheduleChannelNameAutoHide()
        }
    }

    private fun scheduleChannelNameAutoHide() {
        if (isTouchDevice) return
        binding.root.removeCallbacks(hideChannelNameRunnable)
        binding.root.postDelayed(hideChannelNameRunnable, CHANNEL_NAME_AUTO_HIDE_DELAY_MS)
    }

    private fun showStatus(message: String, showProgress: Boolean) {
        binding.statusText.text = message
        binding.statusProgress.visibility = if (showProgress) View.VISIBLE else View.GONE
        binding.statusContainer.visibility = View.VISIBLE
        binding.errorText.visibility = View.GONE
    }

    private fun hideStatus() {
        binding.statusContainer.visibility = View.GONE
    }

    private fun renderState(state: PlaybackState) {
        when (state) {
            PlaybackState.Idle -> {
                switchHintJob?.cancel()
                hideStatus()
                mobileLoadingOverlay?.onPlaybackTransientPauseOrIdle()
                binding.errorText.visibility = View.GONE
                mobileChrome?.hideError()
            }

            PlaybackState.Loading -> {
                mobileChrome?.hideError()
                if (isTouchDevice) {
                    hideStatus()
                    if (!isInPictureInPictureMode) {
                        mobileLoadingOverlay?.showMgtvLoading(currentChannel?.name.orEmpty())
                    }
                } else {
                    showStatus(
                        message = getString(R.string.player_loading, currentChannel?.name.orEmpty()),
                        showProgress = true,
                    )
                }
            }

            PlaybackState.Buffering -> {
                mobileChrome?.hideError()
                if (isTouchDevice) {
                    hideStatus()
                    if (!isInPictureInPictureMode) {
                        mobileLoadingOverlay?.showMgtvBuffering(getString(R.string.player_buffering))
                    }
                } else {
                    showStatus(
                        message = getString(R.string.player_buffering),
                        showProgress = true,
                    )
                }
            }

            PlaybackState.Playing -> {
                switchHintJob?.cancel()
                hideStatus()
                mobileLoadingOverlay?.hide(animate = true)
                binding.errorText.visibility = View.GONE
                mobileChrome?.hideError()
            }

            PlaybackState.Paused -> {
                switchHintJob?.cancel()
                hideStatus()
                mobileLoadingOverlay?.onPlaybackTransientPauseOrIdle()
                binding.errorText.visibility = View.GONE
                mobileChrome?.hideError()
            }

            is PlaybackState.Error -> {
                switchHintJob?.cancel()
                hideStatus()
                mobileLoadingOverlay?.hide(animate = false)
                if (isTouchDevice) {
                    binding.errorText.visibility = View.GONE
                    mobileChrome?.showError(buildErrorDescription(state.error), showRetry = true)
                } else {
                    binding.errorText.visibility = View.VISIBLE
                    binding.errorText.text = buildErrorText(state.error)
                }
            }
        }

        if (isTouchDevice) {
            updatePlayPauseButton(state)
            updateAutoPipParams()
        }
    }

    private fun updatePlayPauseButton(state: PlaybackState = playerManager.playbackState) {
        val isPaused = state == PlaybackState.Paused || !playerManager.isPlayWhenReady()
        mobileChrome?.updatePlayPause(isPaused)
    }

    private fun showRetryingHint(retryCount: Int, maxRetries: Int) {
        switchHintJob?.cancel()
        val msg = getString(R.string.player_retrying, retryCount, maxRetries)
        if (isTouchDevice) {
            hideStatus()
            if (!isInPictureInPictureMode) {
                mobileLoadingOverlay?.showMgtvBuffering(msg)
            }
        } else {
            showStatus(message = msg, showProgress = true)
        }
    }

    private fun showSwitchingSourceHint() {
        switchHintJob?.cancel()
        val msg = getString(R.string.player_switching_source)
        if (isTouchDevice) {
            hideStatus()
            if (!isInPictureInPictureMode) {
                mobileLoadingOverlay?.showMgtvBuffering(msg)
            }
        } else {
            showStatus(message = msg, showProgress = true)
        }
    }

    private fun showChannelSwitchedHint(channel: Channel) {
        val loadingNow = playerManager.playbackState == PlaybackState.Loading ||
            playerManager.playbackState == PlaybackState.Buffering
        showStatus(
            message = getString(R.string.player_switched_to, channel.name),
            showProgress = loadingNow,
        )

        switchHintJob?.cancel()
        switchHintJob = lifecycleScope.launch {
            delay(SWITCH_HINT_AUTO_HIDE_DELAY_MS)
            if (!isFinishing && !isDestroyed) {
                when (playerManager.playbackState) {
                    PlaybackState.Loading -> showStatus(
                        message = getString(R.string.player_loading, currentChannel?.name.orEmpty()),
                        showProgress = true,
                    )

                    PlaybackState.Buffering -> showStatus(
                        message = getString(R.string.player_buffering),
                        showProgress = true,
                    )

                    else -> hideStatus()
                }
            }
        }
    }

    private fun buildErrorDescription(error: PlaybackError): String = when (error) {
        PlaybackError.Network -> getString(R.string.player_error_network)
        is PlaybackError.HttpStatus -> when (error.code) {
            403 -> getString(R.string.player_error_http_forbidden, error.code)
            429 -> getString(R.string.player_error_http_rate_limited, error.code)
            else -> getString(R.string.player_error_http, error.code)
        }
        PlaybackError.SourceParsing -> getString(R.string.player_error_source)
        PlaybackError.Timeout -> getString(R.string.player_error_timeout)
        is PlaybackError.Unknown -> getString(R.string.player_error_unknown)
    }

    private fun buildErrorText(error: PlaybackError): String {
        val description = buildErrorDescription(error)
        val hint = getString(
            if (isTouchDevice) R.string.player_error_hint_touch else R.string.player_error_hint,
        )
        return "$description\n$hint"
    }

    // ---------------- 遥控器按键 ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (
                PlayerKeyPolicy.resolveDispatchKeyDown(
                    keyCode = event.keyCode,
                    isTouchDevice = isTouchDevice,
                    isDrawerOpen = isDrawerOpen,
                    isControllerOpenedByUser = isControllerOpenedByUser,
                    isErrorState = playerManager.playbackState is PlaybackState.Error,
                    isScreenLocked = isScreenLocked,
                )
            ) {
                PlayerKeyPolicy.KeyAction.SWITCH_PREV_CHANNEL -> {
                    switchChannel(direction = -1)
                    return true
                }

                PlayerKeyPolicy.KeyAction.SWITCH_NEXT_CHANNEL -> {
                    switchChannel(direction = 1)
                    return true
                }

                PlayerKeyPolicy.KeyAction.OPEN_DRAWER -> {
                    openDrawer()
                    return true
                }

                PlayerKeyPolicy.KeyAction.CLOSE_DRAWER -> {
                    closeDrawer()
                    return true
                }

                PlayerKeyPolicy.KeyAction.MANUAL_RESET -> {
                    playerManager.manualReset()
                    return true
                }

                PlayerKeyPolicy.KeyAction.OPEN_CONTROLLER -> {
                    isControllerOpenedByUser = true
                    binding.playerView.showController()
                    return true
                }

                else -> Unit
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isTouchDevice) {
                val action = mobileChrome?.handleBackPressed() ?: ChromeBackAction.PASS_THROUGH
                if (action != ChromeBackAction.PASS_THROUGH) return true
                return super.onKeyDown(keyCode, event)
            }
            when (
                PlayerKeyPolicy.resolveBackKeyDown(
                    isTouchDevice = isTouchDevice,
                    isScreenLocked = isScreenLocked,
                    isDrawerOpen = isDrawerOpen,
                    isControllerVisible = isControllerOpenedByUser || binding.playerView.isControllerFullyVisible,
                )
            ) {
                PlayerKeyPolicy.KeyAction.CLOSE_DRAWER -> {
                    closeDrawer()
                    return true
                }

                PlayerKeyPolicy.KeyAction.CLOSE_CONTROLLER -> {
                    isControllerOpenedByUser = false
                    binding.playerView.hideController()
                    return true
                }

                else -> return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun switchChannel(direction: Int) {
        val maxSteps = navigator.channels.size.coerceAtLeast(1)
        var candidate: Channel? = null
        for (step in 0 until maxSteps) {
            val next = (if (direction > 0) navigator.next() else navigator.previous()) ?: break
            if (!isTouchDevice && TvChannelRoutePolicy.isCctvChannel(next.id)) {
                continue
            }
            candidate = next
            break
        }
        val channel = candidate
        if (channel == null) {
            if (!isTouchDevice && navigator.channels.any { TvChannelRoutePolicy.isCctvChannel(it.id) }) {
                Toast.makeText(this, R.string.tv_cctv_unsupported, Toast.LENGTH_SHORT).show()
            } else {
                showStatus(getString(R.string.player_no_channel), showProgress = false)
            }
            return
        }
        if (isTouchDevice && channel.id.startsWith(CctvWebChannel.CHANNEL_PREFIX)) {
            switchToCctvWebPlayer(channel)
            return
        }
        switchToChannel(channel)
    }

    private fun switchToChannel(channel: Channel) {
        binding.channelName.text = channel.name
        isControllerOpenedByUser = false
        binding.playerView.hideController()

        if (isTouchDevice) {
            val wasPlaying = playerManager.playbackState == PlaybackState.Playing
            currentChannel = channel
            MobileUiPreferences.setLastPlayedChannelId(this, channel.id)
            switchHintJob?.cancel()
            hideStatus()
            mobileChrome?.dismissAllImmediately()
            updateOverlayChannelInfo()
            updateFavoriteButton()
            val surfaceView = binding.playerView.videoSurfaceView as? SurfaceView
            val overlay = mobileLoadingOverlay
            if (overlay != null) {
                overlay.beginChannelSwitch(
                    surfaceView = surfaceView,
                    wasPlaying = wasPlaying,
                    inPictureInPicture = isInPictureInPictureMode,
                    channelName = channel.name,
                ) {
                    if (!isFinishing && !isDestroyed) {
                        playerManager.switchChannel(channel)
                    }
                }
            } else {
                playerManager.switchChannel(channel)
            }
            return
        }

        playerManager.switchChannel(channel)
        if (playerManager.playbackState !is PlaybackState.Error) {
            showChannelSwitchedHint(channel)
        }
        updateOverlayChannelInfo()
        updateFavoriteButton()
    }

    private fun hideSystemUi() {
        val controller = WindowInsetsControllerCompat(window, binding.playerRoot)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun applySafeAreaInsets() {
        // TV 端抽屉安全区必须落在这个内部容器上：直接给 channelDrawer 加 padding 会让
        // drawer_left_edge 竖线跟着内缩，出现上下悬空的断层
        val drawerBaseLeft = binding.drawerContent.paddingLeft
        val drawerBaseTop = binding.drawerContent.paddingTop
        val drawerBaseRight = binding.drawerContent.paddingRight
        val drawerBaseBottom = binding.drawerContent.paddingBottom

        val density = resources.displayMetrics.density
        val stableDrawerBottom = (DRAWER_STABLE_BOTTOM_MARGIN_DP * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.playerRoot) { _, windowInsets ->
            // 只跟随 displayCutout：沉浸式下 systemBars 会随"临时呼出"瞬时出现，
            // 若跟着 systemBars 改 padding，顶栏/底栏/抽屉会在手势边缘滑出时反复跳动
            if (isTouchDevice) {
                mobileChrome?.applyCutoutInsets(windowInsets)
            } else {
                val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                binding.drawerContent.setPadding(
                    drawerBaseLeft,
                    drawerBaseTop + cutout.top,
                    drawerBaseRight + cutout.right,
                    drawerBaseBottom + maxOf(cutout.bottom, stableDrawerBottom),
                )
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.playerRoot)
    }

    // ---------------- 频道抽屉（双端共用：触控浮层按钮 / TV 遥控器左键或菜单键） ----------------

    private fun setupChannelDrawer() {
        binding.drawerList.layoutManager = LinearLayoutManager(this)
        drawerAdapter = ChannelDrawerAdapter { channel ->
            if (isTouchDevice && channel.id.startsWith(CctvWebChannel.CHANNEL_PREFIX)) {
                switchToCctvWebPlayer(channel)
                return@ChannelDrawerAdapter
            }
            if (!isTouchDevice && TvChannelRoutePolicy.decide(channel) == TvChannelRouteAction.UnsupportedCctv) {
                Toast.makeText(this, R.string.tv_cctv_unsupported, Toast.LENGTH_SHORT).show()
                return@ChannelDrawerAdapter
            }
            navigator.locate(channel.id, channel.streamUrl)
            switchToChannel(channel)
            closeDrawer()
        }
        binding.drawerList.adapter = drawerAdapter
        binding.drawerScrim.setOnClickListener { closeDrawer() }
    }

    /**
     * 手机端从选台栏切到 CCTV 频道：先校验该 CCTV 频道是否受网页播放支持（4K/8K 直接提示，
     * 不停止当前 ExoPlayer），再无动画无缝交接给 `CctvWebPlayerActivity`（TV 端不会走这里）。
     */
    private fun switchToCctvWebPlayer(channel: Channel) {
        val precheck = PlayerTransitionAndPipPolicy.evaluateMgtvToCctvSwitch(
            channelId = channel.id,
            isSupportedCctv = CctvWebChannel.isSupported(channel.id),
        )
        if (precheck == CrossEngineCctvPrecheck.UNSUPPORTED_CCTV) {
            Toast.makeText(this, R.string.cctv_web_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        if (isSwitchingEngine) return
        val wasPlaying = playerManager.playbackState == PlaybackState.Playing
        isSwitchingEngine = true
        updateAutoPipParams()
        switchHintJob?.cancel()
        hideStatus()
        mobileChrome?.dismissAllImmediately()
        val surfaceView = binding.playerView.videoSurfaceView as? SurfaceView
        val overlay = mobileLoadingOverlay
        if (overlay != null) {
            overlay.prepareCrossEngineHandoff(
                surfaceView = surfaceView,
                wasPlaying = wasPlaying,
                inPictureInPicture = isInPictureInPictureMode,
                channelName = channel.name,
            ) { handoffBitmap ->
                completeSwitchToCctvWebPlayer(channel, handoffBitmap)
            }
        } else {
            completeSwitchToCctvWebPlayer(channel, null)
        }
    }

    private fun completeSwitchToCctvWebPlayer(channel: Channel, handoffBitmap: android.graphics.Bitmap?) {
        if (isFinishing || isDestroyed) {
            if (handoffBitmap != null && !handoffBitmap.isRecycled) handoffBitmap.recycle()
            return
        }
        MobilePlayerTransitionHelper.stashCrossEngineLastFrame(handoffBitmap)
        MobileUiPreferences.setLastPlayedChannelId(this, channel.id)
        runCatching {
            playerManager.stop()
            binding.playerView.player = null
        }
        val targetIntent = Intent(this, CctvWebPlayerActivity::class.java)
            .putExtra(CctvWebPlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            .putExtra(CctvWebPlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
        MobilePlayerTransitionHelper.startSeamlessCrossEngineSwitch(
            from = this,
            targetIntent = targetIntent,
            currentRequestedOrientation = requestedOrientation,
        )
        finish()
    }

    // ---------------- 触控浮层与手势（仅触控设备，接入共享 MobilePlayerChromeController） ----------------

    private fun setupTouchUi() {
        if (!isTouchDevice) {
            binding.mobileChrome.chromeRoot.visibility = View.GONE
            return
        }

        // 触控端完全接管播放控制（共享外框 + 手势），关闭 Media3 原生控制器，
        // 避免确认键/回车把系统控制器叠在自定义浮层上形成双浮层；TV 端保持既有呼出逻辑不变。
        binding.playerView.useController = false
        binding.channelName.visibility = View.GONE
        binding.viewTapCatcher.visibility = View.VISIBLE
        binding.mobileChrome.chromeRoot.visibility = View.VISIBLE

        mobileChrome = MobilePlayerChromeController(
            binding = binding.mobileChrome,
            capabilities = PlayerChromeCapabilities.MGTV,
            callbacks = object : MobilePlayerChromeController.Callbacks {
                override fun onBack() = finish()
                override fun onToggleFavorite() = toggleCurrentFavorite()
                override fun onRotate() = toggleOrientation()
                override fun onPrevChannel() = switchChannel(direction = -1)
                override fun onNextChannel() = switchChannel(direction = 1)
                override fun onTogglePlayPause() = togglePlayPauseWithFeedback()
                override fun onCycleAspect() = cycleAspectMode()
                override fun onCycleVideoEnhance() = cycleVideoEnhanceMode()
                override fun onToggleVideoInfo() = toggleVideoInfoOverlay()
                override fun onEnterPip() = enterPipIfEligible(fromUserAction = true)
                override fun onErrorRetry() = playerManager.manualReset()
                override fun onLockStateChanged(locked: Boolean) {
                    isScreenLocked = locked
                }
                override fun onDrawerVisibilityChanged(open: Boolean) {
                    isDrawerOpen = open
                }
                override fun onChannelPicked(channel: Channel) {
                    if (channel.id.startsWith(CctvWebChannel.CHANNEL_PREFIX)) {
                        switchToCctvWebPlayer(channel)
                        return
                    }
                    navigator.locate(channel.id, channel.streamUrl)
                    switchToChannel(channel)
                }
            },
            initialPlaying = true,
        )

        mobileLoadingOverlay = MobilePlayerLoadingOverlay(
            context = this,
            parentRoot = binding.playerRoot,
            chromeRoot = binding.mobileChrome.chromeRoot,
            onOverlayTap = {
                if (!isDrawerOpen) {
                    mobileChrome?.toggleControls()
                }
            },
        )
        setupTouchGestures()
        updateVideoEnhanceUi(showHud = false)
        scheduleVideoInfoRefresh()
        mobileChrome?.setPipButtonVisible(supportsPip)
        mobileChrome?.dismissAllImmediately()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchGestures() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val edgeExclusionPx = EDGE_EXCLUSION_DP * resources.displayMetrics.density
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    mobileChrome?.toggleControls()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isScreenLocked || playerManager.playbackState is PlaybackState.Error) {
                        return true
                    }
                    togglePlayPauseWithFeedback()
                    return true
                }
            },
        )

        var downX = 0f
        var downY = 0f
        var dragMode = DragMode.NONE
        var dragEligible = false
        var startBrightness = PlayerTouchGestureMath.DEFAULT_BRIGHTNESS
        var startVolume = 0
        var maxVolume = 1

        binding.viewTapCatcher.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            if (isScreenLocked || isDrawerOpen) {
                dragMode = DragMode.NONE
                return@setOnTouchListener true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragMode = DragMode.NONE
                    dragEligible = !PlayerTouchGestureMath.isInEdgeExclusionZone(
                        xPx = downX,
                        viewWidthPx = view.width,
                        edgeExclusionPx = edgeExclusionPx,
                    )
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragEligible) return@setOnTouchListener true
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dragMode == DragMode.NONE && abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        if (downX < view.width / 2f) {
                            dragMode = DragMode.BRIGHTNESS
                            startBrightness = window.attributes.screenBrightness
                        } else {
                            dragMode = DragMode.VOLUME
                            maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                            startVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                        }
                    }
                    when (dragMode) {
                        DragMode.BRIGHTNESS -> {
                            val next = PlayerTouchGestureMath.adjustBrightness(
                                currentBrightness = startBrightness,
                                deltaY = dy,
                                viewHeightPx = view.height,
                            )
                            val attrs = window.attributes
                            attrs.screenBrightness = next
                            window.attributes = attrs
                            mobileChrome?.showHudMeter(
                                icon = MobilePlayerChromeController.HudIcon.BRIGHTNESS,
                                percent = PlayerTouchGestureMath.toPercent(next),
                            )
                        }

                        DragMode.VOLUME -> {
                            if (audioManager != null && maxVolume > 0) {
                                val next = PlayerTouchGestureMath.adjustVolume(
                                    currentVolume = startVolume,
                                    maxVolume = maxVolume,
                                    deltaY = dy,
                                    viewHeightPx = view.height,
                                )
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                                val percent = PlayerTouchGestureMath.toPercent(next.toFloat() / maxVolume)
                                mobileChrome?.showHudMeter(
                                    icon = MobilePlayerChromeController.HudIcon.VOLUME,
                                    percent = percent,
                                )
                            }
                        }

                        DragMode.NONE -> Unit
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragMode != DragMode.NONE) {
                        mobileChrome?.hideHudDelayed()
                    }
                    dragMode = DragMode.NONE
                    dragEligible = false
                }
            }
            true
        }
    }

    private enum class DragMode {
        NONE,
        BRIGHTNESS,
        VOLUME,
    }

    private fun togglePlayPauseWithFeedback() {
        val nowPlaying = playerManager.togglePlayPause()
        updatePlayPauseButton()
        mobileChrome?.showHudText(
            getString(if (nowPlaying) R.string.player_gesture_resumed else R.string.player_gesture_paused),
        )
    }

    private fun toggleCurrentFavorite() {
        val channel = currentChannel ?: return
        val nowFavorite = MobileUiPreferences.toggleFavoriteChannel(this, channel.id)
        updateFavoriteButton()
        mobileChrome?.showHudText(
            getString(
                if (nowFavorite) R.string.mobile_favorite_added else R.string.mobile_favorite_removed,
                channel.name,
            ),
        )
    }

    private fun updateFavoriteButton() {
        if (!isTouchDevice) return
        val channelId = currentChannel?.id
        val isFavorite = channelId != null &&
            channelId in MobileUiPreferences.getFavoriteChannelIds(this)
        mobileChrome?.updateFavorite(isFavorite)
    }

    private fun cycleAspectMode() {
        aspectMode = aspectMode.next()
        val (resizeMode, labelRes) = when (aspectMode) {
            PlayerTouchGestureMath.AspectMode.FIT ->
                AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.player_touch_aspect_fit
            PlayerTouchGestureMath.AspectMode.FILL ->
                AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.player_touch_aspect_fill
            PlayerTouchGestureMath.AspectMode.ZOOM ->
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.player_touch_aspect_zoom
        }
        binding.playerView.resizeMode = resizeMode
        val label = getString(labelRes)
        mobileChrome?.updateAspectLabel(label)
        mobileChrome?.showHudText(label)
    }

    private fun resetVideoInfoSampling() {
        lastVideoInfoFrames = -1
        lastVideoInfoSampleMs = 0L
        lastRxBytes = -1L
        measuredFps = 0f
        measuredSpeedKbps = 0f
    }

    private fun cycleVideoEnhanceMode() {
        videoEnhanceMode = videoEnhanceMode.next()
        MobileUiPreferences.setVideoEnhanceMode(this, videoEnhanceMode)
        playerManager.setVideoEnhanceMode(videoEnhanceMode)
        updateVideoEnhanceUi(showHud = true)
        refreshVideoInfoOverlay()
    }

    private fun toggleVideoInfoOverlay() {
        isVideoInfoVisible = !isVideoInfoVisible
        MobileUiPreferences.setVideoInfoVisible(this, isVideoInfoVisible)
        scheduleVideoInfoRefresh()
    }

    private fun scheduleVideoInfoRefresh() {
        binding.root.removeCallbacks(videoInfoRefreshRunnable)
        refreshVideoInfoOverlay()
        if (isTouchDevice && isVideoInfoVisible) {
            binding.root.postDelayed(videoInfoRefreshRunnable, VIDEO_INFO_REFRESH_INTERVAL_MS)
        }
    }

    private fun refreshVideoInfoOverlay() {
        if (!isTouchDevice) return
        if (!isVideoInfoVisible) {
            mobileChrome?.updateVideoInfoOverlay(visible = false, text = null)
            return
        }
        val snap = playerManager.getVideoInfoSnapshot()
        if (snap == null || snap.inputWidth <= 0 || snap.inputHeight <= 0) {
            mobileChrome?.updateVideoInfoOverlay(visible = true, text = null)
            return
        }
        val nowMs = System.currentTimeMillis()
        val rxBytes = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
        if (lastVideoInfoSampleMs > 0L && snap.renderedFrames >= lastVideoInfoFrames) {
            val dtMs = nowMs - lastVideoInfoSampleMs
            if (dtMs >= 400L) {
                measuredFps = (snap.renderedFrames - lastVideoInfoFrames) * 1000f / dtMs.toFloat()
                if (rxBytes >= 0L && lastRxBytes >= 0L && rxBytes >= lastRxBytes) {
                    measuredSpeedKbps = (rxBytes - lastRxBytes) * 8f / dtMs.toFloat()
                }
                lastVideoInfoFrames = snap.renderedFrames
                lastRxBytes = rxBytes
                lastVideoInfoSampleMs = nowMs
            }
        } else {
            lastVideoInfoFrames = snap.renderedFrames
            lastRxBytes = rxBytes
            lastVideoInfoSampleMs = nowMs
        }

        val status = VideoEnhancePolicy.resolveStatus(snap.enhanceMode, snap.inputWidth, snap.inputHeight)
        val scale = VideoEnhancePolicy.scaleFactorForSource(snap.enhanceMode, snap.inputWidth, snap.inputHeight)
        val statusDetail = when (status) {
            VideoEnhanceStatus.OFF -> if (snap.glPipelineActive) {
                "${getString(R.string.player_video_info_status_off)} (GL 直通)"
            } else {
                "${getString(R.string.player_video_info_status_off)} (api=3)"
            }
            VideoEnhanceStatus.ACTIVE_STANDARD -> {
                val model = if (scale == 4) "Restore_M + Upscale_M+S" else "Restore_M + Upscale_M"
                "${getString(R.string.player_video_info_status_standard, scale)} ($model)"
            }
            VideoEnhanceStatus.ACTIVE_STRONG -> {
                val model = if (scale == 4) "Restore_L + Upscale_M+S" else "Restore_L + Upscale_M"
                "${getString(R.string.player_video_info_status_strong, scale)} ($model)"
            }
            VideoEnhanceStatus.BYPASSED_HIGH_RES -> getString(R.string.player_video_info_status_bypass)
        }
        val outW = if (scale > 1) {
            if (snap.outputWidth > snap.inputWidth) snap.outputWidth else snap.inputWidth * scale
        } else {
            snap.inputWidth
        }
        val outH = if (scale > 1) {
            if (snap.outputHeight > snap.inputHeight) snap.outputHeight else snap.inputHeight * scale
        } else {
            snap.inputHeight
        }
        val fpsVal = if (measuredFps >= 1f) measuredFps else snap.formatFps
        val fpsPart = if (fpsVal > 0f) String.format(java.util.Locale.US, "%.2f", fpsVal) else "--"
        val resLine = if (scale > 1) {
            "${snap.inputWidth}×${snap.inputHeight}@$fpsPart → ${outW}×${outH} (${scale}x)"
        } else {
            "${snap.inputWidth}×${snap.inputHeight}@$fpsPart"
        }

        val vMime = buildString {
            append(snap.codecMime ?: "unknown")
            if (!snap.videoCodecs.isNullOrBlank()) append(";codecs=\"").append(snap.videoCodecs).append('"')
        }
        val aMime = buildString {
            append(snap.audioMime ?: "none")
            if (!snap.audioCodecs.isNullOrBlank()) append(";codecs=\"").append(snap.audioCodecs).append('"')
        }
        val codecPart = VideoEnhancePolicy.formatCodecLabel(snap.codecMime)
        val vBitrate = if (snap.bitrateBps > 0) "${snap.bitrateBps / 1000} Kbps" else "--"
        val aBitrate = if (snap.audioBitrateBps > 0) "${snap.audioBitrateBps / 1000} Kbps" else "--"
        val totalFrames = snap.renderedFrames + snap.droppedFrames
        val bufSec = String.format(java.util.Locale.US, "%.1fs", snap.bufferedMs / 1000.0)
        val netSpeed = if (measuredSpeedKbps >= 1000f) {
            String.format(java.util.Locale.US, "%.2f Mbps", measuredSpeedKbps / 1000f)
        } else {
            String.format(java.util.Locale.US, "%.0f Kbps", measuredSpeedKbps)
        }

        val infoText = buildString {
            appendLine("Mime Type: $vMime, $aMime")
            appendLine("Player: Media3 ExoPlayer (${snap.streamType})")
            appendLine("Resolution: $resLine")
            appendLine("Video DataRate: $vBitrate [$codecPart]")
            appendLine("Audio DataRate: $aBitrate")
            appendLine("Buffer / Dropped: $bufSec · ${snap.droppedFrames} / $totalFrames")
            appendLine("Video Host: ${snap.streamHost ?: "--"}")
            appendLine("Network Speed: $netSpeed")
            append("Super Res: $statusDetail")
        }
        mobileChrome?.updateVideoInfoOverlay(visible = true, text = infoText)
    }

    private fun updateVideoEnhanceUi(showHud: Boolean) {
        val labelRes = when (videoEnhanceMode) {
            VideoEnhanceMode.OFF -> R.string.player_enhance_off
            VideoEnhanceMode.STANDARD -> R.string.player_enhance_standard
            VideoEnhanceMode.STRONG -> R.string.player_enhance_strong
        }
        val label = getString(labelRes)
        mobileChrome?.updateEnhanceLabel(label, active = videoEnhanceMode != VideoEnhanceMode.OFF)
        if (showHud) {
            mobileChrome?.showHudText(getString(R.string.player_enhance_hud_format, label))
        }
    }

    private fun toggleOrientation() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun updateOverlayChannelInfo() {
        if (!isTouchDevice) return
        val channel = currentChannel ?: return
        if (!::navigator.isInitialized) return
        val total = navigator.channels.size
        val position = navigator.currentIndex + 1
        val title = if (total > 0) {
            getString(R.string.player_position_format, channel.name, position, total)
        } else {
            channel.name
        }
        mobileChrome?.setTitle(title)
        mobileChrome?.setCatalog(categories, channel.id)
    }

    private fun refreshDrawerRows() {
        val rows = ChannelDrawerModel.buildRows(categories, currentChannel?.id)
        currentDrawerRows = rows
        binding.drawerEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.drawerList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        drawerAdapter?.updateRows(rows)
    }

    private fun openDrawer() {
        if (isTouchDevice) {
            mobileChrome?.openDrawer()
            return
        }
        isControllerOpenedByUser = false
        binding.playerView.hideController()
        binding.channelName.visibility = View.GONE
        refreshDrawerRows()

        binding.drawerScrim.animate().cancel()
        binding.drawerScrim.alpha = 0f
        binding.drawerScrim.visibility = View.VISIBLE
        binding.drawerScrim.animate().alpha(1f).setDuration(DRAWER_ANIM_MS).start()

        binding.channelDrawer.animate().cancel()
        val slideOffset = if (binding.channelDrawer.width > 0) {
            binding.channelDrawer.width.toFloat()
        } else {
            280f * resources.displayMetrics.density
        }
        binding.channelDrawer.translationX = slideOffset
        binding.channelDrawer.visibility = View.VISIBLE
        binding.channelDrawer.animate().translationX(0f).setDuration(DRAWER_ANIM_MS).start()
        isDrawerOpen = true

        // TV 端：打开抽屉即把焦点交给抽屉当前播放项（无命中时落到首个频道项）
        scrollDrawerToCurrentChannel()
    }

    private fun scrollDrawerToCurrentChannel(requestFocus: Boolean = true) {
        val currentIndex = ChannelDrawerModel.currentRowIndex(currentDrawerRows)
        val targetIndex = if (currentIndex >= 0) {
            currentIndex
        } else {
            currentDrawerRows.indexOfFirst { it is ChannelDrawerRow.ChannelItem }
        }
        if (targetIndex >= 0) {
            binding.drawerList.post {
                val lm = binding.drawerList.layoutManager as? LinearLayoutManager
                val offset = binding.drawerList.height / 3
                lm?.scrollToPositionWithOffset(targetIndex, offset)
                    ?: binding.drawerList.scrollToPosition(targetIndex)
                if (requestFocus) {
                    binding.drawerList.post {
                        binding.drawerList.findViewHolderForAdapterPosition(targetIndex)
                            ?.itemView
                            ?.requestFocus()
                            ?: binding.drawerList.requestFocus()
                    }
                }
            }
        }
    }

    private fun closeDrawer() {
        if (isTouchDevice) {
            mobileChrome?.closeDrawer()
            return
        }
        if (!isDrawerOpen) return
        isDrawerOpen = false
        binding.drawerScrim.animate().cancel()
        binding.drawerScrim.animate()
            .alpha(0f)
            .setDuration(DRAWER_ANIM_MS)
            .withEndAction {
                binding.drawerScrim.visibility = View.GONE
                binding.drawerScrim.alpha = 1f
            }
            .start()

        binding.channelDrawer.animate().cancel()
        val slideOffset = if (binding.channelDrawer.width > 0) {
            binding.channelDrawer.width.toFloat()
        } else {
            280f * resources.displayMetrics.density
        }
        binding.channelDrawer.animate()
            .translationX(slideOffset)
            .setDuration(DRAWER_ANIM_MS)
            .withEndAction {
                binding.channelDrawer.visibility = View.GONE
                binding.channelDrawer.translationX = 0f
                binding.playerView.requestFocus()
            }
            .start()
    }
}
