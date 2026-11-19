package com.livetv.android.ui

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import com.livetv.android.LiveTvApplication
import com.livetv.android.R
import com.livetv.android.databinding.ActivityPlayerBinding
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.domain.repository.ChannelRepository
import com.livetv.android.player.ChannelNavigator
import com.livetv.android.player.PlaybackError
import com.livetv.android.player.PlaybackState
import com.livetv.android.player.PlayerManager
import com.livetv.android.ui.mobile.ChannelDrawerAdapter
import com.livetv.android.ui.mobile.ChannelDrawerModel
import com.livetv.android.util.DeviceUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放页。
 *
 * 播放器逻辑已抽离到 `com.livetv.android.player`：
 * - 纯策略层（`PlaybackReducer` / `RetryPolicy` / `ChannelNavigator` / `PlaybackErrorMapper`）不依赖 Android；
 * - 桥接层 `PlayerManager` 负责 ExoPlayer 生命周期、请求头/超时、重试调度。
 *
 * 本 Activity 只负责：加载频道、渲染状态与提示、把遥控器按键翻译成切台/重置动作。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_STREAM_URL = "stream_url"

        /** 阶段三新增：频道 id，用于在仓库中定位到完整频道（含 backupUrls / headers）。 */
        const val EXTRA_CHANNEL_ID = "channel_id"

        /** 切台提示自动隐藏的延迟（毫秒）。 */
        const val SWITCH_HINT_AUTO_HIDE_DELAY_MS = 3_000L

        /** 触控浮层自动隐藏的延迟（毫秒）。 */
        const val TOUCH_OVERLAY_AUTO_HIDE_DELAY_MS = 5_000L
    }

    private lateinit var binding: ActivityPlayerBinding

    private lateinit var playerManager: PlayerManager
    private lateinit var navigator: ChannelNavigator

    /** 当前播放的频道。 */
    private var currentChannel: Channel? = null

    /**
     * 是否是触控设备（手机 / 平板）。
     *
     * TV 设备上触控浮层与抽屉整体不显示，遥控器行为与阶段 5.5 完全一致。
     */
    private var isTouchDevice = false

    /** 仓库里的全部分类，供"频道列表"抽屉使用。 */
    private var categories: List<ChannelCategory> = emptyList()

    /** 触控浮层的自动隐藏回调（用 `postDelayed` 调度，`onDestroy` 中移除）。 */
    private val hideOverlayRunnable = Runnable { hideTouchOverlay() }

    /** "频道列表"抽屉是否处于打开状态。 */
    private var isDrawerOpen = false

    /**
     * 切台提示的自动隐藏任务。
     *
     * 每次显示新提示前会先 `cancel()` 掉上一个未完成的 Job，避免倒计时相互覆盖；
     * `lifecycleScope` 在 Activity `onDestroy` 时自动取消所有子任务，不会泄漏 Activity 引用。
     */
    private var switchHintJob: Job? = null

    /**
     * 控制器是否由**用户**呼出（按 OK 键）。
     *
     * Media3 会在切换媒体源后自行弹出控制器（即使 `controllerAutoShow = false`，且弹出是异步的，
     * 我们随后的 `hideController()` 挡不住）。若以"控制器可见"作为切台的闸门，
     * 真机上连续换台从第二下起就会失效（阶段 5.5 真机实测）。
     *
     * 因此切台只受这个标记控制：用户主动呼出控制器时，方向键归控制器；
     * 其余情况（包括 Media3 自行弹出）方向键一律用于切台。
     */
    private var isControllerOpenedByUser = false

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        // 关闭控制器的"自动显示"：Media3 在播放状态变化（切台/重连）时会自动弹出控制器，
        // 而控制器一显示就会吞掉方向键，导致电视上连续换台只切一台（阶段 5.5 真机实测发现）。
        // 控制器改为只在用户按确认键时显式呼出。
        binding.playerView.controllerAutoShow = false
        setContentView(binding.root)

        playerManager = PlayerManager(
            context = this,
            onStateChanged = ::renderState,
            onChannelChanged = ::renderChannel,
        ).also {
            it.onRetrying = { retryCount, maxRetries -> showRetryingHint(retryCount, maxRetries) }
            it.onSwitchingSource = { showSwitchingSourceHint() }
        }

        isTouchDevice = !DeviceUtils.isTvDevice(this)
        setupTouchUi()

        resolveInitialChannel()
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
        // 主动取消切台提示的倒计时任务（lifecycleScope 也会兜底取消）
        switchHintJob?.cancel()
        switchHintJob = null
        // 移除触控浮层的自动隐藏回调，避免持有 Activity 引用
        binding.root.removeCallbacks(hideOverlayRunnable)
        super.onDestroy()
    }

    // ---------------- 频道解析 ----------------

    /**
     * 解析要播放的频道。
     *
     * 优先用 `EXTRA_CHANNEL_ID` 在仓库中定位完整频道（含 backupUrls / headers）；
     * 定位不到时退回用 Extra 里的名称/地址构造临时单频道，保证旧调用 100% 兼容。
     */
    private fun resolveInitialChannel() {
        val repository: ChannelRepository =
            (application as LiveTvApplication).channelRepository
        categories = repository.getCategories()
        val flat = ChannelNavigator.flatten(categories)

        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)

        val resolved = flat.firstOrNull { it.id == channelId }
            ?: flat.firstOrNull { it.streamUrl == streamUrl }

        currentChannel = resolved ?: buildFallbackChannel()
        navigator = ChannelNavigator(flat.ifEmpty { listOfNotNull(currentChannel) })
        if (currentChannel != null) {
            navigator.locate(currentChannel?.id, currentChannel?.streamUrl)
        }
    }

    /**
     * 仓库里没有该频道时，用 Intent Extra 构造一个临时频道。
     */
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
        binding.channelName.text = channel.name
        updateOverlayChannelInfo()
    }

    /**
     * 根据播放状态更新界面提示。
     */
    private fun renderState(state: PlaybackState) {
        when (state) {
            PlaybackState.Idle -> {
                binding.statusText.visibility = View.GONE
                binding.errorText.visibility = View.GONE
            }

            PlaybackState.Loading -> {
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.text = getString(R.string.player_loading, currentChannel?.name.orEmpty())
                binding.errorText.visibility = View.GONE
            }

            PlaybackState.Buffering -> {
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.text = getString(R.string.player_buffering)
                binding.errorText.visibility = View.GONE
            }

            PlaybackState.Playing -> {
                binding.statusText.visibility = View.GONE
                binding.errorText.visibility = View.GONE
            }

            PlaybackState.Paused -> {
                binding.statusText.visibility = View.GONE
                binding.errorText.visibility = View.GONE
            }

            is PlaybackState.Error -> {
                binding.statusText.visibility = View.GONE
                binding.errorText.visibility = View.VISIBLE
                binding.errorText.text = buildErrorText(state.error)
                // 触控端：错误态必须保持浮层可见，"重试"按钮才点得到
                if (isTouchDevice) showTouchOverlay()
            }
        }

        // 触控端"重试"按钮只在错误态有意义，离开错误态要立刻收起，
        // 否则切台后会残留一个点了没反应的按钮。
        if (isTouchDevice) {
            binding.overlayRetry.visibility =
                if (state is PlaybackState.Error) View.VISIBLE else View.GONE
        }
    }

    /**
     * 自动重试 / 切源提示（由 `PlayerManager` 在调度重试时回调）。
     */
    private fun showRetryingHint(retryCount: Int, maxRetries: Int) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.player_retrying, retryCount, maxRetries)
        binding.errorText.visibility = View.GONE
    }

    /**
     * 切换备用线路提示。
     */
    private fun showSwitchingSourceHint() {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.player_switching_source)
        binding.errorText.visibility = View.GONE
    }

    /**
     * 切台成功提示：短暂展示频道名，3 秒后自动隐藏。
     *
     * 防泄漏/防竞态（方案 A）：
     * - 先 `cancel()` 上一个未完成的 Job，保证新的 3 秒倒计时从此刻开始；
     * - Job 运行在 `lifecycleScope` 中，Activity 销毁时自动取消，不会持有失效的 View。
     */
    private fun showChannelSwitchedHint(channel: Channel) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.player_switched_to, channel.name)
        binding.errorText.visibility = View.GONE

        switchHintJob?.cancel()
        switchHintJob = lifecycleScope.launch {
            delay(SWITCH_HINT_AUTO_HIDE_DELAY_MS)
            if (!isFinishing && !isDestroyed) {
                binding.statusText.visibility = View.GONE
            }
        }
    }

    /**
     * 错误文案：分类说明 + 操作指引。全部取自 `strings.xml`。
     */
    private fun buildErrorText(error: PlaybackError): String {
        val description = when (error) {
            PlaybackError.Network -> getString(R.string.player_error_network)
            is PlaybackError.HttpStatus -> when (error.code) {
                // 防盗链 / 签名过期：给出可执行的解释，而不是笼统的"服务器错误"
                403 -> getString(R.string.player_error_http_forbidden, error.code)
                // 被风控限流：提示等待而不是让用户以为再试一次就能过
                429 -> getString(R.string.player_error_http_rate_limited, error.code)
                else -> getString(R.string.player_error_http, error.code)
            }
            PlaybackError.SourceParsing -> getString(R.string.player_error_source)
            PlaybackError.Timeout -> getString(R.string.player_error_timeout)
            is PlaybackError.Unknown -> getString(R.string.player_error_unknown)
        }
        // 触控端没有遥控器，操作指引必须换成"点按"的说法
        val hint = getString(
            if (isTouchDevice) R.string.player_error_hint_touch else R.string.player_error_hint,
        )
        return "$description\n$hint"
    }

    // ---------------- 遥控器按键 ----------------

    /**
     * 按键映射（遵循 `docs/TV-INTERACTION.md`）：
     * - 控制器隐藏时：上下键切台；控制器显示时：交还 `PlayerView` 处理焦点；
     * - 确认键：错误态手动重置，否则呼出/隐藏控制器；
     * - 返回键：控制器显示时先隐藏控制器，否则正常退出。
     */
    /**
     * 遥控器按键拦截（**必须在这里，而不是 `onKeyDown`**）。
     *
     * Media3 的 `PlayerView.dispatchKeyEvent` 会先于 Activity 的 `onKeyDown` 拿到事件，
     * 并在"收到方向键且控制器未完全可见"时调用 `maybeShowController()` 后**吞掉事件**。
     * 若只在 `onKeyDown` 里处理，真机上上下键切台与 Error 态 OK 重试都会完全失效
     * （阶段 5.5 真机实测发现）。
     *
     * 因此在这里、于视图树分发之前拦截：控制器隐藏时的方向键与确认键由我们接管；
     * 控制器可见时一律交还 `PlayerView`，让焦点在控制器内部正常移动。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !isControllerOpenedByUser) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    switchChannel(direction = -1)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    switchChannel(direction = 1)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (playerManager.playbackState is PlaybackState.Error) {
                        // 致命错误后手动重置：回主源、计数归零、立即重连
                        playerManager.manualReset()
                    } else {
                        isControllerOpenedByUser = true
                        binding.playerView.showController()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            // 方向键与确认键已在 dispatchKeyEvent 中抢先处理（见上面的说明），这里只剩 BACK。
            KeyEvent.KEYCODE_BACK -> {
                // 抽屉开着时，BACK 只负责收起抽屉，不退出播放页。
                if (isTouchDevice && isDrawerOpen) {
                    closeDrawer()
                    return true
                }
                // 控制器开着（无论是用户呼出还是 Media3 自行弹出）时，BACK 只负责收起它。
                if (isControllerOpenedByUser || binding.playerView.isControllerFullyVisible) {
                    isControllerOpenedByUser = false
                    binding.playerView.hideController()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 切台：丢弃旧频道的重试状态，用新频道从主源重新开始。
     */
    private fun switchChannel(direction: Int) {
        val channel = if (direction > 0) navigator.next() else navigator.previous()
        if (channel == null) {
            binding.statusText.visibility = View.VISIBLE
            binding.statusText.text = getString(R.string.player_no_channel)
            return
        }
        switchToChannel(channel)
    }

    /**
     * 切到指定频道。
     *
     * **遥控器上下键与触控按钮共用这一条路径**，保证两端的状态流完全一致。
     */
    private fun switchToChannel(channel: Channel) {
        binding.channelName.text = channel.name
        playerManager.switchChannel(channel)
        // 必须放在 switchChannel 之后：它会同步派发 Loading 并覆盖 statusText，
        // 先显示会立刻被"加载中…"盖掉，导致切台提示一帧都看不到（阶段 5.5 真机实测发现）。
        showChannelSwitchedHint(channel)
        // 收起控制器放最后：Media3 在切换媒体源后会重新显示控制器，而控制器一显示
        // 就会吞掉方向键，导致连续换台从第二下起失效（阶段 5.5 真机实测发现）。
        binding.playerView.hideController()
        updateOverlayChannelInfo()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        binding.playerView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LOW_PROFILE
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // ---------------- 触控浮层与频道抽屉（仅触控设备） ----------------

    /**
     * 初始化触控端 UI。
     *
     * **TV 设备直接返回**：所有触控视图在 XML 里默认 `gone`，遥控器路径完全不受影响。
     */
    private fun setupTouchUi() {
        if (!isTouchDevice) return

        // 触控端的左上角频道名由浮层顶栏代替，避免与浮层重叠
        binding.channelName.visibility = View.GONE
        binding.viewTapCatcher.visibility = View.VISIBLE
        binding.viewTapCatcher.setOnClickListener { toggleTouchOverlay() }

        binding.touchOverlayContainer.visibility = View.VISIBLE

        binding.overlayBack.setOnClickListener { finish() }
        binding.overlayChannelList.setOnClickListener { openDrawer() }
        // 上一台 / 下一台与遥控器上下键共用同一条切台路径
        binding.overlayPrev.setOnClickListener { switchChannel(direction = -1) }
        binding.overlayNext.setOnClickListener { switchChannel(direction = 1) }
        binding.overlayRetry.setOnClickListener {
            playerManager.manualReset()
            showTouchOverlay()
        }
        binding.drawerScrim.setOnClickListener { closeDrawer() }

        updateOverlayChannelInfo()
    }

    private fun toggleTouchOverlay() {
        if (binding.touchOverlayContainer.visibility == View.VISIBLE) {
            binding.root.removeCallbacks(hideOverlayRunnable)
            binding.touchOverlayContainer.visibility = View.GONE
        } else {
            showTouchOverlay()
        }
    }

    /**
     * 显示触控浮层。
     *
     * 5 秒无操作后自动隐藏；错误态保持常驻，否则"重试"按钮会随之消失。
     */
    private fun showTouchOverlay() {
        if (!isTouchDevice) return
        binding.touchOverlayContainer.visibility = View.VISIBLE
        binding.overlayRetry.visibility =
            if (playerManager.playbackState is PlaybackState.Error) View.VISIBLE else View.GONE
        binding.root.removeCallbacks(hideOverlayRunnable)
        binding.root.postDelayed(hideOverlayRunnable, TOUCH_OVERLAY_AUTO_HIDE_DELAY_MS)
    }

    private fun hideTouchOverlay() {
        if (!isTouchDevice) return
        // 错误态下保持浮层可见，"重试"按钮才点得到
        if (playerManager.playbackState is PlaybackState.Error) return
        binding.touchOverlayContainer.visibility = View.GONE
    }

    /** 顶栏显示"频道名 · 位置/总数"。 */
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

    /**
     * 打开"频道列表"抽屉：按分类列出全部频道，并滚动定位到当前频道。
     */
    private fun openDrawer() {
        if (!isTouchDevice) return
        val rows = ChannelDrawerModel.buildRows(categories, currentChannel?.id)
        // 列表为空时显示空态文案，避免出现"打开抽屉一片黑"
        binding.drawerEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.drawerList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        binding.drawerList.layoutManager = LinearLayoutManager(this)
        binding.drawerList.adapter = ChannelDrawerAdapter(rows) { channel ->
            // 先定位，让后续"上一台/下一台"从选中的频道继续
            navigator.locate(channel.id, channel.streamUrl)
            switchToChannel(channel)
            closeDrawer()
        }
        binding.drawerScrim.visibility = View.VISIBLE
        binding.channelDrawer.visibility = View.VISIBLE
        isDrawerOpen = true

        val index = ChannelDrawerModel.currentRowIndex(rows)
        if (index >= 0) binding.drawerList.scrollToPosition(index)
    }

    private fun closeDrawer() {
        binding.drawerScrim.visibility = View.GONE
        binding.channelDrawer.visibility = View.GONE
        isDrawerOpen = false
    }
}
