package com.livetv.android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.livetv.android.ui.mobile.MobileUiPreferences
import com.livetv.android.util.DeviceUtils
import com.livetv.android.util.PlayerKeyPolicy
import com.livetv.android.util.PlayerOrientationPolicy
import com.livetv.android.util.PlayerTouchGestureMath
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

        /** 触控浮层自动隐藏的延迟（毫秒）。 */
        const val TOUCH_OVERLAY_AUTO_HIDE_DELAY_MS = 5_000L

        /** 锁屏态下解锁按钮自动隐藏延迟（毫秒）。 */
        const val LOCK_BUTTON_AUTO_HIDE_DELAY_MS = 3_000L

        /** 手势调节提示自动隐藏延迟（毫秒）。 */
        const val GESTURE_HINT_AUTO_HIDE_DELAY_MS = 1_100L

        /** TV 端左上角频道名自动隐藏的延迟（毫秒）。 */
        const val CHANNEL_NAME_AUTO_HIDE_DELAY_MS = 4_000L

        /** 触控浮层淡入淡出时长（毫秒）。 */
        private const val OVERLAY_FADE_MS = 180L

        /** 频道抽屉滑入滑出时长（毫秒）。 */
        private const val DRAWER_ANIM_MS = 220L

        /** 边缘防误触保留宽度（dp），避让系统侧滑返回手势。 */
        private const val EDGE_EXCLUSION_DP = 24

        /** 抽屉内容底部稳定安全边距（dp）：固定值，不随瞬时系统栏出现而抖动。 */
        private const val DRAWER_STABLE_BOTTOM_MARGIN_DP = 8

        private const val STATE_CURRENT_CHANNEL_ID = "current_channel_id"
    }

    private val app: LiveTvApplication
        get() = application as LiveTvApplication

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerManager: PlayerManager
    private lateinit var navigator: ChannelNavigator
    private var drawerAdapter: ChannelDrawerAdapter? = null

    /** 当前播放的频道。 */
    private var currentChannel: Channel? = null

    /** 是否是触控设备（手机 / 平板）。TV 端所有触控视图保持 `GONE`。 */
    private var isTouchDevice = false

    /** 聚合目录的全部分类，供“频道列表”抽屉使用。 */
    private var categories: List<ChannelCategory> = emptyList()

    /** 当前抽屉展示的行数据，供旋转后重新居中定位当前频道。 */
    private var currentDrawerRows: List<ChannelDrawerRow> = emptyList()

    /** 当前画面比例模式（触控端切换）。 */
    private var aspectMode = PlayerTouchGestureMath.AspectMode.FIT

    /** 触控端防误触锁屏标记。 */
    private var isScreenLocked = false

    /** 触控浮层是否处于可见态（用于避免动画期间状态抖动）。 */
    private var isTouchOverlayVisible = false

    /** "频道列表"抽屉是否处于打开状态。 */
    private var isDrawerOpen = false

    /** 触控浮层的自动隐藏回调。 */
    private val hideOverlayRunnable = Runnable { hideTouchOverlay() }

    /** 锁屏按钮自动隐藏回调。 */
    private val hideLockButtonRunnable = Runnable {
        if (isTouchDevice && isScreenLocked) {
            fadeOutView(binding.overlayLock)
        }
    }

    /** 手势提示胶囊自动隐藏回调。 */
    private val hideGestureHintRunnable = Runnable {
        fadeOutView(binding.gestureHint)
    }

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
        requestedOrientation = PlayerOrientationPolicy.resolveRequestedOrientation(
            isTvDevice = !isTouchDevice,
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

        applySafeAreaInsets()

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
        }

        setupChannelDrawer()
        setupTouchUi()
        resolveInitialChannel(savedInstanceState)
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
        if (isDrawerOpen) {
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

    override fun onStart() {
        super.onStart()
        playerManager.start(currentChannel)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onStop() {
        super.onStop()
        playerManager.stop()
    }

    override fun onDestroy() {
        switchHintJob?.cancel()
        switchHintJob = null
        binding.root.removeCallbacks(hideOverlayRunnable)
        binding.root.removeCallbacks(hideLockButtonRunnable)
        binding.root.removeCallbacks(hideGestureHintRunnable)
        binding.root.removeCallbacks(hideChannelNameRunnable)
        binding.touchOverlayContainer.animate().cancel()
        binding.overlayLock.animate().cancel()
        binding.gestureHint.animate().cancel()
        binding.channelDrawer.animate().cancel()
        binding.drawerScrim.animate().cancel()
        binding.playerView.player = null
        super.onDestroy()
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
            if (isDrawerOpen) refreshDrawerRows()
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
                binding.errorText.visibility = View.GONE
            }

            PlaybackState.Loading -> {
                showStatus(
                    message = getString(R.string.player_loading, currentChannel?.name.orEmpty()),
                    showProgress = true,
                )
            }

            PlaybackState.Buffering -> {
                showStatus(
                    message = getString(R.string.player_buffering),
                    showProgress = true,
                )
            }

            PlaybackState.Playing -> {
                switchHintJob?.cancel()
                hideStatus()
                binding.errorText.visibility = View.GONE
            }

            PlaybackState.Paused -> {
                switchHintJob?.cancel()
                hideStatus()
                binding.errorText.visibility = View.GONE
            }

            is PlaybackState.Error -> {
                switchHintJob?.cancel()
                hideStatus()
                binding.errorText.visibility = View.VISIBLE
                binding.errorText.text = buildErrorText(state.error)
                if (isTouchDevice && !isScreenLocked) showTouchOverlay()
            }
        }

        if (isTouchDevice) {
            binding.overlayRetry.visibility =
                if (state is PlaybackState.Error) View.VISIBLE else View.GONE
            updatePlayPauseButton(state)
        }
    }

    private fun updatePlayPauseButton(state: PlaybackState = playerManager.playbackState) {
        val isPaused = state == PlaybackState.Paused || !playerManager.isPlayWhenReady()
        binding.overlayPlayPause.text = getString(
            if (isPaused) R.string.player_touch_play else R.string.player_touch_pause,
        )
    }

    private fun showRetryingHint(retryCount: Int, maxRetries: Int) {
        switchHintJob?.cancel()
        showStatus(
            message = getString(R.string.player_retrying, retryCount, maxRetries),
            showProgress = true,
        )
    }

    private fun showSwitchingSourceHint() {
        switchHintJob?.cancel()
        showStatus(
            message = getString(R.string.player_switching_source),
            showProgress = true,
        )
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

    private fun buildErrorText(error: PlaybackError): String {
        val description = when (error) {
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
            when (
                PlayerKeyPolicy.resolveBackKeyDown(
                    isTouchDevice = isTouchDevice,
                    isScreenLocked = isScreenLocked,
                    isDrawerOpen = isDrawerOpen,
                    isControllerVisible = isControllerOpenedByUser || binding.playerView.isControllerFullyVisible,
                )
            ) {
                PlayerKeyPolicy.KeyAction.SHOW_LOCKED_HINT -> {
                    showLockButtonTemporarily()
                    showGestureHint(getString(R.string.player_locked_hint))
                    return true
                }

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
        val channel = if (direction > 0) navigator.next() else navigator.previous()
        if (channel == null) {
            showStatus(getString(R.string.player_no_channel), showProgress = false)
            return
        }
        switchToChannel(channel)
    }

    private fun switchToChannel(channel: Channel) {
        binding.channelName.text = channel.name
        playerManager.switchChannel(channel)
        if (playerManager.playbackState !is PlaybackState.Error) {
            showChannelSwitchedHint(channel)
        }
        isControllerOpenedByUser = false
        binding.playerView.hideController()
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
        val topBaseLeft = binding.overlayTopBar.paddingLeft
        val topBaseTop = binding.overlayTopBar.paddingTop
        val topBaseRight = binding.overlayTopBar.paddingRight
        val topBaseBottom = binding.overlayTopBar.paddingBottom

        val bottomBaseLeft = binding.overlayBottomBar.paddingLeft
        val bottomBaseTop = binding.overlayBottomBar.paddingTop
        val bottomBaseRight = binding.overlayBottomBar.paddingRight
        val bottomBaseBottom = binding.overlayBottomBar.paddingBottom

        // 抽屉安全区必须落在这个内部容器上：直接给 channelDrawer 加 padding 会让
        // drawer_left_edge 竖线跟着内缩，出现上下悬空的断层
        val drawerBaseLeft = binding.drawerContent.paddingLeft
        val drawerBaseTop = binding.drawerContent.paddingTop
        val drawerBaseRight = binding.drawerContent.paddingRight
        val drawerBaseBottom = binding.drawerContent.paddingBottom

        val lockBaseStartMargin = (binding.overlayLock.layoutParams as? android.view.ViewGroup.MarginLayoutParams)
            ?.marginStart ?: 0

        val density = resources.displayMetrics.density
        val stableDrawerBottom = (DRAWER_STABLE_BOTTOM_MARGIN_DP * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.playerRoot) { _, windowInsets ->
            // 只跟随 displayCutout：沉浸式下 systemBars 会随"临时呼出"瞬时出现，
            // 若跟着 systemBars 改 padding，顶栏/底栏/抽屉会在手势边缘滑出时反复跳动
            val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            binding.overlayTopBar.setPadding(
                topBaseLeft + cutout.left,
                topBaseTop + cutout.top,
                topBaseRight + cutout.right,
                topBaseBottom,
            )
            val bottomHorizontalSafe = maxOf(cutout.left, cutout.right)
            binding.overlayBottomBar.setPadding(
                bottomBaseLeft + bottomHorizontalSafe,
                bottomBaseTop,
                bottomBaseRight + bottomHorizontalSafe,
                bottomBaseBottom + cutout.bottom,
            )
            binding.drawerContent.setPadding(
                drawerBaseLeft,
                drawerBaseTop + cutout.top,
                drawerBaseRight + cutout.right,
                drawerBaseBottom + maxOf(cutout.bottom, stableDrawerBottom),
            )
            (binding.overlayLock.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                val targetStart = lockBaseStartMargin + cutout.left
                if (lp.marginStart != targetStart) {
                    lp.marginStart = targetStart
                    binding.overlayLock.layoutParams = lp
                }
            }
            windowInsets
        }
    }

    // ---------------- 频道抽屉（双端共用：触控浮层按钮 / TV 遥控器左键或菜单键） ----------------

    private fun setupChannelDrawer() {
        binding.drawerList.layoutManager = LinearLayoutManager(this)
        drawerAdapter = ChannelDrawerAdapter { channel ->
            if (isTouchDevice && channel.id.startsWith(CctvWebChannel.CHANNEL_PREFIX)) {
                switchToCctvWebPlayer(channel)
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
     * 手机端从选台栏切到 CCTV 频道：先停止并释放当前 ExoPlayer 会话，
     * 再交给 WebView 播放页（TV 端不会走这里）。
     */
    private fun switchToCctvWebPlayer(channel: Channel) {
        closeDrawer()
        MobileUiPreferences.setLastPlayedChannelId(this, channel.id)
        runCatching {
            playerManager.stop()
            binding.playerView.player = null
        }
        startActivity(
            Intent(this, CctvWebPlayerActivity::class.java)
                .putExtra(CctvWebPlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                .putExtra(CctvWebPlayerActivity.EXTRA_CHANNEL_NAME, channel.name),
        )
        finish()
    }

    // ---------------- 触控浮层与手势（仅触控设备） ----------------

    private fun setupTouchUi() {
        if (!isTouchDevice) return

        // 触控端完全接管播放控制（自定义浮层 + 手势），关闭 Media3 原生控制器，
        // 避免确认键/回车把系统控制器叠在自定义浮层上形成双浮层；TV 端保持既有呼出逻辑不变。
        binding.playerView.useController = false
        binding.channelName.visibility = View.GONE
        binding.viewTapCatcher.visibility = View.VISIBLE
        setupTouchGestures()

        binding.overlayBack.setOnClickListener { finish() }
        binding.overlayFavorite.setOnClickListener {
            toggleCurrentFavorite()
            showTouchOverlay()
        }
        binding.overlayAspect.setOnClickListener {
            cycleAspectMode()
            showTouchOverlay()
        }
        binding.overlayChannelList.setOnClickListener { openDrawer() }

        binding.overlayPrev.setOnClickListener {
            switchChannel(direction = -1)
            showTouchOverlay()
        }
        binding.overlayPlayPause.setOnClickListener {
            togglePlayPauseWithFeedback()
            showTouchOverlay()
        }
        binding.overlayNext.setOnClickListener {
            switchChannel(direction = 1)
            showTouchOverlay()
        }
        binding.overlayRetry.setOnClickListener {
            playerManager.manualReset()
            showTouchOverlay()
        }
        binding.overlayRotate.setOnClickListener {
            toggleOrientation()
            showTouchOverlay()
        }
        binding.overlayLock.setOnClickListener { toggleScreenLock() }

        showTouchOverlay(animate = false)
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
                    if (isScreenLocked) {
                        toggleLockButtonVisibility()
                    } else {
                        toggleTouchOverlay()
                    }
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
                            showGestureHint(
                                getString(
                                    R.string.player_gesture_brightness,
                                    PlayerTouchGestureMath.toPercent(next),
                                ),
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
                                showGestureHint(getString(R.string.player_gesture_volume, percent))
                            }
                        }

                        DragMode.NONE -> Unit
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
        showGestureHint(
            getString(if (nowPlaying) R.string.player_gesture_resumed else R.string.player_gesture_paused),
        )
    }

    private fun toggleCurrentFavorite() {
        val channel = currentChannel ?: return
        val nowFavorite = MobileUiPreferences.toggleFavoriteChannel(this, channel.id)
        updateFavoriteButton()
        showGestureHint(
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
        binding.overlayFavorite.text = getString(
            if (isFavorite) R.string.player_touch_favorited else R.string.player_touch_favorite,
        )
        binding.overlayFavorite.isSelected = isFavorite
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
        binding.overlayAspect.text = label
        showGestureHint(label)
    }

    private fun toggleOrientation() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun toggleScreenLock() {
        isScreenLocked = !isScreenLocked
        binding.overlayLock.isSelected = isScreenLocked
        binding.overlayLock.text = getString(
            if (isScreenLocked) R.string.player_touch_unlock else R.string.player_touch_lock,
        )
        if (isScreenLocked) {
            hideTouchOverlay(keepLockButton = true)
            showLockButtonTemporarily()
            showGestureHint(getString(R.string.player_locked_hint))
        } else {
            binding.root.removeCallbacks(hideLockButtonRunnable)
            showTouchOverlay()
        }
    }

    private fun toggleLockButtonVisibility() {
        if (binding.overlayLock.visibility == View.VISIBLE) {
            binding.root.removeCallbacks(hideLockButtonRunnable)
            fadeOutView(binding.overlayLock)
        } else {
            showLockButtonTemporarily()
        }
    }

    private fun showLockButtonTemporarily() {
        fadeInView(binding.overlayLock)
        binding.root.removeCallbacks(hideLockButtonRunnable)
        binding.root.postDelayed(hideLockButtonRunnable, LOCK_BUTTON_AUTO_HIDE_DELAY_MS)
    }

    private fun showGestureHint(text: String) {
        binding.gestureHint.text = text
        fadeInView(binding.gestureHint)
        binding.root.removeCallbacks(hideGestureHintRunnable)
        binding.root.postDelayed(hideGestureHintRunnable, GESTURE_HINT_AUTO_HIDE_DELAY_MS)
    }

    private fun toggleTouchOverlay() {
        if (isTouchOverlayVisible) {
            binding.root.removeCallbacks(hideOverlayRunnable)
            hideTouchOverlay(force = true)
        } else {
            showTouchOverlay()
        }
    }

    private fun showTouchOverlay(animate: Boolean = true) {
        if (!isTouchDevice || isScreenLocked) return
        isTouchOverlayVisible = true
        binding.overlayRetry.visibility =
            if (playerManager.playbackState is PlaybackState.Error) View.VISIBLE else View.GONE
        updatePlayPauseButton()
        if (animate) {
            fadeInView(binding.touchOverlayContainer)
            fadeInView(binding.overlayLock)
        } else {
            binding.touchOverlayContainer.alpha = 1f
            binding.touchOverlayContainer.visibility = View.VISIBLE
            binding.overlayLock.alpha = 1f
            binding.overlayLock.visibility = View.VISIBLE
        }
        binding.root.removeCallbacks(hideOverlayRunnable)
        if (!isDrawerOpen) {
            binding.root.postDelayed(hideOverlayRunnable, TOUCH_OVERLAY_AUTO_HIDE_DELAY_MS)
        }
    }

    private fun hideTouchOverlay(force: Boolean = false, keepLockButton: Boolean = false) {
        if (!isTouchDevice) return
        if (!force && !keepLockButton && (playerManager.playbackState is PlaybackState.Error || isDrawerOpen)) {
            return
        }
        isTouchOverlayVisible = false
        fadeOutView(binding.touchOverlayContainer)
        if (!keepLockButton) {
            fadeOutView(binding.overlayLock)
        }
    }

    private fun fadeInView(view: View) {
        view.animate().cancel()
        if (view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        view.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).withEndAction(null).start()
    }

    private fun fadeOutView(view: View) {
        if (view.visibility != View.VISIBLE) return
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(OVERLAY_FADE_MS)
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
            }
            .start()
    }

    private fun updateOverlayChannelInfo() {
        if (!isTouchDevice) return
        val channel = currentChannel ?: return
        if (!::navigator.isInitialized) return
        val total = navigator.channels.size
        val position = navigator.currentIndex + 1
        binding.overlayChannelInfo.text = if (total > 0) {
            getString(R.string.player_position_format, channel.name, position, total)
        } else {
            channel.name
        }
    }

    private fun refreshDrawerRows() {
        val rows = ChannelDrawerModel.buildRows(categories, currentChannel?.id)
        currentDrawerRows = rows
        binding.drawerEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.drawerList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        drawerAdapter?.updateRows(rows)
    }

    private fun openDrawer() {
        binding.root.removeCallbacks(hideOverlayRunnable)
        // 双端一致：先收起底层浮层/控制器，避免 scrim 下的控件继续留在焦点树里
        // （触控端曾出现抽屉打开后按 OK 命中"锁屏"按钮的问题，见 review P1-1）
        hideTouchOverlay(force = true)
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

        // 双端统一：打开抽屉即把焦点交给抽屉当前播放项（无命中时落到首个频道项），
        // 否则非 TV 设备按方向键/OK 仍会命中被 scrim 盖住的浮层控件。
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
                if (!isTouchDevice) {
                    binding.playerView.requestFocus()
                }
            }
            .start()
        if (isTouchDevice) {
            showTouchOverlay()
        }
    }
}
