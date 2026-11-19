package com.livetv.android.ui.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.LiveTvApplication
import com.livetv.android.R
import com.livetv.android.databinding.ActivityMobileMainBinding
import com.livetv.android.domain.model.Channel
import com.livetv.android.ui.PlayerActivity

/**
 * 手机 / 平板端的主界面：标准 `RecyclerView` + `GridLayoutManager` 触控网格。
 *
 * 与 TV 端完全隔离——TV 端走 `MainActivity` → Leanback `MainBrowseFragment`；
 * 两端共用底层资产：`LiveTvApplication.channelRepository`（数据）、`PlayerActivity`（播放）。
 */
class MobileMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMobileMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMobileMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupList()
        loadChannels()
    }

    private fun setupList() {
        val spanCount = MobileChannelList.gridSpanCount(screenWidthDp())
        val layoutManager = GridLayoutManager(this, spanCount)
        // 分类标题跨满整行，频道卡片各占一格
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val adapter = binding.channelList.adapter
                val viewType = adapter?.getItemViewType(position)
                return if (viewType == MobileChannelAdapter.TYPE_HEADER) spanCount else 1
            }
        }
        binding.channelList.layoutManager = layoutManager
        binding.channelList.setHasFixedSize(true)
    }

    private fun loadChannels() {
        val repository = (application as LiveTvApplication).channelRepository
        val categories = repository.getCategories()
        val rows = MobileChannelList.buildRows(categories)

        if (rows.isEmpty()) {
            binding.channelList.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            return
        }

        binding.channelList.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        binding.channelList.adapter = MobileChannelAdapter(rows, ::openChannel)
    }

    private fun openChannel(channel: Channel) {
        if (channel.streamUrl.isEmpty()) {
            android.widget.Toast.makeText(this, getString(R.string.channel_selected, channel.name), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 播放页双端通用：Extra 与 TV 端 MainBrowseFragment 完全一致
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                .putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl),
        )
    }

    private fun screenWidthDp(): Int {
        val metrics = resources.displayMetrics
        // px -> dp 必须除以 density；不能用 TypedValue.applyDimension（那是 dp -> px）
        return MobileChannelList.pxToDp(metrics.widthPixels, metrics.density)
    }
}
