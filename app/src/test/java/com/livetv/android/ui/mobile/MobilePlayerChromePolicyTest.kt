package com.livetv.android.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MobilePlayerChromeStateMachine] 与 [PlayerChromeCapabilities] 的纯 JVM 单元测试。
 */
class MobilePlayerChromePolicyTest {

    @Test
    fun `MGTV and CCTV capabilities expose exact distinct slots`() {
        val mgtv = PlayerChromeCapabilities.MGTV
        assertTrue(mgtv.showTransportControls)
        assertTrue(mgtv.showAspectRatio)
        assertFalse(mgtv.showWebControlToggle)
        assertFalse(mgtv.showReload)

        val cctv = PlayerChromeCapabilities.CCTV
        assertFalse(cctv.showTransportControls)
        assertFalse(cctv.showAspectRatio)
        assertTrue(cctv.showWebControlToggle)
        assertTrue(cctv.showReload)
    }

    @Test
    fun `timing constants match unified mobile chrome spec`() {
        assertEquals(3_500L, MobilePlayerChromePolicy.CHROME_AUTO_HIDE_MS)
        assertEquals(150L, MobilePlayerChromePolicy.CHROME_FADE_MS)
        assertEquals(2_000L, MobilePlayerChromePolicy.LOCK_BUTTON_AUTO_HIDE_MS)
        assertEquals(800L, MobilePlayerChromePolicy.HUD_AUTO_HIDE_DELAY_MS)
        assertEquals(180L, MobilePlayerChromePolicy.HUD_FADE_MS)
        assertEquals(220L, MobilePlayerChromePolicy.DRAWER_ANIM_MS)
    }

    @Test
    fun `locking screen hides controls shows temporary lock button and blocks back key`() {
        val sm = MobilePlayerChromeStateMachine(PlayerChromeCapabilities.MGTV)
        sm.showControls()
        assertTrue(sm.state.topBarVisible)
        assertTrue(sm.state.bottomBarVisible)
        assertTrue(sm.state.lockButtonVisible)
        assertTrue(sm.shouldAutoHideControls())

        val locked = sm.toggleScreenLock()
        assertTrue(locked.isScreenLocked)
        assertFalse(locked.topBarVisible)
        assertFalse(locked.bottomBarVisible)
        assertTrue(locked.lockButtonVisible)
        assertFalse(sm.shouldAutoHideControls())
        assertEquals(ChromeBackAction.BLOCKED_BY_LOCK, sm.onBackPressed())

        // 锁屏态下再次单击切换解锁按钮可见性
        val tappedHide = sm.onSingleTapWhileLocked()
        assertFalse(tappedHide.lockButtonVisible)
        val tappedShow = sm.onSingleTapWhileLocked()
        assertTrue(tappedShow.lockButtonVisible)

        // 解锁后恢复顶/底栏并允许自动隐藏
        val unlocked = sm.toggleScreenLock()
        assertFalse(unlocked.isScreenLocked)
        assertTrue(unlocked.topBarVisible)
        assertTrue(unlocked.bottomBarVisible)
        assertTrue(sm.shouldAutoHideControls())
    }

    @Test
    fun `CCTV web native controls mode hides bottom bar and keeps top bar persistent`() {
        val sm = MobilePlayerChromeStateMachine(PlayerChromeCapabilities.CCTV)
        sm.setPlaying(true)
        sm.showControls()
        assertTrue(sm.state.topBarVisible)
        assertTrue(sm.state.bottomBarVisible)
        assertTrue(sm.shouldAutoHideControls())

        val orig = sm.setWebNativeControlsVisible(true)
        assertTrue(orig.topBarVisible)
        assertFalse("原控模式下底栏必须自动隐藏，避免与网页控制栏重叠", orig.bottomBarVisible)
        assertFalse("原控模式下顶栏应保持常驻", sm.shouldAutoHideControls())

        val pure = sm.setWebNativeControlsVisible(false)
        assertTrue(pure.topBarVisible)
        assertTrue(pure.bottomBarVisible)
        assertTrue(sm.shouldAutoHideControls())
    }

    @Test
    fun `CCTV waiting state keeps top bar persistent until playback starts`() {
        val sm = MobilePlayerChromeStateMachine(PlayerChromeCapabilities.CCTV)
        sm.setPlaying(false)
        sm.showControls()
        assertTrue(sm.state.topBarVisible)
        assertFalse("待起播态不应自动隐藏顶栏", sm.shouldAutoHideControls())

        sm.setPlaying(true)
        assertTrue("进入播放态后应允许 3500ms 自动隐藏", sm.shouldAutoHideControls())
    }

