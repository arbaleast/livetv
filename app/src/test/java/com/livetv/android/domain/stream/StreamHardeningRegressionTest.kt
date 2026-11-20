package com.livetv.android.domain.stream

import com.livetv.android.domain.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通用流安全策略、HLS 识别边界、并发 403 协调与模块纯净度回归测试。
 */
class StreamHardeningRegressionTest {

    private val scopedHeaders = mapOf(
        "Origin" to "https://www.mgtv.com",
        "Referer" to "https://www.mgtv.com/",
    )

    @Test
    fun `non-empty host allowlist rejects plaintext http and ftp schemes`() {
        assertFalse(HeaderHostPolicy.isHostAllowed("http://padal.qing.mgtv.com/live.m3u8", setOf("mgtv.com")))
        assertFalse(HeaderHostPolicy.isHostAllowed("ftp://padal.qing.mgtv.com/live.m3u8", setOf("mgtv.com")))
        assertEquals(
            emptyMap<String, String>(),
            HeaderHostPolicy.filterHeadersForUrl("http://padal.qing.mgtv.com/live.m3u8", scopedHeaders, setOf("mgtv.com")),
        )
    }

    @Test
    fun `non-empty host allowlist rejects userinfo spoofing`() {
        assertFalse(
            HeaderHostPolicy.isHostAllowed("https://attacker@padal.qing.mgtv.com/live.m3u8", setOf("mgtv.com")),
        )
        assertEquals(
            emptyMap<String, String>(),
            HeaderHostPolicy.filterHeadersForUrl(
                "https://attacker:pass@padal.qing.mgtv.com/live.m3u8",
                scopedHeaders,
                setOf("mgtv.com"),
            ),
        )
    }

    @Test
    fun `stream type detector does not misclassify dash or json under hls path or query m3u8`() {
        assertNull(StreamTypeDetector.inferMimeType("https://cdn.example.invalid/hls/live.mpd"))
        assertNull(StreamTypeDetector.inferMimeType("https://cdn.example.invalid/hls/info.json"))
        assertNull(StreamTypeDetector.inferMimeType("https://cdn.example.invalid/video.mp4?next=foo.m3u8"))
        assertNull(StreamTypeDetector.inferMimeType("https://hls.php.example.invalid/live?bad=["))
    }

    @Test
    fun `concurrent 403 errors trigger at most one re-resolution`() {
        val channel = Channel("mgtv:287", "金鹰卡通", "", "")
        val resolveCount = AtomicInteger(0)
        val resolver = object : StreamResolver {
            override fun resolve(channel: Channel, preferredDefinition: String?): StreamResolveResult {
                val n = resolveCount.incrementAndGet()
                Thread.sleep(25)
                return StreamResolveResult.Success(
                    ResolvedStream(
                        url = "https://cdn.example.invalid/v$n.m3u8",
                        expiresAtMs = 1_000_000L,
                        mimeType = StreamTypeDetector.MIME_HLS,
                        allowedHeaderHosts = setOf("mgtv.com"),
                    ),
                )
            }
        }
        val coordinator = StreamRefreshCoordinator(resolver, clock = { 10_000L })
        coordinator.prepareForPlayback(channel)
        assertEquals(1, resolveCount.get())

        val startGate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map {
                pool.submit<StreamRefreshCoordinator.RefreshAction> {
                    startGate.await(5, TimeUnit.SECONDS)
                    coordinator.onPlaybackHttpError(channel, 403)
                }
            }
            startGate.countDown()
            val actions = futures.map { it.get(5, TimeUnit.SECONDS) }
            val reconnects = actions.count { it is StreamRefreshCoordinator.RefreshAction.Reconnect }
            val fastFails = actions.count { it is StreamRefreshCoordinator.RefreshAction.FastFail }

            assertEquals(1, reconnects)
            assertEquals(3, fastFails)
            assertEquals(2, resolveCount.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `domain stream layer has no android or media3 imports and player manager has no mgtv imports`() {
        val streamDir = File("src/main/java/com/livetv/android/domain/stream")
        assertTrue(streamDir.exists())
        streamDir.listFiles { f -> f.extension == "kt" }.orEmpty().forEach { file ->
            val content = file.readText(Charsets.UTF_8)
            assertFalse("${file.name} must not import android.*", content.contains("import android."))
            assertFalse("${file.name} must not import androidx.media3.*", content.contains("import androidx.media3."))
        }

        val playerManager = File("src/main/java/com/livetv/android/player/PlayerManager.kt")
        assertTrue(playerManager.exists())
        assertFalse(
            "PlayerManager must stay decoupled from data.mgtv",
            playerManager.readText(Charsets.UTF_8).contains("com.livetv.android.data.mgtv"),
        )
    }
}
