package com.livetv.android.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.livetv.android.R
import com.livetv.android.data.repository.DefaultChannelRepository
import com.livetv.android.data.source.LocalAssetChannelSource
import com.livetv.android.databinding.ActivityPlayerBinding
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.repository.ChannelRepository
import com.livetv.android.player.ChannelNavigator
import com.livetv.android.player.PlaybackError
import com.livetv.android.player.PlaybackState
import com.livetv.android.player.PlayerManager
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
    }

    private lateinit var binding: ActivityPlayerBinding

    private lateinit var playerManager: PlayerManager
    private lateinit var navigator: ChannelNavigator

    /** 当前播放的频道。 */
    private var currentChannel: Channel? = null

    /**
     * 切台提示的自动隐藏任务。
     *
     * 每次显示新提示前会先 `cancel()` 掉上一个未完成的 Job，避免倒计时相互覆盖；
     * `lifecycleScope` 在 Activity `onDestroy` 时自动取消所有子任务，不会泄漏 Activity 引用。
     */
    private var switchHintJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playerManager = PlayerManager(
            context = this,
            onStateChanged = ::renderState,
            onChannelChanged = ::renderChannel,
        ).also {
            it.onRetrying = { retryCount, maxRetries -> showRetryingHint(retryCount, maxRetries) }
            it.onSwitchingSource = { showSwitchingSourceHint() }
        }

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
            DefaultChannelRepository(LocalAssetChannelSource(applicationContext.assets))
        val categories = repository.getCategories()
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
            }
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
            is PlaybackError.HttpStatus -> getString(R.string.player_error_http, error.code)
            PlaybackError.SourceParsing -> getString(R.string.player_error_source)
            PlaybackError.Timeout -> getString(R.string.player_error_timeout)
            is PlaybackError.Unknown -> getString(R.string.player_error_unknown)
        }
        return "$description\n${getString(R.string.player_error_hint)}"
    }

    // ---------------- 遥控器按键 ----------------

    /**
     * 按键映射（遵循 `docs/TV-INTERACTION.md`）：
     * - 控制器隐藏时：上下键切台；控制器显示时：交还 `PlayerView` 处理焦点；
     * - 确认键：错误态手动重置，否则呼出/隐藏控制器；
     * - 返回键：控制器显示时先隐藏控制器，否则正常退出。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (binding.playerView.isControllerFullyVisible) return super.onKeyDown(keyCode, event)
                switchChannel(direction = -1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.playerView.isControllerFullyVisible) return super.onKeyDown(keyCode, event)
                switchChannel(direction = 1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val state = playerManager.playbackState
                if (state is PlaybackState.Error) {
                    playerManager.manualReset()
                    return true
                }
                if (binding.playerView.isControllerFullyVisible) {
                    binding.playerView.hideController()
                } else {
                    binding.playerView.showController()
                }
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                if (binding.playerView.isControllerFullyVisible) {
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
        binding.playerView.hideController()
        binding.channelName.text = channel.name
        showChannelSwitchedHint(channel)
        playerManager.switchChannel(channel)
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
}