    @Test
    fun `opening drawer hides bars and back key closes drawer first`() {
        val sm = MobilePlayerChromeStateMachine(PlayerChromeCapabilities.MGTV)
        sm.showControls()
        sm.setDrawerOpen(true)

        assertTrue(sm.state.isDrawerOpen)
        assertFalse(sm.state.topBarVisible)
        assertFalse(sm.state.bottomBarVisible)
        assertEquals(ChromeBackAction.CLOSE_DRAWER, sm.onBackPressed())

        sm.setDrawerOpen(false)
        assertEquals(ChromeBackAction.PASS_THROUGH, sm.onBackPressed())
    }

    @Test
    fun `error state prevents auto-hide until error clears`() {
        val sm = MobilePlayerChromeStateMachine(PlayerChromeCapabilities.MGTV)
        sm.setErrorVisible(true)
        sm.showControls()
        assertFalse(sm.shouldAutoHideControls())

        sm.setErrorVisible(false)
        assertTrue(sm.shouldAutoHideControls())
    }

    @Test
    fun `persisted CCTV web controls mode keeps top bar persistent and bottom bar hidden after playback starts`() {
        val sm = MobilePlayerChromeStateMachine(PlayerChromeCapabilities.CCTV, initialPlaying = false)
        sm.setWebNativeControlsVisible(true)
        sm.showControls()
        assertTrue(sm.state.topBarVisible)
        assertFalse(sm.state.bottomBarVisible)
        assertFalse(sm.shouldAutoHideControls())

        // 起播进入 playing 后依然保持顶栏常驻、底栏隐藏
        sm.setPlaying(true)
        assertTrue(sm.state.topBarVisible)
        assertFalse(sm.state.bottomBarVisible)
        assertFalse(sm.shouldAutoHideControls())

        // 用户退出原控后恢复底栏与 3500ms 自动隐藏
        sm.setWebNativeControlsVisible(false)
        assertTrue(sm.state.topBarVisible)
        assertTrue(sm.state.bottomBarVisible)
        assertTrue(sm.shouldAutoHideControls())
    }

    @Test
    fun `entering picture-in-picture forces all chrome hidden and exiting restores native controls top bar`() {
        val sm = MobilePlayerChromeStateMachine(PlayerChromeCapabilities.CCTV, initialPlaying = true)
        sm.setWebNativeControlsVisible(true)
        assertTrue(sm.state.topBarVisible)
        assertFalse(sm.state.bottomBarVisible)

        val inPip = sm.setPictureInPictureMode(true)
        assertTrue(inPip.isInPictureInPicture)
        assertFalse(inPip.topBarVisible)
        assertFalse(inPip.bottomBarVisible)
        assertFalse(inPip.lockButtonVisible)
        assertFalse(inPip.isDrawerOpen)
        assertFalse(sm.shouldAutoHideControls())

        // PiP 期间呼出控件与抽屉必须被屏蔽
        sm.showControls()
        assertFalse(sm.state.topBarVisible)
        sm.setDrawerOpen(true)
        assertFalse(sm.state.isDrawerOpen)

        // 退出 PiP 回到全屏后，持久化「退出原控」态自动恢复顶栏常驻、底栏隐藏
        val exited = sm.setPictureInPictureMode(false)
        assertFalse(exited.isInPictureInPicture)
        assertTrue(exited.topBarVisible)
        assertFalse(exited.bottomBarVisible)
        assertFalse(sm.shouldAutoHideControls())
    }

    @Test
    fun `syncing native controls without revealing keeps chrome hidden during channel switch loading`() {
        val sm = MobilePlayerChromeStateMachine(PlayerChromeCapabilities.CCTV, initialPlaying = false)
        sm.setDrawerOpen(true)
        sm.setDrawerOpen(false)
        sm.hideControls(force = true)

        val synced = sm.setWebNativeControlsVisible(nativeVisible = true, revealTopBar = false)
        assertTrue(synced.isWebNativeControlsVisible)
        assertFalse("切台加载态同步原控标记不得自动弹出顶栏", synced.topBarVisible)
        assertFalse("切台加载态同步原控标记不得自动弹出底栏", synced.bottomBarVisible)

        // 用户主动轻点屏幕才唤出控制栏（原控态仅显顶栏、底栏隐藏）
        val tapped = sm.toggleControls()
        assertTrue(tapped.topBarVisible)
        assertFalse(tapped.bottomBarVisible)
    }
}
