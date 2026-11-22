package com.livetv.android.ui.mobile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.databinding.ItemPlayerDrawerFilterChipBinding

/**
 * 选台抽屉顶部的横向分组筛选胶囊适配器。
 */
internal class ChannelDrawerFilterAdapter(
    private val onChipClick: (categoryId: String?) -> Unit,
) : RecyclerView.Adapter<ChannelDrawerFilterAdapter.ChipViewHolder>() {

    private var chips: List<ChannelDrawerFilterChip> = emptyList()

    fun updateChips(newChips: List<ChannelDrawerFilterChip>) {
        chips = newChips
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = chips.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val binding = ItemPlayerDrawerFilterChipBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ChipViewHolder(binding, onChipClick)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        holder.bind(chips[position])
    }

    class ChipViewHolder(
        private val binding: ItemPlayerDrawerFilterChipBinding,
        private val onChipClick: (categoryId: String?) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chip: ChannelDrawerFilterChip) {
            binding.drawerFilterChip.text = chip.label
            binding.drawerFilterChip.isSelected = chip.selected
            binding.drawerFilterChip.setOnClickListener { onChipClick(chip.categoryId) }
        }
    }
}
