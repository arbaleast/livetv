package com.livetv.android.ui.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.BuildConfig
import com.livetv.android.LiveTvApplication
import com.livetv.android.R
import com.livetv.android.data.mgtv.MgtvStreamResolver
import com.livetv.android.databinding.ActivityMobileMainBinding
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.ui.PlayerActivity
import com.livetv.android.webplayer.CctvWebChannel
import com.livetv.android.webplayer.CctvWebPlayerActivity
import com.livetv.android.webplayer.CctvWebWarmup
import com.livetv.android.util.HeaderAutoHidePolicy

/**
 * 手机 / 平板端的主界面：响应式分类导航 + 实时搜索 + 本地收藏 + 自适应触控网格。
 *
 * - **紧凑竖屏（`< 600dp`）**：顶部横向分类胶囊条 + 满宽自适应网格（可用宽度 < 360dp 时单列）；
 * - **宽屏 / 横屏（`>= 600dp`）**：左侧竖排分类栏 + 右侧浮动单行顶栏与多列网格；
 * - 数据统一来自应用级 [com.livetv.android.domain.repository.ChannelCatalogStore]（订阅/缓存 + MGTV 目录）；
 * - 上次播放频道与收藏列表通过 [MobileUiPreferences] 与 `PlayerActivity` 双向同步。
 */
class MobileMainActivity : AppCompatActivity() {

    companion object {
        private const val STATE_SELECTED_CATEGORY_ID = "selected_category_id"
        private const val STATE_LAST_PLAYED_ID = "last_played_channel_id"
        private const val STATE_SEARCH_MODE = "search_mode"
        private const val STATE_SEARCH_QUERY = "search_query"

        /** 列表首个可见位置与偏移：旋转 / 分屏重建后恢复滚动位置（重整布局管理器会清空滚动状态）。 */
        private const val STATE_SCROLL_POSITION = "scroll_position"
        private const val STATE_SCROLL_OFFSET = "scroll_offset"

        /** 滚动多少像素后才收起顶栏（防抖）。 */
        private const val HEADER_HIDE_THRESHOLD_PX = 24

        /** 顶栏收起/展开动画时长。 */
        private const val HEADER_ANIM_MS = 180L

        /** 宽屏左侧分类胶囊网格列数（2x2）。 */
        private const val HOME_CAPSULE_SPAN_COUNT = 2
    }

    private lateinit var binding: ActivityMobileMainBinding
    private lateinit var channelAdapter: MobileChannelAdapter
    private lateinit var railAdapter: MobileCategoryRailAdapter

    private var navigationMode = MobileChannelList.NavigationMode.TOP_BAR
    private var categories: List<ChannelCategory> = emptyList()
    private var displayCategories: List<ChannelCategory> = emptyList()
    private var currentRows: List<MobileRow> = emptyList()
    private var currentSpanCount = 2
    private var selectedCategoryId: String? = null
    private var catalogLoading = false

    /** 上次播放的频道 id（用于卡片高亮与蓝点标识）；与播放页双向同步。 */
    private var lastPlayedChannelId: String? = null

    /** 本地收藏频道 id 集合；与播放页双向同步。 */
    private var favoriteIds: Set<String> = emptySet()

    /** 内联实时搜索状态。 */
    private var isSearchMode = false
    private var searchQuery = ""

    /** 待恢复的列表滚动锚点（重建/改变列数时使用）；`NO_POSITION` 表示不需要恢复。 */
    private var pendingScrollPosition = RecyclerView.NO_POSITION
    private var pendingScrollOffset = 0

    /** 顶栏组合容器（顶栏 + 下缘渐变遮罩）的总高度（像素，仅 `LEFT_RAIL` 浮动顶栏模式使用）。 */
    private var headerHeightPx = 0

    /** 顶栏本体高度（像素，不含渐变遮罩）；用于网格顶部预留，避免把 16dp 遮罩也算进 padding。 */
    private var headerBodyHeightPx = 0

