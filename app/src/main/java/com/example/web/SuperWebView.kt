package com.example.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.core.AdBlocker
import com.example.download.DetectedStream
import com.example.download.DownloadHelper

interface SuperWebViewListener {
    fun onPageTitleChanged(title: String)
    fun onUrlChanged(url: String)
    fun onLoadingProgress(progress: Int)
    fun onMediaStateUpdate(isPlaying: Boolean, title: String, url: String)
    fun onMediaTrackEnded(url: String)
    fun onPlayableStreamDetected(stream: DetectedStream)
    fun onAdsBlockedCountUpdated(count: Int)
}

/**
 * Custom isolated WebView engineered for uninterrupted background playback,
 * ad/tracker filtering, dual Guest/Logged-In cookie modes, UA switching,
 * and automatic detection of direct playable streams (.mp4, .m3u8, .mp3).
 */
class SuperWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var backgroundPlayEnabled: Boolean = true
    var adBlockerEnabled: Boolean = true
    var audioOnlyEnabled: Boolean = false
    var playbackSpeed: Float = 1.0f
    var dataSaverEnabled: Boolean = false
    var isGuestMode: Boolean = false
    var isDesktopUa: Boolean = false
    var listener: SuperWebViewListener? = null

    private var lastDetectedUrl: String = ""

    /**
     * Prevent Chromium from pausing HTML5 <video>/<audio> streams when the
     * Activity is backgrounded, minimized, or the screen is turned off.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (backgroundPlayEnabled && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    override fun onPause() {
        if (!backgroundPlayEnabled) {
            super.onPause()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configureStudioDefaults(
        desktopUa: Boolean,
        guestMode: Boolean,
        adBlock: Boolean,
        bgPlay: Boolean,
        dataSaver: Boolean,
        audioOnly: Boolean,
        speed: Float
    ) {
        this.isDesktopUa = desktopUa
        this.isGuestMode = guestMode
        this.adBlockerEnabled = adBlock
        this.backgroundPlayEnabled = bgPlay
        this.dataSaverEnabled = dataSaver
        this.audioOnlyEnabled = audioOnly
        this.playbackSpeed = speed

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = !guestMode
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Harden file/content access against untrusted web pages
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkImage = dataSaver
            cacheMode = if (guestMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            userAgentString = if (desktopUa) {
                StreamPlatform.DESKTOP_USER_AGENT
            } else {
                StreamPlatform.MOBILE_USER_AGENT
            }
        }

        applyCookiePolicy(guestMode)

        removeJavascriptInterface("OmniBridge")
        addJavascriptInterface(OmniJsBridge(), "OmniBridge")

        webViewClient = StudioWebViewClient()
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                listener?.onLoadingProgress(newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    listener?.onPageTitleChanged(title)
                }
            }
        }
    }

    fun applyCookiePolicy(guestMode: Boolean) {
        this.isGuestMode = guestMode
        settings.domStorageEnabled = !guestMode
        settings.cacheMode = if (guestMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(!guestMode)
        cookieManager.setAcceptThirdPartyCookies(this, !guestMode)
        if (!guestMode) {
            cookieManager.flush()
        }
    }

    fun applyUserAgent(desktopUa: Boolean) {
        this.isDesktopUa = desktopUa
        settings.userAgentString = if (desktopUa) {
            StreamPlatform.DESKTOP_USER_AGENT
        } else {
            StreamPlatform.MOBILE_USER_AGENT
        }
    }

    fun applyDataSaver(enabled: Boolean) {
        this.dataSaverEnabled = enabled
        settings.blockNetworkImage = enabled
    }

    fun injectCoreScripts() {
        val script = AdBlocker.buildCoreInjectionScript(
            adBlockEnabled = adBlockerEnabled,
            backgroundPlayEnabled = backgroundPlayEnabled,
            audioOnlyEnabled = audioOnlyEnabled,
            playbackSpeed = playbackSpeed
        )
        evaluateJavascript(script, null)
    }

    fun setHtml5PlaybackSpeed(speed: Float) {
        this.playbackSpeed = speed.coerceIn(0.25f, 3.0f)
        evaluateJavascript(AdBlocker.buildPlaybackSpeedScript(this.playbackSpeed), null)
    }

    fun setHtml5AudioOnly(enabled: Boolean) {
        this.audioOnlyEnabled = enabled
        evaluateJavascript(AdBlocker.buildAudioOnlyScript(enabled), null)
    }

    fun setHtml5Playing(shouldPlay: Boolean) {
        evaluateJavascript(AdBlocker.buildPlayPauseScript(shouldPlay), null)
    }

    fun seekHtml5BySeconds(deltaSeconds: Int) {
        evaluateJavascript(AdBlocker.buildSeekDeltaScript(deltaSeconds), null)
    }

    fun setHtml5LoopSingle(loopSingle: Boolean) {
        evaluateJavascript(AdBlocker.buildLoopScript(loopSingle), null)
    }

    fun loadSafeUrl(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return
        val normalized = when {
            trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://duckduckgo.com/?q=${Uri.encode(trimmed)}"
        }
        val uri = Uri.parse(normalized)
        val scheme = uri.scheme?.lowercase()
        if (scheme == "https" || scheme == "http") {
            loadUrl(normalized)
        }
    }

    private inner class OmniJsBridge {
        @JavascriptInterface
        fun onMediaStateChanged(isPlaying: Boolean, title: String, url: String) {
            post {
                listener?.onMediaStateUpdate(
                    isPlaying = isPlaying,
                    title = title.ifBlank { this@SuperWebView.title ?: "OmniStream Media" },
                    url = url.ifBlank { this@SuperWebView.url.orEmpty() }
                )
            }
        }

        @JavascriptInterface
        fun onMediaEnded(url: String) {
            post {
                listener?.onMediaTrackEnded(url.ifBlank { this@SuperWebView.url.orEmpty() })
            }
        }
    }

    private inner class StudioWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val uri = request?.url ?: return true
            val scheme = uri.scheme?.lowercase() ?: return true

            // Block forced external app redirects (intent://, youtube://, fb://, twitter://)
            // and dangerous schemes (javascript:, file:, content:)
            if (scheme != "http" && scheme != "https") {
                return true
            }

            if (adBlockerEnabled && AdBlocker.isAdRequest(uri)) {
                post { listener?.onAdsBlockedCountUpdated(AdBlocker.blockedCount) }
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            if (adBlockerEnabled && AdBlocker.isAdRequest(request?.url)) {
                post { listener?.onAdsBlockedCountUpdated(AdBlocker.blockedCount) }
                return AdBlocker.createEmptyResponse()
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onLoadResource(view: WebView?, url: String?) {
            super.onLoadResource(view, url)
            if (url.isNullOrBlank() || url == lastDetectedUrl) return
            val detected = DownloadHelper.detectMediaType(url)
            if (detected != null) {
                lastDetectedUrl = url
                val pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: "Detected Stream"
                post {
                    listener?.onPlayableStreamDetected(
                        DetectedStream(
                            url = url,
                            title = pageTitle,
                            mediaType = detected.first,
                            mimeType = detected.second
                        )
                    )
                }
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (!url.isNullOrBlank()) {
                listener?.onUrlChanged(url)
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (!url.isNullOrBlank()) {
                listener?.onUrlChanged(url)
            }
            injectCoreScripts()
        }
    }
}
