package com.livetv.android.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import com.livetv.android.R
import com.livetv.android.data.repository.DefaultChannelRepository
import com.livetv.android.data.source.LocalAssetChannelSource
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.repository.ChannelRepository

class MainBrowseFragment : BrowseSupportFragment() {

    /** 频道数据仓库；默认从 assets/channels.json 读取。 */
    private val channelRepository: ChannelRepository by lazy {
        DefaultChannelRepository(LocalAssetChannelSource(requireContext().assets))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUi()
        loadRows()
        setupEventListeners()
    }

    private fun setupUi() {
        title = getString(R.string.browse_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand_primary)
    }

    private fun loadRows() {
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        channelRepository.getCategories().forEach { category ->
            val channelAdapter = ArrayObjectAdapter(ChannelCardPresenter()).apply {
                addAll(0, category.channels)
            }
            rowsAdapter.add(ListRow(HeaderItem(category.name), channelAdapter))
        }
        adapter = rowsAdapter
    }

    private fun setupEventListeners() {
        onItemViewClickedListener =
            OnItemViewClickedListener { _: Presenter.ViewHolder?, item: Any?, _: RowPresenter.ViewHolder?, _: Row? ->
                if (item is Channel) {
                    if (item.streamUrl.isNotEmpty()) {
                        startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, item.id)
                            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, item.name)
                            putExtra(PlayerActivity.EXTRA_STREAM_URL, item.streamUrl)
                        })
                    } else {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.channel_selected, item.name),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
    }
}
