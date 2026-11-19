package com.livetv.android.ui.mobile

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.LiveTvApplication
import com.livetv.android.R
import com.livetv.android.databinding.ActivityMobileMainBinding
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.ui.PlayerActivity

/**
 * 手机 / 平板端的主界面：标准 `RecyclerView` + `GridLayoutManager` 触控网格。
 *
 * 与 TV 端完全隔离——TV 端走 `MainActivity` → Leanback `MainBrowseFragment`；
 * 两端共用底层资产：`LiveTvApplication.channelRepository`（数据）、`PlayerActivity`（播放）。
 *
 * 顶部提供可横向滚动的分类胶囊：`全部` 显示分组视图，选中某个分类则过滤为纯网格。
 */
class MobileMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMobileMainBinding

    private var categories: List<ChannelCategory> = emptyList()
    private var selectedCategoryId: String? = null
    private var accentColors: IntArray = IntArray(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMobileMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        accentColors = loadAccentColors()

        setupList()
        loadChannels()
    }

    private fun setupList() {
        val spanCount = MobileChannelList.gridSpanCount(screenWidthDp())
        val layoutManager = GridLayoutManager(this, spanCount)
        // 分类标题跨满整行，频道卡片各占一格
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val adapter = binding.channelList.adapter
                val viewType = adapter?.getItemViewType(position)
                return if (viewType == MobileChannelAdapter.TYPE_HEADER) spanCount else 1
            }
        }
        binding.channelList.layoutManager = layoutManager
        binding.channelList.setHasFixedSize(true)
    }

    private fun loadChannels() {
        val repository = (application as LiveTvApplication).channelRepository
        categories = repository.getCategories()

        val totalChannels = categories.sumOf { it.channels.size }
        binding.mobileTotalCount.text = getString(R.string.mobile_total_channels, totalChannels)

        setupCategoryPills()
        applyFilter()
    }

    /**
     * 构建"全部 + 每个分类"的胶囊条。胶囊用 `View.isSelected` 驱动背景与文字颜色的 selector。
     */
    private fun setupCategoryPills() {
        val bar = binding.categoryPillBar
        bar.removeAllViews()

        bar.addView(
            createPill(
                label = getString(R.string.mobile_all_categories),
                onClick = { selectCategory(null) },
            ),
        )

        categories.forEach { category ->
            bar.addView(
                createPill(
                    label = "${category.name} ${category.channels.size}",
                    onClick = { selectCategory(category.id) },
                ),
            )
        }

        // 首次进入时 selectedCategoryId 为 null，"全部"胶囊需要立刻呈选中态
        updatePillSelection()
    }

    private fun createPill(label: String, onClick: () -> Unit): TextView {
        val pill = TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = AppCompatResources.getDrawable(this@MobileMainActivity, R.drawable.bg_mobile_category_pill)
            setTextColor(resources.getColorStateList(R.color.mobile_category_pill_text, theme))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.marginEnd = dp(8)
        pill.layoutParams = params
        return pill
    }

    private fun selectCategory(categoryId: String?) {
        selectedCategoryId = categoryId
        updatePillSelection()
        applyFilter()
    }

    private fun updatePillSelection() {
        val bar = binding.categoryPillBar
        for (index in 0 until bar.childCount) {
            val pill = bar.getChildAt(index) as? TextView ?: continue
            // 第一个胶囊是"全部"，其余按分类顺序对应
            val categoryId = if (index == 0) null else categories.getOrNull(index - 1)?.id
            pill.isSelected = categoryId == selectedCategoryId
        }
    }

    private fun applyFilter() {
        val rows = MobileChannelList.filterRows(categories, selectedCategoryId)

        if (rows.isEmpty()) {
            binding.channelList.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            return
        }

        binding.channelList.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        binding.channelList.adapter = MobileChannelAdapter(rows, accentColors, ::openChannel)
    }

    private fun openChannel(channel: Channel) {
        if (channel.streamUrl.isEmpty()) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.channel_selected, channel.name),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        // 播放页双端通用：Extra 与 TV 端 MainBrowseFragment 完全一致
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                .putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl),
        )
    }

    private fun loadAccentColors(): IntArray {
        val typed = resources.obtainTypedArray(R.array.mobile_badge_accents)
        try {
            return IntArray(typed.length()) { typed.getColor(it, 0) }
        } finally {
            typed.recycle()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun screenWidthDp(): Int {
        val metrics = resources.displayMetrics
        // px -> dp 必须除以 density；不能用 TypedValue.applyDimension（那是 dp -> px）
        return MobileChannelList.pxToDp(metrics.widthPixels, metrics.density)
    }
}
