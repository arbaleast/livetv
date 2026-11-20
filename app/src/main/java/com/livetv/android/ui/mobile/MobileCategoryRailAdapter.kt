package com.livetv.android.ui.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.R
import com.livetv.android.databinding.ItemMobileCategoryRailBinding

/**
 * 左侧分类导航栏适配器。
 *
 * 每一项是"分类名 + 数量角标"的竖排胶囊；选中项用 `isSelected` 驱动背景 selector 高亮。
 * 结构上刻意留出扩展位：后续要加"收藏 / 最近播放"时，只要在 [MobileChannelList.RailItem]
 * 里扩展类型即可，不需要动这里的分组逻辑。
 */
class MobileCategoryRailAdapter(
    items: List<MobileChannelList.RailItem>,
    private val onCategoryClick: (String?) -> Unit,
) : RecyclerView.Adapter<MobileCategoryRailAdapter.RailViewHolder>() {

    private var items: List<MobileChannelList.RailItem> = items

    init {
        setHasStableIds(true)
    }

    fun updateItems(newItems: List<MobileChannelList.RailItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].id?.hashCode()?.toLong() ?: 0L

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RailViewHolder =
        RailViewHolder(
            ItemMobileCategoryRailBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onCategoryClick,
        )

    override fun onBindViewHolder(holder: RailViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class RailViewHolder(
        private val binding: ItemMobileCategoryRailBinding,
        private val onCategoryClick: (String?) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MobileChannelList.RailItem) {
            binding.railCategoryName.text = item.name
            binding.railCategoryCount.text = item.count.toString()
            binding.railCategoryCount.visibility = if (item.count > 0) View.VISIBLE else View.GONE
            binding.root.isSelected = item.selected
            binding.railCategoryName.isSelected = item.selected
            binding.root.contentDescription = "${item.name} ${item.count}"
            // D-Pad：分类项向右直接进频道网格（否则几何搜索会先命中整宽的顶栏）
            binding.root.nextFocusRightId = R.id.rv_channels
            binding.root.setOnClickListener { onCategoryClick(item.id) }
        }
    }
}
