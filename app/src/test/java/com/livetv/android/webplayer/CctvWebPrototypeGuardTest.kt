package com.livetv.android.webplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CCTV WebView 原型的**源码守卫**。
 *
 * 允许的唯一动态执行入口：`CctvWebPlayerActivity` 调用 `CctvWebViewportFocusScript` 中的
 * **静态常量**脚本；其余任何文件不得出现 `evaluateJavascript`，也不得出现字符串拼接式 JS。
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
    fun `no javascript urls interfaces media links or cookies anywhere`() {
        val forbidden = listOf("javascript:", "@JavascriptInterface", ".m3u8", "Cookie:", "addJavascriptInterface")
        sources().forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            forbidden.forEach { token ->
                assertFalse("${file.name} 不应出现 $token", text.contains(token, ignoreCase = true))
            }
        }
    }

    @Test
    fun `evaluateJavascript is only used to run the static focus script`() {
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
        assertTrue(activity.contains("CctvWebViewportFocusScript.CHECK_READY_SCRIPT"))
    }

    @Test
    fun `prototype does not depend on the playback or catalog pipeline`() {
        val forbiddenImports = listOf(
            "com.livetv.android.player.PlayerManager",
            "com.livetv.android.domain.stream.RoutingStreamResolver",
            "com.livetv.android.domain.repository.ChannelCatalogStore",
            "com.livetv.android.domain.stream.StreamRefreshCoordinator",
        )
        sources().forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            forbiddenImports.forEach { symbol ->
                assertFalse("${file.name} 不应依赖 $symbol", text.contains(symbol))
            }
        }
    }

    @Test
    fun `renderer death and main frame failures show a friendly message`() {
        val activity = File(dir, "CctvWebPlayerActivity.kt").readText(Charsets.UTF_8)

        assertTrue(activity.contains("onRenderProcessGone"))
        assertTrue(activity.contains("didCrash()"))
        assertTrue(activity.contains("isForMainFrame"))
        assertTrue(activity.contains("R.string.cctv_web_load_failed"))
        assertTrue(activity.contains("R.string.cctv_web_renderer_gone"))
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
