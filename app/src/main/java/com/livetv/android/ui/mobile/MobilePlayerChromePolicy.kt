package com.livetv.android.ui.mobile

/**
 * 手机端共享播放器外框的能力插槽配置（纯 Kotlin，可纯 JVM 单测）。
 *
 * 通过最小化的功能开关区分 MGTV 原生引擎与 CCTV WebView 引擎，
 * 其余顶栏/锁屏/居中 HUD/抽屉/错误卡片全部共用同一套视觉与状态机。
 */
data class PlayerChromeCapabilities(
    /** 是否显示底栏播控组（上一台 / 播放暂停 / 下一台）。 */
    val showTransportControls: Boolean,
    /** 是否显示画面比例切换按钮（FIT / FILL / ZOOM）。 */
    val showAspectRatio: Boolean,
    /** 是否在顶栏显示 CCTV 专属「原控 / 纯画」切换胶囊。 */
    val showWebControlToggle: Boolean,
    /** 是否在底栏显示刷新重载按钮（CCTV 网页重载）。 */
    val showReload: Boolean,
) {
    companion object {
        val MGTV = PlayerChromeCapabilities(
            showTransportControls = true,
            showAspectRatio = true,
            showWebControlToggle = false,
            showReload = false,
        )

        val CCTV = PlayerChromeCapabilities(
            showTransportControls = false,
            showAspectRatio = false,
            showWebControlToggle = true,
            showReload = true,
        )
    }
}

/** 手机端共享外框统一时序常量。 */
object MobilePlayerChromePolicy {
    /** 顶/底栏自动隐藏延迟（ms）。 */
    const val CHROME_AUTO_HIDE_MS: Long = 3_500L

    /** 顶/底栏与锁屏按钮淡入淡出时长（ms）。 */
    const val CHROME_FADE_MS: Long = 150L

    /** 锁屏态下单击浮现解锁按钮的自动隐藏延迟（ms）。 */
    const val LOCK_BUTTON_AUTO_HIDE_MS: Long = 2_000L

    /** 居中胶囊 HUD 自动隐藏停留时长（ms）。 */
    const val HUD_AUTO_HIDE_DELAY_MS: Long = 800L

    /** 居中胶囊 HUD 淡出时长（ms）。 */
    const val HUD_FADE_MS: Long = 180L

    /** 右侧选台抽屉滑入滑出时长（ms）。 */
    const val DRAWER_ANIM_MS: Long = 220L
}

enum class ChromeBackAction {
    BLOCKED_BY_LOCK,
    CLOSE_DRAWER,
    PASS_THROUGH,
}

data class MobilePlayerChromeState(
    val topBarVisible: Boolean = false,
    val bottomBarVisible: Boolean = false,
    val lockButtonVisible: Boolean = false,
    val isScreenLocked: Boolean = false,
    val isDrawerOpen: Boolean = false,
    val isErrorVisible: Boolean = false,
    val isWebNativeControlsVisible: Boolean = false,
    val isPlaying: Boolean = true,
)

/**
 * 手机端播放器共享外框状态机（纯 Kotlin，无 Android 依赖）。
 */
class MobilePlayerChromeStateMachine(
    val capabilities: PlayerChromeCapabilities,
    initialPlaying: Boolean = true,
) {
    var state: MobilePlayerChromeState = MobilePlayerChromeState(isPlaying = initialPlaying)
        private set

    fun showControls(): MobilePlayerChromeState {
        if (state.isScreenLocked || state.isDrawerOpen) return state
        state = state.copy(
            topBarVisible = true,
            bottomBarVisible = canShowBottomBar(
                isScreenLocked = false,
                isDrawerOpen = false,
                isWebNativeControlsVisible = state.isWebNativeControlsVisible,
            ),
            lockButtonVisible = true,
        )
        return state
    }

    fun hideControls(force: Boolean = false): MobilePlayerChromeState {
        if (!force && !shouldAutoHideControls()) return state
        state = state.copy(
            topBarVisible = false,
            bottomBarVisible = false,
            lockButtonVisible = if (state.isScreenLocked) state.lockButtonVisible else false,
        )
        return state
    }

    fun toggleControls(): MobilePlayerChromeState =
        if (state.topBarVisible) hideControls(force = true) else showControls()

    fun toggleScreenLock(): MobilePlayerChromeState {
        val nextLocked = !state.isScreenLocked
        state = if (nextLocked) {
            state.copy(
                isScreenLocked = true,
                topBarVisible = false,
                bottomBarVisible = false,
                lockButtonVisible = true,
                isDrawerOpen = false,
            )
        } else {
            state.copy(
                isScreenLocked = false,
                topBarVisible = true,
                bottomBarVisible = canShowBottomBar(
                    isScreenLocked = false,
                    isDrawerOpen = state.isDrawerOpen,
                    isWebNativeControlsVisible = state.isWebNativeControlsVisible,
                ),
                lockButtonVisible = true,
            )
        }
        return state
    }

    fun onSingleTapWhileLocked(): MobilePlayerChromeState {
        if (!state.isScreenLocked) return state
        state = state.copy(lockButtonVisible = !state.lockButtonVisible)
        return state
    }

    fun showLockButtonTemporarily(): MobilePlayerChromeState {
        if (!state.isScreenLocked) return state
        state = state.copy(lockButtonVisible = true)
        return state
    }

    fun hideLockButton(): MobilePlayerChromeState {
        state = state.copy(lockButtonVisible = false)
        return state
    }

    fun setDrawerOpen(open: Boolean): MobilePlayerChromeState {
        state = if (open) {
            state.copy(
                isDrawerOpen = true,
                topBarVisible = false,
                bottomBarVisible = false,
                lockButtonVisible = false,
            )
        } else {
            state.copy(isDrawerOpen = false)
        }
        return state
    }

    fun setErrorVisible(visible: Boolean): MobilePlayerChromeState {
        state = state.copy(isErrorVisible = visible)
        return state
    }

    fun setPlaying(playing: Boolean): MobilePlayerChromeState {
        state = state.copy(isPlaying = playing)
        return state
    }

    fun setWebNativeControlsVisible(nativeVisible: Boolean): MobilePlayerChromeState {
        val effective = capabilities.showWebControlToggle && nativeVisible
        state = state.copy(
            isWebNativeControlsVisible = effective,
            topBarVisible = if (effective && !state.isScreenLocked && !state.isDrawerOpen) {
                true
            } else {
                state.topBarVisible
            },
            bottomBarVisible = if (state.topBarVisible || effective) {
                canShowBottomBar(
                    isScreenLocked = state.isScreenLocked,
                    isDrawerOpen = state.isDrawerOpen,
                    isWebNativeControlsVisible = effective,
                )
            } else {
                false
            },
        )
        return state
    }

    fun shouldAutoHideControls(): Boolean =
        state.topBarVisible &&
            !state.isScreenLocked &&
            !state.isDrawerOpen &&
            !state.isErrorVisible &&
            !state.isWebNativeControlsVisible &&
            state.isPlaying

    fun onBackPressed(): ChromeBackAction = when {
        state.isScreenLocked -> ChromeBackAction.BLOCKED_BY_LOCK
        state.isDrawerOpen -> ChromeBackAction.CLOSE_DRAWER
        else -> ChromeBackAction.PASS_THROUGH
    }

    private fun canShowBottomBar(
        isScreenLocked: Boolean,
        isDrawerOpen: Boolean,
        isWebNativeControlsVisible: Boolean,
    ): Boolean = !isScreenLocked && !isDrawerOpen && !isWebNativeControlsVisible
}
