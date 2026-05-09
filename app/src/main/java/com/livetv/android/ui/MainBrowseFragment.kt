package com.livetv.android.ui

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
import com.livetv.android.model.Channel

class MainBrowseFragment : BrowseSupportFragment() {

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
        val newsHeader = HeaderItem(getString(R.string.row_news))
        val newsAdapter = ArrayObjectAdapter(ChannelCardPresenter()).apply {
            addAll(0, sampleNewsChannels())
        }
        rowsAdapter.add(ListRow(newsHeader, newsAdapter))

        val sportsHeader = HeaderItem(getString(R.string.row_sports))
        val sportsAdapter = ArrayObjectAdapter(ChannelCardPresenter()).apply {
            addAll(0, sampleSportsChannels())
        }
        rowsAdapter.add(ListRow(sportsHeader, sportsAdapter))

        adapter = rowsAdapter
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = OnItemViewClickedListener { _: Presenter.ViewHolder?, item: Any?, _: RowPresenter.ViewHolder?, _: Row? ->
            if (item is Channel) {
                Toast.makeText(requireContext(), getString(R.string.channel_selected, item.name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sampleNewsChannels(): List<Channel> = listOf(
        Channel("n1", "综合新闻", "24 小时滚动"),
        Channel("n2", "财经频道", "市场与政策"),
        Channel("n3", "国际资讯", "全球热点"),
    )

    private fun sampleSportsChannels(): List<Channel> = listOf(
        Channel("s1", "体育赛事", "直播与集锦"),
        Channel("s2", "足球世界", "联赛专题"),
        Channel("s3", "奥林匹克", "经典回顾"),
    )
}
