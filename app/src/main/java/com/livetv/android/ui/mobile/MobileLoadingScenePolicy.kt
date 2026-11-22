package com.livetv.android.ui.mobile

/**
 * 手机端双引擎四场景加载分派枚举（纯 Kotlin，可纯 JVM 单测）。
 */
enum class MobileLoadingScene {
    /** 场景 A：MGTV 切台（共享虚化尾帧 + 大字台名 + 3 段柔光呼吸进度条 + 底部无框字幕 Tip 统一视觉骨架，采用快切节奏）。 */
    SCENE_A_MGTV_SWITCH,

    /** 场景 B：CCTV 同页切台（主文案 + 3 段真实信号进度与柔光呼吸 + 可选 EPG 信息卡 + 底部无框字幕 Tips）。 */
    SCENE_B_CCTV_SWITCH,

    /** 场景 C：MGTV -> CCTV 跨源切台（主文案 + 副标题「正在切换信号源」+ 4 段进度含前置 RELEASING + 底部无框字幕 Tips）。 */
    SCENE_C_CROSS_ENGINE_TO_CCTV,

    /** 场景 D：初次冷启动进入直播（主文案「正在进入直播」+ 首次开机副标题 + 4 段进度含前置 INIT_PLAYER + 冷启专属 Tips）。 */
    SCENE_D_COLD_START,
}

/**
 * 真实信号驱动（辅以合理等待窗口缓步保底）的单调递增加载阶段（严禁倒退）。
 */
enum class LoadStage(val order: Int) {
    /** 场景 C 前置阶段：正在送走上一路信号… */
    RELEASING(10),

    /** 场景 D 前置阶段：正在预热电视机… */
    INIT_PLAYER(10),

    /** 阶段 1：发起频道会话/VDN 请求（正在向演播室要信号…）。 */
    CONNECTING(20),

    /** 阶段 2：收到首个媒体清单/切片或播放器进入缓冲（信号已接通，正在缓冲画面…）。 */
    STREAMING(30),

    /** 阶段 3：视频元素就绪或解码冲刺（画面马上出来…）。 */
    DECODING(40),
}

/**
 * 单一加载场景的静态规格快照。
 */
data class MobileLoadingSceneSpec(
    val scene: MobileLoadingScene,
    val showStageProgress: Boolean,
    val segmentCount: Int,
    val initialStage: LoadStage?,
    val allowProgramInfo: Boolean,
    val showTips: Boolean,
    val tipInitialDelayMs: Long,
    val tipRotateIntervalMs: Long,
    val tipPoolSize: Int,
    val reconnectHintDelayMs: Long?,
)

/**
 * 手机端四场景加载策略、单调阶段状态机、柔光呼吸分段计算、缓步推进窗口、只读网络信号分类与 Tips 避重随机抽取器（纯 Kotlin）。
 */
object MobileLoadingScenePolicy {

    const val MGTV_RECONNECT_HINT_DELAY_MS: Long = 1_500L
    const val CCTV_RECONNECT_HINT_DELAY_MS: Long = 4_200L
    const val SWITCH_TIP_INITIAL_DELAY_MS: Long = 0L
    const val SWITCH_TIP_ROTATE_INTERVAL_MS: Long = 4_000L
    const val COLD_TIP_INITIAL_DELAY_MS: Long = 0L
    const val COLD_TIP_ROTATE_INTERVAL_MS: Long = 5_000L

    const val SWITCH_TIP_POOL_SIZE: Int = 8
    const val COLD_TIP_POOL_SIZE: Int = 4

    /** 统一文案、阶段、Tips 与遮罩淡入淡出时长（200ms，配合 AccelerateDecelerateInterpolator）。 */
    const val FADE_DURATION_MS: Long = 200L

    /** 最短展示保护窗口，杜绝秒切时“闪一下就没”的生硬跳变。 */
    const val MIN_OVERLAY_DISPLAY_MS: Long = 240L

    /** 当前活跃分段柔光呼吸最低透明度。 */
    const val SEGMENT_BREATH_MIN_ALPHA: Float = 0.65f

    /** 当前活跃分段柔光呼吸最高透明度。 */
    const val SEGMENT_BREATH_MAX_ALPHA: Float = 1.0f

    /** 当前活跃分段柔光呼吸完整往复周期（毫秒，单程 450ms REVERSE）。 */
    const val SEGMENT_BREATH_CYCLE_MS: Long = 900L

