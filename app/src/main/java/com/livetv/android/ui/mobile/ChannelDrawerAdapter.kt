package com.livetv.android.ui.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.databinding.ItemPlayerDrawerChannelBinding
import com.livetv.android.databinding.ItemPlayerDrawerHeaderBinding
import com.livetv.android.domain.model.Channel

/**
 * 播放页"频道列表"抽屉的适配器：分类标题 + 频道条目。
 *
 * 当前正在播放的频道会显示"播放中"标记（由纯模型 `ChannelDrawerModel` 决定）。
 */
class ChannelDrawerAdapter(
    private val rows: List<ChannelDrawerRow>,
    private val onChannelClick: (Channel) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is ChannelDrawerRow.CategoryHeader -> TYPE_HEADER
        is ChannelDrawerRow.ChannelItem -> TYPE_CHANNEL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(
                ItemPlayerDrawerHeaderBinding.inflate(inflater, parent, false),
            )

            else -> ChannelViewHolder(
                ItemPlayerDrawerChannelBinding.inflate(inflater, parent, false),
                onChannelClick,
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ChannelDrawerRow.CategoryHeader -> (holder as HeaderViewHolder).bind(row)
            is ChannelDrawerRow.ChannelItem -> (holder as ChannelViewHolder).bind(row)
        }
    }

    class HeaderViewHolder(
        private val binding: ItemPlayerDrawerHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(header: ChannelDrawerRow.CategoryHeader) {
            binding.drawerCategoryName.text = header.categoryName
        }
    }

    class ChannelViewHolder(
        private val binding: ItemPlayerDrawerChannelBinding,
        private val onChannelClick: (Channel) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChannelDrawerRow.ChannelItem) {
            binding.root.isSelected = item.isCurrent
            binding.drawerChannelName.text = item.channel.name
            binding.drawerChannelPlaying.visibility =
                if (item.isCurrent) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onChannelClick(item.channel) }
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CHANNEL = 1
    }
}
