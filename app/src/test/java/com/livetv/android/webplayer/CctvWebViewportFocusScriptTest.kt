package com.livetv.android.webplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CctvWebViewportFocusScript` 的静态内容断言。
 *
 * 脚本必须：锚定 `#player`、校验 video/canvas 可见尺寸、用 fixed 满屏黑底、只隐藏兄弟节点；
 * 且**不得**搬移 DOM、模拟点击、强改播放状态或引用外部 URL。
 */
class CctvWebViewportFocusScriptTest {

    private val script = CctvWebViewportFocusScript.FOCUS_SCRIPT

    @Test
    fun `anchors the player and requires a visible video or canvas`() {
        assertTrue(script.contains("'player'"))
        assertTrue(script.contains("video"))
        assertTrue(script.contains("canvas"))
        assertTrue(script.contains("getBoundingClientRect"))
    }

    @Test
    fun `uses a fixed full screen black layout`() {
        assertTrue(script.contains("position:fixed"))
        assertTrue(script.contains("100vw"))
        assertTrue(script.contains("100vh"))
        assertTrue(script.contains("#000"))
    }

    @Test
    fun `watches late dom changes without moving nodes`() {
        assertTrue(script.contains("MutationObserver"))
    }

    @Test
    fun `never moves dom simulates clicks or forces playback`() {
        listOf(
            "appendChild",
            "insertBefore",
            ".click(",
            "dispatchEvent",
            ".play(",
            ".muted",
            "requestFullscreen",
        ).forEach { token ->
            assertFalse("脚本不得出现 $token", script.contains(token))
        }
    }

    @Test
    fun `never references external urls`() {
        assertFalse(script.contains("http://"))
        assertFalse(script.contains("https://"))
        assertFalse(script.contains(".m3u8"))
    }

    @Test
    fun `ready probe is a pure boolean expression`() {
        assertEqualsIgnoringSpace(
            "Boolean(window.__livetvFocusReady)",
            CctvWebViewportFocusScript.CHECK_READY_SCRIPT,
        )
    }

    private fun assertEqualsIgnoringSpace(expected: String, actual: String) {
        org.junit.Assert.assertEquals(expected, actual.replace(" ", ""))
    }
}
