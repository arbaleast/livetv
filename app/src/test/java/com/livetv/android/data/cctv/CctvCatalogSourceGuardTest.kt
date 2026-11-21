package com.livetv.android.data.cctv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CCTV 数据层的**源码守卫**（对齐 `MgtvEndpointsTest` 的守卫写法）。
 *
 * 目的：保证频道数据只来自 `assets/cctv_channels.json`（AGENTS 规则 5），
 * 且任何源码/静态数据都不得携带播放地址、签名或凭据。
 */
class CctvCatalogSourceGuardTest {

    private val forbiddenInSources = listOf(
        "http://",
        "https://",
        ".m3u8",
        ".flv",
        "auth-key",
        "Cookie:",
        "streamUrl = \"http",
    )

    @Test
    fun `cctv source files contain no playback url signature or credential`() {
        val dir = File("src/main/java/com/livetv/android/data/cctv")
        assertTrue("CCTV 数据层目录应存在: ${dir.absolutePath}", dir.exists())

        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".kt") }.orEmpty()
        assertTrue("应至少存在 CCTV 数据层源文件", files.isNotEmpty())

        val ipLike = Regex("(?<![0-9.])\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?![0-9.])")

        files.forEach { file ->
            val source = file.readText(Charsets.UTF_8)
            forbiddenInSources.forEach { token ->
                assertFalse(
                    "${file.name} 不应出现播放地址/签名/凭据 token：$token",
                    source.contains(token),
                )
            }
            assertFalse("${file.name} 不应硬编码 IP 地址", ipLike.containsMatchIn(source))
            assertFalse(
                "${file.name} 不应硬编码频道名称表（频道数据只能来自 assets JSON）",
                Regex("\"CCTV-?\\d").containsMatchIn(source),
            )
        }
    }

    @Test
    fun `cctv channel json only carries static metadata`() {
        val file = File("src/main/assets/cctv_channels.json")
        assertTrue("静态候选频道表应存在: ${file.absolutePath}", file.exists())

        val json = file.readText(Charsets.UTF_8)
        listOf("http://", "https://", ".m3u8", ".ts", ".flv", "auth-key", "Cookie:", "streamUrl")
            .forEach { token ->
                assertFalse("cctv_channels.json 不应包含：$token", json.contains(token))
            }
    }
}
