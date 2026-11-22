package com.livetv.android.webplayer

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.Rational
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.livetv.android.LiveTvApplication
import com.livetv.android.R
import com.livetv.android.databinding.ViewMobilePlayerChromeBinding
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.ui.PlayerActivity
import com.livetv.android.ui.mobile.ChromeBackAction
import com.livetv.android.ui.mobile.MobilePlayerChromeController
import com.livetv.android.ui.mobile.MobilePlayerTransitionHelper
import com.livetv.android.ui.mobile.MobileUiPreferences
import com.livetv.android.ui.mobile.PlayerChromeCapabilities
import com.livetv.android.ui.mobile.PlayerTransitionAndPipPolicy
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CCTV 手机端 WebView 播放页（接入 [MobilePlayerChromeController] 共享手机端外框）。
 *
 * 三阶段体验：
 * 1. **阶段一（视口聚焦 + 首帧对齐黑幕）**：整页加载官方直播页，静态脚本把 `#player` 铺满视口并隐藏外围兄弟；
 *    严格对齐首帧 `STATE_PLAYING`（80ms 轮询 + 120ms 淡出，7s 异常兜底）撤除黑幕；
 * 2. **阶段二（纯视频）**：检测到 `<video>` 首帧起播后立即停止轮询，隐藏 `#player` 内部非媒体控制层，
 *    外框顶/底栏 3.5 秒后自动淡出至 `View.GONE`，单击画面呼出/隐藏；
 * 3. **阶段三（原控切换）**：点击顶栏「原控 / 纯画」恢复或隐藏官方网页控制栏；「原控」态顶栏常驻、
 *    底栏自动隐藏避免与网页底栏冲突，单击透传网页，垂直滑动仍可调亮度/音量。
 *
 * 边界约束：
 * - 只注入 [CctvWebViewportFocusScript] 静态常量，不模拟点击、不改播放状态；
 * - 只读访问 `ChannelCatalogStore` 用于选台抽屉（不写入目录快照）；
 * - 跨源切台到 MGTV/IPTV 前严格销毁 WebView，防止音轨重叠与内存残留。
 */
class CctvWebPlayerActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var touchContainer: CctvTouchContainer
    private lateinit var lastFrameImageView: ImageView
    private lateinit var chrome: MobilePlayerChromeController
    private lateinit var gestureController: CctvWebGestureController

    private var webView: WebView? = null
    private var loadingOverlay: FrameLayout? = null
    private var lastFrameBitmap: Bitmap? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webChromeClient: WebChromeClient? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val app: LiveTvApplication get() = application as LiveTvApplication
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val switchGeneration = CctvWebSwitchGeneration()

    private var currentChannelId: String = DEFAULT_CHANNEL_ID
    private var currentChannelName: String = ""
    private var categories: List<ChannelCategory> = emptyList()
    private var baseUserAgent: String = ""
    private var currentVodUrl: String? = null
    private var currentVodTitle: String? = null
    private val vod4kPool: List<Cctv4kVodItem> by lazy { loadVod4kPool() }

    private var webViewNavigationGeneration: Long = 0L
    private var activeTimeoutGeneration: Long = 0L
    private var activePollGeneration: Long = 0L
    private var activeVodEndedGeneration: Long = 0L
    private var pixelCopyTimeoutRunnable: Runnable? = null
    private var pendingChoreographerCallback: Choreographer.FrameCallback? = null

    private var revealed = false
    private var startedAt = 0L
    private var isPlaying = false
    private var nativeControlsVisible = false
    private var awaitingPageCommit = false
    private var suspendedInBackground = false
    private var hasLoadFailure = false
    private var supportsPip = false
    private var isSwitchingEngine = false

    private val backgroundSuspendRunnable = Runnable { suspendWebViewInBackground() }
    private val vodEndedPollRunnable = Runnable { pollVodEndedState() }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (::chrome.isInitialized && chrome.handleBackPressed() != ChromeBackAction.PASS_THROUGH) {
                return
            }
            if (customView != null) {
                webChromeClient?.onHideCustomView()
            } else {
                finish()
            }
        }
    }

    private val revealTimeoutRunnable = Runnable {
        val gen = activeTimeoutGeneration
        if (!switchGeneration.isCurrent(gen)) return@Runnable
        awaitingPageCommit = false
        reveal(gen)
        if (!isPlaying && webView != null) {
            schedulePoll(gen, POLL_INTERVAL_MS)
        }
    }

    private val pollRunnable: Runnable = Runnable { pollState() }

    private fun schedulePoll(generation: Long, delayMs: Long) {
        if (!switchGeneration.isCurrent(generation)) return
        activePollGeneration = generation
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.postDelayed(pollRunnable, delayMs)
    }

    private fun pollState() {
        val view = webView ?: return
        val generation = activePollGeneration
        if (!switchGeneration.isCurrent(generation)) return
        if (isPlaying || awaitingPageCommit) return

        val elapsed = System.currentTimeMillis() - startedAt
        val focusTimeoutMs = CctvWebChannel.focusTimeoutMs(currentChannelId)
        if (!revealed && elapsed >= focusTimeoutMs) {
            reveal(generation)
        }
        val nextDelay = if (elapsed >= SLOW_POLL_AFTER_MS) SLOW_POLL_INTERVAL_MS else POLL_INTERVAL_MS

        view.evaluateJavascript(CctvWebViewportFocusScript.CHECK_STATE_SCRIPT) { raw ->
            if (!switchGeneration.isCurrent(generation)) return@evaluateJavascript
            if (isFinishing || isDestroyed || isPlaying || awaitingPageCommit) return@evaluateJavascript
            when (raw?.trim()?.trim('"')) {
                STATE_PLAYING -> {
                    onPlaybackDetected(generation)
                    reveal(generation)
                }

                STATE_READY -> {
                    // 视频元素已就位但首帧尚未起播：继续保留黑幕/尾帧直到真首帧播放或超时兜底，
                    // 避免提前撤幕暴露官方加载态与控制栏残影。
                    schedulePoll(generation, nextDelay)
                }

                else -> {
                    schedulePoll(generation, nextDelay)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportsPip = MobilePlayerTransitionHelper.supportsPictureInPicture(this)
        MobilePlayerTransitionHelper.applyIncomingNoTransitionIfNeeded(this, intent)
        MobilePlayerTransitionHelper.registerActiveMobilePlayer(this) { dismissForOtherEngine() }
        requestedOrientation = MobilePlayerTransitionHelper.resolveInitialOrientation(
            isTvDevice = false,
            intent = intent,
        )
        val density = resources.displayMetrics.density
        nativeControlsVisible = MobileUiPreferences.isCctvWebControlsVisible(this)
        gestureController = CctvWebGestureController(
            touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
            edgeExclusionPx = CctvWebGestureController.EDGE_EXCLUSION_DP * density,
        ).apply {
            isNativeControlsVisible = nativeControlsVisible
        }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        applyImmersive()

        onBackPressedDispatcher.addCallback(this, backCallback)

        val storeCategories = runCatching { app.catalogStore.getAllCategories() }.getOrDefault(emptyList())
        categories = CctvWebSwitchPolicy.resolveCategories(storeCategories)

        val rawChannelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: DEFAULT_CHANNEL_ID
        val channelId = if (CctvWebChannel.isSupported(rawChannelId)) {
            rawChannelId
        } else {
            Log.w(TAG, "unsupported channel '$rawChannelId', falling back to $DEFAULT_CHANNEL_ID")
            DEFAULT_CHANNEL_ID
        }
        currentChannelId = channelId
        currentChannelName = CctvWebSwitchPolicy.resolveChannelName(
            channelId = channelId,
            categories = categories,
            fallbackName = intent.getStringExtra(EXTRA_CHANNEL_NAME),
        )

        touchContainer = CctvTouchContainer(this)
        root.addView(touchContainer, matchParent())
        lastFrameImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(lastFrameImageView, matchParent())
        mountChrome()
        applySafeAreaInsets()

        val pageUrl = resolveTargetUrl(channelId, avoidCurrentVod = true)
        if (pageUrl == null) {
            showUnavailableMessage(R.string.cctv_web_unsupported)
            return
        }
        chrome.setTitle(displayTitleForCurrentChannel())

        MobileUiPreferences.setLastPlayedChannelId(this, channelId)
        updateFavoriteUi()

        val created = runCatching { buildWebView() }.getOrNull()
        if (created == null) {
            showUnavailableMessage(R.string.cctv_web_unavailable)
            return
        }
        webView = created
        applyUserAgentForChannel(created, channelId)
        touchContainer.addView(created, matchParent())

        // 阶段一（待起播）默认隐藏外框仅展示居中加载胶囊；若从 MGTV 跨引擎切入则无缝承接上一台虚化尾帧
        val generation = switchGeneration.nextGeneration()
        chrome.dismissAllImmediately()
        val crossBitmap = MobilePlayerTransitionHelper.consumeCrossEngineLastFrame(intent)
        if (crossBitmap != null) {
            applyLastFrameBitmapToView(crossBitmap)
            showOverlay(translucentForLastFrame = true)
        } else {
            showOverlay(translucentForLastFrame = false)
        }
        ensureCatalogLoaded()

        awaitingPageCommit = true
        beginWebViewLoad(created, channelId, pageUrl, generation)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersive()
        ViewCompat.requestApplyInsets(root)
        if (::chrome.isInitialized && chrome.isDrawerOpen) {
            chrome.scrollDrawerToCurrentChannel()
        }
    }

    // ---------------- 共享外框接入 ----------------

    private fun mountChrome() {
        val chromeBinding = ViewMobilePlayerChromeBinding.inflate(layoutInflater, root, false)
        root.addView(chromeBinding.chromeRoot, matchParent())
        chrome = MobilePlayerChromeController(
            binding = chromeBinding,
            capabilities = PlayerChromeCapabilities.CCTV,
            callbacks = object : MobilePlayerChromeController.Callbacks {
                override fun onBack() = finish()
                override fun onToggleFavorite() = toggleCurrentFavorite()
                override fun onRotate() = toggleOrientation()
                override fun onChannelPicked(channel: Channel) = handleChannelPicked(channel)
                override fun onErrorRetry() = retryCurrentChannel()
                override fun onLockStateChanged(locked: Boolean) {
                    gestureController.isScreenLocked = locked
                }
                override fun onToggleWebControls() = toggleNativeControls()
                override fun onReload() = retryCurrentChannel()
                override fun onEnterPip() = enterPipIfEligible(fromUserAction = true)
            },
            initialPlaying = false,
        )
        chrome.setTitle(currentChannelName)
        chrome.setCatalog(categories, currentChannelId)
        chrome.setWebNativeControlsVisible(nativeControlsVisible)
        chrome.setPipButtonVisible(supportsPip)
        updateFavoriteUi()
    }

    private fun applySafeAreaInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            chrome.applyCutoutInsets(windowInsets)
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun toggleCurrentFavorite() {
        val nowFavorite = MobileUiPreferences.toggleFavoriteChannel(this, currentChannelId)
        updateFavoriteUi()
        chrome.showHudText(
            getString(
                if (nowFavorite) R.string.mobile_favorite_added else R.string.mobile_favorite_removed,
                currentChannelName,
            ),
        )
    }

    private fun updateFavoriteUi() {
        val isFavorite = currentChannelId in MobileUiPreferences.getFavoriteChannelIds(this)
        chrome.updateFavorite(isFavorite)
    }

    private fun toggleOrientation() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun retryCurrentChannel() {
        val url = resolveTargetUrl(currentChannelId, avoidCurrentVod = true)
        if (url == null) {
            finish()
            return
        }
        if (webView == null) {
            val recreated = runCatching { buildWebView() }.getOrNull()
            if (recreated == null) {
                showUnavailableMessage(R.string.cctv_web_unavailable)
                return
            }
            webView = recreated
            touchContainer.addView(recreated, matchParent())
        }
        reloadChannel(currentChannelId, currentChannelName, url)
    }

    private fun loadVod4kPool(): List<Cctv4kVodItem> {
        val raw = runCatching {
            assets.open(Cctv4kVodPool.ASSET_FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
        return Cctv4kVodPool.parse(raw)
    }

    private fun resolveTargetUrl(channelId: String, avoidCurrentVod: Boolean): String? {
        if (CctvWebChannel.isVodPoolChannel(channelId)) {
            val avoidUrl = if (avoidCurrentVod) currentVodUrl ?: lastVod4kUrl else null
            val picked = Cctv4kVodPool.pickNext(
                items = vod4kPool,
                currentUrl = avoidUrl,
            ) ?: return null
            currentVodUrl = picked.url
            currentVodTitle = picked.title
            lastVod4kUrl = picked.url
            Log.i(TAG, "4k vod pool picked: ${picked.title}")
            return picked.url
        }
        currentVodUrl = null
        currentVodTitle = null
        return CctvWebChannel.liveUrl(channelId)
    }

    private fun displayTitleForCurrentChannel(): String {
        val vodTitle = currentVodTitle
        return if (CctvWebChannel.isVodPoolChannel(currentChannelId) && !vodTitle.isNullOrBlank()) {
            "CCTV-4K · $vodTitle"
        } else {
            currentChannelName
        }
    }

    private fun applyUserAgentForChannel(view: WebView, channelId: String) {
        if (baseUserAgent.isEmpty()) {
            baseUserAgent = view.settings.userAgentString
        }
        val resolved = CctvWebUserAgent.resolveForChannel(baseUserAgent, channelId)
        if (resolved.isNotEmpty() && view.settings.userAgentString != resolved) {
            view.settings.userAgentString = resolved
        }
    }

    private fun pollVodEndedState() {
        val view = webView ?: return
        val generation = activeVodEndedGeneration
        if (!switchGeneration.isCurrent(generation)) return
        if (!isPlaying || !CctvWebChannel.isVodPoolChannel(currentChannelId)) return
        if (isFinishing || isDestroyed || suspendedInBackground || hasLoadFailure) return
        view.evaluateJavascript(CctvWebViewportFocusScript.CHECK_STATE_SCRIPT) { raw ->
            if (!switchGeneration.isCurrent(generation)) return@evaluateJavascript
            if (!isPlaying || !CctvWebChannel.isVodPoolChannel(currentChannelId)) return@evaluateJavascript
            if (isFinishing || isDestroyed || suspendedInBackground) return@evaluateJavascript
            if (raw?.trim()?.trim('"') == STATE_ENDED) {
                Log.i(TAG, "4k vod ended, advancing to next album")
                retryCurrentChannel()
            } else {
                mainHandler.postDelayed(vodEndedPollRunnable, VOD_ENDED_POLL_INTERVAL_MS)
            }
        }
    }

    // ---------------- WebView ----------------

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val view = WebView(this)
        view.setBackgroundColor(Color.BLACK)
        view.setLayerType(View.LAYER_TYPE_NONE, null)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        applyRequestedVideoFrameRate(view)

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
            setSaveFormData(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            if (baseUserAgent.isEmpty()) {
                baseUserAgent = userAgentString
            }
            userAgentString = CctvWebUserAgent.resolveForChannel(baseUserAgent, currentChannelId)
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                if (request.isForMainFrame) return super.shouldInterceptRequest(view, request)
                val uri = request.url
                val blocked = CctvWebResourceBlocker.match(uri.host, uri.path, uri.query)
                if (blocked != null) {
                    val reason = if (blocked.statusCode == 204) "No Content" else "OK"
                    return WebResourceResponse(
                        blocked.mimeType,
                        "utf-8",
                        blocked.statusCode,
                        reason,
                        mapOf("Access-Control-Allow-Origin" to "*"),
                        ByteArrayInputStream(blocked.body),
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val target = request.url.toString()
                if (CctvWebNavigationPolicy.isAllowed(target)) return false
                Log.w(TAG, CctvWebNavigationPolicy.describeForLog(target))
                return true
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (url == ABOUT_BLANK) return
                val navGen = webViewNavigationGeneration
                if (!switchGeneration.isCurrent(navGen)) return
                injectFocusScript(view, navGen)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == ABOUT_BLANK) return
                val navGen = webViewNavigationGeneration
                if (!switchGeneration.isCurrent(navGen)) return
                injectFocusScript(view, navGen)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                val navGen = webViewNavigationGeneration
                if (!switchGeneration.isCurrent(navGen)) return
                awaitingPageCommit = false
                Log.w(TAG, "main frame error: " + error.errorCode + " gen=" + navGen)
                showLoadFailure(R.string.cctv_web_load_failed, navGen)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (!request.isForMainFrame) return
                val navGen = webViewNavigationGeneration
                if (!switchGeneration.isCurrent(navGen)) return
                awaitingPageCommit = false
                Log.w(TAG, "main frame http: " + errorResponse.statusCode + " gen=" + navGen)
                showLoadFailure(R.string.cctv_web_load_failed, navGen)
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                Log.w(
                    TAG,
                    "renderer gone: crash=" + detail.didCrash() +
                        " priority=" + detail.rendererPriorityAtExit(),
                )
                cancelPendingPixelCopy()
                clearLastFrameView()
                (view.parent as? ViewGroup)?.removeView(view)
                view.webChromeClient = null
                webChromeClient = null
                view.removeAllViews()
                view.destroy()
                if (webView === view) webView = null
                suspendedInBackground = false
                mainHandler.removeCallbacks(backgroundSuspendRunnable)
                showLoadFailure(R.string.cctv_web_renderer_gone, switchGeneration.current)
                return true
            }
        }

        val chromeClient = object : WebChromeClient() {
            // 官方页若调用 window.close()，默认实现可能连带结束宿主 Activity；这里显式忽略，
            // 退出只允许由用户按返回键触发。
            override fun onCloseWindow(window: WebView) {
                Log.w(TAG, "ignored page window.close()")
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                root.addView(view, matchParent())
                webView?.visibility = View.GONE
                applyImmersive()
            }

            override fun onHideCustomView() {
                val activeCustomView = customView ?: return
                root.removeView(activeCustomView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                webView?.visibility = View.VISIBLE
                applyImmersive()
            }
        }
        webChromeClient = chromeClient
        view.webChromeClient = chromeClient
        return view
    }

    // ---------------- 手势拦截容器（包裹 WebView） ----------------

    /**
     * 包裹 [WebView] 的原生手势分流容器：
     * - 锁屏态：屏蔽亮度/音量滑动并拦截点击，短按短暂浮现解锁按钮；
     * - 垂直滑动超 touchSlop（避让左右 24dp 边缘）：左半屏调亮度、右半屏调音量；
     * - 点击：仅在纯视频播放态拦截并切换共享控制层；待起播或「原控」态透传给 [WebView]。
     */
    private inner class CctvTouchContainer(context: Context) : FrameLayout(context) {

        private var intercepted = false

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            // 防止 WebView 抢占触摸流后屏蔽待起播/原控态下的亮度与音量垂直手势
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (chrome.isDrawerOpen || loadingOverlay != null) return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    intercepted = false
                    gestureController.onTouchDown(
                        x = event.x,
                        y = event.y,
                        viewWidthPx = width,
                        startBrightness = readCurrentBrightness(),
                        startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                    )
                    if (gestureController.shouldCaptureTap()) {
                        intercepted = true
                        return true
                    }
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    val action = gestureController.onTouchMove(event.x, event.y, height)
                    if (handleAdjustmentAction(action)) {
                        intercepted = true
                        return true
                    }
                    return false
                }

                MotionEvent.ACTION_UP -> {
                    gestureController.onTouchUp()
                    intercepted = false
                    return false
                }

                MotionEvent.ACTION_CANCEL -> {
                    gestureController.onTouchCancel()
                    intercepted = false
                    return false
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!intercepted) {
                        gestureController.onTouchDown(
                            x = event.x,
                            y = event.y,
                            viewWidthPx = width,
                            startBrightness = readCurrentBrightness(),
                            startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                        )
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val action = gestureController.onTouchMove(event.x, event.y, height)
                    handleAdjustmentAction(action)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    intercepted = false
                    when (gestureController.onTouchUp()) {
                        GestureAction.ToggleTopBar -> {
                            performClick()
                            chrome.toggleControls()
                        }

                        GestureAction.ToggleLockButton -> {
                            performClick()
                            chrome.onSingleTapWhileLocked()
                        }

                        GestureAction.EndAdjustment -> chrome.hideHudDelayed()
                        else -> Unit
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    intercepted = false
                    if (gestureController.onTouchCancel() == GestureAction.EndAdjustment) {
                        chrome.hideHudDelayed()
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean = super.performClick()
    }

    private fun handleAdjustmentAction(action: GestureAction): Boolean = when (action) {
        is GestureAction.AdjustBrightness -> {
            applyBrightness(action.brightness)
            chrome.showHudMeter(MobilePlayerChromeController.HudIcon.BRIGHTNESS, action.percent)
            true
        }

        is GestureAction.AdjustVolume -> {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, action.volume, 0)
            chrome.showHudMeter(MobilePlayerChromeController.HudIcon.VOLUME, action.percent)
            true
        }

        else -> false
    }

    private fun readCurrentBrightness(): Float {
        val current = window.attributes.screenBrightness
        if (current >= 0f) {
            return current.coerceIn(
                CctvWebGestureController.MIN_BRIGHTNESS,
                CctvWebGestureController.MAX_BRIGHTNESS,
            )
        }
        val sys = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        val normalized = when (sys) {
            in 0..255 -> sys / 255f
            in 256..4095 -> sys / 4095f
            in 4096..8191 -> sys / 8191f
            else -> CctvWebGestureController.DEFAULT_BRIGHTNESS
        }
        return normalized.coerceIn(
            CctvWebGestureController.MIN_BRIGHTNESS,
            CctvWebGestureController.MAX_BRIGHTNESS,
        )
    }

    private fun applyBrightness(value: Float) {
        val attrs = window.attributes
        attrs.screenBrightness = value.coerceIn(
            CctvWebGestureController.MIN_BRIGHTNESS,
            CctvWebGestureController.MAX_BRIGHTNESS,
        )
        window.attributes = attrs
    }

    // ---------------- 首帧对齐黑幕与尾帧冻结 ----------------

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun restoreChromeVisibility() {
        if (::chrome.isInitialized && chrome.rootView.visibility != View.VISIBLE) {
            chrome.rootView.visibility = View.VISIBLE
        }
    }

    private fun cancelPendingPixelCopy() {
        pixelCopyTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        pixelCopyTimeoutRunnable = null
        pendingChoreographerCallback?.let { cb ->
            runCatching { Choreographer.getInstance().removeFrameCallback(cb) }
        }
        pendingChoreographerCallback = null
        restoreChromeVisibility()
    }

    private fun applyLastFrameBitmapToView(bitmap: Bitmap) {
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
        if (::lastFrameImageView.isInitialized) {
            lastFrameImageView.animate().cancel()
            lastFrameImageView.alpha = 1f
            lastFrameImageView.visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lastFrameImageView.setRenderEffect(null)
            }
            lastFrameImageView.setImageDrawable(null)
        }
        lastFrameBitmap?.let { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        lastFrameBitmap = null
    }

    private fun hasVisibleLastFrame(): Boolean =
        ::lastFrameImageView.isInitialized &&
            lastFrameImageView.visibility == View.VISIBLE &&
            lastFrameBitmap?.let { !it.isRecycled } == true

    private fun switchingDisplayName(): String =
        currentChannelName.ifBlank { currentChannelId }

    private fun showOverlay(translucentForLastFrame: Boolean = false) {
        restoreChromeVisibility()
        loadingOverlay?.let {
            it.animate().cancel()
            root.removeView(it)
        }
        if (!translucentForLastFrame) {
            clearLastFrameView()
        }
        val scrimColor = when {
            !translucentForLastFrame -> Color.BLACK
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> LAST_FRAME_SCRIM_BLUR_COLOR
            else -> LAST_FRAME_SCRIM_FALLBACK_COLOR
        }
        val spinnerSize = dp(20)
        val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(LOADING_CAPSULE_TINT_COLOR)
        }
        val statusText = TextView(this).apply {
            text = getString(R.string.cctv_web_switching_to, switchingDisplayName())
            setTextColor(LOADING_CAPSULE_TINT_COLOR)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val capsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_player_loading_capsule)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            addView(spinner, LinearLayout.LayoutParams(spinnerSize, spinnerSize))
            addView(
                statusText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(10)
                },
            )
        }
        val box = FrameLayout(this).apply {
            setBackgroundColor(scrimColor)
            isClickable = true
            setOnClickListener {
                if (!chrome.isDrawerOpen) {
                    chrome.toggleControls()
                }
            }
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
        root.addView(box, matchParent())
        loadingOverlay = box
        // 确保共享外框位于遮罩之上：默认隐藏，用户主动轻点遮罩时可唤出顶/底栏或选台抽屉
        chrome.rootView.bringToFront()
    }

    /** 真首帧就绪或超时后 120ms 淡出黑幕/尾帧层并置 View.GONE 释放 Bitmap；按代次严格防竞态。 */
    private fun reveal(generation: Long) {
        if (!switchGeneration.isCurrent(generation)) return
        mainHandler.removeCallbacks(revealTimeoutRunnable)
        awaitingPageCommit = false
        restoreChromeVisibility()
        if (revealed) return
        revealed = true
        val box = loadingOverlay
        loadingOverlay = null
        val fadeLastFrame = ::lastFrameImageView.isInitialized && lastFrameImageView.visibility == View.VISIBLE
        if (box == null && !fadeLastFrame) return
        Log.i(TAG, "overlay reveal start gen=$generation playing=$isPlaying lastFrame=$fadeLastFrame")
        if (fadeLastFrame) {
            lastFrameImageView.animate().cancel()
            lastFrameImageView.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_MS)
                .withEndAction {
                    if (switchGeneration.isCurrent(generation)) {
                        clearLastFrameView()
                    }
                    if (box == null) {
                        Log.i(TAG, "overlay reveal end gen=$generation")
                    }
                }
                .start()
        }
        if (box != null) {
            box.animate().cancel()
            box.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_MS)
                .withEndAction {
                    root.removeView(box)
                    if (switchGeneration.isCurrent(generation) && !fadeLastFrame) {
                        clearLastFrameView()
                    }
                    Log.i(TAG, "overlay reveal end gen=$generation")
                }
                .start()
        }
    }

    private fun beginWebViewLoad(view: WebView, channelId: String, url: String, generation: Long) {
        if (!switchGeneration.isCurrent(generation) || isFinishing || isDestroyed) return
        webViewNavigationGeneration = generation
        activeTimeoutGeneration = generation
        startedAt = System.currentTimeMillis()
        mainHandler.removeCallbacks(revealTimeoutRunnable)
        mainHandler.postDelayed(revealTimeoutRunnable, CctvWebChannel.focusTimeoutMs(channelId))
        applyUserAgentForChannel(view, channelId)
        view.stopLoading()
        view.loadUrl(url)
    }

    private fun captureLastFrameAndLoad(view: WebView, channelId: String, url: String, generation: Long) {
        val decor = window?.decorView
        if (decor == null || !decor.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
            showOverlay(translucentForLastFrame = false)
            beginWebViewLoad(view, channelId, url, generation)
            return
        }
        // 同步取消全部外框/抽屉动画并将整个 chrome 浮层置 View.GONE，
        // 通过 Choreographer 连续等待 2 个 Vsync 帧确保 RenderThread 已向 SurfaceFlinger 提交纯净视频帧
        chrome.dismissAllImmediately()
        chrome.rootView.visibility = View.GONE
        val secondVsyncCallback = Choreographer.FrameCallback {
            pendingChoreographerCallback = null
            if (!switchGeneration.isCurrent(generation) || isFinishing || isDestroyed) {
                restoreChromeVisibility()
                return@FrameCallback
            }
            requestPixelCopyForLastFrame(view, channelId, url, generation)
        }
        val firstVsyncCallback = Choreographer.FrameCallback {
            if (!switchGeneration.isCurrent(generation) || isFinishing || isDestroyed) {
                pendingChoreographerCallback = null
                restoreChromeVisibility()
                return@FrameCallback
            }
            pendingChoreographerCallback = secondVsyncCallback
            Choreographer.getInstance().postFrameCallback(secondVsyncCallback)
        }
        pendingChoreographerCallback = firstVsyncCallback
        Choreographer.getInstance().postFrameCallback(firstVsyncCallback)
    }

    private fun requestPixelCopyForLastFrame(
        view: WebView,
        channelId: String,
        url: String,
        generation: Long,
    ) {
        val decor = window?.decorView
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val decorW = decor?.width ?: 0
        val decorH = decor?.height ?: 0
        val left = loc[0].coerceIn(0, decorW)
        val top = loc[1].coerceIn(0, decorH)
        val right = (loc[0] + view.width).coerceIn(left, decorW)
        val bottom = (loc[1] + view.height).coerceIn(top, decorH)
        val rectW = right - left
        val rectH = bottom - top
        if (rectW <= 0 || rectH <= 0) {
            showOverlay(translucentForLastFrame = false)
            beginWebViewLoad(view, channelId, url, generation)
            return
        }

        val scale = minOf(1f, MAX_LAST_FRAME_EDGE_PX.toFloat() / maxOf(rectW, rectH).toFloat())
        val dstW = maxOf(1, (rectW * scale).toInt())
        val dstH = maxOf(1, (rectH * scale).toInt())
        val bitmap = runCatching {
            Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) {
            showOverlay(translucentForLastFrame = false)
            beginWebViewLoad(view, channelId, url, generation)
            return
        }

        val completed = AtomicBoolean(false)
        val startNs = System.nanoTime()
        val timeoutTask = Runnable {
            if (!completed.compareAndSet(false, true)) return@Runnable
            pixelCopyTimeoutRunnable = null
            if (!switchGeneration.isCurrent(generation) || isFinishing || isDestroyed) {
                restoreChromeVisibility()
                return@Runnable
            }
            Log.w(TAG, "pixelCopy timeout gen=$generation -> fallback black overlay")
            showOverlay(translucentForLastFrame = false)
            beginWebViewLoad(view, channelId, url, generation)
        }
        pixelCopyTimeoutRunnable = timeoutTask
        mainHandler.postDelayed(timeoutTask, PIXEL_COPY_TIMEOUT_MS)

        val srcRect = Rect(left, top, right, bottom)
        val requested = runCatching {
            PixelCopy.request(
                window,
                srcRect,
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
                    if (!switchGeneration.isCurrent(generation) || isFinishing || isDestroyed) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        restoreChromeVisibility()
                        return@request
                    }
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    val nonBlack = copyResult == PixelCopy.SUCCESS && isBitmapNonBlack(bitmap)
                    if (nonBlack) {
                        Log.i(
                            TAG,
                            "pixelCopy ok gen=$generation w=${bitmap.width} h=${bitmap.height} ms=$elapsedMs nonBlack=true",
                        )
                        applyLastFrameBitmapToView(bitmap)
                        showOverlay(translucentForLastFrame = true)
                    } else {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        Log.w(
                            TAG,
                            "pixelCopy fallback gen=$generation result=$copyResult nonBlack=false ms=$elapsedMs",
                        )
                        showOverlay(translucentForLastFrame = false)
                    }
                    beginWebViewLoad(view, channelId, url, generation)
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
            Log.w(TAG, "pixelCopy request failed gen=$generation -> fallback black overlay")
            showOverlay(translucentForLastFrame = false)
            beginWebViewLoad(view, channelId, url, generation)
        }
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

    // ---------------- 纯视频 / 网页控件 ----------------

    private fun onPlaybackDetected(generation: Long) {
        if (!switchGeneration.isCurrent(generation)) return
        if (isPlaying) return
        isPlaying = true
        gestureController.isPlaying = true
        gestureController.isNativeControlsVisible = nativeControlsVisible
        mainHandler.removeCallbacks(pollRunnable)

        if (nativeControlsVisible) {
            if (isInPictureInPictureMode) {
                webView?.evaluateJavascript(CctvWebViewportFocusScript.SET_PURE_VIDEO_ON_SCRIPT, null)
            } else {
                webView?.evaluateJavascript(CctvWebViewportFocusScript.SET_PURE_VIDEO_OFF_SCRIPT, null)
                chrome.setWebNativeControlsVisible(true)
            }
        } else {
            webView?.evaluateJavascript(CctvWebViewportFocusScript.SET_PURE_VIDEO_ON_SCRIPT, null)
            chrome.setWebNativeControlsVisible(false)
        }
        chrome.setPlaying(true)
        updateAutoPipParams()
        if (CctvWebChannel.isVodPoolChannel(currentChannelId)) {
            activeVodEndedGeneration = generation
            mainHandler.removeCallbacks(vodEndedPollRunnable)
            mainHandler.postDelayed(vodEndedPollRunnable, VOD_ENDED_POLL_INTERVAL_MS)
        }
    }

    private fun enterPureVideo() {
        nativeControlsVisible = false
        MobileUiPreferences.setCctvWebControlsVisible(this, false)
        gestureController.isNativeControlsVisible = false
        webView?.evaluateJavascript(CctvWebViewportFocusScript.SET_PURE_VIDEO_ON_SCRIPT, null)
        chrome.setWebNativeControlsVisible(false)
    }

    private fun exitPureVideo() {
        nativeControlsVisible = true
        MobileUiPreferences.setCctvWebControlsVisible(this, true)
        gestureController.isNativeControlsVisible = true
        webView?.evaluateJavascript(CctvWebViewportFocusScript.SET_PURE_VIDEO_OFF_SCRIPT, null)
        // 「网页控件」态由共享外框保持顶栏常驻（露出「退出原控」）、底栏自动隐藏避免与网页底栏重叠
        chrome.setWebNativeControlsVisible(true)
    }

    private fun toggleNativeControls() {
        if (nativeControlsVisible) enterPureVideo() else exitPureVideo()
    }

    // ---------------- 抽屉与切台 ----------------

    private fun ensureCatalogLoaded() {
        if (!app.catalogStore.isEmpty()) return
        app.compositeCatalogCoordinator.refresh { loaded ->
            if (isFinishing || isDestroyed) return@refresh
            categories = CctvWebSwitchPolicy.resolveCategories(loaded)
            currentChannelName = CctvWebSwitchPolicy.resolveChannelName(
                channelId = currentChannelId,
                categories = categories,
                fallbackName = currentChannelName,
            )
            chrome.setTitle(displayTitleForCurrentChannel())
            chrome.setCatalog(categories, currentChannelId)
        }
    }

    private fun handleChannelPicked(channel: Channel) {
        when (val action = CctvWebSwitchPolicy.decide(currentChannelId, channel)) {
            CctvWebSwitchAction.NoOp -> chrome.closeDrawer()

            CctvWebSwitchAction.UnsupportedToast -> {
                Toast.makeText(this, R.string.cctv_web_unsupported, Toast.LENGTH_SHORT).show()
            }

            is CctvWebSwitchAction.ReloadInPlace -> {
                val targetUrl = action.url.ifBlank { resolveTargetUrl(action.channelId, avoidCurrentVod = true) }
                if (targetUrl == null) {
                    Toast.makeText(this, R.string.cctv_web_unsupported, Toast.LENGTH_SHORT).show()
                } else {
                    chrome.dismissAllImmediately()
                    reloadChannel(action.channelId, action.channelName, targetUrl)
                }
            }

            is CctvWebSwitchAction.SwitchToNativePlayer -> {
                startCrossEngineSwitchToNativePlayer(action.channel)
            }
        }
    }

    private fun startCrossEngineSwitchToNativePlayer(targetChannel: Channel) {
        if (isSwitchingEngine) return
        val generation = switchGeneration.nextGeneration()
        val wasPlaying = isPlaying
        isSwitchingEngine = true
        updateAutoPipParams()
        cancelPendingPixelCopy()
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(vodEndedPollRunnable)
        mainHandler.removeCallbacks(revealTimeoutRunnable)
        currentChannelName = targetChannel.name
        chrome.dismissAllImmediately()

        val view = webView
        val decor = window?.decorView
        val canCapture = wasPlaying &&
            !isInPictureInPictureMode &&
            !suspendedInBackground &&
            view != null &&
            view.visibility == View.VISIBLE &&
            view.width > 0 &&
            view.height > 0 &&
            decor != null &&
            decor.isAttachedToWindow

        if (!wasPlaying && hasVisibleLastFrame()) {
            val copy = runCatching { lastFrameBitmap?.copy(Bitmap.Config.RGB_565, false) }.getOrNull()
            showOverlay(translucentForLastFrame = true)
            completeCrossEngineSwitchToNativePlayer(targetChannel, copy)
            return
        }

        if (!canCapture) {
            showOverlay(translucentForLastFrame = false)
            completeCrossEngineSwitchToNativePlayer(targetChannel, null)
            return
        }

        chrome.rootView.visibility = View.GONE
        val secondVsync = Choreographer.FrameCallback {
            pendingChoreographerCallback = null
            if (!switchGeneration.isCurrent(generation) || isFinishing || isDestroyed) {
                restoreChromeVisibility()
                return@FrameCallback
            }
            captureCrossEngineLastFrameAndFinish(view!!, targetChannel, generation)
        }
        val firstVsync = Choreographer.FrameCallback {
            if (!switchGeneration.isCurrent(generation) || isFinishing || isDestroyed) {
                pendingChoreographerCallback = null
                restoreChromeVisibility()
                return@FrameCallback
            }
            pendingChoreographerCallback = secondVsync
            Choreographer.getInstance().postFrameCallback(secondVsync)
        }
        pendingChoreographerCallback = firstVsync
        Choreographer.getInstance().postFrameCallback(firstVsync)
    }

    private fun captureCrossEngineLastFrameAndFinish(
        view: WebView,
        targetChannel: Channel,
        generation: Long,
    ) {
        val decor = window?.decorView
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val decorW = decor?.width ?: 0
        val decorH = decor?.height ?: 0
        val left = loc[0].coerceIn(0, decorW)
        val top = loc[1].coerceIn(0, decorH)
        val right = (loc[0] + view.width).coerceIn(left, decorW)
        val bottom = (loc[1] + view.height).coerceIn(top, decorH)
        val rectW = right - left
        val rectH = bottom - top
        if (rectW <= 0 || rectH <= 0) {
            showOverlay(translucentForLastFrame = false)
            completeCrossEngineSwitchToNativePlayer(targetChannel, null)
            return
        }

        val scale = minOf(1f, MAX_LAST_FRAME_EDGE_PX.toFloat() / maxOf(rectW, rectH).toFloat())
        val dstW = maxOf(1, (rectW * scale).toInt())
        val dstH = maxOf(1, (rectH * scale).toInt())
        val bitmap = runCatching {
            Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) {
            showOverlay(translucentForLastFrame = false)
            completeCrossEngineSwitchToNativePlayer(targetChannel, null)
            return
        }

        val completed = AtomicBoolean(false)
        val startNs = System.nanoTime()
        val timeoutTask = Runnable {
            if (!completed.compareAndSet(false, true)) return@Runnable
            pixelCopyTimeoutRunnable = null
            if (!switchGeneration.isCurrent(generation) || isFinishing || isDestroyed) return@Runnable
            Log.w(TAG, "cctv->mgtv pixelCopy timeout gen=$generation")
            showOverlay(translucentForLastFrame = false)
            completeCrossEngineSwitchToNativePlayer(targetChannel, null)
        }
        pixelCopyTimeoutRunnable = timeoutTask
        mainHandler.postDelayed(timeoutTask, PIXEL_COPY_TIMEOUT_MS)

        val srcRect = Rect(left, top, right, bottom)
        val requested = runCatching {
            PixelCopy.request(
                window,
                srcRect,
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
                    if (!switchGeneration.isCurrent(generation) || isFinishing || isDestroyed) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return@request
                    }
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    val nonBlack = copyResult == PixelCopy.SUCCESS && isBitmapNonBlack(bitmap)
                    if (nonBlack) {
                        Log.i(
                            TAG,
                            "cctv->mgtv pixelCopy ok gen=$generation w=${bitmap.width} h=${bitmap.height} ms=$elapsedMs nonBlack=true",
                        )
                        val handoffCopy = runCatching { bitmap.copy(Bitmap.Config.RGB_565, false) }.getOrNull()
                        applyLastFrameBitmapToView(bitmap)
                        showOverlay(translucentForLastFrame = true)
                        completeCrossEngineSwitchToNativePlayer(targetChannel, handoffCopy)
                    } else {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        showOverlay(translucentForLastFrame = false)
                        completeCrossEngineSwitchToNativePlayer(targetChannel, null)
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
            showOverlay(translucentForLastFrame = false)
            completeCrossEngineSwitchToNativePlayer(targetChannel, null)
        }
    }

    private fun completeCrossEngineSwitchToNativePlayer(
        targetChannel: Channel,
        handoffBitmap: Bitmap?,
    ) {
        if (isFinishing || isDestroyed) {
            if (handoffBitmap != null && !handoffBitmap.isRecycled) handoffBitmap.recycle()
            return
        }
        MobilePlayerTransitionHelper.stashCrossEngineLastFrame(handoffBitmap)
        MobileUiPreferences.setLastPlayedChannelId(this, targetChannel.id)
        destroyWebView()
        val targetIntent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, targetChannel.id)
            .putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, targetChannel.name)
            .putExtra(PlayerActivity.EXTRA_STREAM_URL, targetChannel.streamUrl)
        MobilePlayerTransitionHelper.startSeamlessCrossEngineSwitch(
            from = this,
            targetIntent = targetIntent,
            currentRequestedOrientation = requestedOrientation,
        )
        finish()
    }

    /** 同页复用 WebView 切台或重试：递增代次防竞态，播放态下优先 PixelCopy 尾帧冻结过渡，不重建 Activity。 */
    private fun reloadChannel(channelId: String, channelName: String, url: String) {
        val generation = switchGeneration.nextGeneration()
        val wasPlaying = isPlaying
        val keepExistingLastFrame = !wasPlaying && hasVisibleLastFrame()

        cancelPendingPixelCopy()
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(vodEndedPollRunnable)
        mainHandler.removeCallbacks(revealTimeoutRunnable)

        currentChannelId = channelId
        currentChannelName = CctvWebSwitchPolicy.resolveChannelName(
            channelId = channelId,
            categories = categories,
            fallbackName = channelName,
        )
        MobileUiPreferences.setLastPlayedChannelId(this, channelId)
        chrome.setTitle(displayTitleForCurrentChannel())
        chrome.setCatalog(categories, currentChannelId)
        updateFavoriteUi()

        isPlaying = false
        revealed = false
        hasLoadFailure = false
        awaitingPageCommit = true
        startedAt = System.currentTimeMillis()
        nativeControlsVisible = MobileUiPreferences.isCctvWebControlsVisible(this)
        gestureController.reset()
        gestureController.isNativeControlsVisible = nativeControlsVisible
        chrome.hideError()
        chrome.setPlaying(false)
        chrome.dismissAllImmediately()
        chrome.setWebNativeControlsVisible(nativeControlsVisible, revealTopBar = false)

        webView?.visibility = View.VISIBLE
        updateAutoPipParams()

        val view = webView ?: return
        val canCaptureLastFrame = wasPlaying &&
            !isInPictureInPictureMode &&
            !suspendedInBackground &&
            view.visibility == View.VISIBLE &&
            view.width > 0 &&
            view.height > 0
        when {
            canCaptureLastFrame -> {
                captureLastFrameAndLoad(view, channelId, url, generation)
            }

            keepExistingLastFrame -> {
                showOverlay(translucentForLastFrame = true)
                beginWebViewLoad(view, channelId, url, generation)
            }

            else -> {
                showOverlay(translucentForLastFrame = false)
                beginWebViewLoad(view, channelId, url, generation)
            }
        }
    }

    // ---------------- 脚本注入与错误态 ----------------

    private fun injectFocusScript(view: WebView, generation: Long) {
        if (!switchGeneration.isCurrent(generation)) return
        awaitingPageCommit = false
        view.evaluateJavascript(CctvWebViewportFocusScript.FOCUS_SCRIPT, null)
        if (nativeControlsVisible && !isInPictureInPictureMode) {
            view.evaluateJavascript(CctvWebViewportFocusScript.SET_PURE_VIDEO_OFF_SCRIPT, null)
        }
        if (!isPlaying) {
            schedulePoll(generation, POLL_INTERVAL_MS)
        }
    }

    private fun showLoadFailure(messageRes: Int, generation: Long) {
        if (!switchGeneration.isCurrent(generation)) return
        cancelPendingPixelCopy()
        mainHandler.removeCallbacksAndMessages(null)
        awaitingPageCommit = false
        suspendedInBackground = false
        hasLoadFailure = true
        updateAutoPipParams()
        clearLastFrameView()
        loadingOverlay?.let {
            it.animate().cancel()
            root.removeView(it)
        }
        loadingOverlay = null
        revealed = true
        webView?.visibility = View.GONE
        chrome.setPlaying(false)
        chrome.showError(
            message = getString(messageRes),
            showRetry = CctvWebChannel.isSupported(currentChannelId),
        )
    }

    private fun showUnavailableMessage(messageRes: Int) {
        hasLoadFailure = true
        chrome.setPlaying(false)
        chrome.showError(
            message = getString(messageRes),
            showRetry = false,
        )
    }

    // ---------------- 生命周期 ----------------

    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    private fun applyRequestedVideoFrameRate(target: View) {
        if (Build.VERSION.SDK_INT < 35) return
        val applied = runCatching {
            val method = View::class.java.getMethod(
                "setRequestedFrameRate",
                Float::class.javaPrimitiveType,
            )
            method.invoke(target, TARGET_VIDEO_FRAME_RATE_HZ)
            true
        }.getOrDefault(false)
        Log.i(TAG, "setRequestedFrameRate(" + TARGET_VIDEO_FRAME_RATE_HZ + ")=" + applied)
    }

    private fun suspendWebViewInBackground() {
        val view = webView ?: return
        if (isFinishing || isDestroyed || hasLoadFailure) return
        suspendedInBackground = true
        switchGeneration.nextGeneration()
        cancelPendingPixelCopy()
        clearLastFrameView()
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(vodEndedPollRunnable)
        mainHandler.removeCallbacks(revealTimeoutRunnable)
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
        view.stopLoading()
        view.loadUrl(ABOUT_BLANK)
        Log.i(TAG, "background hard suspend executed")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        isSwitchingEngine = false
        MobilePlayerTransitionHelper.applyIncomingNoTransitionIfNeeded(this, intent)
        MobilePlayerTransitionHelper.registerActiveMobilePlayer(this) { dismissForOtherEngine() }
        if (intent.hasExtra(PlayerTransitionAndPipPolicy.EXTRA_PREFERRED_ORIENTATION)) {
            requestedOrientation = MobilePlayerTransitionHelper.resolveInitialOrientation(
                isTvDevice = false,
                intent = intent,
            )
        }
        val latestStore = runCatching { app.catalogStore.getAllCategories() }.getOrDefault(emptyList())
        if (latestStore.isNotEmpty()) {
            categories = CctvWebSwitchPolicy.resolveCategories(latestStore)
        }
        val newChannelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: return
        val newChannelName = intent.getStringExtra(EXTRA_CHANNEL_NAME)
        val pageUrl = resolveTargetUrl(newChannelId, avoidCurrentVod = true)
        if (pageUrl == null) {
            Toast.makeText(this, R.string.cctv_web_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val sameChannel = CctvWebChannel.keyOf(newChannelId) == CctvWebChannel.keyOf(currentChannelId)
        if (sameChannel && webView != null && !hasLoadFailure && !suspendedInBackground) {
            chrome.setCatalog(categories, currentChannelId)
            updateAutoPipParams()
            return
        }
        if (suspendedInBackground) {
            suspendedInBackground = false
            mainHandler.removeCallbacks(backgroundSuspendRunnable)
            webView?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        if (webView == null) {
            val recreated = runCatching { buildWebView() }.getOrNull()
            if (recreated == null) {
                showUnavailableMessage(R.string.cctv_web_unavailable)
                return
            }
            webView = recreated
            touchContainer.addView(recreated, matchParent())
        }
        MobilePlayerTransitionHelper.consumeCrossEngineLastFrame(intent)?.let { crossBitmap ->
            isPlaying = false
            applyLastFrameBitmapToView(crossBitmap)
        }
        reloadChannel(
            channelId = newChannelId,
            channelName = CctvWebSwitchPolicy.resolveChannelName(newChannelId, categories, newChannelName),
            url = pageUrl,
        )
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(backgroundSuspendRunnable)
        if (suspendedInBackground && webView != null && !hasLoadFailure) {
            suspendedInBackground = false
            webView?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            val pageUrl = resolveTargetUrl(currentChannelId, avoidCurrentVod = false)
            if (pageUrl != null) {
                Log.i(TAG, "foreground resume reload: $currentChannelId")
                reloadChannel(currentChannelId, currentChannelName, pageUrl)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isSwitchingEngine = false
        webView?.onResume()
        webView?.resumeTimers()
        if (!isPlaying && !suspendedInBackground && webView != null) {
            schedulePoll(switchGeneration.current, POLL_INTERVAL_MS)
        } else if (isPlaying && !suspendedInBackground && CctvWebChannel.isVodPoolChannel(currentChannelId)) {
            activeVodEndedGeneration = switchGeneration.current
            mainHandler.removeCallbacks(vodEndedPollRunnable)
            mainHandler.postDelayed(vodEndedPollRunnable, VOD_ENDED_POLL_INTERVAL_MS)
        }
        applyImmersive()
        updateAutoPipParams()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPictureInPictureMode && canEnterPipNow()) {
            enterPipIfEligible(fromUserAction = false)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            Log.i(TAG, "entered picture-in-picture mode")
            chrome.setPictureInPictureMode(true)
            if (nativeControlsVisible) {
                // 小窗期间临时隐藏官方网页控件避免遮挡视频（不改写持久化偏好）
                webView?.evaluateJavascript(CctvWebViewportFocusScript.SET_PURE_VIDEO_ON_SCRIPT, null)
            }
        } else {
            Log.i(TAG, "exited picture-in-picture mode")
            chrome.setPictureInPictureMode(false)
            if (nativeControlsVisible && !isFinishing && !isDestroyed) {
                webView?.evaluateJavascript(CctvWebViewportFocusScript.SET_PURE_VIDEO_OFF_SCRIPT, null)
                chrome.setWebNativeControlsVisible(true)
            }
            applyImmersive()
            ViewCompat.requestApplyInsets(root)
            updateAutoPipParams()
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(vodEndedPollRunnable)
        if (!isInPictureInPictureMode) {
            webView?.onPause()
            webView?.pauseTimers()
        }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && webView != null) {
            // 无论正常退后台还是划掉关闭 PiP 小窗，均在 onStop 立即暂停 WebView 与定时器，
            // 彻底杜绝划掉小窗后 5 秒后台漏音。
            webView?.onPause()
            webView?.pauseTimers()
        }
        if (!isChangingConfigurations && !isFinishing && webView != null && !hasLoadFailure) {
            mainHandler.removeCallbacks(backgroundSuspendRunnable)
            mainHandler.postDelayed(backgroundSuspendRunnable, BACKGROUND_SUSPEND_DELAY_MS)
        }
    }

    private fun dismissForOtherEngine() {
        isSwitchingEngine = true
        destroyWebView()
        // 同 affinity 下 singleTask 新播放页与旧 PiP 共享同一 Task，
        // 仅 finish() 当前旧实例即可释放并保留新拉起的播放页，不得移除整个 Task。
        if (!isFinishing && !isDestroyed) {
            finish()
        }
    }

    private fun canEnterPipNow(): Boolean = PlayerTransitionAndPipPolicy.canEnterPip(
        isTvDevice = false,
        supportsPipFeature = supportsPip,
        isSwitchingEngine = isSwitchingEngine,
        isErrorState = hasLoadFailure,
        isPlaybackReady = isPlaying,
        isFinishingOrDestroyed = isFinishing || isDestroyed,
    )

    private fun buildPipParams(): PictureInPictureParams {
        val ratio = PlayerTransitionAndPipPolicy.resolvePipAspectRatio(16, 9)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(ratio.numerator, ratio.denominator))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
            builder.setAutoEnterEnabled(canEnterPipNow())
        }
        return builder.build()
    }

    private fun updateAutoPipParams() {
        if (!supportsPip || isFinishing || isDestroyed) return
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    private fun enterPipIfEligible(fromUserAction: Boolean) {
        if (!supportsPip) {
            if (fromUserAction) {
                Toast.makeText(this, R.string.player_pip_unsupported, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!canEnterPipNow()) {
            if (fromUserAction) {
                chrome.showHudText(getString(R.string.player_pip_not_ready))
            }
            return
        }
        chrome.setPictureInPictureMode(true)
        if (nativeControlsVisible) {
            webView?.evaluateJavascript(CctvWebViewportFocusScript.SET_PURE_VIDEO_ON_SCRIPT, null)
        }
        val entered = runCatching { enterPictureInPictureMode(buildPipParams()) }.getOrDefault(false)
        if (!entered) {
            chrome.setPictureInPictureMode(false)
            if (nativeControlsVisible) {
                webView?.evaluateJavascript(CctvWebViewportFocusScript.SET_PURE_VIDEO_OFF_SCRIPT, null)
                chrome.setWebNativeControlsVisible(true)
            }
        }
    }

    private fun destroyWebView() {
        suspendedInBackground = false
        switchGeneration.nextGeneration()
        cancelPendingPixelCopy()
        clearLastFrameView()
        mainHandler.removeCallbacksAndMessages(null)
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.onPause()
            view.pauseTimers()
            view.stopLoading()
            view.webChromeClient = null
            webChromeClient = null
            view.loadUrl(ABOUT_BLANK)
            view.clearHistory()
            view.removeAllViews()
            view.destroy()
        }
        webView = null
    }

    override fun onDestroy() {
        MobilePlayerTransitionHelper.unregisterActiveMobilePlayer(this)
        mainHandler.removeCallbacks(backgroundSuspendRunnable)
        cancelPendingPixelCopy()
        clearLastFrameView()
        mainHandler.removeCallbacksAndMessages(null)
        suspendedInBackground = false
        backCallback.isEnabled = false
        loadingOverlay?.animate()?.cancel()
        if (::chrome.isInitialized) chrome.release()

        if (customView != null) {
            webChromeClient?.onHideCustomView()
        }
        destroyWebView()
        loadingOverlay = null

        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }

        super.onDestroy()
    }

    companion object {
        /** 由手机端频道列表或播放页抽屉传入的 `cctv:` 频道 id。 */
        const val EXTRA_CHANNEL_ID = "com.livetv.android.extra.CCTV_CHANNEL_ID"
        const val EXTRA_CHANNEL_NAME = "com.livetv.android.extra.CCTV_CHANNEL_NAME"

        private const val DEFAULT_CHANNEL_ID = "cctv:cctv1"
        private const val ABOUT_BLANK = "about:blank"
        private const val TAG = "CctvWebPlayer"

        private const val STATE_ENDED = "ended"
        private const val STATE_PLAYING = "playing"
        private const val STATE_READY = "ready"

        private const val POLL_INTERVAL_MS = 80L
        private const val SLOW_POLL_AFTER_MS = 15_000L
        private const val SLOW_POLL_INTERVAL_MS = 1_000L
        private const val VOD_ENDED_POLL_INTERVAL_MS = 1_500L
        private const val OVERLAY_FADE_MS = 150L
        private const val PIXEL_COPY_TIMEOUT_MS = 180L
        private const val MAX_LAST_FRAME_EDGE_PX = 1280
        private const val LAST_FRAME_BLUR_RADIUS_PX = 24f
        private const val LAST_FRAME_SCRIM_BLUR_COLOR = 0x73000000.toInt()
        private const val LAST_FRAME_SCRIM_FALLBACK_COLOR = 0x8C000000.toInt()
        private const val LOADING_CAPSULE_TINT_COLOR = 0xE6FFFFFF.toInt()
        private const val TARGET_VIDEO_FRAME_RATE_HZ = 25f
        private const val BACKGROUND_SUSPEND_DELAY_MS = 5_000L

        /** 进程内记住最近一次 4K 彩蛋播放的 URL，确保退出重进与每次刷新必换不同节目。 */
        @Volatile
        private var lastVod4kUrl: String? = null
    }
}
