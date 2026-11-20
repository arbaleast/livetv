package com.livetv.android.domain.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamTypeDetector] 的 HLS 识别测试（防止渐进式流被误判成 HLS）。
 */
class StreamTypeDetectorTest {

    @Test
    fun `detects standard m3u8 and m3u`() {
        assertTrue(StreamTypeDetector.isLikelyHlsUrl("https://cdn.example.invalid/live/index.m3u8"))
        assertTrue(StreamTypeDetector.isLikelyHlsUrl("https://cdn.example.invalid/live/index.m3u"))
    }

    @Test
    fun `detects extensionless hls by query parameter`() {
        assertTrue(StreamTypeDetector.isLikelyHlsUrl("https://iptv.example.invalid/live?format=m3u8"))
        assertTrue(StreamTypeDetector.isLikelyHlsUrl("https://iptv.example.invalid/live?type=hls"))
        assertTrue(StreamTypeDetector.isLikelyHlsUrl("https://iptv.example.invalid/live?protocol=m3u8"))
        assertTrue(StreamTypeDetector.isLikelyHlsUrl("https://iptv.example.invalid/live?fmt=m3u8"))
    }

    @Test
    fun `detects hls by path`() {
        assertTrue(StreamTypeDetector.isLikelyHlsUrl("https://iptv.example.invalid/hls/stream"))
        assertTrue(StreamTypeDetector.isLikelyHlsUrl("https://iptv.example.invalid/live/hls"))
        assertTrue(StreamTypeDetector.isLikelyHlsUrl("https://iptv.example.invalid/hls.php"))
        assertTrue(StreamTypeDetector.isLikelyHlsUrl("https://iptv.example.invalid/m3u8/stream"))
    }

    @Test
    fun `does not misdetect progressive formats as hls`() {
        assertFalse(StreamTypeDetector.isLikelyHlsUrl("https://cdn.example.invalid/movie.mp4"))
        assertFalse(StreamTypeDetector.isLikelyHlsUrl("https://cdn.example.invalid/movie.mkv"))
        assertFalse(StreamTypeDetector.isLikelyHlsUrl("https://cdn.example.invalid/live.flv"))
        assertFalse(StreamTypeDetector.isLikelyHlsUrl("https://cdn.example.invalid/seg.ts"))
        assertFalse(StreamTypeDetector.isLikelyHlsUrl("https://cdn.example.invalid/audio.mp3"))
        assertFalse(StreamTypeDetector.isLikelyHlsUrl("https://cdn.example.invalid/audio.aac"))
        assertFalse(StreamTypeDetector.isLikelyHlsUrl("https://cdn.example.invalid/video.webm"))
    }

    @Test
    fun `infers hls mime type`() {
        assertEquals(StreamTypeDetector.MIME_HLS, StreamTypeDetector.inferMimeType("https://x.invalid/a.m3u8"))
        assertEquals(StreamTypeDetector.MIME_HLS, StreamTypeDetector.inferMimeType("https://x.invalid/live?format=m3u8"))
    }

    @Test
    fun `respects explicit content type header`() {
        assertEquals(
            StreamTypeDetector.MIME_HLS,
            StreamTypeDetector.inferMimeType("https://x.invalid/live", "application/vnd.apple.mpegurl"),
        )
    }

    @Test
    fun `returns null for unknown progressive stream`() {
        assertNull(StreamTypeDetector.inferMimeType("https://cdn.example.invalid/movie.mp4"))
        assertNull(StreamTypeDetector.inferMimeType("https://cdn.example.invalid/live"))
        assertNull(StreamTypeDetector.inferMimeType(""))
    }
}
