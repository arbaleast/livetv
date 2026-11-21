package com.livetv.android.ui.mobile

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
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
 * 卡片视觉：36dp 圆角方形徽章（分类强调色）+ 频道序号 + 频道名 + 副标题。
 * 徽章底色/描边按分类强调色程序化生成，因此这里只需要拿到一组强调色即可。
 */
class MobileChannelAdapter(
    rows: List<MobileRow>,
    private val accentColors: IntArray,
    private val onChannelClick: (Channel) -> Unit,
    /** 上次播放的频道 id：用于给卡片加"最近播放"高亮边框与蓝点。 */
    private var lastPlayedChannelId: String? = null,
    /** 已收藏的频道 id 集合：用于展示卡片星标。 */
    private var favoriteChannelIds: Set<String> = emptySet(),
    /** 长按卡片回调（用于快速收藏/取消收藏）。 */
    private val onChannelLongClick: ((Channel) -> Unit)? = null,
    /** D-Pad UP 回调：传入当前卡片适配器位置，仅当确实消费事件（顶部首行跳转顶栏）时返回 `true`。 */
    private val onDpadUp: ((Int) -> Boolean)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<MobileRow> = rows

    /** 每个频道卡片在可见列表中的序号（1 起），用于右上角的 `01` / `02` 编号。 */
    private var ordinals: IntArray = buildOrdinals(rows)

    /** 原地更新行数据，避免重建 Adapter 导致 ViewHolder 复用池失效。 */
    fun updateRows(newRows: List<MobileRow>) {
        rows = newRows
        ordinals = buildOrdinals(newRows)
        notifyDataSetChanged()
    }

    /** 更新"上次播放"高亮（仅局部刷新受影响的卡片，避免整表闪烁）。 */
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

    private fun buildOrdinals(source: List<MobileRow>): IntArray {
        val result = IntArray(source.size)
        var counter = 0
        source.forEachIndexed { index, row ->
            if (row is MobileRow.ChannelCard) {
                counter += 1
                result[index] = counter
            }
        }
        return result
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
                accentColors = accentColors,
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
                ordinal = ordinals[position],
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
        private val accentColors: IntArray,
        private val onChannelClick: (Channel) -> Unit,
        private val onChannelLongClick: ((Channel) -> Unit)? = null,
        private val onDpadUp: ((Int) -> Boolean)? = null,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            card: MobileRow.ChannelCard,
            ordinal: Int,
            isLastPlayed: Boolean = false,
            isFavorite: Boolean = false,
        ) {
            val channel = card.channel
            binding.root.isSelected = isLastPlayed
            val accent = accentColorFor(card.categoryName)

            binding.channelBadge.text = MobileChannelList.extractChannelBadge(channel.name)
            applyBadgeStyle(binding.channelBadge, accent)
            binding.channelIndex.text = MobileChannelList.formatChannelIndex(ordinal)
            binding.channelName.text = channel.name
            binding.channelPlayingDot.visibility =
                if (isLastPlayed) android.view.View.VISIBLE else android.view.View.GONE
            binding.channelFavoriteMark.visibility =
                if (isFavorite) android.view.View.VISIBLE else android.view.View.GONE

            // 副标题只展示"用户能看懂"的信息；没有有效简介时整行隐藏，主标题自然垂直居中
            val subtitle = MobileChannelList.sanitizeSubtitle(channel)
            if (subtitle == null) {
                binding.channelDescription.visibility = android.view.View.GONE
            } else {
                binding.channelDescription.visibility = android.view.View.VISIBLE
                binding.channelDescription.text = subtitle
            }

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

        /**
         * 分类强调色：同一分类始终得到同一个颜色（由 `categoryAccentIndex` 稳定映射）。
         */
        private fun accentColorFor(categoryName: String): Int {
            if (accentColors.isEmpty()) return DEFAULT_ACCENT
            val index = MobileChannelList.categoryAccentIndex(categoryName)
            return accentColors[index % accentColors.size]
        }

        /**
         * 徽章背景：低饱和分类色底 + 1dp 同色边框，文字用原色，保证暗色背景下仍然克制。
         */
        private fun applyBadgeStyle(badge: android.widget.TextView, accent: Int) {
            val density = badge.resources.displayMetrics.density
            val shape = GradientDrawable()
            shape.shape = GradientDrawable.RECTANGLE
            shape.cornerRadius = 10f * density
            shape.setColor(withAlpha(accent, 0.16f))
            shape.setStroke((1f * density).toInt().coerceAtLeast(1), withAlpha(accent, 0.55f))
            badge.background = shape
            badge.setTextColor(accent)
        }

        private fun withAlpha(color: Int, factor: Float): Int = Color.argb(
            (Color.alpha(color) * factor).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

        companion object {
            private const val DEFAULT_ACCENT = 0xFF3B82F6.toInt()
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CHANNEL = 1
    }
}
