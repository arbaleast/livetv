package com.livetv.android.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import com.livetv.android.LiveTvApplication
import com.livetv.android.R
import com.livetv.android.data.mgtv.MgtvStreamResolver
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.RefreshResult
import com.livetv.android.player.RetryPolicy
import com.livetv.android.ui.mobile.MobileChannelList
import com.livetv.android.ui.mobile.MobileUiPreferences
import com.livetv.android.util.FocusRestorePolicy
import kotlin.math.roundToInt

class MainBrowseFragment : BrowseSupportFragment() {

    private val app: LiveTvApplication
        get() = requireActivity().application as LiveTvApplication

    private var isRefreshing = false
    private var mgtvLoading = false

    /** 当前焦点行的标识（分类名 / 「设置与订阅」行名），刷新后用它恢复焦点。 */
    private var selectedRowKey: String? = null

    /** 当前焦点项的标识（频道 id / 设置动作），用于恢复行内位置。 */
    private var selectedItemKey: String? = null

    /** 本轮列表里所有承载「刷新」动作卡片的分组，用于刷新期间更新副标题。 */
    private val refreshActionAdapters = mutableListOf<ArrayObjectAdapter>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUi()
        app.refreshCatalogFromRepository()
        loadRows(preserveFocus = false)
        setupEventListeners()
        loadMgtvChannelsIfNeeded()

