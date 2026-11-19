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
    private val rows: List<MobileRow>,
    private val accentColors: IntArray,
    private val onChannelClick: (Channel) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 每个频道卡片在可见列表中的序号（1 起），用于右上角的 `01` / `02` 编号。 */
    private val ordinals: IntArray = IntArray(rows.size)

    init {
        var counter = 0
        rows.forEachIndexed { index, row ->
            if (row is MobileRow.ChannelCard) {
                counter += 1
                ordinals[index] = counter
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
                ItemMobileChannelBinding.inflate(inflater, parent, false),
                accentColors,
                onChannelClick,
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is MobileRow.CategoryHeader -> (holder as HeaderViewHolder).bind(row)
            is MobileRow.ChannelCard -> (holder as ChannelViewHolder).bind(row, ordinals[position])
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
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(card: MobileRow.ChannelCard, ordinal: Int) {
            val channel = card.channel
            val accent = accentColorFor(card.categoryName)

            binding.channelBadge.text = MobileChannelList.extractChannelBadge(channel.name)
            applyBadgeStyle(binding.channelBadge, accent)
            binding.channelIndex.text = MobileChannelList.formatChannelIndex(ordinal)
            binding.channelName.text = channel.name
            binding.channelDescription.text = MobileChannelList.formatChannelSubtitle(channel)
            binding.root.setOnClickListener { onChannelClick(channel) }
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
