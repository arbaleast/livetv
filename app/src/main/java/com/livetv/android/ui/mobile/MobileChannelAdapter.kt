package com.livetv.android.ui.mobile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.databinding.ItemMobileChannelBinding
import com.livetv.android.databinding.ItemMobileCategoryHeaderBinding
import com.livetv.android.domain.model.Channel

/**
 * 手机端频道列表适配器：两种条目类型（分类标题 / 频道卡片）。
 *
 * 与 TV 端的 `ChannelCardPresenter`（Leanback）互不影响，两端各自维护展示逻辑。
 */
class MobileChannelAdapter(
    private val rows: List<MobileRow>,
    private val onChannelClick: (Channel) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is MobileRow.CategoryHeader -> TYPE_HEADER
        is MobileRow.ChannelCard -> TYPE_CHANNEL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(
                ItemMobileCategoryHeaderBinding.inflate(inflater, parent, false),
            )

            else -> ChannelViewHolder(
                ItemMobileChannelBinding.inflate(inflater, parent, false),
                onChannelClick,
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is MobileRow.CategoryHeader -> (holder as HeaderViewHolder).bind(row)
            is MobileRow.ChannelCard -> (holder as ChannelViewHolder).bind(row.channel)
        }
    }

    class HeaderViewHolder(
        private val binding: ItemMobileCategoryHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(header: MobileRow.CategoryHeader) {
            binding.categoryName.text = header.categoryName
            binding.categoryCount.text = header.channelCount.toString()
        }
    }

    class ChannelViewHolder(
        private val binding: ItemMobileChannelBinding,
        private val onChannelClick: (Channel) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel) {
            binding.channelName.text = channel.name
            binding.channelDescription.text = channel.description
            binding.root.setOnClickListener { onChannelClick(channel) }
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CHANNEL = 1
    }
}
