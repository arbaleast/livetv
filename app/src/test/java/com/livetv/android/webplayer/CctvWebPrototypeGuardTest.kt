package com.livetv.android.webplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CCTV WebView 原型的**源码守卫**。
 *
 * 边界约束：
 * - 只允许 `CctvWebPlayerActivity` 调用 `CctvWebViewportFocusScript` 中的**静态常量**脚本；
 * - 严禁耦合播放引擎与动态解析管线（`PlayerManager` / `RoutingStreamResolver` / `StreamRefreshCoordinator`）；
 * - 允许只读访问 `ChannelCatalogStore` 用于选台抽屉展示列表（禁止写入 `update`）；
 * - 强制校验休眠冻结定时器、保留磁盘缓存与 `onRenderProcessGone` 彻底销毁。
 */
class CctvWebPrototypeGuardTest {

    private val dir = File("src/main/java/com/livetv/android/webplayer")

    private fun sources(): List<File> {
        assertTrue("原型目录应存在: ${dir.absolutePath}", dir.isDirectory)
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".kt") }.orEmpty()
        assertTrue("原型目录应至少有一个源文件", files.isNotEmpty())
        return files
    }

    @Test
    fun `no javascript urls interfaces media links cookies or clearCache anywhere`() {
        val forbidden = listOf(
            "javascript:",
            "@JavascriptInterface",
            ".m3u8",
            "Cookie:",
            "addJavascriptInterface",
            "clearCache(true)",
        )
        sources().forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            forbidden.forEach { token ->
                assertFalse("${file.name} 不应出现 $token", text.contains(token, ignoreCase = true))
            }
        }
    }

    @Test
    fun `evaluateJavascript is only used to run the static focus script constants`() {
        sources().forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            if (text.contains("evaluateJavascript")) {
                assertTrue(
                    "只有 CctvWebPlayerActivity 允许调用 evaluateJavascript，实际: ${file.name}",
                    file.name == "CctvWebPlayerActivity.kt",
                )
                assertFalse(
                    "${file.name} 不得传入字符串字面量 JS（只能使用脚本常量）",
                    text.contains("evaluateJavascript(\""),
                )
            }
        }

        val activity = File(dir, "CctvWebPlayerActivity.kt").readText(Charsets.UTF_8)
        assertTrue(activity.contains("CctvWebViewportFocusScript.FOCUS_SCRIPT"))
        assertTrue(activity.contains("CctvWebViewportFocusScript.CHECK_STATE_SCRIPT"))
        assertTrue(activity.contains("CctvWebViewportFocusScript.SET_PURE_VIDEO_ON_SCRIPT"))
        assertTrue(activity.contains("CctvWebViewportFocusScript.SET_PURE_VIDEO_OFF_SCRIPT"))
    }

    @Test
    fun `prototype does not depend on playback engine or mutate catalog store`() {
        val forbiddenImports = listOf(
            "com.livetv.android.player.PlayerManager",
            "com.livetv.android.domain.stream.RoutingStreamResolver",
            "com.livetv.android.domain.stream.StreamRefreshCoordinator",
        )
        sources().forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            forbiddenImports.forEach { symbol ->
                assertFalse("${file.name} 不应依赖 $symbol", text.contains(symbol))
            }
            // 允许只读读取 ChannelCatalogStore 用于选台抽屉列表展示，但严禁写入或篡改目录快照
            assertFalse("${file.name} 不得写入 catalogStore", text.contains("catalogStore.update("))
        }
    }

    @Test
    fun `activity pauses timers preserves disk cache and destroys webview on render process gone`() {
        val activity = File(dir, "CctvWebPlayerActivity.kt").readText(Charsets.UTF_8)

        assertTrue(activity.contains("onRenderProcessGone"))
        assertTrue(activity.contains("didCrash()"))
        assertTrue(activity.contains("isForMainFrame"))
        assertTrue(activity.contains("R.string.cctv_web_load_failed"))
        assertTrue(activity.contains("R.string.cctv_web_renderer_gone"))

        // 性能与生命周期加固
        assertTrue("onPause 应冻结 JS 定时器", activity.contains("pauseTimers()"))
        assertTrue("onResume 应恢复 JS 定时器", activity.contains("resumeTimers()"))
        assertTrue("应显式设置 WebSettings.LOAD_DEFAULT", activity.contains("WebSettings.LOAD_DEFAULT"))

        val goneStart = activity.indexOf("override fun onRenderProcessGone")
        assertTrue(goneStart >= 0)
        val goneBlock = activity.substring(goneStart, minOf(activity.length, goneStart + 900))
        assertTrue("onRenderProcessGone 必须从父容器移除 WebView", goneBlock.contains("removeView("))
        assertTrue("onRenderProcessGone 必须清理子视图", goneBlock.contains("removeAllViews()"))
        assertTrue("onRenderProcessGone 必须调用 destroy() 彻底销毁", goneBlock.contains(".destroy()"))

        // 触控容器拦截、抽屉复用与挖孔安全区
        assertTrue("应使用 CctvTouchContainer 拦截手势", activity.contains("onInterceptTouchEvent"))
        assertTrue("抽屉打开时触控容器必须放行不拦截", activity.contains("if (isDrawerOpen"))
        assertTrue("选台抽屉应作为 root 兄弟视图位于触控容器之上", activity.contains("root.addView(\n            drawerPanel"))
        assertTrue("选台抽屉应复用 ChannelDrawerModel", activity.contains("ChannelDrawerModel"))
        assertTrue("选台抽屉应复用 ChannelDrawerAdapter", activity.contains("ChannelDrawerAdapter"))
        assertTrue("顶栏/抽屉应避让 displayCutout", activity.contains("displayCutout()"))

        // R3 awaitingPageCommit 异常/超时路径必须复位防卡死
        val errorStart = activity.indexOf("override fun onReceivedError")
        val httpErrorStart = activity.indexOf("override fun onReceivedHttpError")
        val failureStart = activity.indexOf("private fun showLoadFailure")
        val timeoutLine = activity.indexOf("private val revealTimeoutRunnable")
        assertTrue(errorStart >= 0 && httpErrorStart > errorStart && failureStart > 0 && timeoutLine > 0)
        assertTrue(
            "onReceivedError 主帧异常必须复位 awaitingPageCommit",
            activity.substring(errorStart, httpErrorStart).contains("awaitingPageCommit = false"),
        )
        assertTrue(
            "onReceivedHttpError 主帧异常必须复位 awaitingPageCommit",
            activity.substring(httpErrorStart, minOf(activity.length, httpErrorStart + 450)).contains("awaitingPageCommit = false"),
        )
        assertTrue(
            "showLoadFailure 必须复位 awaitingPageCommit",
            activity.substring(failureStart, minOf(activity.length, failureStart + 450)).contains("awaitingPageCommit = false"),
        )
        assertTrue(
            "7s 超时兜底 revealTimeoutRunnable 必须复位 awaitingPageCommit",
            activity.substring(timeoutLine, minOf(activity.length, timeoutLine + 350)).contains("awaitingPageCommit = false"),
        )

        // 最终收口守卫：主帧放行、不锁 60Hz（防 ColorOS 120Hz 回退）、保留 25fps 帧率投票与后台硬休眠
        val interceptStart = activity.indexOf("override fun shouldInterceptRequest")
        assertTrue("应实现 shouldInterceptRequest", interceptStart >= 0)
        val interceptHead = activity.substring(interceptStart, minOf(activity.length, interceptStart + 260))
        assertTrue("shouldInterceptRequest 首行必须校验 isForMainFrame 放行主帧", interceptHead.contains("isForMainFrame"))

        assertFalse("严禁保留 preferredDisplayModeId（ColorOS 强锁 60Hz 会反升 120Hz）", activity.contains("preferredDisplayModeId"))
        assertFalse("严禁保留 preferredRefreshRate 窗口强锁", activity.contains("preferredRefreshRate"))
        assertTrue("应保留 API 35+ View 级 setRequestedFrameRate(25f)", activity.contains("setRequestedFrameRate"))
        assertTrue("应保留后台 5s 硬休眠 RENDERER_PRIORITY_WAIVED", activity.contains("RENDERER_PRIORITY_WAIVED"))
        assertTrue("onDestroy 应取消后台休眠回调", activity.contains("backgroundSuspendRunnable"))

        val blockerFile = File(dir, "CctvWebResourceBlocker.kt")
        assertTrue("应存在 CctvWebResourceBlocker.kt", blockerFile.isFile)
        val blocker = blockerFile.readText(Charsets.UTF_8)
        assertFalse("CctvWebResourceBlocker 严禁拦截或引用 cntv_Advertise.js", blocker.contains("cntv_Advertise", ignoreCase = true))
        assertFalse("CctvWebResourceBlocker 严禁伪造 window.wx 等 JS 桩对象", blocker.contains("window.wx", ignoreCase = true))
    }

    @Test
    fun `all hardcoded urls point at the official live pages`() {
        val urls = sources().flatMap { file ->
            Regex("""https://[^\s"']+""")
                .findAll(file.readText(Charsets.UTF_8))
                .map { file.name to it.value }
                .toList()
        }

        assertTrue("应至少出现官方直播域名", urls.isNotEmpty())
        urls.forEach { (name, url) ->
            assertTrue("$name 出现非官方 URL: $url", url.startsWith("https://tv.cctv.com/live/"))
        }
    }
}
