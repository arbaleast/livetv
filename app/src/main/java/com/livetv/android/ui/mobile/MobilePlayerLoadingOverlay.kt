package com.livetv.android.ui.mobile

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.livetv.android.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 手机端双引擎（MGTV 原生页 + CCTV WebView 页）共享的「全屏去框化电影排版风」四场景加载组件（TV 端零触碰）。
 *
 * 核心体感与排版规范：
 * 1. **双端统一视觉骨架**：MGTV 与 CCTV 切台统一采用「虚化尾帧 + 居中大字台名（24sp Bold）+ 极细分段进度条 + 阶段提示 + 底部无框字幕式 Tip」，
 *    消除双端切台割裂感；同时保持 MGTV 快切节奏与 CCTV 信号阶段节奏的差异化响应；
 * 2. **进度条活性（真实信号 + 当前段柔光呼吸 + 合理等待窗口缓步保底）**：
 *    - 当前正在推进的分段施加 `0.65f ~ 1.0f`、`900ms` 周期的 GPU 属性柔光呼吸动效（[segmentBreathAnimator]）；
 *    - 收到真实网络/播放器信号立即单调推进；若某阶段等待超过合理窗口未收到下一信号，在不越过上限的前提下克制缓步推进；
 * 3. **丝滑过渡曲线与时序**：统一采用 `200ms` + [AccelerateDecelerateInterpolator]，并提供最短展示保护窗口杜绝“闪一下就没”；
 * 4. **撤幕资源回收闭环**：`withEndAction` 严格执行 `View.GONE`、`setRenderEffect(null)`、`setImageDrawable(null)`、`bitmap.recycle()` 并取消/置空全部计时器与动画。
 */