    /**
     * 判定 CCTV 播放页当前加载应进入的场景：
     * - 从 MGTV 跨源切入（`EXTRA_FROM_CROSS_SWITCH == true`）-> [MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV]
     * - 同页切台 / 刷新或已有尾帧 -> [MobileLoadingScene.SCENE_B_CCTV_SWITCH]
     * - 初次冷启动且无尾帧 -> [MobileLoadingScene.SCENE_D_COLD_START]
     */
    fun resolveCctvEntryScene(
        fromCrossEngineSwitch: Boolean,
        isSamePageSwitch: Boolean,
        hasLastFrame: Boolean,
    ): MobileLoadingScene = when {
        fromCrossEngineSwitch -> MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV
        isSamePageSwitch || hasLastFrame -> MobileLoadingScene.SCENE_B_CCTV_SWITCH
        else -> MobileLoadingScene.SCENE_D_COLD_START
    }

    fun specFor(scene: MobileLoadingScene): MobileLoadingSceneSpec = when (scene) {
        MobileLoadingScene.SCENE_A_MGTV_SWITCH -> MobileLoadingSceneSpec(
            scene = scene,
            showStageProgress = true,
            segmentCount = 3,
            initialStage = LoadStage.CONNECTING,
            allowProgramInfo = true,
            showTips = true,
            tipInitialDelayMs = SWITCH_TIP_INITIAL_DELAY_MS,
            tipRotateIntervalMs = SWITCH_TIP_ROTATE_INTERVAL_MS,
            tipPoolSize = SWITCH_TIP_POOL_SIZE,
            reconnectHintDelayMs = MGTV_RECONNECT_HINT_DELAY_MS,
        )

        MobileLoadingScene.SCENE_B_CCTV_SWITCH -> MobileLoadingSceneSpec(
            scene = scene,
            showStageProgress = true,
            segmentCount = 3,
            initialStage = LoadStage.CONNECTING,
            allowProgramInfo = true,
            showTips = true,
            tipInitialDelayMs = SWITCH_TIP_INITIAL_DELAY_MS,
            tipRotateIntervalMs = SWITCH_TIP_ROTATE_INTERVAL_MS,
            tipPoolSize = SWITCH_TIP_POOL_SIZE,
            reconnectHintDelayMs = CCTV_RECONNECT_HINT_DELAY_MS,
        )

        MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV -> MobileLoadingSceneSpec(
            scene = scene,
            showStageProgress = true,
            segmentCount = 4,
            initialStage = LoadStage.RELEASING,
            allowProgramInfo = true,
            showTips = true,
            tipInitialDelayMs = SWITCH_TIP_INITIAL_DELAY_MS,
            tipRotateIntervalMs = SWITCH_TIP_ROTATE_INTERVAL_MS,
            tipPoolSize = SWITCH_TIP_POOL_SIZE,
            reconnectHintDelayMs = CCTV_RECONNECT_HINT_DELAY_MS,
        )

        MobileLoadingScene.SCENE_D_COLD_START -> MobileLoadingSceneSpec(
            scene = scene,
            showStageProgress = true,
            segmentCount = 4,
            initialStage = LoadStage.INIT_PLAYER,
            allowProgramInfo = true,
            showTips = true,
            tipInitialDelayMs = COLD_TIP_INITIAL_DELAY_MS,
            tipRotateIntervalMs = COLD_TIP_ROTATE_INTERVAL_MS,
            tipPoolSize = COLD_TIP_POOL_SIZE,
            reconnectHintDelayMs = CCTV_RECONNECT_HINT_DELAY_MS,
        )
    }

    /**
     * 计算极细分段进度条当前应点亮的段数（严禁倒退）：
     * - 场景 A / B（共 3 段）：null -> 0，CONNECTING -> 1，STREAMING -> 2，DECODING -> 3
     * - 场景 C / D（共 4 段）：RELEASING/INIT_PLAYER -> 1，CONNECTING -> 2，STREAMING -> 3，DECODING -> 4
     */
    fun litSegmentCount(scene: MobileLoadingScene, stage: LoadStage?): Int = when (scene) {
        MobileLoadingScene.SCENE_A_MGTV_SWITCH,
        MobileLoadingScene.SCENE_B_CCTV_SWITCH,
        -> when (stage) {
            null, LoadStage.RELEASING, LoadStage.INIT_PLAYER -> 0
            LoadStage.CONNECTING -> 1
            LoadStage.STREAMING -> 2
            LoadStage.DECODING -> 3
        }
        MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV,
        MobileLoadingScene.SCENE_D_COLD_START,
        -> when (stage) {
            null -> 0
            LoadStage.RELEASING, LoadStage.INIT_PLAYER -> 1
            LoadStage.CONNECTING -> 2
            LoadStage.STREAMING -> 3
            LoadStage.DECODING -> 4
        }
    }

    /**
     * 计算当前应施加柔光呼吸动效（0.65f ~ 1.0f, 900ms 周期）的活跃分段下标：
     * - `< activeIndex` 的已完成分段保持常亮 `1.0f`；
     * - `== activeIndex` 的当前推进分段做 `0.65f ~ 1.0f` 柔光往复呼吸；
     * - `> activeIndex` 的未达分段保持 `0.22f`。
     */
    fun activeBreathingSegmentIndex(scene: MobileLoadingScene, stage: LoadStage?): Int {
        val total = specFor(scene).segmentCount
        if (total <= 0) return -1
        val lit = litSegmentCount(scene, stage)
        return (lit - 1).coerceIn(0, total - 1)
    }

