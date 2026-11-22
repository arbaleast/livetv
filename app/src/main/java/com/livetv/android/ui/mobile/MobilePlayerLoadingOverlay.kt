package com.livetv.android.ui.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.livetv.android.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机端 MGTV 原生播放页的统一影院风加载/切台遮罩控制器（仅触控设备挂载，TV 端零触碰）。
 *
 * 与 CCTV 网页播放页保持 100% 一致的视觉与动效：
 * - 同页播放态切台：通过 `PixelCopy.request(surfaceView, ...)` 截取上一台纯净视频尾帧，
 *   API 31+ 施加 `RenderEffect(24f)` 柔和虚化 + `#73000000` 暗罩（API 28–30 回退 `#8C000000`）；
 * - 居中磨砂加载胶囊：`R.drawable.bg_player_loading_capsule`（`#BF0E1117`、20dp 圆角、1dp `#24FFFFFF` 细边）
 *   + 20dp 纯白 `#E6FFFFFF` 细进度环 + 10dp 间距 + 13sp `#E6FFFFFF` 单行状态文案；
 * - 命中起播态后 `150ms` 平滑淡出，`withEndAction` 立即置 `View.GONE`、清除 `RenderEffect` 并回收 `Bitmap`。
 */
internal class MobilePlayerLoadingOverlay(
    private val context: Context,
    parentRoot: FrameLayout,
    chromeRoot: View,
    private val onOverlayTap: () -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private val lastFrameImageView: ImageView
    private val scrimContainer: FrameLayout
    private val statusTextView: TextView

    private var lastFrameBitmap: Bitmap? = null
    private var pixelCopyTimeoutRunnable: Runnable? = null
    private var switchGeneration: Long = 0L
    private var isHiding: Boolean = false
    private var isChannelSwitching: Boolean = false

    init {
        val chromeIndex = parentRoot.indexOfChild(chromeRoot).takeIf { it >= 0 } ?: parentRoot.childCount

        lastFrameImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        parentRoot.addView(lastFrameImageView, chromeIndex, matchParent())

        val spinnerSize = dp(20)
        val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(CAPSULE_TINT_COLOR)
        }
        statusTextView = TextView(context).apply {
            setTextColor(CAPSULE_TINT_COLOR)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_player_loading_capsule)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            addView(spinner, LinearLayout.LayoutParams(spinnerSize, spinnerSize))
            addView(
                statusTextView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(10)
                },
            )
        }
        scrimContainer = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener { onOverlayTap() }
            addView(
                capsule,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ).apply {
                    marginStart = dp(24)
                    marginEnd = dp(24)
                },
            )
        }
        parentRoot.addView(scrimContainer, chromeIndex + 1, matchParent())
        chromeRoot.bringToFront()
    }

    /**
     * 切台入口：若当前处于播放态且 Surface 有效，先通过 PixelCopy 冻结纯净尾帧再触发切台回调；
     * 否则直接展示纯黑底/已有尾帧 + 磨砂加载胶囊并立即触发切台。
     */
    fun beginChannelSwitch(
        surfaceView: SurfaceView?,
        wasPlaying: Boolean,
        inPictureInPicture: Boolean,
        message: String,
        onReadyToSwitch: () -> Unit,
    ) {
        val generation = ++switchGeneration
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
            showScrimWithCapsule(message = message, scrimColor = blurScrimColor())
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
            showScrimWithCapsule(message = message, scrimColor = Color.BLACK)
            onReadyToSwitch()
            return
        }

        val w = surfaceView!!.width
        val h = surfaceView.height
        val scale = minOf(1f, MAX_LAST_FRAME_EDGE_PX.toFloat() / maxOf(w, h).toFloat())
        val dstW = maxOf(1, (w * scale).toInt())
        val dstH = maxOf(1, (h * scale).toInt())
        // 使用 RGB_565（天然不透明无 Alpha 通道）：避免硬件解码 SurfaceView 经 PixelCopy 输出
        // alpha=0 的 ARGB 像素导致在黑色底板上被混合成纯黑，且内存减半。
        val bitmap = runCatching {
            Bitmap.createBitmap(dstW, dstH, Bitmap.Config.RGB_565)
        }.getOrNull()

        if (bitmap == null) {
            clearLastFrameView()
            showScrimWithCapsule(message = message, scrimColor = Color.BLACK)
            onReadyToSwitch()
            return
        }

        val completed = AtomicBoolean(false)
        val startNs = System.nanoTime()
        val timeoutTask = Runnable {
            if (!completed.compareAndSet(false, true)) return@Runnable
            pixelCopyTimeoutRunnable = null
            if (generation != switchGeneration) return@Runnable
            Log.w(TAG, "mgtv pixelCopy timeout gen=$generation -> fallback black scrim")
            clearLastFrameView()
            showScrimWithCapsule(message = message, scrimColor = Color.BLACK)
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
                    if (generation != switchGeneration) {
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
                        showScrimWithCapsule(message = message, scrimColor = blurScrimColor())
                    } else {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        Log.w(
                            TAG,
                            "mgtv pixelCopy fallback gen=$generation result=$copyResult ms=$elapsedMs",
                        )
                        clearLastFrameView()
                        showScrimWithCapsule(message = message, scrimColor = Color.BLACK)
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
            showScrimWithCapsule(message = message, scrimColor = Color.BLACK)
            onReadyToSwitch()
        }
    }

    /**
     * 接收从 CCTV 跨引擎切入时移交的上一台尾帧 Bitmap，立即挂载虚化尾帧与磨砂加载胶囊，
     * 并在切台期间锁定 `isChannelSwitching = true` 防止 ExoPlayer 初始化重置抹掉尾帧。
     */
    fun applyCrossEngineLastFrame(bitmap: Bitmap, message: String) {
        if (bitmap.isRecycled) return
        switchGeneration++
        cancelPendingPixelCopy()
        isHiding = false
        isChannelSwitching = true
        applyLastFrameBitmap(bitmap)
        showScrimWithCapsule(message = message, scrimColor = blurScrimColor())
    }

    /**
     * MGTV -> CCTV 跨引擎切台前捕获当前 SurfaceView 纯净尾帧：
     * 既在本页立即显示虚化尾帧 + 磨砂胶囊消除退场黑闪，又复制一份独立 RGB_565 位图移交目标 CCTV 页。
     */
    fun prepareCrossEngineHandoff(
        surfaceView: SurfaceView?,
        wasPlaying: Boolean,
        inPictureInPicture: Boolean,
        message: String,
        onReady: (handoffBitmap: Bitmap?) -> Unit,
    ) {
        val generation = ++switchGeneration
        cancelPendingPixelCopy()
        isHiding = false
        isChannelSwitching = true

        if (inPictureInPicture) {
            onReady(null)
            return
        }

        if (!wasPlaying && hasVisibleLastFrame()) {
            val copy = runCatching { lastFrameBitmap?.copy(Bitmap.Config.RGB_565, false) }.getOrNull()
            showScrimWithCapsule(message = message, scrimColor = blurScrimColor())
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
            showScrimWithCapsule(message = message, scrimColor = Color.BLACK)
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
            showScrimWithCapsule(message = message, scrimColor = Color.BLACK)
            onReady(null)
            return
        }

        val completed = AtomicBoolean(false)
        val startNs = System.nanoTime()
        val timeoutTask = Runnable {
            if (!completed.compareAndSet(false, true)) return@Runnable
            pixelCopyTimeoutRunnable = null
            if (generation != switchGeneration) return@Runnable
            Log.w(TAG, "mgtv->cctv pixelCopy timeout gen=$generation")
            showScrimWithCapsule(message = message, scrimColor = Color.BLACK)
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
                    if (generation != switchGeneration) {
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
                        showScrimWithCapsule(message = message, scrimColor = blurScrimColor())
                        onReady(handoffCopy)
                    } else {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        showScrimWithCapsule(message = message, scrimColor = Color.BLACK)
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
            showScrimWithCapsule(message = message, scrimColor = Color.BLACK)
            onReady(null)
        }
    }

    /** 频道首次加载 / 切台加载态：保留已冻结的虚化尾帧，否则用纯黑底 + 居中磨砂加载胶囊。 */
    fun showLoading(message: String) {
        isHiding = false
        isChannelSwitching = true
        val scrimColor = if (hasVisibleLastFrame()) blurScrimColor() else Color.BLACK
        showScrimWithCapsule(message = message, scrimColor = scrimColor)
    }

    /** 播放中缓冲 / 重试 / 切源：切台过程中保持「正在切换至 XXX」文案不闪跳；播中缓冲仅居中浮现统一磨砂胶囊。 */
    fun showBuffering(message: String) {
        isHiding = false
        if (isChannelSwitching && scrimContainer.visibility == View.VISIBLE) {
            return
        }
        val scrimColor = if (hasVisibleLastFrame()) blurScrimColor() else Color.TRANSPARENT
        showScrimWithCapsule(message = message, scrimColor = scrimColor)
    }

    /**
     * 播放引擎切台内部 `player.stop()` 会先短暂触发 `Paused` / `Idle` 再进入 `Loading`，
     * 切台期间忽略该过渡回调，防止提前抹掉刚截取的虚化尾帧导致闪黑。
     */
    fun onPlaybackTransientPauseOrIdle() {
        if (isChannelSwitching) return
        hide(animate = false)
    }

    /** 起播或退出加载态：与 CCTV 一致的 150ms 平滑淡出并彻底释放 RenderEffect 与 Bitmap。 */
    fun hide(animate: Boolean) {
        cancelPendingPixelCopy()
        isChannelSwitching = false
        val hasScrim = scrimContainer.visibility == View.VISIBLE
        val hasFrame = lastFrameImageView.visibility == View.VISIBLE
        if (!hasScrim && !hasFrame) {
            clearLastFrameView()
            return
        }
        if (!animate) {
            clearImmediately()
            return
        }
        if (isHiding) return
        isHiding = true
        val gen = switchGeneration
        if (hasFrame) {
            lastFrameImageView.animate().cancel()
            lastFrameImageView.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_MS)
                .withEndAction {
                    if (gen == switchGeneration) {
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
                .withEndAction {
                    if (gen == switchGeneration) {
                        scrimContainer.visibility = View.GONE
                        scrimContainer.alpha = 1f
                        isHiding = false
                        if (!hasFrame) {
                            clearLastFrameView()
                        }
                    }
                }
                .start()
        }
    }

    fun release() {
        switchGeneration++
        cancelPendingPixelCopy()
        clearImmediately()
    }

    private fun clearImmediately() {
        isHiding = false
        isChannelSwitching = false
        scrimContainer.animate().cancel()
        scrimContainer.alpha = 1f
        scrimContainer.visibility = View.GONE
        clearLastFrameView()
    }

    private fun showScrimWithCapsule(message: String, scrimColor: Int) {
        statusTextView.text = message
        scrimContainer.animate().cancel()
        scrimContainer.alpha = 1f
        scrimContainer.setBackgroundColor(scrimColor)
        scrimContainer.visibility = View.VISIBLE
    }

    private fun applyLastFrameBitmap(bitmap: Bitmap) {
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

    private fun clearLastFrameView() {
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

    private fun hasVisibleLastFrame(): Boolean =
        lastFrameImageView.visibility == View.VISIBLE &&
            lastFrameBitmap?.let { !it.isRecycled } == true

    private fun cancelPendingPixelCopy() {
        pixelCopyTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        pixelCopyTimeoutRunnable = null
    }

    private fun blurScrimColor(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        LAST_FRAME_SCRIM_BLUR_COLOR
    } else {
        LAST_FRAME_SCRIM_FALLBACK_COLOR
    }

    private fun isBitmapNonBlack(bitmap: Bitmap): Boolean {
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

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        private const val TAG = "MobileLoadingOverlay"
        private const val OVERLAY_FADE_MS = 150L
        private const val PIXEL_COPY_TIMEOUT_MS = 180L
        private const val MAX_LAST_FRAME_EDGE_PX = 1280
        private const val LAST_FRAME_BLUR_RADIUS_PX = 24f
        private const val LAST_FRAME_SCRIM_BLUR_COLOR = 0x73000000.toInt()
        private const val LAST_FRAME_SCRIM_FALLBACK_COLOR = 0x8C000000.toInt()
        private const val CAPSULE_TINT_COLOR = 0xE6FFFFFF.toInt()
    }
}