internal class MobilePlayerLoadingOverlay(
    private val context: Context,
    parentRoot: FrameLayout,
    chromeRoot: View,
    private val onOverlayTap: () -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val smoothInterpolator = AccelerateDecelerateInterpolator()

    val lastFrameImageView: ImageView
    private val scrimContainer: FrameLayout
    private val centerColumn: LinearLayout
    private val mainTitleView: TextView
    private val subtitleView: TextView
    private val segmentBarContainer: LinearLayout
    private val segmentViews: List<View>
    private val fallbackSpinnerView: ProgressBar
    private val stageView: TextView
    private val programInfoCard: LinearLayout
    private val nowPlayingView: TextView
    private val nextProgramView: TextView
    private val tipsView: TextView

    private var lastFrameBitmap: Bitmap? = null
    private var pixelCopyTimeoutRunnable: Runnable? = null
    private var reconnectHintRunnable: Runnable? = null
    private var tipsRotateRunnable: Runnable? = null
    private var softStageAdvanceRunnable: Runnable? = null
    private var deferredHideRunnable: Runnable? = null
    private var segmentBreathAnimator: ObjectAnimator? = null
    private var breathingSegmentIndex: Int = -1

    private var activeGeneration: Long = 0L
    private var activeScene: MobileLoadingScene = MobileLoadingScene.SCENE_A_MGTV_SWITCH
    private var currentStage: LoadStage? = null
    private var lastTipIndex: Int = -1
    private var lastShownUptimeMs: Long = 0L
    private var isHiding: Boolean = false
    private var isChannelSwitching: Boolean = false

    val isOverlayVisible: Boolean
        get() = scrimContainer.visibility == View.VISIBLE || lastFrameImageView.visibility == View.VISIBLE

    init {
        val chromeIndex = parentRoot.indexOfChild(chromeRoot).takeIf { it >= 0 } ?: parentRoot.childCount

        lastFrameImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        parentRoot.addView(lastFrameImageView, chromeIndex, matchParent())

        mainTitleView = TextView(context).apply {
            setTextColor(TITLE_TEXT_COLOR)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            applyTextShadow()
        }

        subtitleView = TextView(context).apply {
            setTextColor(SUBTITLE_TEXT_COLOR)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            applyTextShadow()
            visibility = View.GONE
        }

        val segHeightPx = maxOf(2, dp(3))
        val segGapPx = dp(5)
        val segRadiusPx = dpFloat(1.5f)
        val builtSegments = mutableListOf<View>()
        segmentBarContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            repeat(MAX_SEGMENT_COUNT) { idx ->
                val seg = View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = segRadiusPx
                        setColor(Color.WHITE)
                    }
                    alpha = SEGMENT_UNLIT_ALPHA
                }
                val lp = LinearLayout.LayoutParams(0, segHeightPx, 1f).apply {
                    if (idx > 0) marginStart = segGapPx
                }
                addView(seg, lp)
                builtSegments += seg
            }
        }
        segmentViews = builtSegments

        val fallbackSpinnerSize = dp(16)
        fallbackSpinnerView = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
        }

        stageView = TextView(context).apply {
            setTextColor(STAGE_TEXT_COLOR)
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            applyTextShadow()
            visibility = View.GONE
        }

        nowPlayingView = TextView(context).apply {
            setTextColor(STAGE_TEXT_COLOR)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            applyTextShadow()
        }
        nextProgramView = TextView(context).apply {
            setTextColor(SUBTITLE_TEXT_COLOR)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            applyTextShadow()
            visibility = View.GONE
        }
        programInfoCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(
                nowPlayingView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                nextProgramView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(2)
                },
            )
        }

        centerColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                mainTitleView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                subtitleView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(6)
                },
            )
            addView(
                segmentBarContainer,
                LinearLayout.LayoutParams(dp(140), segHeightPx).apply {
                    topMargin = dp(14)
                },
            )
            addView(
                fallbackSpinnerView,
                LinearLayout.LayoutParams(fallbackSpinnerSize, fallbackSpinnerSize).apply {
                    topMargin = dp(14)
                },
            )
            addView(
                stageView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12)
                },
            )
            addView(
                programInfoCard,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12)
                },
            )
        }

        tipsView = TextView(context).apply {
            setTextColor(TIPS_TEXT_COLOR)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            applyTextShadow()
            visibility = View.GONE
        }

        scrimContainer = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener { onOverlayTap() }
            addView(
                centerColumn,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ).apply {
                    marginStart = dp(24)
                    marginEnd = dp(24)
                },
            )
            addView(
                tipsView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply {
                    marginStart = dp(32)
                    marginEnd = dp(32)
                    bottomMargin = dp(28)
                },
            )
        }
        parentRoot.addView(scrimContainer, chromeIndex + 1, matchParent())
        chromeRoot.bringToFront()
    }

    // ---------------- CCTV 四场景入口（场景 B / C / D） ----------------

    /**
     * 启动 CCTV 加载场景（场景 B 同页切台 / 场景 C 跨源切台 / 场景 D 初次冷启动）。
     */
    fun showForCctvScene(
        scene: MobileLoadingScene,
        channelName: String,
        generation: Long,
        translucentForLastFrame: Boolean,
        animateEntry: Boolean = true,
    ) {
        activeGeneration = generation
        activeScene = scene
        isHiding = false
        isChannelSwitching = true
        lastShownUptimeMs = SystemClock.uptimeMillis()
        cancelTimersOnly()

        if (!translucentForLastFrame) {
            clearLastFrameView()
        }
        val hasLastFrame = translucentForLastFrame && hasVisibleLastFrame()
        val scrimColor = if (hasLastFrame) {
            blurScrimColor()
        } else {
            Color.BLACK
        }
        val titleText = when (scene) {
            MobileLoadingScene.SCENE_D_COLD_START -> context.getString(R.string.player_entering_live)
            else -> channelName.ifBlank { context.getString(R.string.player_entering_live) }
        }
        val subtitleText = when (scene) {
            MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV ->
                context.getString(R.string.subtitle_switching_source)
            MobileLoadingScene.SCENE_D_COLD_START ->
                context.getString(R.string.player_first_load_hint)
            else -> null
        }

        val spec = MobileLoadingScenePolicy.specFor(scene)
        currentStage = spec.initialStage
        val litCount = MobileLoadingScenePolicy.litSegmentCount(scene, currentStage)
        val breathingIdx = MobileLoadingScenePolicy.activeBreathingSegmentIndex(scene, currentStage)
        bindCenterTypographyImmediate(
            title = titleText,
            subtitle = subtitleText,
            stage = currentStage?.let(::stageTextFor),
        )
        configureSegmentBarImmediate(
            totalSegments = spec.segmentCount,
            litCount = litCount,
            breathingIndex = breathingIdx,
        )
        Log.i(TAG, "scene start gen=$generation scene=$scene stage=$currentStage lit=$litCount breathIdx=$breathingIdx")
        resetProgramInfoCard()
        showScrim(scrimColor = scrimColor, hasLastFrame = hasLastFrame, animateEntry = animateEntry)
        scheduleSceneTimers(spec, generation)
        scheduleSoftStageAdvance(generation)
    }

    /**
     * 挂载 CCTV 或跨引擎移交的上一台尾帧 Bitmap（含 API 31+ RenderEffect 24f 高斯模糊）。
     */
    fun applyLastFrameBitmap(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        clearLastFrameView()
        lastFrameBitmap = bitmap
        lastFrameImageView.setImageBitmap(bitmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lastFrameImageView.setRenderEffect(
                RenderEffect.createBlurEffect(
                    LAST_FRAME_BLUR_RADIUS_PX,
                    LAST_FRAME_BLUR_RADIUS_PX,
                    Shader.TileMode.CLAMP,
                ),
            )
        }
        lastFrameImageView.alpha = 1f
        lastFrameImageView.visibility = View.VISIBLE
    }

    /**
     * 接收真实信号（或合理等待窗口缓步保底）推进阶段：仅当前代次且单调递增时，
     * 以 200ms AccelerateDecelerateInterpolator 平滑点亮分段进度条、切换当前呼吸段并淡入切换阶段文案。
     */
    fun onStageSignal(generation: Long, candidate: LoadStage, fromSoftWatchdog: Boolean = false) {
        if (!MobileLoadingScenePolicy.isGenerationValid(activeGeneration, generation)) return
        if (isHiding || scrimContainer.visibility != View.VISIBLE) return
        val next = MobileLoadingScenePolicy.advanceStage(activeScene, currentStage, candidate) ?: return
        if (next == currentStage && stageView.visibility == View.VISIBLE) return
        currentStage = next
        val litCount = MobileLoadingScenePolicy.litSegmentCount(activeScene, next)
        val breathingIdx = MobileLoadingScenePolicy.activeBreathingSegmentIndex(activeScene, next)
        animateSegmentBarTo(litCount = litCount, breathingIndex = breathingIdx)
        val text = stageTextFor(next)
        val source = if (fromSoftWatchdog) "soft" else "real"
        Log.i(TAG, "stage advance ($source) gen=$generation scene=$activeScene stage=$next lit=$litCount breathIdx=$breathingIdx")
        crossfadeTextView(stageView, text)
        scheduleSoftStageAdvance(generation)
    }

    /**
     * 当真实阶段信号无法细分时，隐藏极细分段条并回退为居中的 16dp 纯白极简细环。
     */
    fun fallbackToIndeterminate(generation: Long = activeGeneration) {
        if (!MobileLoadingScenePolicy.isGenerationValid(activeGeneration, generation)) return
        if (isHiding || scrimContainer.visibility != View.VISIBLE) return
        stopSegmentBreathing()
        segmentViews.forEach { it.animate().cancel() }
        segmentBarContainer.visibility = View.GONE
        fadeInView(fallbackSpinnerView)
    }

    /**
     * 本地 EPG 缓存命中时绑定无框当前/下档节目信息；缓存 miss（`nowPlaying` 为空）时整块保持 `View.GONE`。
     */
    fun bindProgramInfo(
        nowPlaying: String?,
        nextProgram: String? = null,
        generation: Long = activeGeneration,
    ) {
        if (!MobileLoadingScenePolicy.isGenerationValid(activeGeneration, generation)) return
        val spec = MobileLoadingScenePolicy.specFor(activeScene)
        if (!spec.allowProgramInfo || nowPlaying.isNullOrBlank()) {
            programInfoCard.animate().cancel()
            programInfoCard.visibility = View.GONE
            return
        }
        nowPlayingView.text = context.getString(R.string.info_now_playing, nowPlaying.trim())
        if (!nextProgram.isNullOrBlank()) {
            nextProgramView.text = context.getString(R.string.info_next_program, nextProgram.trim())
            nextProgramView.visibility = View.VISIBLE
        } else {
            nextProgramView.visibility = View.GONE
        }
        fadeInView(programInfoCard)
    }

    // ---------------- MGTV 场景 A 入口（与 CCTV 共享统一视觉骨架） ----------------

    fun applyCrossEngineLastFrame(bitmap: Bitmap, channelName: String) {
        if (bitmap.isRecycled) return
        val generation = nextInternalGeneration()
        cancelPendingPixelCopy()
        applyLastFrameBitmap(bitmap)
        enterSceneA(
            channelName = channelName,
            generation = generation,
            scrimColor = blurScrimColor(),
            fromCrossEngine = true,
            animateEntry = false,
        )
    }

    fun beginChannelSwitch(
        surfaceView: SurfaceView?,
        wasPlaying: Boolean,
        inPictureInPicture: Boolean,
        channelName: String,
        onReadyToSwitch: () -> Unit,
    ) {
        val generation = nextInternalGeneration()
        cancelPendingPixelCopy()
        isHiding = false
        isChannelSwitching = true

        if (inPictureInPicture) {
            clearImmediately()
            onReadyToSwitch()
            return
        }

        val keepExistingLastFrame = !wasPlaying && hasVisibleLastFrame()
        if (keepExistingLastFrame) {
            enterSceneA(
                channelName = channelName,
                generation = generation,
                scrimColor = blurScrimColor(),
                fromCrossEngine = false,
                animateEntry = false,
            )
            onReadyToSwitch()
            return
        }

        val canCapture = wasPlaying &&
            surfaceView != null &&
            surfaceView.visibility == View.VISIBLE &&
            surfaceView.width > 0 &&
            surfaceView.height > 0 &&
            surfaceView.holder?.surface?.isValid == true

        if (!canCapture) {
            clearLastFrameView()
            enterSceneA(
                channelName = channelName,
                generation = generation,
                scrimColor = Color.BLACK,
                fromCrossEngine = false,
                animateEntry = true,
            )
            onReadyToSwitch()
            return
        }

        val w = surfaceView!!.width
        val h = surfaceView.height
        val scale = minOf(1f, MAX_LAST_FRAME_EDGE_PX.toFloat() / maxOf(w, h).toFloat())
        val dstW = maxOf(1, (w * scale).toInt())
        val dstH = maxOf(1, (h * scale).toInt())
        val bitmap = runCatching {
            Bitmap.createBitmap(dstW, dstH, Bitmap.Config.RGB_565)
        }.getOrNull()

        if (bitmap == null) {
            clearLastFrameView()
            enterSceneA(
                channelName = channelName,
                generation = generation,
                scrimColor = Color.BLACK,
                fromCrossEngine = false,
                animateEntry = true,
            )
            onReadyToSwitch()
            return
        }

        val completed = AtomicBoolean(false)
        val startNs = System.nanoTime()
        val timeoutTask = Runnable {
            if (!completed.compareAndSet(false, true)) return@Runnable
            pixelCopyTimeoutRunnable = null
            if (!MobileLoadingScenePolicy.isGenerationValid(activeGeneration, generation)) return@Runnable
            Log.w(TAG, "mgtv pixelCopy timeout gen=$generation -> fallback black scrim")
            clearLastFrameView()
            enterSceneA(
                channelName = channelName,
                generation = generation,
                scrimColor = Color.BLACK,
                fromCrossEngine = false,
                animateEntry = true,
            )
            onReadyToSwitch()
        }
        pixelCopyTimeoutRunnable = timeoutTask
        mainHandler.postDelayed(timeoutTask, PIXEL_COPY_TIMEOUT_MS)

        val requested = runCatching {
            PixelCopy.request(
                surfaceView,
                bitmap,
                { copyResult ->
                    if (!completed.compareAndSet(false, true)) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return@request
                    }
                    mainHandler.removeCallbacks(timeoutTask)
                    if (pixelCopyTimeoutRunnable === timeoutTask) {
                        pixelCopyTimeoutRunnable = null
                    }
                    if (!MobileLoadingScenePolicy.isGenerationValid(activeGeneration, generation)) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return@request
                    }
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    val nonBlack = copyResult == PixelCopy.SUCCESS && isBitmapNonBlack(bitmap)
                    if (nonBlack) {
                        Log.i(
                            TAG,
                            "mgtv pixelCopy ok gen=$generation w=${bitmap.width} h=${bitmap.height} ms=$elapsedMs nonBlack=true",
                        )
                        applyLastFrameBitmap(bitmap)
                        enterSceneA(
                            channelName = channelName,
                            generation = generation,
                            scrimColor = blurScrimColor(),
                            fromCrossEngine = false,
                            animateEntry = true,
                        )
                    } else {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        Log.w(
                            TAG,
                            "mgtv pixelCopy fallback gen=$generation result=$copyResult ms=$elapsedMs",
                        )
                        clearLastFrameView()
                        enterSceneA(
                            channelName = channelName,
                            generation = generation,
                            scrimColor = Color.BLACK,
                            fromCrossEngine = false,
                            animateEntry = true,
                        )
                    }
                    onReadyToSwitch()
                },
                mainHandler,
            )
            true
        }.getOrDefault(false)

        if (!requested && completed.compareAndSet(false, true)) {
            mainHandler.removeCallbacks(timeoutTask)
            if (pixelCopyTimeoutRunnable === timeoutTask) {
                pixelCopyTimeoutRunnable = null
            }
            if (!bitmap.isRecycled) bitmap.recycle()
            clearLastFrameView()
            enterSceneA(
                channelName = channelName,
                generation = generation,
                scrimColor = Color.BLACK,
                fromCrossEngine = false,
                animateEntry = true,
            )
            onReadyToSwitch()
        }
    }

    fun prepareCrossEngineHandoff(
        surfaceView: SurfaceView?,
        wasPlaying: Boolean,
        inPictureInPicture: Boolean,
        channelName: String,
        onReady: (handoffBitmap: Bitmap?) -> Unit,
    ) {
        val generation = nextInternalGeneration()
        cancelPendingPixelCopy()
        isHiding = false
        isChannelSwitching = true

        if (inPictureInPicture) {
            onReady(null)
            return
        }

        if (!wasPlaying && hasVisibleLastFrame()) {
            val copy = runCatching { lastFrameBitmap?.copy(Bitmap.Config.RGB_565, false) }.getOrNull()
            showForCctvScene(
                scene = MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV,
                channelName = channelName,
                generation = generation,
                translucentForLastFrame = true,
                animateEntry = false,
            )
            onReady(copy)
            return
        }

        val canCapture = wasPlaying &&
            surfaceView != null &&
            surfaceView.visibility == View.VISIBLE &&
            surfaceView.width > 0 &&
            surfaceView.height > 0 &&
            surfaceView.holder?.surface?.isValid == true

        if (!canCapture) {
            showForCctvScene(
                scene = MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV,
                channelName = channelName,
                generation = generation,
                translucentForLastFrame = false,
                animateEntry = false,
            )
            onReady(null)
            return
        }

        val w = surfaceView!!.width
        val h = surfaceView.height
        val scale = minOf(1f, MAX_LAST_FRAME_EDGE_PX.toFloat() / maxOf(w, h).toFloat())
        val dstW = maxOf(1, (w * scale).toInt())
        val dstH = maxOf(1, (h * scale).toInt())
        val bitmap = runCatching {
            Bitmap.createBitmap(dstW, dstH, Bitmap.Config.RGB_565)
        }.getOrNull()
        if (bitmap == null) {
            showForCctvScene(
                scene = MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV,
                channelName = channelName,
                generation = generation,
                translucentForLastFrame = false,
                animateEntry = false,
            )
            onReady(null)
            return
        }

        val completed = AtomicBoolean(false)
        val startNs = System.nanoTime()
        val timeoutTask = Runnable {
            if (!completed.compareAndSet(false, true)) return@Runnable
            pixelCopyTimeoutRunnable = null
            if (!MobileLoadingScenePolicy.isGenerationValid(activeGeneration, generation)) return@Runnable
            Log.w(TAG, "mgtv->cctv pixelCopy timeout gen=$generation")
            showForCctvScene(
                scene = MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV,
                channelName = channelName,
                generation = generation,
                translucentForLastFrame = false,
                animateEntry = false,
            )
            onReady(null)
        }
        pixelCopyTimeoutRunnable = timeoutTask
        mainHandler.postDelayed(timeoutTask, PIXEL_COPY_TIMEOUT_MS)

        val requested = runCatching {
            PixelCopy.request(
                surfaceView,
                bitmap,
                { copyResult ->
                    if (!completed.compareAndSet(false, true)) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return@request
                    }
                    mainHandler.removeCallbacks(timeoutTask)
                    if (pixelCopyTimeoutRunnable === timeoutTask) {
                        pixelCopyTimeoutRunnable = null
                    }
                    if (!MobileLoadingScenePolicy.isGenerationValid(activeGeneration, generation)) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return@request
                    }
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    val nonBlack = copyResult == PixelCopy.SUCCESS && isBitmapNonBlack(bitmap)
                    if (nonBlack) {
                        Log.i(
                            TAG,
                            "mgtv->cctv pixelCopy ok gen=$generation w=${bitmap.width} h=${bitmap.height} ms=$elapsedMs nonBlack=true",
                        )
                        val handoffCopy = runCatching { bitmap.copy(Bitmap.Config.RGB_565, false) }.getOrNull()
                        applyLastFrameBitmap(bitmap)
                        showForCctvScene(
                            scene = MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV,
                            channelName = channelName,
                            generation = generation,
                            translucentForLastFrame = true,
                            animateEntry = false,
                        )
                        onReady(handoffCopy)
                    } else {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        showForCctvScene(
                            scene = MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV,
                            channelName = channelName,
                            generation = generation,
                            translucentForLastFrame = false,
                            animateEntry = false,
                        )
                        onReady(null)
                    }
                },
                mainHandler,
            )
            true
        }.getOrDefault(false)

        if (!requested && completed.compareAndSet(false, true)) {
            mainHandler.removeCallbacks(timeoutTask)
            if (pixelCopyTimeoutRunnable === timeoutTask) {
                pixelCopyTimeoutRunnable = null
            }
            if (!bitmap.isRecycled) bitmap.recycle()
            showForCctvScene(
                scene = MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV,
                channelName = channelName,
                generation = generation,
                translucentForLastFrame = false,
                animateEntry = false,
            )
            onReady(null)
        }
    }

    /** MGTV 首次加载 / 切台加载态：若已处于同代次场景 A 则保持不重置计时器，否则进入场景 A。 */
    fun showMgtvLoading(channelName: String) {
        if (isChannelSwitching && scrimContainer.visibility == View.VISIBLE &&
            activeScene == MobileLoadingScene.SCENE_A_MGTV_SWITCH
        ) {
            return
        }
        val generation = nextInternalGeneration()
        val hasLastFrame = hasVisibleLastFrame()
        val scrimColor = if (hasLastFrame) blurScrimColor() else Color.BLACK
        enterSceneA(
            channelName = channelName,
            generation = generation,
            scrimColor = scrimColor,
            fromCrossEngine = false,
            animateEntry = true,
        )
    }

    /**
     * MGTV 缓冲 / 重试 / 切源：
     * - 切台进行中收到 ExoPlayer `STATE_BUFFERING` 时，将其作为真实 `STREAMING` 信号推进第 2 段进度；
     * - 收到重试或切备用源提示时，保持 3 段进度条骨架并以 200ms 柔顺淡入新状态提示。
     */
    fun showMgtvBuffering(message: String) {
        isHiding = false
        deferredHideRunnable?.let { mainHandler.removeCallbacks(it) }
        deferredHideRunnable = null
        if (isChannelSwitching && scrimContainer.visibility == View.VISIBLE) {
            val defaultBufferingText = context.getString(R.string.player_buffering)
            if (message == defaultBufferingText) {
                onStageSignal(activeGeneration, LoadStage.STREAMING)
            } else {
                onStageSignal(activeGeneration, LoadStage.CONNECTING)
                crossfadeTextView(stageView, message)
            }
            return
        }
        val generation = nextInternalGeneration()
        activeScene = MobileLoadingScene.SCENE_A_MGTV_SWITCH
        currentStage = LoadStage.STREAMING
        lastShownUptimeMs = SystemClock.uptimeMillis()
        cancelTimersOnly()
        val currentTitle = mainTitleView.text?.toString()?.takeIf { it.isNotBlank() }
            ?: message
        val titleToDisplay = if (currentTitle == message) {
            context.getString(R.string.player_entering_live)
        } else {
            currentTitle
        }
        bindCenterTypographyImmediate(title = titleToDisplay, subtitle = null, stage = message)
        val spec = MobileLoadingScenePolicy.specFor(MobileLoadingScene.SCENE_A_MGTV_SWITCH)
        configureSegmentBarImmediate(
            totalSegments = spec.segmentCount,
            litCount = MobileLoadingScenePolicy.litSegmentCount(activeScene, currentStage),
            breathingIndex = MobileLoadingScenePolicy.activeBreathingSegmentIndex(activeScene, currentStage),
        )
        resetProgramInfoCard()
        val hasLastFrame = hasVisibleLastFrame()
        val scrimColor = if (hasLastFrame) blurScrimColor() else Color.TRANSPARENT
        showScrim(scrimColor = scrimColor, hasLastFrame = hasLastFrame, animateEntry = true)
        scheduleSoftStageAdvance(generation)
    }

    fun onPlaybackTransientPauseOrIdle() {
        if (isChannelSwitching) return
        hide(animate = false)
    }

    /**
     * 首帧出画或退出加载态：
     * - 若展示时长不足 [MobileLoadingScenePolicy.MIN_OVERLAY_DISPLAY_MS]，先点亮满格分段并补足最短展示窗口，杜绝“闪一下就没”；
     * - 随后以 200ms [AccelerateDecelerateInterpolator] 平滑淡出，withEndAction 中严格执行 View.GONE、
     *   setRenderEffect(null)、setImageDrawable(null)、bitmap.recycle() 并取消全部计时器与呼吸动画。
     */
    fun hide(animate: Boolean, generation: Long = activeGeneration) {
        if (generation != activeGeneration && activeGeneration > 0L) return
        cancelPendingPixelCopy()
        cancelTimersOnly()
        isChannelSwitching = false
        val hasScrim = scrimContainer.visibility == View.VISIBLE
        val hasFrame = lastFrameImageView.visibility == View.VISIBLE
        if (!hasScrim && !hasFrame) {
            clearImmediately()
            return
        }
        if (!animate) {
            clearImmediately()
            return
        }
        if (isHiding) return

        // 撤幕前将进度条平滑推满至 100% 常亮，停止呼吸闪烁，使淡出更具完成感
        val totalSegments = MobileLoadingScenePolicy.specFor(activeScene).segmentCount
        if (totalSegments > 0 && segmentBarContainer.visibility == View.VISIBLE) {
            animateSegmentBarTo(litCount = totalSegments, breathingIndex = -1)
        }

        val hideGen = activeGeneration
        val elapsedMs = SystemClock.uptimeMillis() - lastShownUptimeMs
        val remainingMinDisplayMs = (MobileLoadingScenePolicy.MIN_OVERLAY_DISPLAY_MS - elapsedMs).coerceAtLeast(0L)
        if (remainingMinDisplayMs > 0L && lastShownUptimeMs > 0L) {
            isHiding = true
            val deferred = Runnable {
                deferredHideRunnable = null
                if (hideGen != activeGeneration) return@Runnable
                performAnimatedHide(hideGen)
            }
            deferredHideRunnable = deferred
            mainHandler.postDelayed(deferred, remainingMinDisplayMs)
            return
        }
        isHiding = true
        performAnimatedHide(hideGen)
    }

    private fun performAnimatedHide(hideGen: Long) {
        stopSegmentBreathing()
        val hasScrim = scrimContainer.visibility == View.VISIBLE
        val hasFrame = lastFrameImageView.visibility == View.VISIBLE
        if (!hasScrim && !hasFrame) {
            clearImmediately()
            return
        }
        if (hasFrame) {
            lastFrameImageView.animate().cancel()
            lastFrameImageView.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_MS)
                .setInterpolator(smoothInterpolator)
                .withEndAction {
                    if (hideGen == activeGeneration) {
                        clearLastFrameView()
                    }
                }
                .start()
        }
        if (hasScrim) {
            scrimContainer.animate().cancel()
            scrimContainer.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_MS)
                .setInterpolator(smoothInterpolator)
                .withEndAction {
                    if (hideGen == activeGeneration) {
                        resetScrimAndChildViews()
                        if (!hasFrame) {
                            clearLastFrameView()
                        }
                    }
                }
                .start()
        }
    }

    fun release() {
        activeGeneration++
        cancelPendingPixelCopy()
        cancelTimersOnly()
        clearImmediately()
    }

    fun hasVisibleLastFrame(): Boolean =
        lastFrameImageView.visibility == View.VISIBLE &&
            lastFrameBitmap?.let { !it.isRecycled } == true

    fun copyCurrentLastFrame(): Bitmap? =
        runCatching { lastFrameBitmap?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.RGB_565, false) }.getOrNull()

    fun isBitmapNonBlack(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        val steps = 4
        for (iy in 0 until steps) {
            val y = ((0.18f + 0.64f * iy / (steps - 1)) * h).toInt().coerceIn(0, h - 1)
            for (ix in 0 until steps) {
                val x = ((0.18f + 0.64f * ix / (steps - 1)) * w).toInt().coerceIn(0, w - 1)
                val px = bitmap.getPixel(x, y)
                if (Color.red(px) >= 12 || Color.green(px) >= 12 || Color.blue(px) >= 12) {
                    return true
                }
            }
        }
        return false
    }

    // ---------------- 内部场景调度、柔光呼吸与 200ms 丝滑动效 ----------------

    private fun nextInternalGeneration(): Long {
        activeGeneration += 1L
        return activeGeneration
    }

    private fun enterSceneA(
        channelName: String,
        generation: Long,
        scrimColor: Int,
        fromCrossEngine: Boolean,
        animateEntry: Boolean,
    ) {
        activeGeneration = generation
        activeScene = MobileLoadingScene.SCENE_A_MGTV_SWITCH
        val spec = MobileLoadingScenePolicy.specFor(MobileLoadingScene.SCENE_A_MGTV_SWITCH)
        currentStage = spec.initialStage
        isHiding = false
        isChannelSwitching = true
        lastShownUptimeMs = SystemClock.uptimeMillis()
        cancelTimersOnly()

        val title = channelName.ifBlank { context.getString(R.string.player_entering_live) }
        val subtitle = if (fromCrossEngine) context.getString(R.string.subtitle_switching_source) else null
        val litCount = MobileLoadingScenePolicy.litSegmentCount(activeScene, currentStage)
        val breathingIdx = MobileLoadingScenePolicy.activeBreathingSegmentIndex(activeScene, currentStage)

        bindCenterTypographyImmediate(
            title = title,
            subtitle = subtitle,
            stage = currentStage?.let(::stageTextFor),
        )
        configureSegmentBarImmediate(
            totalSegments = spec.segmentCount,
            litCount = litCount,
            breathingIndex = breathingIdx,
        )
        Log.i(TAG, "scene start gen=$generation scene=$activeScene stage=$currentStage lit=$litCount breathIdx=$breathingIdx")
        resetProgramInfoCard()
        val hasLastFrame = hasVisibleLastFrame()
        showScrim(scrimColor = scrimColor, hasLastFrame = hasLastFrame, animateEntry = animateEntry)
        scheduleSceneTimers(spec, generation)
        scheduleSoftStageAdvance(generation)
    }

    private fun configureSegmentBarImmediate(
        totalSegments: Int,
        litCount: Int,
        breathingIndex: Int,
    ) {
        stopSegmentBreathing()
        fallbackSpinnerView.animate().cancel()
        fallbackSpinnerView.alpha = 1f
        fallbackSpinnerView.visibility = View.GONE

        if (totalSegments <= 0) {
            segmentViews.forEach { it.animate().cancel() }
            segmentBarContainer.visibility = View.GONE
            return
        }
        segmentBarContainer.visibility = View.VISIBLE
        segmentViews.forEachIndexed { index, view ->
            view.animate().cancel()
            if (index < totalSegments) {
                view.visibility = View.VISIBLE
                view.alpha = if (index < litCount) SEGMENT_LIT_ALPHA else SEGMENT_UNLIT_ALPHA
            } else {
                view.visibility = View.GONE
            }
        }
        if (breathingIndex in 0 until totalSegments) {
            startSegmentBreathing(breathingIndex)
        }
    }

    private fun animateSegmentBarTo(litCount: Int, breathingIndex: Int) {
        if (segmentBarContainer.visibility != View.VISIBLE) return
        stopSegmentBreathing()
        val total = MobileLoadingScenePolicy.specFor(activeScene).segmentCount
        segmentViews.forEachIndexed { index, view ->
            if (index < total) {
                val targetAlpha = if (index < litCount) SEGMENT_LIT_ALPHA else SEGMENT_UNLIT_ALPHA
                view.animate().cancel()
                if (kotlin.math.abs(view.alpha - targetAlpha) > 0.01f) {
                    view.animate()
                        .alpha(targetAlpha)
                        .setDuration(OVERLAY_FADE_MS)
                        .setInterpolator(smoothInterpolator)
                        .withEndAction {
                            if (!isHiding && index == breathingIndex && breathingIndex in 0 until total) {
                                startSegmentBreathing(breathingIndex)
                            }
                        }
                        .start()
                } else if (index == breathingIndex && breathingIndex in 0 until total) {
                    startSegmentBreathing(breathingIndex)
                }
            }
        }
    }

    /**
     * 对当前正在推进的进度分段施加 0.65f ~ 1.0f、900ms 往复周期的 GPU 属性柔光呼吸动效，
     * 消除等待信号期间“进度条僵死不动”的卡顿感。
     */
    private fun startSegmentBreathing(segmentIndex: Int) {
        stopSegmentBreathing()
        if (isHiding || segmentIndex !in segmentViews.indices) return
        val targetView = segmentViews[segmentIndex]
        if (targetView.visibility != View.VISIBLE) return
        breathingSegmentIndex = segmentIndex
        val halfCycleMs = (MobileLoadingScenePolicy.SEGMENT_BREATH_CYCLE_MS / 2L).coerceAtLeast(200L)
        segmentBreathAnimator = ObjectAnimator.ofFloat(
            targetView,
            View.ALPHA,
            MobileLoadingScenePolicy.SEGMENT_BREATH_MAX_ALPHA,
            MobileLoadingScenePolicy.SEGMENT_BREATH_MIN_ALPHA,
        ).apply {
            duration = halfCycleMs
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = smoothInterpolator
            start()
        }
    }

    private fun stopSegmentBreathing() {
        val prevIndex = breathingSegmentIndex
        breathingSegmentIndex = -1
        segmentBreathAnimator?.cancel()
        segmentBreathAnimator = null
        if (prevIndex in segmentViews.indices) {
            val litCount = MobileLoadingScenePolicy.litSegmentCount(activeScene, currentStage)
            segmentViews[prevIndex].alpha = if (prevIndex < litCount) SEGMENT_LIT_ALPHA else SEGMENT_UNLIT_ALPHA
        }
    }

    /**
     * 当某阶段等待超过合理窗口仍未收到下一真实信号时，在不超过 DECODING 上限的前提下
     * 克制缓步推进至下一阶段，杜绝进度条长时间卡在第一格。
     */
    private fun scheduleSoftStageAdvance(generation: Long) {
        softStageAdvanceRunnable?.let { mainHandler.removeCallbacks(it) }
        softStageAdvanceRunnable = null
        val delayMs = MobileLoadingScenePolicy.softAdvanceDelayMs(activeScene, currentStage) ?: return
        val targetStage = MobileLoadingScenePolicy.nextSoftFallbackStage(activeScene, currentStage) ?: return
        val task = Runnable {
            softStageAdvanceRunnable = null
            if (!MobileLoadingScenePolicy.isGenerationValid(activeGeneration, generation)) return@Runnable
            if (isHiding || scrimContainer.visibility != View.VISIBLE) return@Runnable
            onStageSignal(generation = generation, candidate = targetStage, fromSoftWatchdog = true)
        }
        softStageAdvanceRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun scheduleSceneTimers(spec: MobileLoadingSceneSpec, generation: Long) {
        reconnectHintRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectHintRunnable = null
        tipsRotateRunnable?.let { mainHandler.removeCallbacks(it) }
        tipsRotateRunnable = null

        spec.reconnectHintDelayMs?.let { delayMs ->
            val task = Runnable {
                reconnectHintRunnable = null
                if (!MobileLoadingScenePolicy.isGenerationValid(activeGeneration, generation)) return@Runnable
                if (isHiding || scrimContainer.visibility != View.VISIBLE) return@Runnable
                crossfadeTextView(stageView, context.getString(R.string.player_reconnecting))
            }
            reconnectHintRunnable = task
            mainHandler.postDelayed(task, delayMs)
        }

        if (spec.showTips && spec.tipPoolSize > 0) {
            lastTipIndex = -1
            if (spec.tipInitialDelayMs <= 0L) {
                showNextTip(spec = spec, generation = generation, animateCrossfade = false)
            } else {
                val firstTipTask = Runnable {
                    if (!MobileLoadingScenePolicy.isGenerationValid(activeGeneration, generation)) return@Runnable
                    if (isHiding || scrimContainer.visibility != View.VISIBLE) return@Runnable
                    showNextTip(spec = spec, generation = generation, animateCrossfade = true)
                }
                tipsRotateRunnable = firstTipTask
                mainHandler.postDelayed(firstTipTask, spec.tipInitialDelayMs)
            }
        } else {
            hideTipsImmediate()
        }
    }

    private fun showNextTip(
        spec: MobileLoadingSceneSpec,
        generation: Long,
        animateCrossfade: Boolean,
    ) {
        if (!MobileLoadingScenePolicy.isGenerationValid(activeGeneration, generation)) return
        if (isHiding || scrimContainer.visibility != View.VISIBLE) return
        val pool = if (spec.scene == MobileLoadingScene.SCENE_D_COLD_START) COLD_TIP_RES_IDS else SWITCH_TIP_RES_IDS
        val nextIndex = MobileLoadingScenePolicy.pickNextTipIndex(
            poolSize = pool.size,
            lastIndex = lastTipIndex,
        ) { bound -> Random.nextInt(bound) }
        if (nextIndex < 0) return
        lastTipIndex = nextIndex
        val tipText = context.getString(pool[nextIndex])
        Log.i(TAG, "tip shown gen=$generation scene=${spec.scene} idx=$nextIndex")

        if (!animateCrossfade) {
            tipsView.animate().cancel()
            tipsView.text = tipText
            tipsView.alpha = 1f
            tipsView.visibility = View.VISIBLE
        } else {
            crossfadeTextView(tipsView, tipText)
        }

        if (spec.tipRotateIntervalMs > 0L) {
            val nextTask = Runnable {
                if (!MobileLoadingScenePolicy.isGenerationValid(activeGeneration, generation)) return@Runnable
                if (isHiding || scrimContainer.visibility != View.VISIBLE) return@Runnable
                showNextTip(spec = spec, generation = generation, animateCrossfade = true)
            }
            tipsRotateRunnable = nextTask
            mainHandler.postDelayed(nextTask, spec.tipRotateIntervalMs)
        }
    }

    private fun bindCenterTypographyImmediate(
        title: String,
        subtitle: String?,
        stage: String?,
    ) {
        mainTitleView.animate().cancel()
        mainTitleView.alpha = 1f
        mainTitleView.text = title

        subtitleView.animate().cancel()
        subtitleView.alpha = 1f
        if (!subtitle.isNullOrBlank()) {
            subtitleView.text = subtitle
            subtitleView.visibility = View.VISIBLE
        } else {
            subtitleView.text = ""
            subtitleView.visibility = View.GONE
        }

        stageView.animate().cancel()
        stageView.alpha = 1f
        if (!stage.isNullOrBlank()) {
            stageView.text = stage
            stageView.visibility = View.VISIBLE
        } else {
            stageView.text = ""
            stageView.visibility = View.GONE
        }
    }

    private fun crossfadeTextView(target: TextView, newText: String) {
        val currentGen = activeGeneration
        target.animate().cancel()
        if (target.visibility != View.VISIBLE) {
            target.text = newText
            target.alpha = 0f
            target.visibility = View.VISIBLE
            target.animate()
                .alpha(1f)
                .setDuration(OVERLAY_FADE_MS)
                .setInterpolator(smoothInterpolator)
                .withEndAction(null)
                .start()
            return
        }
        if (target.text?.toString() == newText) return
        target.animate()
            .alpha(0.15f)
            .setDuration(OVERLAY_FADE_MS / 2L)
            .setInterpolator(smoothInterpolator)
            .withEndAction {
                if (currentGen != activeGeneration || isHiding) return@withEndAction
                target.text = newText
                target.animate()
                    .alpha(1f)
                    .setDuration(OVERLAY_FADE_MS)
                    .setInterpolator(smoothInterpolator)
                    .withEndAction(null)
                    .start()
            }
            .start()
    }

    private fun fadeInView(target: View) {
        target.animate().cancel()
        if (target.visibility != View.VISIBLE) {
            target.alpha = 0f
            target.visibility = View.VISIBLE
        }
        target.animate()
            .alpha(1f)
            .setDuration(OVERLAY_FADE_MS)
            .setInterpolator(smoothInterpolator)
            .withEndAction(null)
            .start()
    }

    private fun showScrim(
        scrimColor: Int,
        hasLastFrame: Boolean,
        animateEntry: Boolean,
    ) {
        val wasVisible = scrimContainer.visibility == View.VISIBLE
        scrimContainer.animate().cancel()
        centerColumn.animate().cancel()
        scrimContainer.setBackgroundColor(scrimColor)
        scrimContainer.visibility = View.VISIBLE

        if (!animateEntry || wasVisible) {
            scrimContainer.alpha = 1f
            centerColumn.alpha = 1f
            return
        }
        if (hasLastFrame) {
            // 虚化尾帧已100%托底：将暗罩与前景文字/进度条以 200ms 缓动整体柔顺浮现
            centerColumn.alpha = 1f
            scrimContainer.alpha = 0f
            scrimContainer.animate()
                .alpha(1f)
                .setDuration(OVERLAY_FADE_MS)
                .setInterpolator(smoothInterpolator)
                .withEndAction(null)
                .start()
        } else {
            // 纯黑背景托底：保持底板不透明防露底，仅将前景排版以 200ms 缓动柔顺浮现
            scrimContainer.alpha = 1f
            centerColumn.alpha = 0f
            centerColumn.animate()
                .alpha(1f)
                .setDuration(OVERLAY_FADE_MS)
                .setInterpolator(smoothInterpolator)
                .withEndAction(null)
                .start()
        }
    }

    private fun resetProgramInfoCard() {
        programInfoCard.animate().cancel()
        programInfoCard.alpha = 1f
        programInfoCard.visibility = View.GONE
        nowPlayingView.text = ""
        nextProgramView.text = ""
        nextProgramView.visibility = View.GONE
    }

    private fun hideTipsImmediate() {
        tipsView.animate().cancel()
        tipsView.alpha = 1f
        tipsView.text = ""
        tipsView.visibility = View.GONE
    }

    private fun resetScrimAndChildViews() {
        isHiding = false
        stopSegmentBreathing()
        centerColumn.animate().cancel()
        centerColumn.alpha = 1f
        mainTitleView.animate().cancel()
        mainTitleView.alpha = 1f
        mainTitleView.text = ""
        subtitleView.animate().cancel()
        subtitleView.alpha = 1f
        subtitleView.text = ""
        subtitleView.visibility = View.GONE
        configureSegmentBarImmediate(totalSegments = 0, litCount = 0, breathingIndex = -1)
        stageView.animate().cancel()
        stageView.alpha = 1f
        stageView.text = ""
        stageView.visibility = View.GONE
        resetProgramInfoCard()
        hideTipsImmediate()
        scrimContainer.animate().cancel()
        scrimContainer.alpha = 1f
        scrimContainer.visibility = View.GONE
    }

    private fun clearImmediately() {
        isChannelSwitching = false
        resetScrimAndChildViews()
        clearLastFrameView()
    }

    fun clearLastFrameView() {
        lastFrameImageView.animate().cancel()
        lastFrameImageView.alpha = 1f
        lastFrameImageView.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lastFrameImageView.setRenderEffect(null)
        }
        lastFrameImageView.setImageDrawable(null)
        lastFrameBitmap?.let { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        lastFrameBitmap = null
    }

    private fun cancelPendingPixelCopy() {
        pixelCopyTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        pixelCopyTimeoutRunnable = null
    }

    private fun cancelTimersOnly() {
        reconnectHintRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectHintRunnable = null
        tipsRotateRunnable?.let { mainHandler.removeCallbacks(it) }
        tipsRotateRunnable = null
        softStageAdvanceRunnable?.let { mainHandler.removeCallbacks(it) }
        softStageAdvanceRunnable = null
        deferredHideRunnable?.let { mainHandler.removeCallbacks(it) }
        deferredHideRunnable = null
        stopSegmentBreathing()
    }

    private fun stageTextFor(stage: LoadStage): String = when (stage) {
        LoadStage.RELEASING -> context.getString(R.string.stage_releasing_previous)
        LoadStage.INIT_PLAYER -> context.getString(R.string.stage_init_player)
        LoadStage.CONNECTING -> context.getString(R.string.stage_connecting)
        LoadStage.STREAMING -> context.getString(R.string.stage_streaming)
        LoadStage.DECODING -> context.getString(R.string.stage_decoding)
    }

    private fun blurScrimColor(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        LAST_FRAME_SCRIM_BLUR_COLOR
    } else {
        LAST_FRAME_SCRIM_FALLBACK_COLOR
    }

    private fun TextView.applyTextShadow() {
        setShadowLayer(4f, 0f, 1.5f, TEXT_SHADOW_COLOR)
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun dpFloat(value: Float): Float = value * density

    companion object {
        private const val TAG = "MobileLoadingOverlay"
        private const val OVERLAY_FADE_MS = MobileLoadingScenePolicy.FADE_DURATION_MS
        private const val PIXEL_COPY_TIMEOUT_MS = 180L
        private const val MAX_LAST_FRAME_EDGE_PX = 1280
        private const val LAST_FRAME_BLUR_RADIUS_PX = 24f
        private const val LAST_FRAME_SCRIM_BLUR_COLOR = 0x80000000.toInt()
        private const val LAST_FRAME_SCRIM_FALLBACK_COLOR = 0x8C000000.toInt()
        private const val TITLE_TEXT_COLOR = 0xFFFFFFFF.toInt()
        private const val SUBTITLE_TEXT_COLOR = 0x99FFFFFF.toInt()
        private const val STAGE_TEXT_COLOR = 0xCCFFFFFF.toInt()
        private const val TIPS_TEXT_COLOR = 0xB3FFFFFF.toInt()
        private const val TEXT_SHADOW_COLOR = 0x99000000.toInt()
        private const val MAX_SEGMENT_COUNT = 4
        private const val SEGMENT_LIT_ALPHA = 1.0f
        private const val SEGMENT_UNLIT_ALPHA = 0.22f

        private val SWITCH_TIP_RES_IDS = intArrayOf(
            R.string.tip_switch_1,
            R.string.tip_switch_2,
            R.string.tip_switch_3,
            R.string.tip_switch_4,
            R.string.tip_switch_5,
            R.string.tip_switch_6,
            R.string.tip_switch_7,
            R.string.tip_switch_8,
        )

        private val COLD_TIP_RES_IDS = intArrayOf(
            R.string.tip_cold_1,
            R.string.tip_cold_2,
            R.string.tip_cold_3,
            R.string.tip_cold_4,
        )
    }
}