    /** 网格原始顶部 padding（像素）；计算顶栏预留时必须在它之上叠加，不能覆盖。 */
    private var baseChannelsTopPadding = 0

    /** 顶栏是否处于收起态（仅 `LEFT_RAIL` 浮动顶栏模式有效）。 */
    private var headerHidden = false

    private var accumulatedDy = 0
    private var lastOpenChannelElapsedMs = 0L

    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeSearchMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigationMode = MobileChannelList.resolveNavigationMode(resources.configuration.screenWidthDp)
        binding = ActivityMobileMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 在代码改动任何 padding 之前记录原始值，保证 Insets 回调反复触发时幂等不叠加
        baseChannelsTopPadding = binding.rvChannels.paddingTop

        onBackPressedDispatcher.addCallback(this, searchBackCallback)

        applyImmersiveSystemBars()
        applyCutoutSafeInsets()

        selectedCategoryId = savedInstanceState?.getString(STATE_SELECTED_CATEGORY_ID)
        lastPlayedChannelId = savedInstanceState?.getString(STATE_LAST_PLAYED_ID)
            ?: MobileUiPreferences.getLastPlayedChannelId(this)
        favoriteIds = MobileUiPreferences.getFavoriteChannelIds(this)
        isSearchMode = savedInstanceState?.getBoolean(STATE_SEARCH_MODE, false) ?: false
        searchQuery = savedInstanceState?.getString(STATE_SEARCH_QUERY).orEmpty()
        pendingScrollPosition = savedInstanceState?.getInt(STATE_SCROLL_POSITION, RecyclerView.NO_POSITION)
            ?: RecyclerView.NO_POSITION
        pendingScrollOffset = savedInstanceState?.getInt(STATE_SCROLL_OFFSET, 0) ?: 0

