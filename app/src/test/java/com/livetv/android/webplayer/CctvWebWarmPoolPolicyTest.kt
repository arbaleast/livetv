package com.livetv.android.webplayer

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CctvWebWarmPoolPolicyTest {

    private val categories = listOf(
        ChannelCategory(
            id = "cctv:all",
            name = "央视",
            channels = listOf(
                Channel("cctv:cctv1", "CCTV-1", "", ""),
                Channel("cctv:cctv2", "CCTV-2", "", ""),
                Channel("cctv:cctv4k", "CCTV-4K", "", ""),
                Channel("cctv:cctv3", "CCTV-3", "", ""),
                Channel("mgtv:1", "湖南卫视", "", "https://example.com/live"),
            ),
        ),
    )

    @Test
    fun `picks adjacent live cctv channel in direction and skips vod4k and non-cctv`() {
        val next = CctvWebWarmPoolPolicy.pickNextChannel("cctv:cctv2", categories, direction = 1)
        assertEquals("cctv:cctv3", next?.id)

        val prev = CctvWebWarmPoolPolicy.pickNextChannel("cctv:cctv1", categories, direction = -1)
        assertEquals("cctv:cctv3", prev?.id)
    }

    @Test
    fun `prefers strong transition habit over frequency and adjacent`() {
        val next = CctvWebWarmPoolPolicy.pickNextChannel(
            currentChannelId = "cctv:cctv1",
            categories = categories,
            direction = 1,
            frequencies = mapOf("cctv2" to 10, "cctv3" to 3),
            transitions = mapOf("cctv1>cctv3" to 2),
        )
        assertEquals("cctv:cctv3", next?.id)
    }

    @Test
    fun `prefers highest frequency channel when no strong transition exists`() {
        val next = CctvWebWarmPoolPolicy.pickNextChannel(
            currentChannelId = "cctv:cctv1",
            categories = categories,
            direction = 1,
            frequencies = mapOf("cctv1" to 20, "cctv3" to 7, "cctv2" to 2),
        )
        assertEquals("cctv:cctv3", next?.id)
    }

    @Test
    fun `prefers favorite channel before adjacent fallback when no history`() {
        val next = CctvWebWarmPoolPolicy.pickNextChannel(
            currentChannelId = "cctv:cctv1",
            categories = categories,
            direction = 1,
            favorites = setOf("cctv:cctv3"),
        )
        assertEquals("cctv:cctv3", next?.id)
    }

    @Test
    fun `returns null for vod4k or unsupported channel`() {
        assertNull(CctvWebWarmPoolPolicy.pickNextChannel("cctv:cctv4k", categories, direction = 1))
        assertNull(CctvWebWarmPoolPolicy.pickNextChannel("mgtv:1", categories, direction = 1))
    }

    @Test
    fun `shouldWarm enforces foreground playing live state`() {
        assertTrue(
            CctvWebWarmPoolPolicy.shouldWarm(
                isPlaying = true,
                revealed = true,
                isInPictureInPictureMode = false,
                suspendedInBackground = false,
                isFinishingOrDestroyed = false,
                isSwitchingEngine = false,
                currentChannelId = "cctv:cctv1",
            ),
        )
        assertFalse(
            CctvWebWarmPoolPolicy.shouldWarm(
                isPlaying = false,
                revealed = true,
                isInPictureInPictureMode = false,
                suspendedInBackground = false,
                isFinishingOrDestroyed = false,
                isSwitchingEngine = false,
                currentChannelId = "cctv:cctv1",
            ),
        )
        assertFalse(
            CctvWebWarmPoolPolicy.shouldWarm(
                isPlaying = true,
                revealed = true,
                isInPictureInPictureMode = true,
                suspendedInBackground = false,
                isFinishingOrDestroyed = false,
                isSwitchingEngine = false,
                currentChannelId = "cctv:cctv1",
            ),
        )
        assertFalse(
            CctvWebWarmPoolPolicy.shouldWarm(
                isPlaying = true,
                revealed = true,
                isInPictureInPictureMode = false,
                suspendedInBackground = false,
                isFinishingOrDestroyed = false,
                isSwitchingEngine = false,
                currentChannelId = "cctv:cctv4k",
            ),
        )
    }
}
