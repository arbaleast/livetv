package com.livetv.android.ui.mobile

import com.livetv.android.util.PlayerOrientationPolicy
import com.livetv.android.webplayer.CctvWebChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlayerTransitionAndPipPolicy] 纯 JVM 单元测试：
 * 覆盖跨引擎切台方向透传、PiP 进入门禁、安全宽高比钳制及 MGTV -> CCTV 4K/8K 前置校验。
 */
class PlayerTransitionAndPipPolicyTest {

    @Test
    fun `orientation passthrough preserves explicit landscape or portrait lock on mobile`() {
        assertEquals(
            PlayerTransitionAndPipPolicy.ORIENTATION_SENSOR_LANDSCAPE,
            PlayerTransitionAndPipPolicy.resolveInitialOrientation(
                isTvDevice = false,
                preferredOrientation = PlayerTransitionAndPipPolicy.ORIENTATION_SENSOR_LANDSCAPE,
            ),
        )
        assertEquals(
            PlayerTransitionAndPipPolicy.ORIENTATION_SENSOR_PORTRAIT,
            PlayerTransitionAndPipPolicy.resolveInitialOrientation(
                isTvDevice = false,
                preferredOrientation = PlayerTransitionAndPipPolicy.ORIENTATION_SENSOR_PORTRAIT,
            ),
        )
    }

    @Test
    fun `orientation passthrough falls back to sensor landscape on unspecified or invalid value and locks landscape on TV`() {
        assertEquals(
            PlayerOrientationPolicy.ORIENTATION_SENSOR_LANDSCAPE,
            PlayerTransitionAndPipPolicy.resolveInitialOrientation(
                isTvDevice = false,
                preferredOrientation = PlayerTransitionAndPipPolicy.ORIENTATION_UNSPECIFIED,
            ),
        )
        assertEquals(
            PlayerOrientationPolicy.ORIENTATION_SENSOR_LANDSCAPE,
            PlayerTransitionAndPipPolicy.resolveInitialOrientation(
                isTvDevice = false,
                preferredOrientation = 999,
            ),
        )
        // TV 端永远锁死横屏，不受手机端透传参数影响
        assertEquals(
            PlayerOrientationPolicy.ORIENTATION_LANDSCAPE,
            PlayerTransitionAndPipPolicy.resolveInitialOrientation(
                isTvDevice = true,
                preferredOrientation = PlayerTransitionAndPipPolicy.ORIENTATION_SENSOR_PORTRAIT,
            ),
        )
    }

    @Test
    fun `cross switch orientation extraction preserves manual lock or defaults to sensor landscape`() {
        assertEquals(
            PlayerTransitionAndPipPolicy.ORIENTATION_SENSOR_LANDSCAPE,
            PlayerTransitionAndPipPolicy.orientationForCrossSwitch(
                currentRequestedOrientation = PlayerTransitionAndPipPolicy.ORIENTATION_SENSOR_LANDSCAPE,
            ),
        )
        assertEquals(
            PlayerTransitionAndPipPolicy.ORIENTATION_SENSOR_PORTRAIT,
            PlayerTransitionAndPipPolicy.orientationForCrossSwitch(
                currentRequestedOrientation = PlayerTransitionAndPipPolicy.ORIENTATION_SENSOR_PORTRAIT,
            ),
        )
        assertEquals(
            PlayerOrientationPolicy.ORIENTATION_SENSOR_LANDSCAPE,
            PlayerTransitionAndPipPolicy.orientationForCrossSwitch(
                currentRequestedOrientation = PlayerTransitionAndPipPolicy.ORIENTATION_UNSPECIFIED,
            ),
        )
    }