        setupList()
        setupHeaderActions()
        setupDebugEntries()
        setupSearchActions()
        loadChannels()
        if (isSearchMode) {
            // 先恢复搜索过滤结果，再应用滚动锚点：否则锚点会落到"未过滤前的全部分类"列表上
            restoreSearchUi()
        }
        restorePendingScrollIfNeeded()
        CctvWebWarmup.scheduleOnIdle()
    }

    override fun onResume() {
        super.onResume()
        // 从弹窗/播放页返回时收起状态栏，并同步播放页内最新的切台与收藏变更
        applyImmersiveSystemBars()
        syncPreferencesFromPlayer()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveSystemBars()
    }

    private fun syncPreferencesFromPlayer() {
        val latestPlayed = MobileUiPreferences.getLastPlayedChannelId(this)
        if (latestPlayed != lastPlayedChannelId) {
            lastPlayedChannelId = latestPlayed
            channelAdapter.setLastPlayedChannelId(latestPlayed)
            updateContinueCard()
        }
        val latestFavorites = MobileUiPreferences.getFavoriteChannelIds(this)
        if (latestFavorites != favoriteIds) {
            favoriteIds = latestFavorites
            channelAdapter.setFavoriteChannelIds(latestFavorites)
            applyCategories()
        }
    }

    private fun applyImmersiveSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    private fun applyCutoutSafeInsets() {
        val baseChannelsBottom = binding.rvChannels.paddingBottom
        val baseCategoriesBottom = binding.rvCategories.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.mobileRoot) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val applyLeft = maxOf(cutout.left, navBar.left)
            val applyRight = maxOf(cutout.right, navBar.right)
            val applyTop = cutout.top
            if (view.paddingLeft != applyLeft || view.paddingTop != applyTop || view.paddingRight != applyRight) {
                view.setPadding(applyLeft, applyTop, applyRight, 0)
            }

            val channelsBottom = baseChannelsBottom + navBar.bottom
            if (binding.rvChannels.paddingBottom != channelsBottom) {
                binding.rvChannels.setPadding(
                    binding.rvChannels.paddingStart,
                    binding.rvChannels.paddingTop,
                    binding.rvChannels.paddingEnd,
                    channelsBottom,
                )
            }
            if (navigationMode == MobileChannelList.NavigationMode.LEFT_RAIL) {
                val categoriesBottom = baseCategoriesBottom + navBar.bottom
                if (binding.rvCategories.paddingBottom != categoriesBottom) {
                    binding.rvCategories.setPadding(
                        binding.rvCategories.paddingStart,
                        binding.rvCategories.paddingTop,
                        binding.rvCategories.paddingEnd,
                        categoriesBottom,
                    )
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.mobileRoot)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_CATEGORY_ID, selectedCategoryId)
        outState.putString(STATE_LAST_PLAYED_ID, lastPlayedChannelId)
        outState.putBoolean(STATE_SEARCH_MODE, isSearchMode)
        outState.putString(STATE_SEARCH_QUERY, searchQuery)

        val layoutManager = binding.rvChannels.layoutManager as? LinearLayoutManager
        val firstVisible = layoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        outState.putInt(STATE_SCROLL_POSITION, firstVisible)
        outState.putInt(
            STATE_SCROLL_OFFSET,
            if (firstVisible != RecyclerView.NO_POSITION) {
                layoutManager?.findViewByPosition(firstVisible)?.top ?: 0
            } else {
                0
            },
        )
    }

    override fun onDestroy() {
        // 规范要求：延时动画必须在 onDestroy 清理，避免 ViewPropertyAnimator 在销毁后继续持有视图
        binding.layoutTopHeaderContainer.animate().cancel()
        super.onDestroy()
    }

    private val app: LiveTvApplication
        get() = application as LiveTvApplication

    /** 仅 Debug 构建可见：长按标题进入 CCTV WebView 原型（Release 不挂任何入口）。 */
    private fun setupDebugEntries() {
        if (!BuildConfig.DEBUG) return
        binding.topTitle.setOnLongClickListener {
            startActivity(Intent(this, CctvWebPlayerActivity::class.java))
            true
        }
    }

    private fun setupHeaderActions() {
        binding.mobileRefresh.setOnClickListener { reloadCatalogs() }
        binding.btnEmptyRetry.setOnClickListener { reloadCatalogs() }

        val expandHeaderOnFocus = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus && isFloatingHeaderMode()) {
                showTopHeader(animate = true)
            }
        }
        binding.mobileSearch.onFocusChangeListener = expandHeaderOnFocus
        binding.mobileRefresh.onFocusChangeListener = expandHeaderOnFocus
    }

    // ---------------- 内联实时搜索 ----------------

    private fun setupSearchActions() {
        binding.mobileSearch.setOnClickListener { openSearchMode() }
        binding.btnSearchClear.setOnClickListener {
            binding.editSearch.setText("")
            binding.editSearch.requestFocus()
        }
        binding.btnSearchClose.setOnClickListener { closeSearchMode() }
        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val next = s?.toString().orEmpty()
                if (next == searchQuery) return
                searchQuery = next
                binding.btnSearchClear.visibility = if (next.isNotEmpty()) View.VISIBLE else View.GONE
                applyFilter()
            }
        })
    }

    private fun openSearchMode() {
        isSearchMode = true
        searchBackCallback.isEnabled = true
        showTopHeader(animate = false)
        binding.layoutTopHeader.visibility = View.GONE
        binding.layoutSearchBar.visibility = View.VISIBLE
        binding.btnSearchClear.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
        binding.editSearch.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.editSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun restoreSearchUi() {
        searchBackCallback.isEnabled = true
        binding.layoutTopHeader.visibility = View.GONE
        binding.layoutSearchBar.visibility = View.VISIBLE
        binding.editSearch.setText(searchQuery)
        binding.editSearch.setSelection(searchQuery.length)
        binding.btnSearchClear.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
        applyFilter()
    }

    /**
     * 退出内联搜索。
     *
     * @param restoreFocusToSearchButton 关闭后是否把焦点还给顶栏「搜索」按钮。
     *   点「取消」/返回键时为 `true`（避免方向键用户丢失焦点）；
     *   点分类胶囊导致关闭时为 `false`（不能抢走用户刚点的分类项焦点）。
     */
    private fun closeSearchMode(restoreFocusToSearchButton: Boolean = true) {
        isSearchMode = false
        searchBackCallback.isEnabled = false
        searchQuery = ""
        binding.editSearch.setText("")
        binding.layoutSearchBar.visibility = View.GONE
        binding.layoutTopHeader.visibility = View.VISIBLE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.editSearch.windowToken, 0)
        applyFilter()
        if (restoreFocusToSearchButton) {
            binding.mobileSearch.requestFocus()
        }
    }

    // ---------------- 频道目录刷新（MGTV） ----------------

    private fun setReloadingState(reloading: Boolean) {
        binding.mobileRefresh.isEnabled = !reloading
        binding.mobileRefresh.alpha = if (reloading) 0.5f else 1f
        // 宽屏首页刷新按钮为图标按钮（无文字），仅竖屏文字按钮切换「刷新 / 刷新中…」
        if (!isFloatingHeaderMode()) {
            binding.mobileRefresh.text = getString(
                if (reloading) R.string.mobile_action_refreshing else R.string.mobile_action_refresh,
            )
        }
    }

    /** 重新加载频道目录（CCTV + MGTV 分槽合并）；失败时保留旧列表。 */
    private fun reloadCatalogs(silent: Boolean = false) {
        if (catalogLoading) {
            if (!silent) toast(getString(R.string.mobile_refresh_busy))
            return
        }
        catalogLoading = true
        setReloadingState(true)
        app.compositeCatalogCoordinator.refresh {
            catalogLoading = false
            if (isFinishing || isDestroyed) return@refresh
            setReloadingState(false)
            categories = MobileChannelCategoryPolicy.merge(app.catalogStore.getAllCategories())
            applyCategories()
            if (!silent) {
                val total = categories.sumOf { it.channels.size }
                if (total > 0) {
                    toast(getString(R.string.mobile_refresh_remote, total))
                } else {
                    toast(getString(R.string.mobile_refresh_failed))
                }
            }
        }
    }

    private fun toast(message: String, longDuration: Boolean = false) {
        val duration = if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(this, message, duration).show()
    }

    private fun isFloatingHeaderMode(): Boolean =
        navigationMode == MobileChannelList.NavigationMode.LEFT_RAIL

    // ---------------- 列表与网格 ----------------

    private fun setupList() {
        val isTopBar = navigationMode == MobileChannelList.NavigationMode.TOP_BAR

        railAdapter = MobileCategoryRailAdapter(
            items = emptyList(),
            horizontal = isTopBar,
            onCategoryClick = ::selectCategory,
        )
        binding.rvCategories.layoutManager = if (isTopBar) {
            LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        } else {
            // 宽屏左侧栏：紧凑 2x2 分类胶囊
            GridLayoutManager(this, HOME_CAPSULE_SPAN_COUNT)
        }
        binding.rvCategories.adapter = railAdapter
        binding.rvCategories.setHasFixedSize(false)
        binding.rvCategories.itemAnimator = null

        channelAdapter = MobileChannelAdapter(
            rows = emptyList(),
            onChannelClick = ::openChannel,
            lastPlayedChannelId = lastPlayedChannelId,
            favoriteChannelIds = favoriteIds,
            onChannelLongClick = ::toggleFavoriteChannel,
            onDpadUp = { position ->
                if (!binding.rvChannels.canScrollVertically(-1) &&
                    MobileChannelList.isInTopGridRow(currentRows, position, currentSpanCount)
                ) {
                    if (isTopBar) {
                        binding.rvCategories.requestFocus()
                    } else {
                        showTopHeader(animate = true)
                        binding.mobileRefresh.requestFocus()
                    }
                    true
                } else {
                    false
                }
            },
        )
        binding.rvChannels.adapter = channelAdapter
        binding.rvChannels.itemAnimator = null
        binding.rvChannels.addItemDecoration(GridSpacingDecoration())
        setGridLayoutManager(currentSpanCount)
        binding.rvChannels.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (isSearchMode && newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(binding.editSearch.windowToken, 0)
                }
            }
        })
        if (isFloatingHeaderMode()) {
            binding.rvChannels.addOnScrollListener(headerHideOnScrollListener())
            binding.layoutTopHeaderContainer.post {
                // 容器 = 顶栏本体 + 16dp 渐变遮罩：动画整体位移用容器高度，网格预留只算本体高度。
                // 竖屏（TOP_BAR）布局没有浮动容器，此处不会执行；frame 缺失时退回容器高度。
                headerHeightPx = binding.layoutTopHeaderContainer.height
                headerBodyHeightPx =
                    binding.layoutTopHeaderFrame?.height ?: headerHeightPx
                updateHeaderPadding()
            }
        } else {
            // 竖屏顶栏在正常流里、不会压住列表；此时渐变遮罩只会把首行卡片顶部压暗，直接隐藏
            binding.topEdgeScrim.visibility = View.GONE
        }
        applyGridSpanCount()

        binding.rvChannels.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            binding.rvChannels.post { applyGridSpanCount() }
        }

        if (!isTopBar) {
            binding.rvChannels.nextFocusLeftId = R.id.rv_categories
        }
    }

    private fun applyGridSpanCount() {
        val widthPx = binding.rvChannels.width
        if (widthPx <= 0) return
        val density = resources.displayMetrics.density
        val availableWidthPx = widthPx - binding.rvChannels.paddingStart - binding.rvChannels.paddingEnd
        val widthDp = MobileChannelList.pxToDp(availableWidthPx, density)
        val spanCount = if (isFloatingHeaderMode()) {
            // 宽屏首页：固定 3 列频道墙
            MobileChannelList.resolveHomeWallSpanCount(widthDp)
        } else {
            MobileChannelList.gridSpanCount(widthDp)
        }
        val current = (binding.rvChannels.layoutManager as? GridLayoutManager)?.spanCount
        if (current == spanCount) return

        setGridLayoutManager(spanCount)
    }

    private fun setGridLayoutManager(spanCount: Int) {
        // 换 LayoutManager 会丢掉 RecyclerView 的滚动锚点（旋转/分屏改变列数时尤其明显）：
        // 先记录当前首个可见项与偏移，装好新管理器后立刻回到同一位置。
        val oldLayoutManager = binding.rvChannels.layoutManager as? LinearLayoutManager
        // 适配器还没数据时先不消费待恢复锚点：空表布局会把 LinearLayoutManager 的 pending position 清掉
        val savedPosition = pendingScrollPosition
            .takeIf { it != RecyclerView.NO_POSITION && channelAdapter.itemCount > 0 }
        val anchorPosition = savedPosition
            ?: oldLayoutManager?.findFirstVisibleItemPosition()?.takeIf { it != RecyclerView.NO_POSITION }
        val anchorOffset = if (savedPosition != null) {
            pendingScrollOffset
        } else {
            anchorPosition?.let { oldLayoutManager?.findViewByPosition(it)?.top } ?: 0
        }

        currentSpanCount = spanCount
        val layoutManager = GridLayoutManager(this, spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val viewType = channelAdapter.getItemViewType(position)
                return if (viewType == MobileChannelAdapter.TYPE_HEADER) spanCount else 1
            }
        }
        binding.rvChannels.layoutManager = layoutManager
        if (anchorPosition != null) {
            layoutManager.scrollToPositionWithOffset(anchorPosition, anchorOffset)
        }
        if (savedPosition != null) {
            pendingScrollPosition = RecyclerView.NO_POSITION
            pendingScrollOffset = 0
        }
    }

    /**
     * 旋转 / 分屏重建后恢复频道网格的滚动位置。
     *
     * 必须等适配器有数据后再调用：空表时的布局会清掉 `LinearLayoutManager` 的待滚动位置。
     */
    private fun restorePendingScrollIfNeeded() {
        if (pendingScrollPosition == RecyclerView.NO_POSITION) return
        if (channelAdapter.itemCount == 0) return
        val layoutManager = binding.rvChannels.layoutManager as? LinearLayoutManager
        layoutManager?.scrollToPositionWithOffset(pendingScrollPosition, pendingScrollOffset)
        pendingScrollPosition = RecyclerView.NO_POSITION
        pendingScrollOffset = 0
    }

    private fun headerHideOnScrollListener() = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (!isFloatingHeaderMode() || isSearchMode || headerHeightPx <= 0) return
            val result = HeaderAutoHidePolicy.onScroll(
                accumulatedDy = accumulatedDy,
                dy = dy,
                headerHidden = headerHidden,
                thresholdPx = HEADER_HIDE_THRESHOLD_PX,
            )
            accumulatedDy = result.accumulatedDy
            when (result.action) {
                HeaderAutoHidePolicy.Action.HIDE ->
                    if (canScroll()) hideTopHeader() else accumulatedDy = 0

                HeaderAutoHidePolicy.Action.SHOW -> showTopHeader(animate = true)

                HeaderAutoHidePolicy.Action.NONE -> {
                    // 已经滚到顶部时无条件展开，避免"停在顶部但顶栏还在屏幕外"
                    if (!recyclerView.canScrollVertically(-1)) {
                        accumulatedDy = 0
                        if (headerHidden) showTopHeader(animate = true)
                    }
                }
            }
        }
    }

    private fun canScroll(): Boolean = binding.rvChannels.canScrollVertically(1)

    private fun hideTopHeader() {
        if (!isFloatingHeaderMode() || isSearchMode || headerHidden || headerHeightPx <= 0) return
        headerHidden = true
        // 顶栏与下缘渐变遮罩在同一个垂直容器里，整体上移即可同时隐藏，避免遮罩单独留在屏幕上
        binding.layoutTopHeaderContainer.animate()
            .translationY(-headerHeightPx.toFloat())
            .setDuration(HEADER_ANIM_MS)
            .start()
    }

    private fun showTopHeader(animate: Boolean) {
        if (!isFloatingHeaderMode() || headerHeightPx <= 0) return
        headerHidden = false
        accumulatedDy = 0
        if (animate) {
            binding.layoutTopHeaderContainer.animate().translationY(0f).setDuration(HEADER_ANIM_MS).start()
        } else {
            binding.layoutTopHeaderContainer.translationY = 0f
        }
    }

    private fun updateHeaderPadding() {
        if (!isFloatingHeaderMode() || headerBodyHeightPx <= 0) return
        // 只预留顶栏本体高度：16dp 渐变遮罩应压在卡片上方做柔化，而不是把首行推下去
        binding.rvChannels.setPadding(
            binding.rvChannels.paddingStart,
            headerBodyHeightPx + baseChannelsTopPadding,
            binding.rvChannels.paddingEnd,
            binding.rvChannels.paddingBottom,
        )
    }

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
        categories = MobileChannelCategoryPolicy.merge(app.catalogStore.getAllCategories())
        val needsLoad = categories.isEmpty() && !catalogLoading
        if (needsLoad) {
            catalogLoading = true
            setReloadingState(true)
        }
        applyCategories()
        if (needsLoad) {
            app.compositeCatalogCoordinator.refresh {
                catalogLoading = false
                if (isFinishing || isDestroyed) return@refresh
                setReloadingState(false)
                categories = MobileChannelCategoryPolicy.merge(app.catalogStore.getAllCategories())
                applyCategories()
            }
        }
    }

    private fun applyCategories() {
        displayCategories = MobileChannelList.prependFavoritesCategory(categories, favoriteIds)
        selectedCategoryId = if (isFloatingHeaderMode()) {
            MobileChannelList.resolveHomeCategorySelection(categories, selectedCategoryId)
        } else {
            MobileChannelList.resolveSelectedCategoryId(displayCategories, selectedCategoryId)
        }

        // 总台数按基础目录计算，避免“收藏”虚拟分组重复计数
        val totalChannels = categories.sumOf { it.channels.size }
        binding.mobileTotalCount.text = getString(R.string.mobile_total_channels, totalChannels)

        railAdapter.updateItems(buildDisplayRailItems())
        scrollRailToSelected()
        applyFilter()
        updateHomeHeader()
        updateContinueCard()
    }

    private fun buildDisplayRailItems(): List<MobileChannelList.RailItem> {
        if (isFloatingHeaderMode()) {
            // 宽屏首页：全部 / 收藏 / 央视 / 卫视·芒果 2x2 胶囊（收藏为空也保留，点击展示友好空态）
            return MobileChannelList.buildHomeCategoryCapsules(categories, favoriteIds, selectedCategoryId)
        }
        val raw = MobileChannelList.buildRailItems(displayCategories, selectedCategoryId)
        val totalChannels = categories.sumOf { it.channels.size }
        // 修正“全部”项角标为真实总台数（不含收藏重复计数）
        return raw.mapIndexed { index, item ->
            if (index == 0 && item.id == null) item.copy(count = totalChannels) else item
        }
    }

    /** 宽屏右侧顶栏：左侧显示当前选中分类标题（竖屏保持品牌标题不变）。 */
    private fun updateHomeHeader() {
        if (!isFloatingHeaderMode()) return
        val title = buildDisplayRailItems().firstOrNull { it.selected }?.name
        binding.topTitle.text = title ?: getString(R.string.app_name)
    }

    /**
     * 刷新左侧「继续观看」主卡：命中最近观看频道则展示「继续观看」+ 频道全称，
     * 无历史时降级为「开始观看」推荐首个频道；目录为空时整卡禁用，不造假。
     */
    private fun updateContinueCard() {
        val card = binding.homeContinueCard ?: return
        val state = MobileChannelList.resolveContinueWatching(categories, lastPlayedChannelId)
        val target = state.targetChannel
        binding.homeContinueBadge?.text = state.badgeLabel
        binding.homeContinueTitle?.text = target?.name ?: getString(R.string.mobile_continue_empty)
        card.isEnabled = target != null
        card.alpha = if (target != null) 1f else 0.5f
        card.contentDescription = getString(
            R.string.mobile_continue_desc,
            state.badgeLabel,
            target?.name ?: getString(R.string.mobile_continue_empty),
        )
        card.setOnClickListener { target?.let(::openChannel) }
    }

    private fun selectCategory(categoryId: String?) {
        selectedCategoryId = categoryId
        if (isSearchMode) {
            // 用户主动点了分类：焦点跟随分类项，不要把焦点抢回搜索按钮
            closeSearchMode(restoreFocusToSearchButton = false)
        }
        railAdapter.updateItems(buildDisplayRailItems())
        scrollRailToSelected()
        applyFilter()
        updateHomeHeader()
        showTopHeader(animate = true)
        binding.rvChannels.scrollToPosition(0)
    }

    private fun scrollRailToSelected() {
        val index = buildDisplayRailItems().indexOfFirst { it.selected }
        if (index >= 0) {
            (binding.rvCategories.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(index, dp(4))
        }
    }

    private fun applyFilter() {
        val trimmedQuery = searchQuery.trim()
        val rows = if (isSearchMode && trimmedQuery.isNotEmpty()) {
            MobileChannelList.searchRows(categories, trimmedQuery)
        } else if (isFloatingHeaderMode()) {
            // 宽屏首页：平铺 3 列频道墙（全部 / 收藏 / 指定大类）
            MobileChannelList.buildWallRows(categories, favoriteIds, selectedCategoryId)
        } else {
            // 「全部」视图不重复展示“收藏”卡片分段，仅在左侧/顶部「收藏」分组下展示收藏网格
            val sourceCategories = if (selectedCategoryId == null) categories else displayCategories
            MobileChannelList.filterRows(sourceCategories, selectedCategoryId)
        }
        currentRows = rows

        if (rows.isEmpty()) {
            binding.rvChannels.visibility = View.GONE
            val showLoading = !isSearchMode && catalogLoading && categories.isEmpty()
            binding.loadingView.visibility = if (showLoading) View.VISIBLE else View.GONE
            binding.emptyContainer.visibility = if (showLoading) View.GONE else View.VISIBLE
            val isSearchEmpty = isSearchMode && trimmedQuery.isNotEmpty()
            val isFavoritesEmpty =
                selectedCategoryId == MobileChannelList.FAVORITE_CATEGORY_ID && categories.isNotEmpty()
            binding.emptyView.text = when {
                isSearchEmpty -> getString(R.string.mobile_search_empty, trimmedQuery)
                isFavoritesEmpty -> getString(R.string.mobile_favorites_empty)
                else -> getString(R.string.mobile_empty_channels)
            }
            binding.btnEmptyRetry.visibility =
                if (!showLoading && !isSearchEmpty && !isFavoritesEmpty) View.VISIBLE else View.GONE
            showTopHeader(animate = false)
            return
        }

        binding.loadingView.visibility = View.GONE
        binding.emptyContainer.visibility = View.GONE
        binding.rvChannels.visibility = View.VISIBLE
        channelAdapter.updateRows(rows)
        if (isFloatingHeaderMode()) {
            binding.rvChannels.post {
                if (!canScroll()) showTopHeader(animate = true)
            }
        }
    }

    private fun toggleFavoriteChannel(channel: Channel) {
        val nowFavorite = MobileUiPreferences.toggleFavoriteChannel(this, channel.id)
        favoriteIds = MobileUiPreferences.getFavoriteChannelIds(this)
        channelAdapter.setFavoriteChannelIds(favoriteIds)
        applyCategories()
        toast(
            getString(
                if (nowFavorite) R.string.mobile_favorite_added else R.string.mobile_favorite_removed,
                channel.name,
            ),
        )
    }

    private fun openChannel(channel: Channel) {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - lastOpenChannelElapsedMs < 500L) return
        lastOpenChannelElapsedMs = nowMs
        if (channel.id.startsWith(CctvWebChannel.CHANNEL_PREFIX)) {
            MobilePlayerTransitionHelper.dismissOtherEngineBeforeLaunch(CctvWebPlayerActivity::class.java)
            startActivity(
                Intent(this, CctvWebPlayerActivity::class.java)
                    .putExtra(CctvWebPlayerActivity.EXTRA_CHANNEL_ID, channel.id),
            )
            return
        }
        val isDynamicSource = channel.id.startsWith(MgtvStreamResolver.CHANNEL_ID_PREFIX)
        if (channel.streamUrl.isEmpty() && !isDynamicSource) {
            toast(getString(R.string.channel_no_stream_url, channel.name))
            return
        }
        rememberLastPlayed(channel.id)
        MobilePlayerTransitionHelper.dismissOtherEngineBeforeLaunch(PlayerActivity::class.java)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                .putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl),
        )
    }

    private fun rememberLastPlayed(channelId: String) {
        lastPlayedChannelId = channelId
        channelAdapter.setLastPlayedChannelId(channelId)
        MobileUiPreferences.setLastPlayedChannelId(this, channelId)
        updateContinueCard()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