    /**
     * 单调递增阶段状态机：仅当场景启用阶段进度且 `candidate.order > currentStage.order` 时推进，绝不倒退。
     */
    fun advanceStage(
        scene: MobileLoadingScene,
        currentStage: LoadStage?,
        candidate: LoadStage,
    ): LoadStage? {
        if (!specFor(scene).showStageProgress) return null
        if (currentStage == null) return candidate
        return if (candidate.order > currentStage.order) candidate else currentStage
    }

    /**
     * 当某阶段超过合理等待窗口仍未收到下一真实信号时，在不超过下一阶段上限的前提下
     * 返回允许缓步推进的目标阶段（已达 DECODING 上限时返回 null，绝不越界）。
     */
    fun nextSoftFallbackStage(
        scene: MobileLoadingScene,
        currentStage: LoadStage?,
    ): LoadStage? {
        if (!specFor(scene).showStageProgress) return null
        return when (currentStage) {
            null -> specFor(scene).initialStage ?: LoadStage.CONNECTING
            LoadStage.RELEASING, LoadStage.INIT_PLAYER -> LoadStage.CONNECTING
            LoadStage.CONNECTING -> LoadStage.STREAMING
            LoadStage.STREAMING -> LoadStage.DECODING
            LoadStage.DECODING -> null
        }
    }

    /**
     * 各场景在当前阶段等待下一信号的合理超时窗口（毫秒）：
     * - MGTV 保持快切节奏（420ms -> 520ms）；
     * - CCTV 保持信号阶段推进节奏（750ms -> 1100ms -> 1400ms）。
     */
    fun softAdvanceDelayMs(
        scene: MobileLoadingScene,
        currentStage: LoadStage?,
    ): Long? {
        if (nextSoftFallbackStage(scene, currentStage) == null) return null
        return when (scene) {
            MobileLoadingScene.SCENE_A_MGTV_SWITCH -> when (currentStage) {
                null, LoadStage.RELEASING, LoadStage.INIT_PLAYER, LoadStage.CONNECTING -> 420L
                LoadStage.STREAMING -> 520L
                LoadStage.DECODING -> null
            }
            MobileLoadingScene.SCENE_B_CCTV_SWITCH,
            MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV,
            MobileLoadingScene.SCENE_D_COLD_START,
            -> when (currentStage) {
                null, LoadStage.RELEASING, LoadStage.INIT_PLAYER -> 750L
                LoadStage.CONNECTING -> 1_100L
                LoadStage.STREAMING -> 1_400L
                LoadStage.DECODING -> null
            }
        }
    }

    /**
     * 从 `shouldInterceptRequest` 只读观察到的 URL host/path 提取真实网络阶段信号：
     * - `vdnx.live.cntv.cn` / `vdn.apps.cntv.cn` -> [LoadStage.CONNECTING]
     * - `.m3u8` / `.ts` -> [LoadStage.STREAMING]
     * - 其余子资源返回 `null`。
     */
    fun classifyCctvNetworkSignal(host: String?, path: String?): LoadStage? {
        val h = host?.trim()?.lowercase().orEmpty()
        val p = path?.trim()?.lowercase().orEmpty()
        if (h.isEmpty()) return null
        if (p.endsWith(".m3u8") || p.endsWith(".ts")) {
            return LoadStage.STREAMING
        }
        if (h == "vdnx.live.cntv.cn" || h == "vdn.apps.cntv.cn") {
            return LoadStage.CONNECTING
        }
        return null
    }

    /**
     * 避重随机抽取下一条炉石风 Tip 索引：当 `poolSize > 1` 且 `lastIndex in 0 until poolSize` 时，
     * 100% 保证连续两次不返回相同索引。
     */
    fun pickNextTipIndex(
        poolSize: Int,
        lastIndex: Int,
        randomInt: (bound: Int) -> Int,
    ): Int {
        if (poolSize <= 0) return -1
        if (poolSize == 1) return 0
        if (lastIndex !in 0 until poolSize) {
            return randomInt(poolSize).coerceIn(0, poolSize - 1)
        }
        val offset = randomInt(poolSize - 1).coerceIn(0, poolSize - 2)
        return if (offset >= lastIndex) offset + 1 else offset
    }

    /** 校验异步阶段信号或计时器回调是否仍属于当前活跃切台代次。 */
    fun isGenerationValid(activeGeneration: Long, callbackGeneration: Long): Boolean =
        activeGeneration > 0L && activeGeneration == callbackGeneration
}
