package com.livetv.android.webplayer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.livetv.android.LiveTvApplication
import com.livetv.android.R
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.ui.PlayerActivity
import com.livetv.android.ui.mobile.ChannelDrawerAdapter
import com.livetv.android.ui.mobile.ChannelDrawerModel
import com.livetv.android.ui.mobile.ChannelDrawerRow
import com.livetv.android.ui.mobile.MobileUiPreferences
import java.io.ByteArrayInputStream

/**
 * CCTV 手机端 WebView 播放页。
 *
 * 三阶段体验：
 * 1. **阶段一（视口聚焦 + 黑幕过渡）**：整页加载官方直播页，静态脚本把 `#player` 铺满视口并隐藏外围兄弟；
 *    画面就绪或超时后撤除黑幕，顶栏保持可见、单击透传网页起播，同时左/右半屏上下滑可调亮度与音量；
 * 2. **阶段二（纯视频）**：检测到 `<video>` 实际起播后停止高频轮询，对 `#player` 内部非媒体祖先路径控件
 *    施加 `opacity:0 + pointer-events:none`（**不**用 display:none，不搬移 DOM），顶栏 3.5 秒后自动隐藏，
 *    单击画面呼出/隐藏顶栏；
 * 3. **阶段三（原控切换）**：点击顶栏「原控 / 纯画」可随时显示或隐藏官方网页控制栏；「原控」态保持顶栏常驻，
 *    单击透传网页，垂直滑动仍可调亮度/音量。
 *
 * 边界约束：
 * - 只注入 [CctvWebViewportFocusScript] 静态常量，不模拟点击、不改播放状态；
 * - 只读访问 `ChannelCatalogStore` 用于右侧原生选台抽屉（不写入目录快照）；
 * - 跨源切台到 MGTV/IPTV 前严格销毁 WebView，防止音轨重叠与内存残留。
 */
class CctvWebPlayerActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var touchContainer: CctvTouchContainer
    private lateinit var topBar: LinearLayout
    private lateinit var topTitle: TextView
    private lateinit var controlsButton: TextView
    private lateinit var hud: TextView
    private lateinit var drawerScrim: View
    private lateinit var drawerPanel: FrameLayout
    private lateinit var drawerContent: LinearLayout
    private lateinit var drawerList: RecyclerView
    private lateinit var drawerAdapter: ChannelDrawerAdapter

    private var webView: WebView? = null
    private var loadingOverlay: FrameLayout? = null
    private var errorMessageView: TextView? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webChromeClient: WebChromeClient? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var gestureController: CctvWebGestureController

    private val app: LiveTvApplication get() = application as LiveTvApplication
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private var currentChannelId: String = DEFAULT_CHANNEL_ID
    private var currentChannelName: String = ""
    private var categories: List<ChannelCategory> = emptyList()
    private var currentDrawerRows: List<ChannelDrawerRow> = emptyList()

    private var revealed = false
    private var startedAt = 0L
    private var isPlaying = false
    private var nativeControlsVisible = false
    private var isDrawerOpen = false
    private var awaitingPageCommit = false
    private var suspendedInBackground = false

    private val backgroundSuspendRunnable = Runnable { suspendWebViewInBackground() }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                isDrawerOpen -> closeDrawer()
                customView != null -> webChromeClient?.onHideCustomView()
                else -> finish()
            }
        }
    }

    private val hideTopBarRunnable = Runnable { hideTopBar() }
    private val revealTimeoutRunnable = Runnable {
        awaitingPageCommit = false
        reveal()
        if (!isPlaying && webView != null) {
            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        }
    }

    private val hideHudRunnable = Runnable {
        hud.animate().cancel()
        hud.animate()
            .alpha(0f)
            .setDuration(HUD_FADE_DURATION_MS)
            .withEndAction {
                hud.visibility = View.GONE
                hud.alpha = 1f
            }
            .start()
    }

    private val pollRunnable: Runnable = Runnable { pollState() }

    private fun pollState() {
        val view = webView ?: return
        if (isPlaying || awaitingPageCommit) return

        val elapsed = System.currentTimeMillis() - startedAt
        if (!revealed && elapsed >= FOCUS_TIMEOUT_MS) {
            reveal()
        }
        val nextDelay = if (elapsed >= SLOW_POLL_AFTER_MS) SLOW_POLL_INTERVAL_MS else POLL_INTERVAL_MS

        view.evaluateJavascript(CctvWebViewportFocusScript.CHECK_STATE_SCRIPT) { raw ->
            if (isFinishing || isDestroyed || isPlaying || awaitingPageCommit) return@evaluateJavascript
            when (raw?.trim()?.trim('"')) {
                STATE_PLAYING -> {
                    onPlaybackDetected()
                    reveal()
                }

                STATE_READY -> {
                    // 视频元素已就位但首帧尚未起播：继续保留黑幕直到首帧播放或超时兜底，
                    // 避免提前撤幕暴露官方加载态与控制栏残影。
                    mainHandler.postDelayed(pollRunnable, nextDelay)
                }

                else -> {
                    mainHandler.postDelayed(pollRunnable, nextDelay)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gestureController = CctvWebGestureController(
            touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
        )

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        applyImmersive()

        onBackPressedDispatcher.addCallback(this, backCallback)

        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: DEFAULT_CHANNEL_ID
        val pageUrl = CctvWebChannel.liveUrl(channelId)
        if (pageUrl == null) {
            showUnavailableMessage(R.string.cctv_web_unsupported)
            return
        }

        val storeCategories = runCatching { app.catalogStore.getAllCategories() }.getOrDefault(emptyList())
        categories = CctvWebSwitchPolicy.resolveCategories(storeCategories)
        currentChannelId = channelId
        currentChannelName = CctvWebSwitchPolicy.resolveChannelName(
            channelId = channelId,
            categories = categories,
            fallbackName = intent.getStringExtra(EXTRA_CHANNEL_NAME),
        )
        MobileUiPreferences.setLastPlayedChannelId(this, channelId)

        val created = runCatching { buildWebView() }.getOrNull()
        if (created == null) {
            showUnavailableMessage(R.string.cctv_web_unavailable)
            return
        }
        webView = created

        touchContainer = CctvTouchContainer(this).apply {
            addView(created, matchParent())
        }
        root.addView(touchContainer, matchParent())

        buildTopBar()
        buildHud()
        buildDrawer()
        applySafeAreaInsets()

        // 阶段一（待起播）保持顶栏可见，方便随时返回或打开选台抽屉
        showTopBar(autoHide = false)
        showOverlay()
        ensureCatalogLoaded()

        startedAt = System.currentTimeMillis()
        awaitingPageCommit = true
        mainHandler.postDelayed(revealTimeoutRunnable, FOCUS_TIMEOUT_MS)
        created.loadUrl(pageUrl)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersive()
        ViewCompat.requestApplyInsets(root)
        if (isDrawerOpen) {
            scrollDrawerToCurrentChannel()
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
            userAgentString = CctvWebUserAgent.sanitize(userAgentString)
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
                injectFocusScript(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == ABOUT_BLANK) return
                injectFocusScript(view)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                awaitingPageCommit = false
                Log.w(TAG, "main frame error: " + error.errorCode)
                showLoadFailure(R.string.cctv_web_load_failed)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (!request.isForMainFrame) return
                awaitingPageCommit = false
                Log.w(TAG, "main frame http: " + errorResponse.statusCode)
                showLoadFailure(R.string.cctv_web_load_failed)
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
                (view.parent as? ViewGroup)?.removeView(view)
                view.webChromeClient = null
                webChromeClient = null
                view.removeAllViews()
                view.destroy()
                if (webView === view) webView = null
                suspendedInBackground = false
                mainHandler.removeCallbacks(backgroundSuspendRunnable)
                showLoadFailure(R.string.cctv_web_renderer_gone)
                return true
            }
        }

        val chrome = object : WebChromeClient() {
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
        webChromeClient = chrome
        view.webChromeClient = chrome
        return view
    }

    // ---------------- 手势拦截容器（包裹 WebView） ----------------

    /**
     * 包裹 [WebView] 的原生手势分流容器：
     * - 垂直滑动超 touchSlop：三态下均拦截并调节亮度（左半屏）或音量（右半屏）；
     * - 点击：仅在纯视频播放态拦截并切换原生顶栏；待起播或「原控」态透传给 [WebView]。
     */
    private inner class CctvTouchContainer(context: Context) : FrameLayout(context) {

        private var intercepted = false

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            // 防止 WebView 抢占触摸流后屏蔽待起播/原控态下的亮度与音量垂直手势
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (isDrawerOpen || loadingOverlay != null) return false

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
                            toggleTopBar()
                        }

                        GestureAction.EndAdjustment -> hideHudDelayed()
                        else -> Unit
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    intercepted = false
                    if (gestureController.onTouchCancel() == GestureAction.EndAdjustment) {
                        hideHudDelayed()
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
            showHud(getString(R.string.player_gesture_brightness, action.percent))
            true
        }

        is GestureAction.AdjustVolume -> {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, action.volume, 0)
            showHud(getString(R.string.player_gesture_volume, action.percent))
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

    // ---------------- 原生轻顶栏、HUD、抽屉、遮罩 ----------------

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildTopBar() {
        val topGradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xCC000000.toInt(), 0x00000000),
        )
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = topGradient
            setPadding(dp(10), dp(10), dp(10), dp(14))
            visibility = View.VISIBLE
        }

        val back = barButton(getString(R.string.cctv_web_back)).apply {
            setOnClickListener { finish() }
        }
        topTitle = TextView(this).apply {
            text = currentChannelName
            setTextColor(ContextCompat.getColor(this@CctvWebPlayerActivity, R.color.mobile_text_primary))
            textSize = 14f
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(8)
            }
        }
        controlsButton = barButton(getString(R.string.cctv_web_control_native)).apply {
            setOnClickListener { toggleNativeControls() }
        }
        val channels = barButton(getString(R.string.cctv_web_channel_drawer)).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(6)
            }
            setOnClickListener { openDrawer() }
        }

        topBar.addView(back)
        topBar.addView(topTitle)
        topBar.addView(controlsButton)
        topBar.addView(channels)

        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
    }

    private fun barButton(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(ContextCompat.getColor(this@CctvWebPlayerActivity, R.color.mobile_text_primary))
        textSize = 13f
        gravity = Gravity.CENTER
        minWidth = dp(48)
        minHeight = dp(48)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundResource(R.drawable.bg_player_touch_button)
        isClickable = true
        isFocusable = true
    }

    private fun buildHud() {
        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_player_badge)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            visibility = View.GONE
        }
        root.addView(
            hud,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
    }

    private fun buildDrawer() {
        drawerScrim = View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@CctvWebPlayerActivity, R.color.mobile_drawer_scrim))
            isClickable = true
            isFocusable = false
            contentDescription = getString(R.string.player_drawer_scrim_desc)
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        root.addView(drawerScrim, matchParent())

        drawerPanel = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_player_drawer)
            visibility = View.GONE
        }

        val leftEdge = View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@CctvWebPlayerActivity, R.color.mobile_drawer_edge))
        }
        drawerPanel.addView(
            leftEdge,
            FrameLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START),
        )

        drawerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val drawerTitle = TextView(this).apply {
            text = getString(R.string.player_drawer_title)
            setTextColor(ContextCompat.getColor(this@CctvWebPlayerActivity, R.color.mobile_text_tertiary))
            textSize = 12f
            setPadding(dp(18), dp(18), dp(18), dp(10))
        }
        drawerContent.addView(
            drawerTitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        drawerAdapter = ChannelDrawerAdapter { channel -> onChannelPicked(channel) }
        drawerList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CctvWebPlayerActivity)
            adapter = drawerAdapter
            clipToPadding = false
            setPadding(0, 0, 0, dp(18))
            isVerticalScrollBarEnabled = false
        }
        drawerContent.addView(
            drawerList,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        drawerPanel.addView(drawerContent, matchParent())
        root.addView(
            drawerPanel,
            FrameLayout.LayoutParams(
                dp(DRAWER_WIDTH_DP),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END,
            ),
        )
    }

    private fun applySafeAreaInsets() {
        val topBaseLeft = topBar.paddingLeft
        val topBaseTop = topBar.paddingTop
        val topBaseRight = topBar.paddingRight
        val topBaseBottom = topBar.paddingBottom

        val drawerBaseLeft = drawerContent.paddingLeft
        val drawerBaseTop = drawerContent.paddingTop
        val drawerBaseRight = drawerContent.paddingRight
        val drawerBaseBottom = drawerContent.paddingBottom
        val stableDrawerBottom = dp(DRAWER_STABLE_BOTTOM_MARGIN_DP)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(
                topBaseLeft + cutout.left,
                topBaseTop + cutout.top,
                topBaseRight + cutout.right,
                topBaseBottom,
            )
            drawerContent.setPadding(
                drawerBaseLeft,
                drawerBaseTop + cutout.top,
                drawerBaseRight + cutout.right,
                drawerBaseBottom + maxOf(cutout.bottom, stableDrawerBottom),
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun showOverlay() {
        loadingOverlay?.let {
            it.animate().cancel()
            root.removeView(it)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ProgressBar(this@CctvWebPlayerActivity).apply { isIndeterminate = true })
            addView(
                TextView(this@CctvWebPlayerActivity).apply {
                    text = getString(R.string.cctv_web_loading)
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(0, dp(12), 0, 0)
                },
            )
        }
        val box = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                column,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        root.addView(box, matchParent())
        loadingOverlay = box
        // 确保原生顶栏与抽屉始终位于加载黑幕之上，加载期间仍可返回或切台
        topBar.bringToFront()
        drawerScrim.bringToFront()
        drawerPanel.bringToFront()
    }

    /** 画面就绪或超时后淡出黑幕；幂等。 */
    private fun reveal() {
        mainHandler.removeCallbacks(revealTimeoutRunnable)
        awaitingPageCommit = false
        if (revealed) return
        revealed = true
        val box = loadingOverlay ?: return
        loadingOverlay = null
        Log.i(TAG, "overlay reveal start playing=$isPlaying")
        box.animate()
            .alpha(0f)
            .setDuration(OVERLAY_FADE_MS)
            .withEndAction {
                root.removeView(box)
                Log.i(TAG, "overlay reveal end")
            }
            .start()
    }

    // ---------------- 纯视频 / 原控与顶栏 ----------------

    private fun onPlaybackDetected() {
        if (isPlaying) return
        isPlaying = true
        gestureController.isPlaying = true
        mainHandler.removeCallbacks(pollRunnable)

        if (!nativeControlsVisible) {
            controlsButton.text = getString(R.string.cctv_web_control_native)
            webView?.evaluateJavascript(CctvWebViewportFocusScript.SET_PURE_VIDEO_ON_SCRIPT, null)
            if (!isDrawerOpen) {
                showTopBar(autoHide = true)
            }
        }
    }

    private fun enterPureVideo() {
        nativeControlsVisible = false
        gestureController.isNativeControlsVisible = false
        controlsButton.text = getString(R.string.cctv_web_control_native)
        webView?.evaluateJavascript(CctvWebViewportFocusScript.SET_PURE_VIDEO_ON_SCRIPT, null)
        showTopBar(autoHide = isPlaying)
    }

    private fun exitPureVideo() {
        nativeControlsVisible = true
        gestureController.isNativeControlsVisible = true
        controlsButton.text = getString(R.string.cctv_web_control_pure)
        webView?.evaluateJavascript(CctvWebViewportFocusScript.SET_PURE_VIDEO_OFF_SCRIPT, null)
        // 「原控」态保持顶栏可见，方便随时切回「纯画」或选台
        showTopBar(autoHide = false)
    }

    private fun toggleNativeControls() {
        if (nativeControlsVisible) enterPureVideo() else exitPureVideo()
    }

    private fun toggleTopBar() {
        if (topBar.visibility == View.VISIBLE) {
            hideTopBar()
        } else {
            showTopBar(autoHide = true)
        }
    }

    private fun showTopBar(autoHide: Boolean) {
        topBar.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideTopBarRunnable)
        if (autoHide && isPlaying && !nativeControlsVisible && !isDrawerOpen) {
            mainHandler.postDelayed(hideTopBarRunnable, CctvWebGestureController.TOP_BAR_AUTO_HIDE_MS)
        }
    }

    private fun hideTopBar() {
        mainHandler.removeCallbacks(hideTopBarRunnable)
        topBar.visibility = View.GONE
    }

    private fun showHud(text: String) {
        hud.animate().cancel()
        hud.text = text
        hud.alpha = 1f
        hud.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideHudRunnable)
    }

    private fun hideHudDelayed() {
        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.postDelayed(hideHudRunnable, CctvWebGestureController.HUD_FADE_DELAY_MS)
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
            topTitle.text = currentChannelName
            if (isDrawerOpen) {
                refreshDrawerRows()
            }
        }
    }

    private fun refreshDrawerRows() {
        val storeCategories = runCatching { app.catalogStore.getAllCategories() }.getOrDefault(emptyList())
        categories = CctvWebSwitchPolicy.resolveCategories(storeCategories)
        val rows = ChannelDrawerModel.buildRows(categories, currentChannelId)
        currentDrawerRows = rows
        drawerAdapter.updateRows(rows)
    }

    private fun openDrawer() {
        refreshDrawerRows()
        isDrawerOpen = true
        hideTopBar()

        drawerScrim.animate().cancel()
        drawerScrim.alpha = 0f
        drawerScrim.visibility = View.VISIBLE
        drawerScrim.animate().alpha(1f).setDuration(DRAWER_ANIM_MS).start()

        drawerPanel.animate().cancel()
        val slideOffset = if (drawerPanel.width > 0) {
            drawerPanel.width.toFloat()
        } else {
            dp(DRAWER_WIDTH_DP).toFloat()
        }
        drawerPanel.translationX = slideOffset
        drawerPanel.visibility = View.VISIBLE
        drawerPanel.animate().translationX(0f).setDuration(DRAWER_ANIM_MS).start()

        scrollDrawerToCurrentChannel()
    }

    private fun scrollDrawerToCurrentChannel() {
        val currentIndex = ChannelDrawerModel.currentRowIndex(currentDrawerRows)
        val targetIndex = if (currentIndex >= 0) {
            currentIndex
        } else {
            currentDrawerRows.indexOfFirst { it is ChannelDrawerRow.ChannelItem }
        }
        if (targetIndex >= 0) {
            drawerList.post {
                val lm = drawerList.layoutManager as? LinearLayoutManager
                val offset = drawerList.height / 3
                lm?.scrollToPositionWithOffset(targetIndex, offset)
                    ?: drawerList.scrollToPosition(targetIndex)
            }
        }
    }

    private fun closeDrawer(restoreTopBar: Boolean = true) {
        if (!isDrawerOpen) return
        isDrawerOpen = false

        drawerScrim.animate().cancel()
        drawerScrim.animate()
            .alpha(0f)
            .setDuration(DRAWER_ANIM_MS)
            .withEndAction {
                drawerScrim.visibility = View.GONE
                drawerScrim.alpha = 1f
            }
            .start()

        drawerPanel.animate().cancel()
        val slideOffset = if (drawerPanel.width > 0) {
            drawerPanel.width.toFloat()
        } else {
            dp(DRAWER_WIDTH_DP).toFloat()
        }
        drawerPanel.animate()
            .translationX(slideOffset)
            .setDuration(DRAWER_ANIM_MS)
            .withEndAction {
                drawerPanel.visibility = View.GONE
                drawerPanel.translationX = 0f
            }
            .start()

        if (restoreTopBar && (!isPlaying || nativeControlsVisible)) {
            showTopBar(autoHide = false)
        }
    }

    private fun onChannelPicked(channel: Channel) {
        when (val action = CctvWebSwitchPolicy.decide(currentChannelId, channel)) {
            CctvWebSwitchAction.NoOp -> closeDrawer()

            CctvWebSwitchAction.UnsupportedToast -> {
                Toast.makeText(this, R.string.cctv_web_unsupported, Toast.LENGTH_SHORT).show()
            }

            is CctvWebSwitchAction.ReloadInPlace -> {
                closeDrawer(restoreTopBar = false)
                reloadChannel(action.channelId, action.channelName, action.url)
            }

            is CctvWebSwitchAction.SwitchToNativePlayer -> {
                closeDrawer(restoreTopBar = false)
                MobileUiPreferences.setLastPlayedChannelId(this, action.channel.id)
                destroyWebView()
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, action.channel.id)
                        .putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, action.channel.name)
                        .putExtra(PlayerActivity.EXTRA_STREAM_URL, action.channel.streamUrl),
                )
                finish()
            }
        }
    }

    /** 同页复用 WebView 切台：重置到阶段一待起播态并黑幕过渡，不重建 Activity。 */
    private fun reloadChannel(channelId: String, channelName: String, url: String) {
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(hideTopBarRunnable)
        mainHandler.removeCallbacks(revealTimeoutRunnable)

        currentChannelId = channelId
        currentChannelName = CctvWebSwitchPolicy.resolveChannelName(
            channelId = channelId,
            categories = categories,
            fallbackName = channelName,
        )
        MobileUiPreferences.setLastPlayedChannelId(this, channelId)
        topTitle.text = currentChannelName

        isPlaying = false
        nativeControlsVisible = false
        revealed = false
        awaitingPageCommit = true
        startedAt = System.currentTimeMillis()
        gestureController.reset()
        controlsButton.text = getString(R.string.cctv_web_control_native)

        errorMessageView?.let { root.removeView(it) }
        errorMessageView = null
        webView?.visibility = View.VISIBLE
        showTopBar(autoHide = false)
        showOverlay()

        // 先停止旧页并等待新页 onPageCommitVisible 再注入与轮询，避免读到旧页残留的 __livetvPlaying
        mainHandler.postDelayed(revealTimeoutRunnable, FOCUS_TIMEOUT_MS)
        webView?.stopLoading()
        webView?.loadUrl(url)
    }

    // ---------------- 脚本注入与错误态 ----------------

    private fun injectFocusScript(view: WebView) {
        awaitingPageCommit = false
        view.evaluateJavascript(CctvWebViewportFocusScript.FOCUS_SCRIPT, null)
        if (!isPlaying) {
            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        }
    }

    private fun showLoadFailure(messageRes: Int) {
        mainHandler.removeCallbacksAndMessages(null)
        awaitingPageCommit = false
        suspendedInBackground = false
        loadingOverlay?.let {
            it.animate().cancel()
            root.removeView(it)
        }
        loadingOverlay = null
        revealed = true
        hud.visibility = View.GONE
        webView?.visibility = View.GONE
        showTopBar(autoHide = false)
        showUnavailableMessage(messageRes)
    }

    private fun showUnavailableMessage(messageRes: Int) {
        errorMessageView?.let { root.removeView(it) }
        val message = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = getString(messageRes)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        errorMessageView = message
        root.addView(message, matchParent())
        if (::topBar.isInitialized) {
            topBar.bringToFront()
            drawerScrim.bringToFront()
            drawerPanel.bringToFront()
        }
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
        if (isFinishing || isDestroyed || errorMessageView != null) return
        suspendedInBackground = true
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(revealTimeoutRunnable)
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
        view.stopLoading()
        view.loadUrl(ABOUT_BLANK)
        Log.i(TAG, "background hard suspend executed")
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(backgroundSuspendRunnable)
        if (suspendedInBackground && webView != null && errorMessageView == null) {
            suspendedInBackground = false
            webView?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            val pageUrl = CctvWebChannel.liveUrl(currentChannelId)
            if (pageUrl != null) {
                Log.i(TAG, "foreground resume reload: $currentChannelId")
                reloadChannel(currentChannelId, currentChannelName, pageUrl)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
        if (!isPlaying && !suspendedInBackground && webView != null) {
            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        }
        applyImmersive()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(pollRunnable)
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing && webView != null && errorMessageView == null) {
            mainHandler.removeCallbacks(backgroundSuspendRunnable)
            mainHandler.postDelayed(backgroundSuspendRunnable, BACKGROUND_SUSPEND_DELAY_MS)
        }
    }

    private fun destroyWebView() {
        suspendedInBackground = false
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
        mainHandler.removeCallbacks(backgroundSuspendRunnable)
        mainHandler.removeCallbacksAndMessages(null)
        suspendedInBackground = false
        backCallback.isEnabled = false
        loadingOverlay?.animate()?.cancel()
        if (::hud.isInitialized) hud.animate().cancel()
        if (::drawerScrim.isInitialized) drawerScrim.animate().cancel()
        if (::drawerPanel.isInitialized) drawerPanel.animate().cancel()

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

        private const val STATE_PLAYING = "playing"
        private const val STATE_READY = "ready"

        private const val POLL_INTERVAL_MS = 80L
        private const val SLOW_POLL_AFTER_MS = 15_000L
        private const val SLOW_POLL_INTERVAL_MS = 1_000L
        private const val FOCUS_TIMEOUT_MS = 7_000L
        private const val OVERLAY_FADE_MS = 120L
        private const val HUD_FADE_DURATION_MS = 200L
        private const val DRAWER_ANIM_MS = 220L
        private const val DRAWER_WIDTH_DP = 280
        private const val DRAWER_STABLE_BOTTOM_MARGIN_DP = 8
        private const val TARGET_VIDEO_FRAME_RATE_HZ = 25f
        private const val BACKGROUND_SUSPEND_DELAY_MS = 5_000L
    }
}
