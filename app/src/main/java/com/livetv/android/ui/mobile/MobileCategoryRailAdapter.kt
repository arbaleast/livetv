package com.livetv.android.ui.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.R
import com.livetv.android.databinding.ItemMobileCategoryPillHorizontalBinding
import com.livetv.android.databinding.ItemMobileCategoryRailBinding

/**
 * 分类导航适配器：同时支持宽屏左侧竖排导航栏（`horizontal = false`）与竖屏顶部横向胶囊条（`horizontal = true`）。
 *
 * 每一项是"分类名 + 数量角标"；选中项用 `isSelected` 驱动背景与文字对比度 selector。
 */
class MobileCategoryRailAdapter(
    items: List<MobileChannelList.RailItem>,
    private val horizontal: Boolean = false,
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RailViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (horizontal) {
            val binding = ItemMobileCategoryPillHorizontalBinding.inflate(inflater, parent, false)
            RailViewHolder(
                root = binding.root,
                nameView = binding.railCategoryName,
                countView = binding.railCategoryCount,
                horizontal = true,
                onCategoryClick = onCategoryClick,
            )
        } else {
            val binding = ItemMobileCategoryRailBinding.inflate(inflater, parent, false)
            RailViewHolder(
                root = binding.root,
                nameView = binding.railCategoryName,
                countView = binding.railCategoryCount,
                horizontal = false,
                onCategoryClick = onCategoryClick,
            )
        }
    }

    override fun onBindViewHolder(holder: RailViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class RailViewHolder(
        private val root: View,
        private val nameView: TextView,
        private val countView: TextView,
        private val horizontal: Boolean,
        private val onCategoryClick: (String?) -> Unit,
    ) : RecyclerView.ViewHolder(root) {

        fun bind(item: MobileChannelList.RailItem) {
            nameView.text = item.name
            countView.text = item.count.toString()
            // 宽屏 2x2 胶囊只显示分类名；竖屏顶部胶囊条保留数量角标
            countView.visibility = if (horizontal && item.count > 0) View.VISIBLE else View.GONE
            root.isSelected = item.selected
            nameView.isSelected = item.selected
            countView.isSelected = item.selected
            root.contentDescription = "${item.name} ${item.count}"
            if (horizontal) {
                root.nextFocusUpId = R.id.mobile_refresh
                root.nextFocusDownId = R.id.rv_channels
            } else {
                // D-Pad：左侧分类项向右直接进频道网格（否则几何搜索会先命中整宽的顶栏）
                root.nextFocusRightId = R.id.rv_channels
            }
            root.setOnClickListener { onCategoryClick(item.id) }
        }
    }
}