        // 与手机端对齐：已配置订阅源时，首次进入静默刷新一次
        if (savedInstanceState == null && !app.subscriptionRepository.getSubscriptionUrl().isNullOrBlank()) {
            refreshSubscription(silent = true)
        }
    }

    private fun setupUi() {
        title = getString(R.string.browse_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand_primary)
    }

    private fun loadRows(preserveFocus: Boolean) {
        val previousSelectedRow = if (preserveFocus) selectedPosition else -1
        val previousRowKey = if (preserveFocus) selectedRowKey else null
        val previousItemKey = if (preserveFocus) selectedItemKey else null
        refreshActionAdapters.clear()

        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val baseCategories = app.catalogStore.getAllCategories().filter { it.channels.isNotEmpty() }
        val favoriteIds = context?.let { MobileUiPreferences.getFavoriteChannelIds(it) }.orEmpty()
        val categories = MobileChannelList.prependFavoritesCategory(baseCategories, favoriteIds)

        if (categories.isEmpty()) {
            val emptyAdapter = ArrayObjectAdapter(TvActionCardPresenter()).apply {
                val idleSubtitle = getString(R.string.tv_empty_channels_desc)
                add(
                    TvActionItem(
                        action = TvAction.REFRESH,
                        title = getString(R.string.tv_empty_channels),
                        subtitle = if (isRefreshing) getString(R.string.tv_action_refreshing) else idleSubtitle,
                        idleSubtitle = idleSubtitle,
                    ),
                )
            }
            refreshActionAdapters += emptyAdapter
            rowsAdapter.add(ListRow(HeaderItem(getString(R.string.browse_title)), emptyAdapter))
        } else {
            categories.forEach { category ->
                val channelAdapter = ArrayObjectAdapter(ChannelCardPresenter(category.name)).apply {
                    addAll(0, category.channels)
                }
                rowsAdapter.add(ListRow(HeaderItem(category.name), channelAdapter))
            }
        }

        // TV 端遥控器可达的订阅源与刷新入口（纯 D-Pad + OK，无需触屏）
        val settingsAdapter = ArrayObjectAdapter(TvActionCardPresenter()).apply {
            add(
                TvActionItem(
                    action = TvAction.REFRESH,
                    title = getString(R.string.tv_action_refresh),
                    subtitle = refreshActionSubtitle(),
                    idleSubtitle = getString(R.string.tv_action_refresh_desc),
                ),
            )
            add(
                TvActionItem(
                    action = TvAction.SUBSCRIPTION,
                    title = getString(R.string.tv_action_subscription),
                    subtitle = getString(R.string.tv_action_subscription_desc),
                ),
            )
        }
        refreshActionAdapters += settingsAdapter
        rowsAdapter.add(ListRow(HeaderItem(getString(R.string.tv_row_settings)), settingsAdapter))

        adapter = rowsAdapter

        // 遵循 TV-INTERACTION.md §2.2：先按行标识恢复，再按行号兜底，最后才落回首行
        restoreFocus(
            previousRowKey = previousRowKey,
            previousItemKey = previousItemKey,
            fallbackRow = previousSelectedRow,
            rowCount = rowsAdapter.size(),
        )
    }

    /** 刷新卡片副标题：刷新中给出可见的加载态文案，结束后恢复说明文字。 */
    private fun refreshActionSubtitle(): String = getString(
        if (isRefreshing) R.string.tv_action_refreshing else R.string.tv_action_refresh_desc,
    )

    private fun updateRefreshActionCards() {
        val busySubtitle = getString(R.string.tv_action_refreshing)
        refreshActionAdapters.forEach { rowAdapter ->
            for (index in 0 until rowAdapter.size()) {
                val item = rowAdapter.get(index) as? TvActionItem ?: continue
                if (item.action != TvAction.REFRESH) continue
                val target = if (isRefreshing) busySubtitle else item.idleSubtitle
                if (item.subtitle != target) {
                    rowAdapter.replace(index, item.copy(subtitle = target))
                }
            }
        }
    }

    /**
     * 刷新 / 目录变化后恢复焦点。
     *
     * 优先按**行标识**（分类名 / 「设置与订阅」）找回原来的行，因为分类增删会让行号整体偏移；
     * 标识找不到时退回原来的行号（有界），再不行就交给 Leanback 默认落回首行。
     * 行标识恢复成功且行内条目仍在时，再把行内焦点恢复到原来的频道/动作卡片上。
     */
    private fun restoreFocus(
        previousRowKey: String?,
        previousItemKey: String?,
        fallbackRow: Int,
        rowCount: Int,
    ) {
        if (rowCount <= 0) return
        val rowKeys = (0 until rowCount).map { rowHeaderName(it) }
        val target = FocusRestorePolicy.resolveRowIndex(rowKeys, previousRowKey, fallbackRow) ?: return
        selectedPosition = target
        val restoredByKey = previousRowKey != null && rowKeys.getOrNull(target) == previousRowKey
        if (restoredByKey && previousItemKey != null) {
            restoreChildSelection(target, previousItemKey)
        }
    }

    private fun rowHeaderName(index: Int): String? =
        ((adapter as? ArrayObjectAdapter)?.get(index) as? ListRow)
            ?.headerItem
            ?.name
            ?.toString()

    private fun restoreChildSelection(rowIndex: Int, itemKey: String) {
        val rowsAdapter = adapter as? ArrayObjectAdapter ?: return
        val row = rowsAdapter.get(rowIndex) as? ListRow ?: return
        val rowAdapter = row.adapter as? ArrayObjectAdapter ?: return
        val itemIndex = (0 until rowAdapter.size()).firstOrNull { position ->
            itemKeyFor(rowAdapter.get(position)) == itemKey
        } ?: return
        if (!isAdded || view == null) return
        val rowsFragment = getRowsSupportFragment()
        rowsFragment.view?.post {
            if (!isAdded) return@post
            val currentRowCount = adapter?.size() ?: 0
            if (rowIndex >= currentRowCount) return@post
            val holder = rowsFragment.getRowViewHolder(rowIndex) as? ListRowPresenter.ViewHolder
                ?: return@post
            val gridItemCount = holder.gridView.adapter?.itemCount ?: 0
            if (itemIndex in 0 until gridItemCount) {
                holder.gridView.setSelectedPosition(itemIndex)
            }
        }
    }

    private fun itemKeyFor(item: Any?): String? = when (item) {
        is Channel -> "channel:${item.id}"
        is TvActionItem -> "action:${item.action.name}"
        else -> null
    }

    private fun setupEventListeners() {
        // 记录焦点所在的行标识与行内条目标识，供刷新后按标识恢复（TV-INTERACTION.md §2.2）
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, row ->
            val headerName = (row as? ListRow)?.headerItem?.name?.toString()
            if (headerName != null) {
                selectedRowKey = headerName
                selectedItemKey = itemKeyFor(item)
            }
        }
        onItemViewClickedListener =
            OnItemViewClickedListener { _: Presenter.ViewHolder?, item: Any?, _: RowPresenter.ViewHolder?, _: Row? ->
                when (item) {
                    is Channel -> handleChannelClick(item)
                    is TvActionItem -> handleActionClick(item.action)
                }
            }
    }

    private fun handleChannelClick(channel: Channel) {
        val isDynamicSource = channel.id.startsWith(MgtvStreamResolver.CHANNEL_ID_PREFIX)
        if (channel.streamUrl.isNotEmpty() || isDynamicSource) {
            startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
            })
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.channel_no_stream_url, channel.name),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun loadMgtvChannelsIfNeeded() {
        if (app.catalogStore.hasSupplementalCategories() || mgtvLoading) return
        mgtvLoading = true
        app.mgtvCatalogCoordinator.load { mgtvCategories ->
            mgtvLoading = false
            if (mgtvCategories.isNotEmpty()) {
                app.catalogStore.updateSupplementalCategories(mgtvCategories)
            }
            if (!isAdded || view == null) return@load
            if (mgtvCategories.isNotEmpty()) {
                loadRows(preserveFocus = true)
            }
        }
    }

    private fun handleActionClick(action: TvAction) {
        when (action) {
            TvAction.REFRESH -> refreshSubscription(silent = false)
            TvAction.SUBSCRIPTION -> showSubscriptionDialog()
        }
    }

    private fun refreshSubscription(silent: Boolean) {
        if (isRefreshing) {
            if (!silent && isAdded) {
                Toast.makeText(requireContext(), R.string.mobile_refresh_busy, Toast.LENGTH_SHORT).show()
            }
            return
        }
        isRefreshing = true
        // 刷新期间把「刷新订阅」卡片副标题切到“刷新中…”，大屏上无需触屏也能看到进度反馈
        updateRefreshActionCards()
        if (!silent && isAdded) {
            Toast.makeText(requireContext(), R.string.mobile_action_refreshing, Toast.LENGTH_SHORT).show()
        }
        app.subscriptionCoordinator.refresh { result ->
            isRefreshing = false
            app.refreshCatalogFromRepository()
            loadMgtvChannelsIfNeeded()
            if (!isAdded || view == null) return@refresh
            loadRows(preserveFocus = true)
            updateRefreshActionCards()
            if (!silent) showRefreshResult(result)
        }
    }

    private fun updateSubscriptionUrl(url: String?) {
        if (isRefreshing) {
            if (isAdded) {
                Toast.makeText(requireContext(), R.string.mobile_refresh_busy, Toast.LENGTH_SHORT).show()
            }
            return
        }
        isRefreshing = true
        updateRefreshActionCards()
        app.subscriptionCoordinator.updateUrl(url) { result ->
            isRefreshing = false
            app.refreshCatalogFromRepository()
            loadMgtvChannelsIfNeeded()
            if (!isAdded || view == null) return@updateUrl
            loadRows(preserveFocus = true)
            updateRefreshActionCards()
            showRefreshResult(result)
        }
    }

    private fun showSubscriptionDialog() {
        val ctx = context ?: return
        val density = resources.displayMetrics.density
        val pad = (20 * density).roundToInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val current = app.subscriptionRepository.getSubscriptionUrl()
        val status = TextView(ctx).apply {
            text = if (current.isNullOrBlank()) {
                getString(R.string.mobile_subscription_none)
            } else {
                getString(R.string.mobile_subscription_current, current)
            }
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.mobile_text_secondary))
        }
        container.addView(status)

        val input = EditText(ctx).apply {
            setText(current.orEmpty())
            hint = getString(R.string.mobile_subscription_hint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            // 大屏暗色主题下显式指定前景/提示色，避免输入框文字与提示语融入背景
            setTextColor(ContextCompat.getColor(ctx, R.color.mobile_text_primary))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.mobile_text_tertiary))
            textSize = 15f
            minHeight = (48 * density).roundToInt()
        }
        container.addView(input)

        val builder = AlertDialog.Builder(ctx)
            .setTitle(R.string.mobile_subscription_title)
            .setView(container)
            .setPositiveButton(R.string.mobile_subscription_save, null)
            .setNegativeButton(R.string.mobile_subscription_cancel, null)

        if (!current.isNullOrBlank()) {
            builder.setNeutralButton(R.string.mobile_subscription_reset) { _, _ ->
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.mobile_subscription_reset_confirm_title)
                    .setMessage(R.string.mobile_subscription_reset_confirm_message)
                    .setPositiveButton(R.string.mobile_subscription_reset_confirm_ok) { _, _ ->
                        updateSubscriptionUrl(null)
                    }
                    .setNegativeButton(R.string.mobile_subscription_cancel, null)
                    .show()
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

    private fun showRefreshResult(result: RefreshResult) {
        val totalAvailable = app.catalogStore.getAllCategories().sumOf { it.channels.size }
        val message = when (result) {
            is RefreshResult.BuiltinOnly -> getString(R.string.mobile_refresh_builtin, totalAvailable)
            is RefreshResult.RemoteLoaded -> getString(R.string.mobile_refresh_remote, totalAvailable)
            is RefreshResult.FallbackToCache ->
                getString(R.string.mobile_refresh_cache, result.channelCount, result.reason)
            is RefreshResult.FallbackToBuiltin ->
                getString(R.string.mobile_refresh_builtin_fallback, totalAvailable, result.reason)
        }
        val duration = if (result is RefreshResult.FallbackToCache || result is RefreshResult.FallbackToBuiltin) {
            Toast.LENGTH_LONG
        } else {
            Toast.LENGTH_SHORT
        }
        Toast.makeText(requireContext(), message, duration).show()
    }

    private enum class TvAction {
        REFRESH,
        SUBSCRIPTION,
    }

    private data class TvActionItem(
        val action: TvAction,
        val title: String,
        val subtitle: String,
        /** 非刷新态的默认副标题，用于刷新结束后恢复。 */
        val idleSubtitle: String = subtitle,
    )

    private class TvActionCardPresenter : Presenter() {

        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val density = parent.resources.displayMetrics.density
            val card = ImageCardView(parent.context).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setMainImageDimensions((240 * density).roundToInt(), (120 * density).roundToInt())
            }
            return ViewHolder(card)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val actionItem = item as TvActionItem
            val card = viewHolder.view as ImageCardView
            card.titleText = actionItem.title
            card.contentText = actionItem.subtitle
            card.mainImage = TvActionCardDrawable(
                action = actionItem.action,
                density = card.resources.displayMetrics.density,
            )
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {
            val card = viewHolder.view as ImageCardView
            card.badgeImage = null
            card.mainImage = null
        }
    }

    /**
     * TV 端设置操作卡片的自绘矢量图标 Drawable（零 Bitmap 分配，解绑即释放）。
     */
    private class TvActionCardDrawable(
        private val action: TvAction,
        private val density: Float,
    ) : Drawable() {

        private val accent = when (action) {
            TvAction.REFRESH -> 0xFF3B82F6.toInt()
            TvAction.SUBSCRIPTION -> 0xFF10B981.toInt()
        }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF151A23.toInt()
            style = Paint.Style.FILL
        }
        private val circleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent))
            style = Paint.Style.FILL
        }
        private val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(165, Color.red(accent), Color.green(accent), Color.blue(accent))
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 2.6f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.FILL
        }
        private val arcRect = RectF()
        private val arrowPath = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(b, bgPaint)

            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val badgeRadius = 28f * density
            canvas.drawCircle(cx, cy, badgeRadius, circleFillPaint)
            canvas.drawCircle(cx, cy, badgeRadius, circleStrokePaint)

            when (action) {
                TvAction.REFRESH -> drawRefreshIcon(canvas, cx, cy)
                TvAction.SUBSCRIPTION -> drawSubscriptionIcon(canvas, cx, cy)
            }
        }

        private fun drawRefreshIcon(canvas: Canvas, cx: Float, cy: Float) {
            val r = 12f * density
            arcRect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(arcRect, -55f, 285f, false, iconStrokePaint)

            // 右上方顺时针箭头
            val tipX = cx + r * 0.72f
            val tipY = cy - r * 0.78f
            val wing = 5f * density
            arrowPath.reset()
            arrowPath.moveTo(tipX + wing * 0.35f, tipY + wing * 0.25f)
            arrowPath.lineTo(tipX - wing * 0.9f, tipY + wing * 0.1f)
            arrowPath.lineTo(tipX + wing * 0.1f, tipY - wing * 0.9f)
            arrowPath.close()
            canvas.drawPath(arrowPath, iconFillPaint)
        }

        private fun drawSubscriptionIcon(canvas: Canvas, cx: Float, cy: Float) {
            val halfW = 12f * density
            val stepY = 7f * density
            // 三条紧凑列表线 + 左侧圆点，清晰表达 M3U 播放列表配置语义
            for (i in -1..1) {
                val y = cy + i * stepY
                canvas.drawCircle(cx - halfW + 1.5f * density, y, 2f * density, iconFillPaint)
                canvas.drawLine(cx - halfW + 7f * density, y, cx + halfW, y, iconStrokePaint)
            }
        }

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bgPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
