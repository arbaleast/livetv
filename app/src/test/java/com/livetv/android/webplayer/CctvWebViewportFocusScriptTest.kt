package com.livetv.android.webplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CctvWebViewportFocusScript` 的静态内容断言。
 *
 * 脚本必须：
 * 1. 锚定 `#player`、校验 video/canvas 可见尺寸、用 fixed 满屏黑底、只隐藏外围兄弟节点；
 * 2. 对 MutationObserver 做节流（250~300ms）并在就绪后收缩监听至 `document.body` (`subtree: false`)；
 * 3. 检测播放态进入阶段二并设置 `window.__livetvPlaying = true`；
 * 4. 使用 `html.livetv-pure-video` + `opacity:0 !important;pointer-events:none !important;` 隐藏内部控制层，
 *    保持 `object-fit:contain !important`；
 * 5. **严禁**对 `#player` 内部控件使用 `display:none`，严禁搬移 DOM、模拟点击、强改播放状态或引用外部 URL。
 */
class CctvWebViewportFocusScriptTest {

    private val script = CctvWebViewportFocusScript.FOCUS_SCRIPT
    private val allScripts = listOf(
        CctvWebViewportFocusScript.FOCUS_SCRIPT,
        CctvWebViewportFocusScript.CHECK_READY_SCRIPT,
        CctvWebViewportFocusScript.CHECK_STATE_SCRIPT,
        CctvWebViewportFocusScript.SET_PURE_VIDEO_ON_SCRIPT,
        CctvWebViewportFocusScript.SET_PURE_VIDEO_OFF_SCRIPT,
    )

    @Test
    fun `anchors the player and requires a visible video or canvas`() {
        assertTrue(script.contains("'player'"))
        assertTrue(script.contains("video"))
        assertTrue(script.contains("canvas"))
        assertTrue(script.contains("getBoundingClientRect"))
    }

    @Test
    fun `uses a fixed full screen black layout with object fit contain`() {
        assertTrue(script.contains("position:fixed"))
        assertTrue(script.contains("100vw"))
        assertTrue(script.contains("100vh"))
        assertTrue(script.contains("#000"))
        assertTrue(script.contains("object-fit:contain !important"))
    }

    @Test
    fun `throttles mutation observer and narrows scope after player is ready`() {
        assertTrue(script.contains("MutationObserver"))
        assertTrue("MutationObserver 应包含节流定时器", script.contains("setTimeout"))
        assertTrue("就绪后应先 disconnect 再收缩观察范围", script.contains("disconnect()"))
        assertTrue("就绪后应收缩监听 document.body", script.contains("document.body"))
        assertTrue(
            "就绪后应使用 subtree: false 避免高频空转",
            script.contains("subtree:false") || script.contains("subtree: false"),
        )
    }

    @Test
    fun `detects playing video state and toggles two stage pure video styles safely`() {
        assertTrue(script.contains("readyState"))
        assertTrue(script.contains("currentTime"))
        assertTrue(script.contains("playing"))
        assertTrue(script.contains("timeupdate"))
        assertTrue(script.contains("window.__livetvPlaying"))
        assertTrue(script.contains("html.livetv-pure-video #player .livetv-pure-control-hidden"))
        assertTrue("纯视频内部控制层应包含 visibility:hidden !important", script.contains("visibility:hidden !important"))
        assertTrue(script.contains("opacity:0 !important"))
        assertTrue(script.contains("pointer-events:none !important"))

        // 内部控制层样式不得使用 display:none（防止 offsetWidth 为 0 破坏官方布局计算）
        val pureRuleLine = script.lines().firstOrNull { it.contains("livetv-pure-control-hidden") && it.contains("opacity") }
        assertTrue("应存在纯视频内部控制层隐藏 CSS 规则", pureRuleLine != null)
        assertFalse("纯视频内部控制层规则严禁使用 display:none", pureRuleLine!!.contains("display:none"))
    }

    @Test
    fun `prunes outer dom and disconnects observer after playback stabilizes`() {
        assertTrue("应包含外围 DOM 物理剪枝 .remove()", script.contains(".remove()"))
        assertTrue("剪枝后应断开 MutationObserver", script.contains("observer.disconnect()"))
    }

    @Test
    fun `pure video toggle and state probe scripts are properly defined`() {
        val onScript = CctvWebViewportFocusScript.SET_PURE_VIDEO_ON_SCRIPT
        val offScript = CctvWebViewportFocusScript.SET_PURE_VIDEO_OFF_SCRIPT
        val stateScript = CctvWebViewportFocusScript.CHECK_STATE_SCRIPT

        assertTrue(onScript.contains("livetv-pure-video"))
        assertTrue(onScript.contains("__livetvPureVideo"))
        assertTrue(offScript.contains("livetv-pure-video"))
        assertTrue(offScript.contains("remove"))
        assertEqualsIgnoringSpace(
            "(window.__livetvPlaying?'playing':(window.__livetvFocusReady?'ready':'loading'))",
            stateScript,
        )
        assertTrue(stateScript.contains("__livetvPlaying"))
        assertTrue(stateScript.contains("__livetvFocusReady"))
        assertTrue(stateScript.contains("'playing'"))
        assertTrue(stateScript.contains("'ready'"))
        assertTrue(stateScript.contains("'loading'"))
    }

    @Test
    fun `never moves dom simulates clicks or forces playback`() {
        val forbidden = listOf(
            "appendChild",
            "insertBefore",
            ".click(",
            "dispatchEvent",
            ".play(",
            ".muted",
            "requestFullscreen",
        )
        allScripts.forEach { s ->
            forbidden.forEach { token ->
                assertFalse("脚本不得出现 $token", s.contains(token))
            }
        }
    }

    @Test
    fun `never references external urls`() {
        allScripts.forEach { s ->
            assertFalse(s.contains("http://"))
            assertFalse(s.contains("https://"))
            assertFalse(s.contains(".m3u8"))
        }
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
