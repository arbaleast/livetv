package com.livetv.android.util

/**
 * 播放器实时画质增强（AI 轻量 CNN 超分）档位。
 *
 * - [OFF]：完全关闭，`ExoPlayer` 不挂载任何 `VideoEffect`（保留原生 `SurfaceView api=3` 硬件直出与系统 PQ/SR）。
 * - [STANDARD]：Anime4K `Restore_CNN_M + Upscale_CNN_x2_M`（17 Pass，PKG110 实测 360p ~7.9ms / 540p ~15.4ms）。
 * - [STRONG]：Anime4K `Restore_CNN_L + Upscale_CNN_x2_M`（18 Pass，PKG110 实测 360p ~11.5ms / 540p ~21.9ms）。
 */
enum class VideoEnhanceMode(val storageValue: String) {
    OFF("off"),
    STANDARD("standard"),
    STRONG("strong"),
    ;

    fun next(): VideoEnhanceMode = when (this) {
        OFF -> STANDARD
        STANDARD -> STRONG
        STRONG -> OFF
    }

    companion object {
        fun fromStorageValue(raw: String?): VideoEnhanceMode =
            entries.firstOrNull { it.storageValue == raw } ?: OFF
    }
}

enum class VideoEnhanceStatus {
    OFF,
    ACTIVE_STANDARD,
    ACTIVE_STRONG,
    BYPASSED_HIGH_RES,
}

/**
 * 实时 CNN 超分策略判定（纯 Kotlin，无 Android/Media3 依赖）。
 */
object VideoEnhancePolicy {

    private const val BYPASS_MIN_WIDTH = 1920
    private const val BYPASS_MIN_HEIGHT = 1080
    private const val FOUR_X_MAX_WIDTH = 960
    private const val FOUR_X_MAX_HEIGHT = 480

    /**
     * 仅当档位非 [VideoEnhanceMode.OFF] 时，才在播放器启动阶段挂载 VideoEffect；
     * [VideoEnhanceMode.OFF] 启动期绝对不调用 `setVideoEffects(...)`，防止把原生 `api=3` 降级为 `api=1` EGL。
     */
    fun shouldApplyOnPlayerStart(mode: VideoEnhanceMode): Boolean =
        mode != VideoEnhanceMode.OFF

    /**
     * `>= 1080p` 高清源或无效尺寸自动直通跳过 2x CNN 推理，避免浪费 GPU 算力。
     */
    fun shouldBypassForSource(width: Int, height: Int): Boolean =
        width <= 0 || height <= 0 || width >= BYPASS_MIN_WIDTH || height >= BYPASS_MIN_HEIGHT

    /** 返回当前源尺寸下超分引擎的实际生效状态。 */
    fun resolveStatus(mode: VideoEnhanceMode, inputWidth: Int, inputHeight: Int): VideoEnhanceStatus =
        when (mode) {
            VideoEnhanceMode.OFF -> VideoEnhanceStatus.OFF
            VideoEnhanceMode.STANDARD ->
                if (shouldBypassForSource(inputWidth, inputHeight)) VideoEnhanceStatus.BYPASSED_HIGH_RES
                else VideoEnhanceStatus.ACTIVE_STANDARD
            VideoEnhanceMode.STRONG ->
                if (shouldBypassForSource(inputWidth, inputHeight)) VideoEnhanceStatus.BYPASSED_HIGH_RES
                else VideoEnhanceStatus.ACTIVE_STRONG
        }

    /** 将 MIME 类型映射为简短编码名称。 */
    fun formatCodecLabel(mimeType: String?): String = when (mimeType?.lowercase()) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9", "video/vp9" -> "VP9"
        else -> mimeType?.substringAfter('/')?.uppercase() ?: "未知"
    }

    /**
     * `<= 480p`（如 640×360、854×480）低清源启用两级级联 4x 超分（直出 1440p/1920p），
     * 540p/720p 源走单级 2x 超分。
     */
    fun shouldUse4xForSource(width: Int, height: Int): Boolean =
        width in 1..FOUR_X_MAX_WIDTH && height in 1..FOUR_X_MAX_HEIGHT

    /** 返回当前源尺寸下超分引擎的放大倍数（1 / 2 / 4）。 */
    fun scaleFactorForSource(mode: VideoEnhanceMode, width: Int, height: Int): Int {
        if (mode == VideoEnhanceMode.OFF || shouldBypassForSource(width, height)) return 1
        return if (shouldUse4xForSource(width, height)) 4 else 2
    }

    /** 返回对应档位 2x 预编译 Shader 清单的 assets 路径；[VideoEnhanceMode.OFF] 返回 `null`。 */
    fun shaderAssetPath(mode: VideoEnhanceMode): String? = when (mode) {
        VideoEnhanceMode.OFF -> null
        VideoEnhanceMode.STANDARD -> "shaders/a4k_standard.passes"
        VideoEnhanceMode.STRONG -> "shaders/a4k_strong.passes"
    }

    /** 返回对应档位 4x 预编译 Shader 清单的 assets 路径；[VideoEnhanceMode.OFF] 返回 `null`。 */
    fun shader4xAssetPath(mode: VideoEnhanceMode): String? = when (mode) {
        VideoEnhanceMode.OFF -> null
        VideoEnhanceMode.STANDARD -> "shaders/a4k_standard_4x.passes"
        VideoEnhanceMode.STRONG -> "shaders/a4k_strong_4x.passes"
    }
}
