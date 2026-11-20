package com.livetv.android.ui

import android.app.AlertDialog
import android.content.Intent
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
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import com.livetv.android.LiveTvApplication
import com.livetv.android.R
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.RefreshResult
import com.livetv.android.player.RetryPolicy
import kotlin.math.roundToInt

class MainBrowseFragment : BrowseSupportFragment() {

    private val app: LiveTvApplication
        get() = requireActivity().application as LiveTvApplication

    private var isRefreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUi()
        loadRows(preserveFocus = false)
        setupEventListeners()

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
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val categories = app.channelRepository.getCategories().filter { it.channels.isNotEmpty() }

        if (categories.isEmpty()) {
            val emptyAdapter = ArrayObjectAdapter(TvActionCardPresenter()).apply {
                add(
                    TvActionItem(
                        action = TvAction.REFRESH,
                        title = getString(R.string.tv_empty_channels),
                        subtitle = getString(R.string.tv_empty_channels_desc),
                    ),
                )
            }
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
                    subtitle = getString(R.string.tv_action_refresh_desc),
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
        rowsAdapter.add(ListRow(HeaderItem(getString(R.string.tv_row_settings)), settingsAdapter))

        adapter = rowsAdapter

        // 遵循 TV-INTERACTION.md §2.2：刷新后恢复行焦点，避免焦点丢失
        if (previousSelectedRow >= 0 && rowsAdapter.size() > 0) {
            selectedPosition = previousSelectedRow.coerceAtMost(rowsAdapter.size() - 1)
        }
    }

    private fun setupEventListeners() {
        onItemViewClickedListener =
            OnItemViewClickedListener { _: Presenter.ViewHolder?, item: Any?, _: RowPresenter.ViewHolder?, _: Row? ->
                when (item) {
                    is Channel -> handleChannelClick(item)
                    is TvActionItem -> handleActionClick(item.action)
                }
            }
    }

    private fun handleChannelClick(channel: Channel) {
        if (channel.streamUrl.isNotEmpty()) {
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
        app.subscriptionCoordinator.refresh { result ->
            isRefreshing = false
            if (!isAdded || view == null) return@refresh
            loadRows(preserveFocus = true)
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
        app.subscriptionCoordinator.updateUrl(url) { result ->
            isRefreshing = false
            if (!isAdded || view == null) return@updateUrl
            loadRows(preserveFocus = true)
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
        }
        container.addView(status)

        val input = EditText(ctx).apply {
            setText(current.orEmpty())
            hint = getString(R.string.mobile_subscription_hint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
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
        val message = when (result) {
            is RefreshResult.BuiltinOnly -> getString(R.string.mobile_refresh_builtin, result.channelCount)
            is RefreshResult.RemoteLoaded -> getString(R.string.mobile_refresh_remote, result.channelCount)
            is RefreshResult.FallbackToCache ->
                getString(R.string.mobile_refresh_cache, result.channelCount, result.reason)
            is RefreshResult.FallbackToBuiltin ->
                getString(R.string.mobile_refresh_builtin_fallback, result.channelCount, result.reason)
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
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {
            val card = viewHolder.view as ImageCardView
            card.badgeImage = null
            card.mainImage = null
        }
    }
}
