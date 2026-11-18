package com.livetv.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 纯策略层的**架构约束**测试。
 *
 * 任务书要求：`RetryPolicy` / `ChannelNavigator` / `PlaybackState` / `PlaybackReducer` /
 * `PlaybackError` / `PlaybackErrorMapper` 不得引用 `android.*`、`android.os.Handler`、
 * `androidx.media3.*`。这里用源码扫描把该约束固化成可回归的测试。
 */
class PlayerLayerPurityTest {

    /** 必须保持纯 Kotlin（不依赖 Android / Media3）的源文件。 */
    private val pureFiles = listOf(
        "PlaybackState.kt",
        "PlaybackEvent.kt",
        "PlaybackReducer.kt",
        "PlaybackError.kt",
        "PlaybackErrorMapper.kt",
        "RetryPolicy.kt",
        "ChannelNavigator.kt",
    )

    private fun sourceOf(fileName: String): String {
        val file = File("src/main/java/com/livetv/android/player/$fileName")
        assertTrue("源文件应存在: ${file.absolutePath}", file.exists())
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `pure strategy layer does not import android or media3`() {
        pureFiles.forEach { fileName ->
            val source = sourceOf(fileName)
            val forbidden = listOf(
                "import android.",
                "import androidx.media3.",
                "android.net.Uri",
                "android.os.Handler",
            )
            forbidden.forEach { token ->
                assertFalse(
                    "$fileName 不应引用 $token",
                    source.contains(token),
                )
            }
        }
    }

    @Test
    fun `pure strategy layer does not reference Handler or Looper`() {
        pureFiles.forEach { fileName ->
            val source = sourceOf(fileName)
            assertFalse("$fileName 不应引用 Handler", source.contains("Handler("))
            assertFalse("$fileName 不应引用 Looper", source.contains("Looper"))
        }
    }

    @Test
    fun `player package contains exactly the expected pure files plus the bridge`() {
        val playerDir = File("src/main/java/com/livetv/android/player")
        assertTrue("player 包目录应存在", playerDir.exists())

        val actual = playerDir.listFiles()?.map { it.name }?.sorted().orEmpty()
        val expected = (pureFiles + "PlayerManager.kt").sorted()

        assertEquals(expected, actual)
    }
}
