package com.livetv.android.ui

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
import android.view.ViewGroup
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
import com.livetv.android.domain.model.Channel
import com.livetv.android.ui.mobile.MobileChannelList
import com.livetv.android.ui.mobile.MobileUiPreferences
import com.livetv.android.util.FocusRestorePolicy
import com.livetv.android.util.TvChannelRouteAction
import com.livetv.android.util.TvChannelRoutePolicy
import kotlin.math.roundToInt

class MainBrowseFragment : BrowseSupportFragment() {

    private val app: LiveTvApplication
        get() = requireActivity().application as LiveTvApplication

    private var catalogLoading = false

    /** 当前焦点行的标识（分类名 / 「设置与订阅」行名），刷新后用它恢复焦点。 */
    private var selectedRowKey: String? = null

    /** 当前焦点项的标识（频道 id / 设置动作），用于恢复行内位置。 */
    private var selectedItemKey: String? = null

    /** 本轮列表里所有承载「刷新」动作卡片的分组，用于刷新期间更新副标题。 */
    private val refreshActionAdapters = mutableListOf<ArrayObjectAdapter>()
    private var lastKnownFavorites: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUi()
        loadRows(preserveFocus = false)
        setupEventListeners()
        if (app.catalogStore.isEmpty()) {
            reloadCatalogs(preserveFocus = false)
        }
    }

    override fun onResume() {
        super.onResume()
        val currentFavorites = context?.let { MobileUiPreferences.getFavoriteChannelIds(it) }.orEmpty()
        if (currentFavorites != lastKnownFavorites) {
            loadRows(preserveFocus = true)
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
        lastKnownFavorites = favoriteIds
        val categories = MobileChannelList.prependFavoritesCategory(baseCategories, favoriteIds)

        if (categories.isEmpty()) {
            val emptyAdapter = ArrayObjectAdapter(TvActionCardPresenter()).apply {
                val idleSubtitle = getString(R.string.tv_empty_channels_desc)
                add(
                    TvActionItem(
                        action = TvAction.REFRESH,
                        title = getString(R.string.tv_empty_channels),
                        subtitle = if (catalogLoading) getString(R.string.tv_action_refreshing) else idleSubtitle,
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

            // TV 端遥控器可达的「重新加载频道」入口（纯 D-Pad + OK，无需触屏）
            val settingsAdapter = ArrayObjectAdapter(TvActionCardPresenter()).apply {
                add(
                    TvActionItem(
                        action = TvAction.REFRESH,
                        title = getString(R.string.tv_action_refresh),
                        subtitle = refreshActionSubtitle(),
                        idleSubtitle = getString(R.string.tv_action_refresh_desc),
                    ),
                )
            }
            refreshActionAdapters += settingsAdapter
            rowsAdapter.add(ListRow(HeaderItem(getString(R.string.tv_row_settings)), settingsAdapter))
        }

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
        if (catalogLoading) R.string.tv_action_refreshing else R.string.tv_action_refresh_desc,
    )

    private fun updateRefreshActionCards() {
        val busySubtitle = getString(R.string.tv_action_refreshing)
        refreshActionAdapters.forEach { rowAdapter ->
            for (index in 0 until rowAdapter.size()) {
                val item = rowAdapter.get(index) as? TvActionItem ?: continue
                if (item.action != TvAction.REFRESH) continue
                val target = if (catalogLoading) busySubtitle else item.idleSubtitle
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
        when (TvChannelRoutePolicy.decide(channel)) {
            TvChannelRouteAction.UnsupportedCctv -> {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.tv_cctv_unsupported),
                    Toast.LENGTH_SHORT,
                ).show()
            }

            TvChannelRouteAction.PlayNative -> {
                startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                    putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                    putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
                })
            }

            TvChannelRouteAction.NoStreamUrl -> {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.channel_no_stream_url, channel.name),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /** 重新加载频道目录（CCTV + MGTV 分槽合并）；失败时保留旧列表与焦点。 */
    private fun reloadCatalogs(preserveFocus: Boolean) {
        if (catalogLoading) return
        catalogLoading = true
        updateRefreshActionCards()
        app.compositeCatalogCoordinator.refresh {
            catalogLoading = false
            if (!isAdded || view == null) return@refresh
            loadRows(preserveFocus = preserveFocus)
            updateRefreshActionCards()
        }
    }

    private fun handleActionClick(action: TvAction) {
        when (action) {
            TvAction.REFRESH -> reloadCatalogs(preserveFocus = true)
        }
    }

    private enum class TvAction {
        REFRESH,
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

        private val accent = 0xFF3B82F6.toInt()

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

            drawRefreshIcon(canvas, cx, cy)
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
