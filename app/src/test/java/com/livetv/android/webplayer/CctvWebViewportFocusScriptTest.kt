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
    fun `anchors both player and myPlayer containers and requires a visible video or canvas`() {
        assertTrue(script.contains("'player'"))
        assertTrue("应同时兼容 4K 点播页容器 #myPlayer", script.contains("'myPlayer'"))
        assertTrue("CSS 聚焦应同时覆盖 #player 与 #myPlayer", script.contains("#myPlayer"))
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
        assertTrue("observe 前必须判空 document.documentElement 防止早注入抛 TypeError 中断定时器", script.contains("!document.documentElement"))
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
        assertTrue(script.contains("videoWidth > 0"))
        assertTrue(script.contains("currentTime > 0.05"))
        assertTrue(script.contains("playing"))
        assertTrue(script.contains("timeupdate"))
        assertTrue(script.contains("window.__livetvPlaying"))
        assertTrue(script.contains("html.livetv-pure-video #player .livetv-pure-control-hidden"))
        assertTrue(
            "纯视频态应通过 CSS 屏蔽 WebView 原生 <video controls> Shadow DOM（避免 4K 点播撤幕瞬间闪出进度条/暂停圆钮/加载圈）",
            script.contains("::-webkit-media-controls"),
        )
        assertTrue("纯视频内部控制层应包含 visibility:hidden !important", script.contains("visibility:hidden !important"))
        assertTrue(script.contains("opacity:0 !important"))
        assertTrue(script.contains("pointer-events:none !important"))

        // 内部控制层样式不得使用 display:none（防止 offsetWidth 为 0 破坏官方布局计算）
        val pureRuleLine = script.lines().firstOrNull { it.contains("livetv-pure-control-hidden") && it.contains("opacity") }
        assertTrue("应存在纯视频内部控制层隐藏 CSS 规则", pureRuleLine != null)
        assertFalse("纯视频内部控制层规则严禁使用 display:none", pureRuleLine!!.contains("display:none"))

        // 官方加载遮罩层在纯视频态（原控态除外）应被强制 display:none !important，覆盖 #player 与 #myPlayer
        val loadingMaskLine = script.lines().firstOrNull { it.contains("livetv-loading-mask-hidden") && it.contains("display:none") }
        assertTrue("应存在 #player/#myPlayer 官方加载遮罩层隐藏 CSS 规则", loadingMaskLine != null)
        assertTrue("加载遮罩规则必须受 html.livetv-pure-video 门控（原控态除外）", loadingMaskLine!!.contains("html.livetv-pure-video"))
        assertTrue("加载遮罩规则必须覆盖 #player", loadingMaskLine.contains("#player"))
        assertTrue("加载遮罩规则必须覆盖 #myPlayer", loadingMaskLine.contains("#myPlayer"))
        assertTrue("加载遮罩规则必须强制 display:none !important", loadingMaskLine.contains("display:none !important"))
    }

    @Test
    fun `uses one-shot requestVideoFrameCallback for immediate first frame detection`() {
        assertTrue(
            "应优先绑定 requestVideoFrameCallback 紧贴真实首帧出画",
            script.contains("requestVideoFrameCallback"),
        )
        assertTrue(
            "requestVideoFrameCallback 应写入 __livetvFirstFrameRendered 真首帧标记",
            script.contains("__livetvFirstFrameRendered"),
        )
        val occurrences = Regex("requestVideoFrameCallback").findAll(script).count()
        assertTrue(
            "requestVideoFrameCallback 仅允许存在性检测 + 单次注册（禁止逐帧递归空转），实际出现 $occurrences 次",
            occurrences == 2,
        )
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
        assertTrue("应监听 video ended 事件支持 4K 点播自动轮播下一期", script.contains("'ended'"))
        assertTrue("状态探针必须校验 videoWidth > 0", stateScript.contains("videoWidth>0"))
        assertTrue("状态探针必须优先认可 __livetvFirstFrameRendered 真首帧标记", stateScript.contains("__livetvFirstFrameRendered"))
        assertTrue(
            "状态探针必须保留 readyState >= 2 && currentTime > 0.05 回退条件",
            stateScript.contains("readyState>=2") && stateScript.contains("currentTime>0.05"),
        )
        assertTrue(stateScript.contains("__livetvEnded"))
        assertTrue(stateScript.contains("__livetvPlaying"))
        assertTrue(stateScript.contains("__livetvFocusReady"))
        assertTrue(stateScript.contains("'ended'"))
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