    @Test
    fun `pip entry gate allows only mobile ready non-error non-switching state`() {
        assertTrue(
            PlayerTransitionAndPipPolicy.canEnterPip(
                isTvDevice = false,
                supportsPipFeature = true,
                isSwitchingEngine = false,
                isErrorState = false,
                isPlaybackReady = true,
                isFinishingOrDestroyed = false,
            ),
        )
        // TV 端严禁启用 PiP
        assertFalse(
            PlayerTransitionAndPipPolicy.canEnterPip(
                isTvDevice = true,
                supportsPipFeature = true,
                isSwitchingEngine = false,
                isErrorState = false,
                isPlaybackReady = true,
                isFinishingOrDestroyed = false,
            ),
        )
        // 跨引擎切台期间严禁触发 PiP（防 onUserLeaveHint 误进小窗）
        assertFalse(
            PlayerTransitionAndPipPolicy.canEnterPip(
                isTvDevice = false,
                supportsPipFeature = true,
                isSwitchingEngine = true,
                isErrorState = false,
                isPlaybackReady = true,
                isFinishingOrDestroyed = false,
            ),
        )
        // 错误态、未起播态、无系统 PiP 能力或正在销毁均禁止进 PiP
        assertFalse(
            PlayerTransitionAndPipPolicy.canEnterPip(
                isTvDevice = false,
                supportsPipFeature = true,
                isSwitchingEngine = false,
                isErrorState = true,
                isPlaybackReady = true,
                isFinishingOrDestroyed = false,
            ),
        )
        assertFalse(
            PlayerTransitionAndPipPolicy.canEnterPip(
                isTvDevice = false,
                supportsPipFeature = true,
                isSwitchingEngine = false,
                isErrorState = false,
                isPlaybackReady = false,
                isFinishingOrDestroyed = false,
            ),
        )
        assertFalse(
            PlayerTransitionAndPipPolicy.canEnterPip(
                isTvDevice = false,
                supportsPipFeature = false,
                isSwitchingEngine = false,
                isErrorState = false,
                isPlaybackReady = true,
                isFinishingOrDestroyed = false,
            ),
        )
        assertFalse(
            PlayerTransitionAndPipPolicy.canEnterPip(
                isTvDevice = false,
                supportsPipFeature = true,
                isSwitchingEngine = false,
                isErrorState = false,
                isPlaybackReady = true,
                isFinishingOrDestroyed = true,
            ),
        )
    }

    @Test
    fun `pip aspect ratio defaults to 16_9 and clamps extreme dimensions into Android valid range`() {
        assertEquals(
            PipAspectRatio(16, 9),
            PlayerTransitionAndPipPolicy.resolvePipAspectRatio(0, 0),
        )
        assertEquals(
            PipAspectRatio(16, 9),
            PlayerTransitionAndPipPolicy.resolvePipAspectRatio(-100, 720),
        )
        assertEquals(
            PipAspectRatio(1920, 1080),
            PlayerTransitionAndPipPolicy.resolvePipAspectRatio(1920, 1080),
        )
        assertEquals(
            PipAspectRatio(4, 3),
            PlayerTransitionAndPipPolicy.resolvePipAspectRatio(4, 3),
        )

        // 过宽（超过 2.39:1）必须钳制到合法上限内
        val ultraWide = PlayerTransitionAndPipPolicy.resolvePipAspectRatio(4000, 1000)
        val wideValue = ultraWide.numerator.toDouble() / ultraWide.denominator.toDouble()
        assertTrue("过宽比例必须 <= 2.39，实际 $wideValue", wideValue <= 2.39 && wideValue >= 2.30)

        // 过窄竖屏（低于 1:2.39）必须钳制到合法下限内
        val ultraTall = PlayerTransitionAndPipPolicy.resolvePipAspectRatio(1000, 4000)
        val tallValue = ultraTall.numerator.toDouble() / ultraTall.denominator.toDouble()
        assertTrue("过窄比例必须 >= 1/2.39，实际 $tallValue", tallValue >= 1.0 / 2.39 && tallValue <= 0.45)
    }

    @Test
    fun `mgtv drawer cctv precheck rejects 4k and 8k before stopping exoplayer`() {
        assertEquals(
            CrossEngineCctvPrecheck.SWITCH_TO_CCTV_WEB,
            PlayerTransitionAndPipPolicy.evaluateMgtvToCctvSwitch(
                channelId = "cctv:cctv1",
                supportedLiveUrl = CctvWebChannel.liveUrl("cctv:cctv1"),
            ),
        )
        assertEquals(
            CrossEngineCctvPrecheck.SWITCH_TO_CCTV_WEB,
            PlayerTransitionAndPipPolicy.evaluateMgtvToCctvSwitch(
                channelId = "cctv:cctv13",
                supportedLiveUrl = CctvWebChannel.liveUrl("cctv:cctv13"),
            ),
        )
        assertEquals(
            CrossEngineCctvPrecheck.UNSUPPORTED_CCTV,
            PlayerTransitionAndPipPolicy.evaluateMgtvToCctvSwitch(
                channelId = "cctv:cctv4k",
                supportedLiveUrl = CctvWebChannel.liveUrl("cctv:cctv4k"),
            ),
        )
        assertEquals(
            CrossEngineCctvPrecheck.UNSUPPORTED_CCTV,
            PlayerTransitionAndPipPolicy.evaluateMgtvToCctvSwitch(
                channelId = "cctv:cctv8k",
                supportedLiveUrl = CctvWebChannel.liveUrl("cctv:cctv8k"),
            ),
        )
        assertEquals(
            CrossEngineCctvPrecheck.NOT_CCTV,
            PlayerTransitionAndPipPolicy.evaluateMgtvToCctvSwitch(
                channelId = "mgtv:280",
                supportedLiveUrl = CctvWebChannel.liveUrl("mgtv:280"),
            ),
        )
    }
}
