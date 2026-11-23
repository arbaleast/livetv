package com.livetv.android.ui.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.R
import com.livetv.android.databinding.ItemMobileCategoryHeaderBinding
import com.livetv.android.databinding.ItemMobileChannelBinding
import com.livetv.android.domain.model.Channel

/**
 * 手机端频道列表适配器：两种条目类型（分类标题 / 频道卡片）。
 *
 * 与 TV 端的 `ChannelCardPresenter`（Leanback）互不影响，两端各自维护展示逻辑。
 *
 * 卡片视觉（暗黑影院风）：半透明暗卡 + 上下双行左对齐排版（短台号 / 定位副标题），
 * 不再有彩色徽章与递增序号；最近观看以克制亮边与小光点标识。
 */
class MobileChannelAdapter(
    rows: List<MobileRow>,
    private val onChannelClick: (Channel) -> Unit,
    /** 上次播放的频道 id：用于给卡片加\"最近播放\"柔和亮边与光点。 */
    private var lastPlayedChannelId: String? = null,
    /** 已收藏的频道 id 集合：用于展示卡片星标。 */
    private var favoriteChannelIds: Set<String> = emptySet(),
    /** 长按卡片回调（用于快速收藏/取消收藏）。 */
    private val onChannelLongClick: ((Channel) -> Unit)? = null,
    /** D-Pad UP 回调：传入当前卡片适配器位置，仅当确实消费事件（顶部首行跳转顶栏）时返回 `true`。 */
    private val onDpadUp: ((Int) -> Boolean)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<MobileRow> = rows

    /** 原地更新行数据，避免重建 Adapter 导致 ViewHolder 复用池失效。 */
    fun updateRows(newRows: List<MobileRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    /** 更新\"上次播放\"高亮（仅局部刷新受影响的卡片，避免整表闪烁）。 */
    fun setLastPlayedChannelId(channelId: String?) {
        if (lastPlayedChannelId == channelId) return
        val previous = lastPlayedChannelId
        lastPlayedChannelId = channelId
        rows.forEachIndexed { index, row ->
            if (row is MobileRow.ChannelCard &&
                (row.channel.id == previous || row.channel.id == channelId)
            ) {
                notifyItemChanged(index)
            }
        }
    }

    /** 更新本地收藏集合（仅局部刷新收藏态发生变化的卡片）。 */
    fun setFavoriteChannelIds(ids: Set<String>) {
        if (favoriteChannelIds == ids) return
        val changed = (favoriteChannelIds - ids) + (ids - favoriteChannelIds)
        favoriteChannelIds = ids
        rows.forEachIndexed { index, row ->
            if (row is MobileRow.ChannelCard && row.channel.id in changed) {
                notifyItemChanged(index)
            }
        }
    }

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
                binding = ItemMobileChannelBinding.inflate(inflater, parent, false),
                onChannelClick = onChannelClick,
                onChannelLongClick = onChannelLongClick,
                onDpadUp = onDpadUp,
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is MobileRow.CategoryHeader -> (holder as HeaderViewHolder).bind(row)
            is MobileRow.ChannelCard -> (holder as ChannelViewHolder).bind(
                card = row,
                isLastPlayed = row.channel.id == lastPlayedChannelId,
                isFavorite = row.channel.id in favoriteChannelIds,
            )
        }
    }

    class HeaderViewHolder(
        private val binding: ItemMobileCategoryHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(header: MobileRow.CategoryHeader) {
            binding.categoryName.text = header.categoryName
            binding.categoryCount.text =
                binding.root.context.getString(R.string.mobile_category_count, header.channelCount)
        }
    }

    class ChannelViewHolder(
        private val binding: ItemMobileChannelBinding,
        private val onChannelClick: (Channel) -> Unit,
        private val onChannelLongClick: ((Channel) -> Unit)? = null,
        private val onDpadUp: ((Int) -> Boolean)? = null,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            card: MobileRow.ChannelCard,
            isLastPlayed: Boolean = false,
            isFavorite: Boolean = false,
        ) {
            val channel = card.channel
            binding.root.isSelected = isLastPlayed

            // 上下双行：短台号/简称 + 定位副标题，避免单行挤压截断
            val typography = MobileChannelList.resolveCardTypography(channel, card.categoryName)
            binding.channelName.text = typography.title
            if (typography.subtitle.isBlank()) {
                binding.channelDescription.visibility = View.GONE
            } else {
                binding.channelDescription.visibility = View.VISIBLE
                binding.channelDescription.text = typography.subtitle
            }
            binding.channelPlayingDot.visibility = if (isLastPlayed) View.VISIBLE else View.GONE
            binding.channelFavoriteMark.visibility = if (isFavorite) View.VISIBLE else View.GONE

            // 无障碍描述统一走字符串资源（含状态后缀占位符），避免硬编码文案
            val context = binding.root.context
            var contentDescription = channel.name
            if (isFavorite) {
                contentDescription = context.getString(R.string.mobile_channel_desc_favorite, contentDescription)
            }
            if (isLastPlayed) {
                contentDescription = context.getString(R.string.mobile_channel_desc_last_played, contentDescription)
            }
            binding.root.contentDescription = contentDescription
            // D-Pad：仅在首个可聚焦行按 UP 且列表无法继续上滚时消费事件并移交顶栏；
            // 其余行必须返回 false 交还 GridLayoutManager 正常向上移动焦点。
            binding.root.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                    event.action == android.view.KeyEvent.ACTION_DOWN &&
                    onDpadUp != null
                ) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onDpadUp.invoke(pos) else false
                } else {
                    false
                }
            }
            binding.root.setOnClickListener { onChannelClick(channel) }
            binding.root.setOnLongClickListener {
                onChannelLongClick?.let { callback ->
                    callback.invoke(channel)
                    true
                } ?: false
            }
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CHANNEL = 1
    }
}
