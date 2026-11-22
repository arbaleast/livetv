package com.livetv.android.ui.mobile

import com.livetv.android.util.PlayerOrientationPolicy
import kotlin.math.roundToInt

/** PiP 合法宽高比（确保落入 Android 允许的 1:2.39 ~ 2.39:1 区间）。 */
data class PipAspectRatio(
    val numerator: Int,
    val denominator: Int,
)

/** 手机端 MGTV 抽屉点选频道时的 CCTV 前置校验结果。 */
enum class CrossEngineCctvPrecheck {
    NOT_CCTV,
    SWITCH_TO_CCTV_WEB,
    UNSUPPORTED_CCTV,
}

/**
 * 手机端跨引擎无感切台与画中画（PiP）策略（纯 Kotlin，可纯 JVM 单测）。
 *
 * 常量值与 `android.content.pm.ActivityInfo` 编译期常量一致，避免引入 Android 框架依赖。
 */
object PlayerTransitionAndPipPolicy {

    const val EXTRA_PREFERRED_ORIENTATION = "com.livetv.android.extra.PREFERRED_ORIENTATION"
    const val EXTRA_FROM_CROSS_SWITCH = "com.livetv.android.extra.FROM_CROSS_SWITCH"

    /** 对应 `ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED`。 */
    const val ORIENTATION_UNSPECIFIED = -1

    /** 对应 `ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE`。 */
    const val ORIENTATION_LANDSCAPE = 0

    /** 对应 `ActivityInfo.SCREEN_ORIENTATION_PORTRAIT`。 */
    const val ORIENTATION_PORTRAIT = 1

    /** 对应 `ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE`。 */
    const val ORIENTATION_SENSOR_LANDSCAPE = 6

    /** 对应 `ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT`。 */
    const val ORIENTATION_SENSOR_PORTRAIT = 7

    private const val CCTV_PREFIX = "cctv:"
    private const val DEFAULT_ASPECT_W = 16
    private const val DEFAULT_ASPECT_H = 9

    // Android PictureInPictureParams 要求比例严格落在 [1/2.39, 2.39]，取安全内界 [101/240, 238/100]
    private const val MIN_ASPECT_NUMERATOR = 101
    private const val MIN_ASPECT_DENOMINATOR = 240
    private const val MAX_ASPECT_NUMERATOR = 238
    private const val MAX_ASPECT_DENOMINATOR = 100

    private val ALLOWED_MOBILE_ORIENTATIONS = setOf(
        ORIENTATION_LANDSCAPE,
        ORIENTATION_PORTRAIT,
        ORIENTATION_SENSOR_LANDSCAPE,
        ORIENTATION_SENSOR_PORTRAIT,
        PlayerOrientationPolicy.ORIENTATION_USER_LANDSCAPE,
        PlayerOrientationPolicy.ORIENTATION_FULL_USER,
    )

    /**
     * 解析播放页初始化屏幕方向：
     * - TV 端恒定锁横屏，忽略手机端透传参数；
     * - 手机端若收到合法透传方向（例如跨源切台前锁定的横/竖屏），优先沿用，否则回退 `FULL_USER`。
     */
    fun resolveInitialOrientation(
        isTvDevice: Boolean,
        preferredOrientation: Int?,
    ): Int {
        if (isTvDevice) {
            return PlayerOrientationPolicy.resolveRequestedOrientation(isTvDevice = true)
        }
        if (preferredOrientation != null && preferredOrientation in ALLOWED_MOBILE_ORIENTATIONS) {
            return preferredOrientation
        }
        return PlayerOrientationPolicy.resolveRequestedOrientation(isTvDevice = false)
    }

    /** 跨引擎切台时提取应透传给目标 Activity 的方向值。 */
    fun orientationForCrossSwitch(currentRequestedOrientation: Int): Int =
        if (currentRequestedOrientation in ALLOWED_MOBILE_ORIENTATIONS) {
            currentRequestedOrientation
        } else {
            PlayerOrientationPolicy.resolveRequestedOrientation(isTvDevice = false)
        }

    /**
     * 画中画（PiP）进入门禁：
     * 仅手机端、系统支持 PiP、未在跨引擎切台、非错误态、已起播且未退出时允许进入。
     */
    fun canEnterPip(
        isTvDevice: Boolean,
        supportsPipFeature: Boolean,
        isSwitchingEngine: Boolean,
        isErrorState: Boolean,
        isPlaybackReady: Boolean,
        isFinishingOrDestroyed: Boolean = false,
    ): Boolean = !isTvDevice &&
        supportsPipFeature &&
        !isSwitchingEngine &&
        !isErrorState &&
        isPlaybackReady &&
        !isFinishingOrDestroyed

    /** 计算安全的 PiP 宽高比；无效尺寸默认 16:9，极端比例钳制在 Android 合法范围内。 */
    fun resolvePipAspectRatio(videoWidth: Int, videoHeight: Int): PipAspectRatio {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return PipAspectRatio(DEFAULT_ASPECT_W, DEFAULT_ASPECT_H)
        }
        val ratio = videoWidth.toDouble() / videoHeight.toDouble()
        val minRatio = MIN_ASPECT_NUMERATOR.toDouble() / MIN_ASPECT_DENOMINATOR.toDouble()
        val maxRatio = MAX_ASPECT_NUMERATOR.toDouble() / MAX_ASPECT_DENOMINATOR.toDouble()
        return when {
            ratio < minRatio -> PipAspectRatio(MIN_ASPECT_NUMERATOR, MIN_ASPECT_DENOMINATOR)
            ratio > maxRatio -> PipAspectRatio(MAX_ASPECT_NUMERATOR, MAX_ASPECT_DENOMINATOR)
            else -> {
                val safeW = videoWidth.coerceAtMost(10_000)
                val safeH = if (videoWidth > 10_000) {
                    (safeW / ratio).roundToInt().coerceAtLeast(1)
                } else {
                    videoHeight
                }
                PipAspectRatio(safeW, safeH)
            }
        }
    }

    /**
     * 手机端 MGTV 抽屉点击频道时对 `cctv:` 频道做前置校验：
     * 不支持的 4K/8K 直接拒绝，不停止当前 ExoPlayer、不白跳 `CctvWebPlayerActivity`。
     */
    fun evaluateMgtvToCctvSwitch(
        channelId: String,
        supportedLiveUrl: String?,
    ): CrossEngineCctvPrecheck {
        val normalized = channelId.trim().lowercase()
        if (!normalized.startsWith(CCTV_PREFIX)) {
            return CrossEngineCctvPrecheck.NOT_CCTV
        }
        return if (!supportedLiveUrl.isNullOrBlank()) {
            CrossEngineCctvPrecheck.SWITCH_TO_CCTV_WEB
        } else {
            CrossEngineCctvPrecheck.UNSUPPORTED_CCTV
        }
    }
}
