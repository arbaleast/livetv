package com.livetv.android.ui.mobile

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.livetv.android.R
import com.livetv.android.databinding.ViewMobilePlayerChromeBinding
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory

/**
 * 手机端共享播放器 UI 外框控制器（MGTV 原生页与 CCTV WebView 页共用）。
 *
 * 严格遵循性能红线：
 * - 所有淡出控件（顶栏、底栏、HUD、锁屏按钮、抽屉遮罩/面板）在 `withEndAction` 中立即置 `View.GONE`，
 *   绝不保留 `alpha = 0f` 的可见视图叠加在 WebView / PlayerView 之上；
 * - LIVE 徽标与抽屉「播放中」保持 100% 静态矢量样式，零持续动画。
 */
internal class MobilePlayerChromeController(
    private val binding: ViewMobilePlayerChromeBinding,
    private val capabilities: PlayerChromeCapabilities,
    private val callbacks: Callbacks,
    initialPlaying: Boolean = true,
) {

    interface Callbacks {
        fun onBack()
        fun onToggleFavorite()
        fun onRotate()
        fun onChannelPicked(channel: Channel)
        fun onErrorRetry()
        fun onLockStateChanged(locked: Boolean) {}
        fun onPrevChannel() {}
        fun onNextChannel() {}
        fun onTogglePlayPause() {}
        fun onCycleAspect() {}
        fun onToggleWebControls() {}
        fun onReload() {}
        fun onDrawerVisibilityChanged(open: Boolean) {}
    }

    enum class HudIcon {
        BRIGHTNESS,
        VOLUME,
    }

    private val handler = Handler(Looper.getMainLooper())
    private val stateMachine = MobilePlayerChromeStateMachine(capabilities, initialPlaying)

    private val filterAdapter = ChannelDrawerFilterAdapter { categoryId ->
        selectedCategoryId = categoryId
        renderDrawerData(scrollToCurrent = true)
    }
    private val drawerAdapter = ChannelDrawerAdapter { channel ->
        callbacks.onChannelPicked(channel)
    }

    private var categories: List<ChannelCategory> = emptyList()
    private var currentChannelId: String? = null
    private var selectedCategoryId: String? = null
    private var currentDrawerRows: List<ChannelDrawerRow> = emptyList()

    // 记录基础边距与 padding，确保 displayCutout 回调幂等不叠加
    private val topBaseLeft = binding.chromeTopBar.paddingLeft
    private val topBaseTop = binding.chromeTopBar.paddingTop
    private val topBaseRight = binding.chromeTopBar.paddingRight
    private val topBaseBottom = binding.chromeTopBar.paddingBottom

    private val bottomBaseLeft = binding.chromeBottomBar.paddingLeft
    private val bottomBaseTop = binding.chromeBottomBar.paddingTop
    private val bottomBaseRight = binding.chromeBottomBar.paddingRight
    private val bottomBaseBottom = binding.chromeBottomBar.paddingBottom

    private val drawerBaseLeft = binding.chromeDrawerContent.paddingLeft
    private val drawerBaseTop = binding.chromeDrawerContent.paddingTop
    private val drawerBaseRight = binding.chromeDrawerContent.paddingRight
    private val drawerBaseBottom = binding.chromeDrawerContent.paddingBottom

    private val lockBaseStartMargin =
        (binding.chromeLock.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0

    private val autoHideControlsRunnable = Runnable { hideControls(force = false) }
    private val autoHideLockRunnable = Runnable {
        if (stateMachine.state.isScreenLocked) {
            stateMachine.hideLockButton()
            fadeOutToGone(binding.chromeLock, MobilePlayerChromePolicy.CHROME_FADE_MS)
        }
    }
    private val autoHideHudRunnable = Runnable {
        fadeOutToGone(binding.chromeHud, MobilePlayerChromePolicy.HUD_FADE_MS)
    }

    val rootView: View get() = binding.chromeRoot
    val isScreenLocked: Boolean get() = stateMachine.state.isScreenLocked
    val isDrawerOpen: Boolean get() = stateMachine.state.isDrawerOpen
    val isControlsVisible: Boolean get() = stateMachine.state.topBarVisible

    init {
        applyCapabilitySlots()
        setupDrawerLists()
        bindClickListeners()
    }

    private fun applyCapabilitySlots() {
        val transportVis = if (capabilities.showTransportControls) View.VISIBLE else View.GONE
        binding.chromePrev.visibility = transportVis
        binding.chromePlayPause.visibility = transportVis
        binding.chromeNext.visibility = transportVis

        binding.chromeAspect.visibility = if (capabilities.showAspectRatio) View.VISIBLE else View.GONE
        binding.chromeWebControls.visibility = if (capabilities.showWebControlToggle) View.VISIBLE else View.GONE
        binding.chromeReload.visibility = if (capabilities.showReload) View.VISIBLE else View.GONE
    }

    private fun setupDrawerLists() {
        val ctx = binding.chromeRoot.context
        binding.chromeDrawerFilters.layoutManager =
            LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        binding.chromeDrawerFilters.adapter = filterAdapter
        binding.chromeDrawerFilters.itemAnimator = null

        binding.chromeDrawerList.layoutManager = LinearLayoutManager(ctx)
        binding.chromeDrawerList.adapter = drawerAdapter
        binding.chromeDrawerList.itemAnimator = null
    }

    private fun bindClickListeners() {
        binding.chromeBack.setOnClickListener { callbacks.onBack() }
        binding.chromeFavorite.setOnClickListener {
            callbacks.onToggleFavorite()
            rescheduleAutoHideIfNeeded()
        }
        binding.chromeChannels.setOnClickListener { openDrawer() }
        binding.chromeWebControls.setOnClickListener { callbacks.onToggleWebControls() }

        binding.chromePrev.setOnClickListener {
            callbacks.onPrevChannel()
            rescheduleAutoHideIfNeeded()
        }
        binding.chromePlayPause.setOnClickListener {
            callbacks.onTogglePlayPause()
            rescheduleAutoHideIfNeeded()
        }
        binding.chromeNext.setOnClickListener {
            callbacks.onNextChannel()
            rescheduleAutoHideIfNeeded()
        }
        binding.chromeReload.setOnClickListener {
            callbacks.onReload()
            rescheduleAutoHideIfNeeded()
        }
        binding.chromeAspect.setOnClickListener {
            callbacks.onCycleAspect()
            rescheduleAutoHideIfNeeded()
        }
        binding.chromeRotate.setOnClickListener {
            callbacks.onRotate()
            rescheduleAutoHideIfNeeded()
        }
        binding.chromeLock.setOnClickListener { toggleScreenLock() }

        binding.chromeErrorRetry.setOnClickListener { callbacks.onErrorRetry() }
        binding.chromeErrorChannels.setOnClickListener { openDrawer() }
        binding.chromeDrawerScrim.setOnClickListener { closeDrawer() }
    }

    fun applyCutoutInsets(windowInsets: WindowInsetsCompat) {
        val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val density = binding.chromeRoot.resources.displayMetrics.density
        val stableDrawerBottom = (DRAWER_STABLE_BOTTOM_MARGIN_DP * density).toInt()

        binding.chromeTopBar.setPadding(
            topBaseLeft + cutout.left,
            topBaseTop + cutout.top,
            topBaseRight + cutout.right,
            topBaseBottom,
        )
        val bottomHorizontalSafe = maxOf(cutout.left, cutout.right)
        binding.chromeBottomBar.setPadding(
            bottomBaseLeft + bottomHorizontalSafe,
            bottomBaseTop,
            bottomBaseRight + bottomHorizontalSafe,
            bottomBaseBottom + cutout.bottom,
        )
        binding.chromeDrawerContent.setPadding(
            drawerBaseLeft,
            drawerBaseTop + cutout.top,
            drawerBaseRight + cutout.right,
            drawerBaseBottom + maxOf(cutout.bottom, stableDrawerBottom),
        )
        (binding.chromeLock.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val targetStart = lockBaseStartMargin + cutout.left
            if (lp.marginStart != targetStart) {
                lp.marginStart = targetStart
                binding.chromeLock.layoutParams = lp
            }
        }
    }

    // ---------------- 状态更新 ----------------

    fun setTitle(title: String) {
        binding.chromeTitle.text = title
    }

    fun setLiveBadgeVisible(visible: Boolean) {
        binding.chromeLiveBadge.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun updateFavorite(isFavorite: Boolean) {
        binding.chromeFavorite.isSelected = isFavorite
        binding.chromeFavorite.setImageResource(
            if (isFavorite) R.drawable.ic_player_favorite_filled else R.drawable.ic_player_favorite_outline,
        )
        val tint = if (isFavorite) FAVORITE_ACTIVE_TINT else WHITE_TINT
        binding.chromeFavorite.imageTintList = ColorStateList.valueOf(tint)
    }

    fun updatePlayPause(isPaused: Boolean) {
        binding.chromePlayPause.setImageResource(
            if (isPaused) R.drawable.ic_player_play else R.drawable.ic_player_pause,
        )
    }

    fun updateAspectLabel(label: String) {
        binding.chromeAspectLabel.text = label
    }

    fun setPlaying(playing: Boolean) {
        stateMachine.setPlaying(playing)
        rescheduleAutoHideIfNeeded()
    }

    fun setWebNativeControlsVisible(nativeVisible: Boolean) {
        val next = stateMachine.setWebNativeControlsVisible(nativeVisible)
        binding.chromeWebControls.isSelected = next.isWebNativeControlsVisible
        binding.chromeWebControls.setText(
            if (next.isWebNativeControlsVisible) {
                R.string.cctv_web_control_pure
            } else {
                R.string.cctv_web_control_native
            },
        )
        syncBarVisibility(animate = true)
        rescheduleAutoHideIfNeeded()
    }

    fun setCatalog(categories: List<ChannelCategory>, currentChannelId: String?) {
        this.categories = categories
        this.currentChannelId = currentChannelId
        if (isDrawerOpen) {
            renderDrawerData(scrollToCurrent = false)
        }
    }

    // ---------------- 顶/底栏与锁屏 ----------------

    fun showControls(animate: Boolean = true) {
        if (isScreenLocked || isDrawerOpen) return
        stateMachine.showControls()
        syncBarVisibility(animate = animate)
        rescheduleAutoHideIfNeeded()
    }

    fun hideControls(force: Boolean = false) {
        handler.removeCallbacks(autoHideControlsRunnable)
        val prevTop = stateMachine.state.topBarVisible
        val prevBottom = stateMachine.state.bottomBarVisible
        val prevLock = stateMachine.state.lockButtonVisible
        val next = stateMachine.hideControls(force = force)
        if (prevTop && !next.topBarVisible) {
            fadeOutToGone(binding.chromeTopBar, MobilePlayerChromePolicy.CHROME_FADE_MS)
        }
        if (prevBottom && !next.bottomBarVisible) {
            fadeOutToGone(binding.chromeBottomBar, MobilePlayerChromePolicy.CHROME_FADE_MS)
        }
        if (prevLock && !next.lockButtonVisible) {
            fadeOutToGone(binding.chromeLock, MobilePlayerChromePolicy.CHROME_FADE_MS)
        }
    }

    fun toggleControls() {
        if (isScreenLocked) {
            onSingleTapWhileLocked()
            return
        }
        if (stateMachine.state.topBarVisible) {
            hideControls(force = true)
        } else {
            showControls(animate = true)
        }
    }

    fun toggleScreenLock() {
        handler.removeCallbacks(autoHideControlsRunnable)
        handler.removeCallbacks(autoHideLockRunnable)
        val next = stateMachine.toggleScreenLock()
        updateLockIcon(next.isScreenLocked)
        callbacks.onLockStateChanged(next.isScreenLocked)

        if (next.isScreenLocked) {
            fadeOutToGone(binding.chromeTopBar, MobilePlayerChromePolicy.CHROME_FADE_MS)
            fadeOutToGone(binding.chromeBottomBar, MobilePlayerChromePolicy.CHROME_FADE_MS)
            fadeInView(binding.chromeLock, MobilePlayerChromePolicy.CHROME_FADE_MS)
            handler.postDelayed(autoHideLockRunnable, MobilePlayerChromePolicy.LOCK_BUTTON_AUTO_HIDE_MS)
            showHudText(binding.chromeRoot.context.getString(R.string.player_locked_hint))
        } else {
            syncBarVisibility(animate = true)
            rescheduleAutoHideIfNeeded()
        }
    }

    fun onSingleTapWhileLocked() {
        if (!isScreenLocked) return
        handler.removeCallbacks(autoHideLockRunnable)
        val next = stateMachine.onSingleTapWhileLocked()
        if (next.lockButtonVisible) {
            fadeInView(binding.chromeLock, MobilePlayerChromePolicy.CHROME_FADE_MS)
            handler.postDelayed(autoHideLockRunnable, MobilePlayerChromePolicy.LOCK_BUTTON_AUTO_HIDE_MS)
        } else {
            fadeOutToGone(binding.chromeLock, MobilePlayerChromePolicy.CHROME_FADE_MS)
        }
    }

    fun handleBackPressed(): ChromeBackAction {
        val action = stateMachine.onBackPressed()
        when (action) {
            ChromeBackAction.BLOCKED_BY_LOCK -> {
                handler.removeCallbacks(autoHideLockRunnable)
                stateMachine.showLockButtonTemporarily()
                fadeInView(binding.chromeLock, MobilePlayerChromePolicy.CHROME_FADE_MS)
                handler.postDelayed(autoHideLockRunnable, MobilePlayerChromePolicy.LOCK_BUTTON_AUTO_HIDE_MS)
                showHudText(binding.chromeRoot.context.getString(R.string.player_locked_hint))
            }

            ChromeBackAction.CLOSE_DRAWER -> closeDrawer()
            ChromeBackAction.PASS_THROUGH -> Unit
        }
        return action
    }

    private fun updateLockIcon(locked: Boolean) {
        binding.chromeLock.isSelected = locked
        binding.chromeLock.setImageResource(
            if (locked) R.drawable.ic_player_unlock else R.drawable.ic_player_lock,
        )
    }

    private fun syncBarVisibility(animate: Boolean) {
        val st = stateMachine.state
        setViewVisible(binding.chromeTopBar, st.topBarVisible, animate)
        setViewVisible(binding.chromeBottomBar, st.bottomBarVisible, animate)
        setViewVisible(binding.chromeLock, st.lockButtonVisible, animate)
    }

    private fun rescheduleAutoHideIfNeeded() {
        handler.removeCallbacks(autoHideControlsRunnable)
        if (stateMachine.shouldAutoHideControls()) {
            handler.postDelayed(autoHideControlsRunnable, MobilePlayerChromePolicy.CHROME_AUTO_HIDE_MS)
        }
    }

    // ---------------- 居中胶囊 HUD ----------------

    fun showHudMeter(icon: HudIcon, percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        binding.chromeHudText.visibility = View.GONE
        binding.chromeHudMeter.visibility = View.VISIBLE
        binding.chromeHudIcon.setImageResource(
            when (icon) {
                HudIcon.BRIGHTNESS -> R.drawable.ic_player_brightness
                HudIcon.VOLUME -> R.drawable.ic_player_volume
            },
        )
        binding.chromeHudProgress.progress = clamped
        binding.chromeHudPercent.text = "$clamped%"

        binding.chromeHud.animate().cancel()
        binding.chromeHud.alpha = 1f
        binding.chromeHud.visibility = View.VISIBLE
        handler.removeCallbacks(autoHideHudRunnable)
    }

    fun hideHudDelayed() {
        handler.removeCallbacks(autoHideHudRunnable)
        handler.postDelayed(autoHideHudRunnable, MobilePlayerChromePolicy.HUD_AUTO_HIDE_DELAY_MS)
    }

    fun showHudText(text: String) {
        binding.chromeHudMeter.visibility = View.GONE
        binding.chromeHudText.visibility = View.VISIBLE
        binding.chromeHudText.text = text

        fadeInView(binding.chromeHud, MobilePlayerChromePolicy.CHROME_FADE_MS)
        hideHudDelayed()
    }

    // ---------------- 统一错误卡片 ----------------

    fun showError(message: String, showRetry: Boolean = true) {
        stateMachine.setErrorVisible(true)
        binding.chromeErrorMessage.text = message
        binding.chromeErrorRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
        binding.chromeErrorCard.visibility = View.VISIBLE
        if (!isScreenLocked && !isDrawerOpen) {
            showControls(animate = true)
        }
        handler.removeCallbacks(autoHideControlsRunnable)
    }

    fun hideError() {
        stateMachine.setErrorVisible(false)
        binding.chromeErrorCard.visibility = View.GONE
        rescheduleAutoHideIfNeeded()
    }

    // ---------------- 选台抽屉（含分组筛选） ----------------

    fun openDrawer() {
        if (isScreenLocked) return
        handler.removeCallbacks(autoHideControlsRunnable)
        stateMachine.setDrawerOpen(true)
        callbacks.onDrawerVisibilityChanged(true)

        fadeOutToGone(binding.chromeTopBar, MobilePlayerChromePolicy.CHROME_FADE_MS)
        fadeOutToGone(binding.chromeBottomBar, MobilePlayerChromePolicy.CHROME_FADE_MS)
        fadeOutToGone(binding.chromeLock, MobilePlayerChromePolicy.CHROME_FADE_MS)

        renderDrawerData(scrollToCurrent = true)

        binding.chromeDrawerScrim.animate().cancel()
        binding.chromeDrawerScrim.alpha = 0f
        binding.chromeDrawerScrim.visibility = View.VISIBLE
        binding.chromeDrawerScrim.animate()
            .alpha(1f)
            .setDuration(MobilePlayerChromePolicy.DRAWER_ANIM_MS)
            .start()

        binding.chromeDrawerPanel.animate().cancel()
        val slideOffset = drawerSlideOffsetPx()
        binding.chromeDrawerPanel.translationX = slideOffset
        binding.chromeDrawerPanel.visibility = View.VISIBLE
        binding.chromeDrawerPanel.animate()
            .translationX(0f)
            .setDuration(MobilePlayerChromePolicy.DRAWER_ANIM_MS)
            .start()
    }

    fun closeDrawer(restoreControls: Boolean = false) {
        if (!stateMachine.state.isDrawerOpen) return
        stateMachine.setDrawerOpen(false)
        callbacks.onDrawerVisibilityChanged(false)

        binding.chromeDrawerScrim.animate().cancel()
        binding.chromeDrawerScrim.animate()
            .alpha(0f)
            .setDuration(MobilePlayerChromePolicy.DRAWER_ANIM_MS)
            .withEndAction {
                binding.chromeDrawerScrim.visibility = View.GONE
                binding.chromeDrawerScrim.alpha = 1f
            }
            .start()

        binding.chromeDrawerPanel.animate().cancel()
        val slideOffset = drawerSlideOffsetPx()
        binding.chromeDrawerPanel.animate()
            .translationX(slideOffset)
            .setDuration(MobilePlayerChromePolicy.DRAWER_ANIM_MS)
            .withEndAction {
                binding.chromeDrawerPanel.visibility = View.GONE
                binding.chromeDrawerPanel.translationX = 0f
            }
            .start()

        if (restoreControls || stateMachine.state.isWebNativeControlsVisible || !stateMachine.state.isPlaying) {
            showControls(animate = true)
        }
    }

    fun scrollDrawerToCurrentChannel() {
        if (!isDrawerOpen) return
        val currentIndex = ChannelDrawerModel.currentRowIndex(currentDrawerRows)
        val targetIndex = if (currentIndex >= 0) {
            currentIndex
        } else {
            currentDrawerRows.indexOfFirst { it is ChannelDrawerRow.ChannelItem }
        }
        if (targetIndex >= 0) {
            binding.chromeDrawerList.post {
                val lm = binding.chromeDrawerList.layoutManager as? LinearLayoutManager
                val offset = binding.chromeDrawerList.height / 3
                lm?.scrollToPositionWithOffset(targetIndex, offset)
                    ?: binding.chromeDrawerList.scrollToPosition(targetIndex)
            }
        }
    }

    private fun renderDrawerData(scrollToCurrent: Boolean) {
        val allLabel = binding.chromeRoot.context.getString(R.string.player_drawer_all)
        val chips = ChannelDrawerModel.buildFilterChips(
            categories = categories,
            selectedCategoryId = selectedCategoryId,
            allLabel = allLabel,
        )
        // 若此前选中的分类已不存在，同步复位 selectedCategoryId
        selectedCategoryId = chips.firstOrNull { it.selected }?.categoryId
        filterAdapter.updateChips(chips)
        binding.chromeDrawerFilters.visibility = if (chips.size > 1) View.VISIBLE else View.GONE

        val rows = ChannelDrawerModel.buildFilteredRows(
            categories = categories,
            currentChannelId = currentChannelId,
            selectedCategoryId = selectedCategoryId,
        )
        currentDrawerRows = rows
        binding.chromeDrawerEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.chromeDrawerList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        drawerAdapter.updateRows(rows)

        if (scrollToCurrent) {
            scrollDrawerToCurrentChannel()
        }
    }

    private fun drawerSlideOffsetPx(): Float {
        val width = binding.chromeDrawerPanel.width
        return if (width > 0) {
            width.toFloat()
        } else {
            DRAWER_WIDTH_DP * binding.chromeRoot.resources.displayMetrics.density
        }
    }

    // ---------------- 动效（淡出结束必置 View.GONE） ----------------

    private fun setViewVisible(view: View, visible: Boolean, animate: Boolean) {
        if (visible) {
            if (animate) {
                fadeInView(view, MobilePlayerChromePolicy.CHROME_FADE_MS)
            } else {
                view.animate().cancel()
                view.alpha = 1f
                view.visibility = View.VISIBLE
            }
        } else {
            if (animate) {
                fadeOutToGone(view, MobilePlayerChromePolicy.CHROME_FADE_MS)
            } else {
                view.animate().cancel()
                view.alpha = 1f
                view.visibility = View.GONE
            }
        }
    }

    private fun fadeInView(view: View, durationMs: Long) {
        view.animate().cancel()
        if (view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        view.animate()
            .alpha(1f)
            .setDuration(durationMs)
            .withEndAction(null)
            .start()
    }

    private fun fadeOutToGone(view: View, durationMs: Long) {
        if (view.visibility != View.VISIBLE) return
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(durationMs)
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
            }
            .start()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        binding.chromeTopBar.animate().cancel()
        binding.chromeBottomBar.animate().cancel()
        binding.chromeLock.animate().cancel()
        binding.chromeHud.animate().cancel()
        binding.chromeDrawerScrim.animate().cancel()
        binding.chromeDrawerPanel.animate().cancel()
    }

    companion object {
        private const val DRAWER_WIDTH_DP = 280
        private const val DRAWER_STABLE_BOTTOM_MARGIN_DP = 8
        private const val FAVORITE_ACTIVE_TINT = 0xFFFBBF24.toInt()
        private const val WHITE_TINT = 0xFFFFFFFF.toInt()
    }
}
