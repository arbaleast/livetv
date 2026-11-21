package com.livetv.android.webplayer

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.livetv.android.R

/**
 * CCTV 手机端 WebView 播放页：整页加载官方直播页，再用**静态脚本**把 `#player` 聚焦到视口。
 *
 * 边界：
 * - 只注入 [CctvWebViewportFocusScript] 中的**静态常量**脚本，不拼接任何外部输入；
 * - 不搬移 DOM、不模拟点击、不改播放状态、不隐藏播放器内部元素（片头广告/台标保持原样）；
 * - 顶层导航走 [CctvWebNavigationPolicy] 白名单（仅 https 官方域）；
 * - 黑幕遮罩在画面就绪或超时后淡出，避免"永久黑屏"。
 */
class CctvWebPlayerActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout

    private var webView: WebView? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlay: FrameLayout? = null
    private var revealed = false
    private var startedAt = 0L

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (customView != null) {
                webChromeClient?.onHideCustomView()
            } else {
                finish()
            }
        }
    }

    private var webChromeClient: WebChromeClient? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            val view = webView ?: return
            if (revealed) return
            if (System.currentTimeMillis() - startedAt >= FOCUS_TIMEOUT_MS) {
                reveal()
                return
            }
            view.evaluateJavascript(CctvWebViewportFocusScript.CHECK_READY_SCRIPT) { value ->
                if (revealed) return@evaluateJavascript
                if (value?.trim()?.trim('"') == "true") {
                    reveal()
                } else {
                    mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        applyImmersive()

        onBackPressedDispatcher.addCallback(this, backCallback)

        // 不支持的频道：在创建 WebView / 遮罩之前直接短路，避免白拉起 Chromium 内核
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: DEFAULT_CHANNEL_ID
        val pageUrl = CctvWebChannel.liveUrl(channelId)
        if (pageUrl == null) {
            showUnavailableMessage(R.string.cctv_web_unsupported)
            return
        }

        val created = runCatching { buildWebView() }.getOrNull()
        if (created == null) {
            showUnavailableMessage(R.string.cctv_web_unavailable)
            return
        }
        webView = created
        root.addView(
            created,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        showLoadingOverlay()
        startedAt = System.currentTimeMillis()
        created.loadUrl(pageUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val view = WebView(this)
        view.setBackgroundColor(Color.BLACK)

        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setGeolocationEnabled(false)
        settings.setSaveFormData(false)
        settings.userAgentString = CctvWebUserAgent.sanitize(settings.userAgentString)

        view.webViewClient = object : WebViewClient() {
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
                injectFocusScript(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectFocusScript(view)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                Log.w(TAG, "main frame error: " + error.errorCode)
                showLoadFailure(R.string.cctv_web_load_failed)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse,
            ) {
                if (!request.isForMainFrame) return
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
                showLoadFailure(R.string.cctv_web_renderer_gone)
                return true
            }
        }

        val chrome = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                root.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                webView?.visibility = View.GONE
                applyImmersive()
            }

            override fun onHideCustomView() {
                val view = customView ?: return
                root.removeView(view)
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

    /** 只注入静态常量脚本；同一页面重复回调时由脚本内部幂等守卫兜底。 */
    private fun injectFocusScript(view: WebView) {
        if (revealed) return
        view.evaluateJavascript(CctvWebViewportFocusScript.FOCUS_SCRIPT, null)
        if (!isPolling) {
            isPolling = true
            mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        }
    }

    private var isPolling = false

    private fun showLoadingOverlay() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ProgressBar(this@CctvWebPlayerActivity).apply { isIndeterminate = true })
            addView(
                TextView(this@CctvWebPlayerActivity).apply {
                    text = getString(R.string.cctv_web_loading)
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(0, 24, 0, 0)
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
        root.addView(
            box,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay = box
    }

    /** 画面就绪或超时后淡出黑幕；幂等。 */
    private fun reveal() {
        if (revealed) return
        revealed = true
        isPolling = false
        mainHandler.removeCallbacksAndMessages(null)
        val box = overlay ?: return
        box.animate()
            .alpha(0f)
            .setDuration(OVERLAY_FADE_MS)
            .withEndAction {
                root.removeView(box)
                if (overlay === box) overlay = null
            }
            .start()
    }

    private fun showLoadFailure(messageRes: Int) {
        mainHandler.removeCallbacksAndMessages(null)
        isPolling = false
        revealed = true
        overlay?.let { root.removeView(it) }
        overlay = null
        webView?.visibility = View.GONE
        showUnavailableMessage(messageRes)
    }

    private fun showUnavailableMessage(messageRes: Int) {
        val message = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = getString(messageRes)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(
            message,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

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

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
        applyImmersive()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        backCallback.isEnabled = false

        if (customView != null) {
            webChromeClient?.onHideCustomView()
        }

        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.webChromeClient = null
            webChromeClient = null
            view.loadUrl(ABOUT_BLANK)
            view.clearHistory()
            view.removeAllViews()
            view.destroy()
        }
        webView = null
        overlay = null

        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }

        super.onDestroy()
    }

    companion object {
        /** 由手机端频道列表传入的 `cctv:` 频道 id。 */
        const val EXTRA_CHANNEL_ID = "com.livetv.android.extra.CCTV_CHANNEL_ID"

        private const val DEFAULT_CHANNEL_ID = "cctv:cctv1"
        private const val ABOUT_BLANK = "about:blank"
        private const val TAG = "CctvWebPlayer"

        private const val POLL_INTERVAL_MS = 250L
        private const val FOCUS_TIMEOUT_MS = 7_000L
        private const val OVERLAY_FADE_MS = 250L
    }
}
