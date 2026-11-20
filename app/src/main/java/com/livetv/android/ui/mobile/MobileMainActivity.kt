package com.livetv.android.ui.mobile

import android.content.Intent
import android.graphics.Rect
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.LiveTvApplication
import com.livetv.android.R
import com.livetv.android.databinding.ActivityMobileMainBinding
import com.livetv.android.domain.model.Channel
import com.livetv.android.data.mgtv.MgtvStreamResolver
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.domain.model.RefreshResult
import com.livetv.android.player.RetryPolicy
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
        private const val STATE_SELECTED_CATEGORY_ID = "selected_category_id"
        private const val STATE_LAST_PLAYED_ID = "last_played_channel_id"
        private const val PREFS_NAME = "livetv_mobile_ui"
        private const val KEY_LAST_PLAYED = "last_played_channel_id"

        /** 滚动多少像素后才收起顶栏（防抖）。 */
        private const val HEADER_HIDE_THRESHOLD_PX = 24

        /** 顶栏收起/展开动画时长。 */
        private const val HEADER_ANIM_MS = 180L
    }

    private lateinit var binding: ActivityMobileMainBinding
    private lateinit var channelAdapter: MobileChannelAdapter
    private lateinit var railAdapter: MobileCategoryRailAdapter

    private var categories: List<ChannelCategory> = emptyList()
    private var selectedCategoryId: String? = null
    private var accentColors: IntArray = IntArray(0)
    private var isRefreshing = false
    private var mgtvLoaded = false
    private var mgtvLoading = false
    /** 上次播放的频道 id（用于卡片高亮）；随 Activity 状态一起保存。 */
    private var lastPlayedChannelId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMobileMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyImmersiveSystemBars()
        applyCutoutSafeInsets()

        selectedCategoryId = savedInstanceState?.getString(STATE_SELECTED_CATEGORY_ID)
        lastPlayedChannelId = savedInstanceState?.getString(STATE_LAST_PLAYED_ID)
            ?: getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_LAST_PLAYED, null)
        accentColors = loadAccentColors()

        setupList()
        loadChannels()
        setupSubscriptionActions()

        // 仅首次进入时静默刷新（旋转重建不重复拉取）
        if (savedInstanceState == null && !app.subscriptionRepository.getSubscriptionUrl().isNullOrBlank()) {
            refreshSubscription(silent = true)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从弹窗/其它页面返回时状态栏可能被系统重新显示，这里再收一次
        applyImmersiveSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 弹窗关闭、通知栏下拉收起等都会触发焦点变化，重新进入沉浸态
        if (hasFocus) applyImmersiveSystemBars()
    }

    /**
     * 沉浸式系统栏：默认隐藏状态栏，边缘下滑可临时呼出并自动收起。
     *
     * 额外在 `onResume` / `onWindowFocusChanged` 里重复调用，避免打开再关闭弹窗后
     * 状态栏被系统恢复成常驻。
     */
    private fun applyImmersiveSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // 只隐藏状态栏：底部导航/手势条保留在播放页之外，避免影响返回手势
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    /**
     * 挖孔屏安全区：把 `displayCutout()` 的**左右**安全区加到根容器内边距上。
     *
     * - 只在横屏（左右挖孔）时生效，竖屏时不额外加内边距；
     * - 不叠加状态栏高度（状态栏是隐藏的），避免顶部出现多余空白。
     */
    private fun applyCutoutSafeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mobileRoot) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val applyLeft = maxOf(cutout.left, navBar.left)
            val applyRight = maxOf(cutout.right, navBar.right)
            if (view.paddingLeft != applyLeft || view.paddingRight != applyRight) {
                view.setPadding(applyLeft, view.paddingTop, applyRight, view.paddingBottom)
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.mobileRoot)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_CATEGORY_ID, selectedCategoryId)
        outState.putString(STATE_LAST_PLAYED_ID, lastPlayedChannelId)
    }

    private val app: LiveTvApplication
        get() = application as LiveTvApplication

    private fun setupSubscriptionActions() {
        binding.mobileRefresh.setOnClickListener { refreshSubscription(silent = false) }
        binding.mobileSubscription.setOnClickListener { showSubscriptionDialog() }
    }

    private fun setRefreshingState(refreshing: Boolean) {
        isRefreshing = refreshing
        binding.mobileRefresh.isEnabled = !refreshing
        binding.mobileRefresh.alpha = if (refreshing) 0.5f else 1f
        binding.mobileRefresh.text = getString(
            if (refreshing) R.string.mobile_action_refreshing else R.string.mobile_action_refresh,
        )
        binding.mobileSubscription.isEnabled = !refreshing
        binding.mobileSubscription.alpha = if (refreshing) 0.5f else 1f
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
        setRefreshingState(true)

        app.subscriptionCoordinator.refresh { result ->
            if (isFinishing || isDestroyed) return@refresh
            setRefreshingState(false)
            loadChannels()
            if (!silent) showRefreshResult(result)
        }
    }

    private fun updateSubscriptionUrl(url: String?) {
        if (isRefreshing) {
            toast(getString(R.string.mobile_refresh_busy))
            return
        }
        setRefreshingState(true)

        app.subscriptionCoordinator.updateUrl(url) { result ->
            if (isFinishing || isDestroyed) return@updateUrl
            setRefreshingState(false)
            loadChannels()
            showRefreshResult(result)
        }
    }

    private fun showRefreshResult(result: RefreshResult) {
        val isFallback = result is RefreshResult.FallbackToCache || result is RefreshResult.FallbackToBuiltin
        toast(describeRefresh(result), longDuration = isFallback)
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
     * 订阅源配置对话框：输入 M3U 地址、保存并刷新、恢复内置源（带二次确认）、取消。
     *
     * 只允许输入 http(s) 的 M3U 地址；不内置任何"演示源"快捷入口。
     */
    private fun showSubscriptionDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }

        val current = app.subscriptionRepository.getSubscriptionUrl()
        val status = TextView(this).apply {
            text = if (current.isNullOrBlank()) {
                getString(R.string.mobile_subscription_none)
            } else {
                getString(R.string.mobile_subscription_current, current)
            }
            textSize = 13f
            setTextColor(getColor(R.color.mobile_text_secondary))
            setPadding(dp(2), 0, dp(2), dp(10))
        }
        container.addView(status)

        val input = EditText(this).apply {
            setText(current.orEmpty())
            hint = getString(R.string.mobile_subscription_hint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(getColor(R.color.mobile_text_primary))
            setHintTextColor(getColor(R.color.mobile_text_tertiary))
            minHeight = dp(48)
        }
        container.addView(input)

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.mobile_subscription_title)
            .setView(container)
            .setPositiveButton(R.string.mobile_subscription_save, null)
            .setNegativeButton(R.string.mobile_subscription_cancel, null)

        if (!current.isNullOrBlank()) {
            builder.setNeutralButton(R.string.mobile_subscription_reset) { _, _ ->
                confirmResetSubscription()
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text.toString().trim()
                if (!RetryPolicy.isValidUrl(url)) {
                    input.error = getString(R.string.mobile_subscription_invalid_url)
                    return@setOnClickListener
                }
                dialog.dismiss()
                updateSubscriptionUrl(url)
            }
        }
        dialog.show()
    }

    private fun confirmResetSubscription() {
        AlertDialog.Builder(this)
            .setTitle(R.string.mobile_subscription_reset_confirm_title)
            .setMessage(R.string.mobile_subscription_reset_confirm_message)
            .setPositiveButton(R.string.mobile_subscription_reset_confirm_ok) { _, _ ->
                updateSubscriptionUrl(null)
            }
            .setNegativeButton(R.string.mobile_subscription_cancel, null)
            .show()
    }

    private fun toast(message: String, longDuration: Boolean = false) {
        val duration = if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(this, message, duration).show()
    }

    private fun setupList() {
        // 左侧分类导航栏
        railAdapter = MobileCategoryRailAdapter(emptyList()) { categoryId -> selectCategory(categoryId) }
        binding.rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.rvCategories.adapter = railAdapter
        binding.rvCategories.setHasFixedSize(false)
        binding.rvCategories.itemAnimator = null

        // 右侧自适应网格
        channelAdapter = MobileChannelAdapter(
            rows = emptyList(),
            accentColors = accentColors,
            onChannelClick = ::openChannel,
            lastPlayedChannelId = lastPlayedChannelId,
            onDpadUp = {
                // 网格已在顶部：展开顶栏并把焦点交给刷新按钮（遥控器链路）
                if (!binding.rvChannels.canScrollVertically(-1)) {
                    showTopHeader(animate = true)
                    binding.mobileRefresh.requestFocus()
                }
            },
        )
        binding.rvChannels.adapter = channelAdapter
        binding.rvChannels.itemAnimator = null
        binding.rvChannels.addItemDecoration(GridSpacingDecoration())
        binding.rvChannels.addOnScrollListener(headerHideOnScrollListener())
        applyGridSpanCount()

        // 顶栏是浮层：给网格留出同样的顶部内边距，并把顶栏高度记下来用于滑动隐藏
        binding.layoutTopHeader.post {
            headerHeightPx = binding.layoutTopHeader.height
            updateHeaderPadding()
        }
        binding.rvChannels.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyGridSpanCount()
        }

        // D-Pad / 焦点：网格最上一行向上 → 顶栏；最左列向左 → 分类栏
        binding.rvChannels.nextFocusUpId = R.id.mobile_refresh
        binding.rvChannels.nextFocusLeftId = R.id.rv_categories
        binding.layoutTopHeader.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showTopHeader(animate = true)
        }
    }

    /** 顶栏高度（像素）；未测量完成前为 0。 */
    private var headerHeightPx = 0

    /** 顶栏是否处于收起态。 */
    private var headerHidden = false

    /** 按网格实测可用宽度重算列数（不写死分辨率/列数）。 */
    private fun applyGridSpanCount() {
        val widthPx = binding.rvChannels.width
        if (widthPx <= 0) return
        val density = resources.displayMetrics.density
        val availableWidthPx = widthPx - binding.rvChannels.paddingStart - binding.rvChannels.paddingEnd
        val spanCount = MobileChannelList.gridSpanCount(
            MobileChannelList.pxToDp(availableWidthPx, density),
        )
        val current = (binding.rvChannels.layoutManager as? GridLayoutManager)?.spanCount
        if (current == spanCount) return

        val layoutManager = GridLayoutManager(this, spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val viewType = channelAdapter.getItemViewType(position)
                return if (viewType == MobileChannelAdapter.TYPE_HEADER) spanCount else 1
            }
        }
        binding.rvChannels.layoutManager = layoutManager
    }

    /**
     * 滚动隐藏顶栏（不依赖 Material/AppBarLayout：仓库没有引入 `com.google.android.material`，
     * 用 `OnScrollListener` + `translationY` 实现，零新增依赖）。
     *
     * 阈值防抖：累计位移超过 [HEADER_HIDE_THRESHOLD_PX] 才切换，避免手抖导致顶栏闪烁。
     */
    private fun headerHideOnScrollListener() = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (headerHeightPx <= 0) return
            accumulatedDy += dy
            if (!headerHidden && accumulatedDy > HEADER_HIDE_THRESHOLD_PX && canScroll()) {
                accumulatedDy = 0
                hideTopHeader()
            } else if (headerHidden && accumulatedDy < -HEADER_HIDE_THRESHOLD_PX) {
                accumulatedDy = 0
                showTopHeader(animate = true)
            } else if (!recyclerView.canScrollVertically(-1)) {
                accumulatedDy = 0
                if (headerHidden) showTopHeader(animate = true)
            }
        }
    }

    private var accumulatedDy = 0

    /** 当前分类内容是否足够滚动（不足一屏时不允许收起顶栏，避免回不来）。 */
    private fun canScroll(): Boolean = binding.rvChannels.canScrollVertically(1)

    private fun hideTopHeader() {
        if (headerHidden || headerHeightPx <= 0) return
        headerHidden = true
        binding.layoutTopHeader.animate()
            .translationY(-headerHeightPx.toFloat())
            .setDuration(HEADER_ANIM_MS)
            .start()
        binding.topEdgeScrim.animate().alpha(0f).setDuration(HEADER_ANIM_MS).start()
    }

    private fun showTopHeader(animate: Boolean) {
        if (headerHeightPx <= 0) return
        headerHidden = false
        accumulatedDy = 0
        if (animate) {
            binding.layoutTopHeader.animate().translationY(0f).setDuration(HEADER_ANIM_MS).start()
            binding.topEdgeScrim.animate().alpha(1f).setDuration(HEADER_ANIM_MS).start()
        } else {
            binding.layoutTopHeader.translationY = 0f
            binding.topEdgeScrim.alpha = 1f
        }
    }

    /** 顶栏高度 = 网格顶部内边距（收敛为一个来源，避免两处写死）。 */
    private fun updateHeaderPadding() {
        if (headerHeightPx <= 0) return
        binding.rvChannels.setPadding(
            binding.rvChannels.paddingStart,
            headerHeightPx + dp(6),
            binding.rvChannels.paddingEnd,
            binding.rvChannels.paddingBottom,
        )
    }

    /** 网格间距：统一由 ItemDecoration 控制，保证左右边缘与行列间距对称。 */
    private inner class GridSpacingDecoration : RecyclerView.ItemDecoration() {
        private val halfGap = dp(4)

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            outRect.set(halfGap, halfGap, halfGap, halfGap)
        }
    }

    private fun loadChannels() {
        val repository = (application as LiveTvApplication).channelRepository
        categories = repository.getCategories()
        applyCategories()
        loadMgtvChannelsIfNeeded()
    }

    /**
     * 加载 MGTV 频道目录并作为**独立分组**追加到列表末尾。
     *
     * 只拉一次；失败时保持原列表不变，不影响内置/订阅频道。
     */
    private fun loadMgtvChannelsIfNeeded() {
        if (mgtvLoaded || mgtvLoading) return
        mgtvLoading = true
        app.mgtvCatalogCoordinator.load { mgtvCategories ->
            mgtvLoading = false
            if (isFinishing || isDestroyed) return@load
            if (mgtvCategories.isEmpty()) return@load
            mgtvLoaded = true
            // 追加到末尾，避免打乱内置分组的顺序
            categories = categories + mgtvCategories
            applyCategories()
        }
    }

    /** 按当前分类列表刷新总数、左侧导航栏与网格。 */
    private fun applyCategories() {
        selectedCategoryId = MobileChannelList.resolveSelectedCategoryId(categories, selectedCategoryId)

        val totalChannels = categories.sumOf { it.channels.size }
        binding.mobileTotalCount.text = getString(R.string.mobile_total_channels, totalChannels)

        railAdapter.updateItems(MobileChannelList.buildRailItems(categories, selectedCategoryId))
        scrollRailToSelected()
        applyFilter()
    }

    private fun selectCategory(categoryId: String?) {
        selectedCategoryId = categoryId
        railAdapter.updateItems(MobileChannelList.buildRailItems(categories, selectedCategoryId))
        scrollRailToSelected()
        applyFilter()
        // 切换分类必须恢复顶栏：短列表回滑不上去，否则顶栏会永久消失
        showTopHeader(animate = true)
        binding.rvChannels.scrollToPosition(0)
    }

    private fun scrollRailToSelected() {
        val index = MobileChannelList.buildRailItems(categories, selectedCategoryId)
            .indexOfFirst { it.selected }
        if (index >= 0) {
            (binding.rvCategories.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(index, dp(4))
        }
    }

    private fun applyFilter() {
        val rows = MobileChannelList.filterRows(categories, selectedCategoryId)

        if (rows.isEmpty()) {
            binding.rvChannels.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            showTopHeader(animate = false)
            return
        }

        binding.rvChannels.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        channelAdapter.updateRows(rows)
        // 内容变化后（刷新/切分类/追加 MGTV 分组）重新评估：短列表不允许保持收起态
        binding.rvChannels.post {
            if (!canScroll()) showTopHeader(animate = true)
        }
    }

    private fun openChannel(channel: Channel) {
        // 动态源频道（如 `mgtv:` 前缀）的 streamUrl 本来就是空的：
        // 真实地址要等播放页里由 StreamResolver 按会话去解析，因此不能按"空地址"拦下。
        val isDynamicSource = channel.id.startsWith(MgtvStreamResolver.CHANNEL_ID_PREFIX)
        if (channel.streamUrl.isEmpty() && !isDynamicSource) {
            toast(getString(R.string.channel_no_stream_url, channel.name))
            return
        }
        // 播放页双端通用：Extra 与 TV 端 MainBrowseFragment 完全一致
        rememberLastPlayed(channel.id)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                .putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl),
        )
    }

    /** 记住"上次播放"的频道：用于列表卡片的高亮边框（纯本地 UI 状态，不入库）。 */
    private fun rememberLastPlayed(channelId: String) {
        lastPlayedChannelId = channelId
        channelAdapter.setLastPlayedChannelId(channelId)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PLAYED, channelId)
            .apply()
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
