package com.livetv.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VideoEnhancePolicy] 与 [VideoEnhanceMode] 的纯 JVM 单元测试。
 */
class VideoEnhancePolicyTest {

    @Test
    fun `cycle wraps OFF to STANDARD to STRONG back to OFF`() {
        assertEquals(VideoEnhanceMode.STANDARD, VideoEnhanceMode.OFF.next())
        assertEquals(VideoEnhanceMode.STRONG, VideoEnhanceMode.STANDARD.next())
        assertEquals(VideoEnhanceMode.OFF, VideoEnhanceMode.STRONG.next())
    }

    @Test
    fun `storage round-trip falls back to OFF on unknown values`() {
        assertEquals(VideoEnhanceMode.OFF, VideoEnhanceMode.fromStorageValue(null))
        assertEquals(VideoEnhanceMode.OFF, VideoEnhanceMode.fromStorageValue(""))
        assertEquals(VideoEnhanceMode.OFF, VideoEnhanceMode.fromStorageValue("unknown_mode"))
        assertEquals(VideoEnhanceMode.STANDARD, VideoEnhanceMode.fromStorageValue(VideoEnhanceMode.STANDARD.storageValue))
        assertEquals(VideoEnhanceMode.STRONG, VideoEnhanceMode.fromStorageValue(VideoEnhanceMode.STRONG.storageValue))
        assertEquals(VideoEnhanceMode.OFF, VideoEnhanceMode.fromStorageValue(VideoEnhanceMode.OFF.storageValue))
    }

    @Test
    fun `OFF mode never applies effects on player startup`() {
        assertFalse(VideoEnhancePolicy.shouldApplyOnPlayerStart(VideoEnhanceMode.OFF))
        assertTrue(VideoEnhancePolicy.shouldApplyOnPlayerStart(VideoEnhanceMode.STANDARD))
        assertTrue(VideoEnhancePolicy.shouldApplyOnPlayerStart(VideoEnhanceMode.STRONG))
    }

    @Test
    fun `bypass skips 1080p and invalid dimensions while enhancing SD and 720p`() {
        assertTrue(VideoEnhancePolicy.shouldBypassForSource(0, 0))
        assertTrue(VideoEnhancePolicy.shouldBypassForSource(-1, 360))
        assertFalse(VideoEnhancePolicy.shouldBypassForSource(640, 360))
        assertFalse(VideoEnhancePolicy.shouldBypassForSource(960, 540))
        assertFalse(VideoEnhancePolicy.shouldBypassForSource(1280, 720))
        assertTrue(VideoEnhancePolicy.shouldBypassForSource(1920, 1080))
        assertTrue(VideoEnhancePolicy.shouldBypassForSource(3840, 2160))
        assertTrue(VideoEnhancePolicy.shouldBypassForSource(1440, 1080))
    }

    @Test
    fun `asset shader paths map deterministically per active mode`() {
        assertEquals(null, VideoEnhancePolicy.shaderAssetPath(VideoEnhanceMode.OFF))
        assertEquals("shaders/a4k_standard.passes", VideoEnhancePolicy.shaderAssetPath(VideoEnhanceMode.STANDARD))
        assertEquals("shaders/a4k_strong.passes", VideoEnhancePolicy.shaderAssetPath(VideoEnhanceMode.STRONG))
        assertEquals(null, VideoEnhancePolicy.shader4xAssetPath(VideoEnhanceMode.OFF))
        assertEquals("shaders/a4k_standard_4x.passes", VideoEnhancePolicy.shader4xAssetPath(VideoEnhanceMode.STANDARD))
        assertEquals("shaders/a4k_strong_4x.passes", VideoEnhancePolicy.shader4xAssetPath(VideoEnhanceMode.STRONG))
    }

    @Test
    fun `scale factor selects 4x for 360p-480p, 2x for 540p-720p, and 1x for 1080p or OFF`() {
        assertEquals(4, VideoEnhancePolicy.scaleFactorForSource(VideoEnhanceMode.STANDARD, 640, 360))
        assertEquals(4, VideoEnhancePolicy.scaleFactorForSource(VideoEnhanceMode.STRONG, 854, 480))
        assertEquals(2, VideoEnhancePolicy.scaleFactorForSource(VideoEnhanceMode.STANDARD, 960, 540))
        assertEquals(2, VideoEnhancePolicy.scaleFactorForSource(VideoEnhanceMode.STRONG, 1280, 720))
        assertEquals(1, VideoEnhancePolicy.scaleFactorForSource(VideoEnhanceMode.STANDARD, 1920, 1080))
        assertEquals(1, VideoEnhancePolicy.scaleFactorForSource(VideoEnhanceMode.OFF, 640, 360))
    }

    @Test
    fun `resolveStatus distinguishes OFF, active 2x, and 1080p bypass`() {
        assertEquals(
            VideoEnhanceStatus.OFF,
            VideoEnhancePolicy.resolveStatus(VideoEnhanceMode.OFF, 640, 360),
        )
        assertEquals(
            VideoEnhanceStatus.ACTIVE_STANDARD,
            VideoEnhancePolicy.resolveStatus(VideoEnhanceMode.STANDARD, 640, 360),
        )
        assertEquals(
            VideoEnhanceStatus.ACTIVE_STRONG,
            VideoEnhancePolicy.resolveStatus(VideoEnhanceMode.STRONG, 960, 540),
        )
        assertEquals(
            VideoEnhanceStatus.BYPASSED_HIGH_RES,
            VideoEnhancePolicy.resolveStatus(VideoEnhanceMode.STANDARD, 1920, 1080),
        )
    }

    @Test
    fun `formatCodecLabel maps common MIME types cleanly`() {
        assertEquals("H.264", VideoEnhancePolicy.formatCodecLabel("video/avc"))
        assertEquals("H.265", VideoEnhancePolicy.formatCodecLabel("video/hevc"))
        assertEquals("AV1", VideoEnhancePolicy.formatCodecLabel("video/av01"))
        assertEquals("未知", VideoEnhancePolicy.formatCodecLabel(null))
    }
}
