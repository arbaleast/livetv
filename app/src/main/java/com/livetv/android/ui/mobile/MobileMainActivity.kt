package com.livetv.android.ui.mobile

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.LiveTvApplication
import com.livetv.android.R
import com.livetv.android.databinding.ActivityMobileMainBinding
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.domain.model.RefreshResult
import com.livetv.android.ui.PlayerActivity

/**
 * 手机 / 平板端的主界面：标准 `RecyclerView` + `GridLayoutManager` 触控网格。
 *
 * 与 TV 端完全隔离——TV 端走 `MainActivity` → Leanback `MainBrowseFragment`；
 * 两端共用底层资产：`LiveTvApplication.channelRepository`（数据）、`PlayerActivity`（播放）。
 *
 * 顶部提供可横向滚动的分类胶囊：`全部` 显示分组视图，选中某个分类则过滤为纯网格。
 *
 * 阶段八新增：顶栏的「刷新」与「订阅源」入口。
 * - 数据来源是**三级兜底**：远程订阅 → 本地有效缓存 → 内置 assets；
 * - 拉取在后台线程执行（[com.livetv.android.data.SubscriptionCoordinator]），
 *   结果回主线程刷新列表；
 * - 启动时若已配置订阅源，会在渲染内存快照之后**静默**刷新一次。
 */
class MobileMainActivity : AppCompatActivity() {

    companion object {
        /**
         * 本地 SecLab 教学沙箱的播放列表地址。
         *
         * 仅在 **Debug 包**中通过「填入本地 SecLab」按钮使用（见 [isDebuggable]），
         * 正式包既不展示该入口，也不会把该地址写进订阅配置。
         */
        const val LOCAL_LAB_PLAYLIST_URL = "http://127.0.0.1:18080/playlist.m3u"
    }

    private lateinit var binding: ActivityMobileMainBinding

    private var categories: List<ChannelCategory> = emptyList()
    private var selectedCategoryId: String? = null
    private var accentColors: IntArray = IntArray(0)
    private var isRefreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMobileMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        accentColors = loadAccentColors()

        setupList()
        loadChannels()
        setupSubscriptionActions()

        // 先渲染内存快照（远程订阅 → 缓存 → 内置），再在后台静默刷新一次
        if (!app.subscriptionRepository.getSubscriptionUrl().isNullOrBlank()) {
            refreshSubscription(silent = true)
        }
    }

    private val app: LiveTvApplication
        get() = application as LiveTvApplication

    private fun setupSubscriptionActions() {
        binding.mobileRefresh.setOnClickListener { refreshSubscription(silent = false) }
        binding.mobileSubscription.setOnClickListener { showSubscriptionDialog() }
    }

    /**
     * 后台刷新订阅源。
     *
     * @param silent `true` 时不弹提示（启动时的静默刷新）。
     */
    private fun refreshSubscription(silent: Boolean) {
        if (isRefreshing) {
            if (!silent) toast(getString(R.string.mobile_refresh_busy))
            return
        }
        isRefreshing = true
        binding.mobileRefresh.isEnabled = false
        binding.mobileRefresh.alpha = 0.5f

        app.subscriptionCoordinator.refresh { result ->
            isRefreshing = false
            binding.mobileRefresh.isEnabled = true
            binding.mobileRefresh.alpha = 1f
            loadChannels()
            if (!silent) toast(describeRefresh(result))
        }
    }

    private fun describeRefresh(result: RefreshResult): String = when (result) {
        is RefreshResult.BuiltinOnly -> getString(R.string.mobile_refresh_builtin, result.channelCount)
        is RefreshResult.RemoteLoaded -> getString(R.string.mobile_refresh_remote, result.channelCount)
        is RefreshResult.FallbackToCache ->
            getString(R.string.mobile_refresh_cache, result.channelCount, result.reason)
        is RefreshResult.FallbackToBuiltin ->
            getString(R.string.mobile_refresh_builtin_fallback, result.channelCount, result.reason)
    }

    /**
     * 订阅源配置对话框：输入 M3U 地址、保存并刷新、恢复内置源、取消。
     *
     * Debug 包额外提供「填入本地 SecLab」快捷按钮，方便课堂演示指向本机沙箱。
     */
    private fun showSubscriptionDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val current = app.subscriptionRepository.getSubscriptionUrl()
        val status = TextView(this).apply {
            text = if (current.isNullOrBlank()) {
                getString(R.string.mobile_subscription_none)
            } else {
                getString(R.string.mobile_subscription_current, current)
            }
            textSize = 12f
            setTextColor(getColor(R.color.mobile_text_tertiary))
            setPadding(dp(2), 0, dp(2), dp(6))
        }
        container.addView(status)

        val input = EditText(this).apply {
            setText(current.orEmpty())
            hint = getString(R.string.mobile_subscription_hint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(getColor(R.color.mobile_text_primary))
            setHintTextColor(getColor(R.color.mobile_text_tertiary))
        }
        container.addView(input)

        if (isDebuggable()) {
            val fill = TextView(this).apply {
                text = getString(R.string.mobile_subscription_fill_local)
                textSize = 12f
                setTextColor(getColor(R.color.mobile_accent))
                setPadding(dp(2), dp(12), dp(2), 0)
                isClickable = true
                setOnClickListener { input.setText(LOCAL_LAB_PLAYLIST_URL) }
            }
            container.addView(fill)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.mobile_subscription_title)
            .setView(container)
            .setPositiveButton(R.string.mobile_subscription_save) { _, _ ->
                app.subscriptionCoordinator.updateUrl(input.text.toString().trim()) { result ->
                    loadChannels()
                    toast(describeRefresh(result))
                }
            }
            .setNegativeButton(R.string.mobile_subscription_reset) { _, _ ->
                app.subscriptionCoordinator.updateUrl(null) { result ->
                    loadChannels()
                    toast(describeRefresh(result))
                }
            }
            .setNeutralButton(R.string.mobile_subscription_cancel, null)
            .show()
    }

    /** 是否为可调试构建（只有 Debug 包才提供本地沙箱快捷入口）。 */
    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
