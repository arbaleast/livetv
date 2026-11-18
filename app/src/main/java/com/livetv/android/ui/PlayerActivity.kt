package com.livetv.android.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.livetv.android.databinding.ActivityPlayerBinding

// HlsMediaSource / ExoPlayer.setMediaSource(MediaSource) 属于 Media3 的实验性 API，
// 显式声明 opt-in 以通过 Android Lint 的 UnsafeOptInUsageError 检查。
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_STREAM_URL = "stream_url"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var playWhenReady = true
    private var playbackPosition = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun initializePlayer() {
        // 防止重复初始化导致播放器实例泄漏（onStart 之后 player 应始终为空或已存在）
        if (player != null) return
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: run {
            finish()
            return
        }
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""

        binding.channelName.text = channelName

        player = ExoPlayer.Builder(this)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer

                val dataSourceFactory = DefaultHttpDataSource.Factory()
                val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(streamUrl))

                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.playWhenReady = playWhenReady
                exoPlayer.seekTo(playbackPosition)
                exoPlayer.prepare()

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        runOnUiThread {
                            binding.errorText.visibility = View.VISIBLE
                            binding.errorText.text = "播放失败: ${error.message}"
                        }
                    }
                })
            }
    }

    private fun releasePlayer() {
        player?.let {
            playbackPosition = it.currentPosition
            playWhenReady = it.playWhenReady
            // 先解除 PlayerView 对已释放播放器的引用，避免视图持有失效实例
            binding.playerView.player = null
            it.release()
        }
        player = null
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
