package com.aho.streambrowser.detector

import android.graphics.Bitmap
import android.webkit.*
import com.aho.streambrowser.util.RequestBlocker
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowserWebViewClient(
    private val detector: StreamDetector,
    private val blocker: RequestBlocker,
    private val onPageStarted:  (url: String, favicon: Bitmap?) -> Unit,
    private val onPageFinished: (url: String) -> Unit
) : WebViewClient() {

    /** J1: SSL certificate error bypass (dev/reverse-engineering use only) */
    var sslBypassEnabled = false

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
        if (sslBypassEnabled) {
            handler.proceed()
        } else {
            super.onReceivedSslError(view, handler, error)
        }
    }


    private var currentUrl  = ""
    private var previousUrl = ""          // FIX: track previous URL separately
    private var isDestroyed = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val activeRequests = AtomicInteger(0)
    private val MAX_CONCURRENT = 8
    private val MAX_CAPTURE_BYTES = 64 * 1024
    private val MAX_PREVIEW_CHARS = 4_000

    // Activity-log: "JS Bridge ready" only needs to be logged once per app session
    private var bridgeReadyLogged = false
    // Fix: warn (once per page) when the response-fetch concurrency cap causes silent skips
    private var capWarningLogged = false

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val injectedUrls   = mutableSetOf<String>()
    private var lastInjectTime = 0L
    private val INJECT_COOLDOWN = 300L   // ms

    // ── Page lifecycle ───────────────────────────────────────────────────────

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (isDestroyed) return
        try {
            previousUrl = currentUrl      // FIX: save BEFORE overwriting
            currentUrl  = url

            // FIX: compare against previousUrl, not currentUrl (was always false!)
            if (url != previousUrl) {
                detector.clear()
                injectedUrls.clear()      // Reset injection tracking for new page
                capWarningLogged = false
            }

            if (shouldInjectJs(url)) injectJavaScript(view)
            onPageStarted(url, favicon)
        } catch (e: Exception) {
            onPageStarted(url, favicon)
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (isDestroyed) return
        try {
            // Re-inject for SPAs that finish loading without navigation
            if (shouldInjectJs(url)) injectJavaScript(view)
            onPageFinished(url)
        } catch (e: Exception) {
            onPageFinished(url)
        }
    }

    private fun shouldInjectJs(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.startsWith("about:") || url.startsWith("data:")) return false
        val now = System.currentTimeMillis()
        if (now - lastInjectTime < INJECT_COOLDOWN) return false
        if (url in injectedUrls) return false
        return true
    }

    private fun injectJavaScript(view: WebView) {
        try {
            val js = loadHookJs(view.context).replace("%(PROTECTED_DOMAINS)s",
                PROTECTED_DOMAINS.joinToString(",", "[", "]") { "\"$it\"" })
            view.evaluateJavascript(js) { result ->
                if (result != "null" && result != null) {
                    lastInjectTime = System.currentTimeMillis()
                    injectedUrls.add(currentUrl)
                    if (injectedUrls.size > 20) injectedUrls.remove(injectedUrls.iterator().next())
                    if (!bridgeReadyLogged) {
                        bridgeReadyLogged = true
                        detector.addLog("info", "StreamBrowser — JS Bridge ready", "system")
                    }
                    detector.addLog("success", "HOOK_JS injected successfully", "inject")
                }
            }
        } catch (_: Exception) {}
    }

    // ── Request intercept ────────────────────────────────────────────────────

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (isDestroyed) return null
        try {
            val url = request.url?.toString() ?: return null
            if (blocker.shouldBlock(url)) { blocker.incrementBlocked(); return emptyResponse() }
            detector.interceptRequest(request, currentUrl)
            fetchResponseAsync(url, request.requestHeaders ?: emptyMap(), request.method ?: "GET")
        } catch (_: Exception) {}
        return null
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", null)

    /**
     * Makes a bounded *secondary* fetch only for useful textual candidates. WebView still owns the
     * real request; this path exists exclusively to inspect manifests/JSON. Keeping it bounded
     * prevents a video/image response from being duplicated into memory or exhausting the network.
     */
    private fun fetchResponseAsync(url: String, headers: Map<String, String>, method: String) {
        if (method.uppercase() !in listOf("GET", "HEAD") || isLikelyBinaryStatic(url)) return
        val isStream = url.contains(".m3u8", true) || url.contains(".m3u", true) ||
            url.contains(".mpd", true) || url.contains(".m3u9", true)
        val limit = if (isStream) MAX_CONCURRENT else MAX_CONCURRENT - 2
        if (!tryAcquireCaptureSlot(limit)) {
            if (!capWarningLogged) {
                capWarningLogged = true
                detector.addLog("warn", "Response-fetch cap reached ($MAX_CONCURRENT concurrent) — some response bodies on this page were skipped", "capture")
            }
            return
        }

        scope.launch {
            try {
                val skipHdrs = setOf("host", "connection", "content-length", "accept-encoding", "transfer-encoding")
                val rb = Request.Builder().url(url)
                headers.forEach { (k, v) -> if (k.lowercase() !in skipHdrs) rb.addHeader(k, v) }
                if (method.uppercase() == "HEAD") rb.head() else rb.get()

                okHttpClient.newCall(rb.build()).execute().use { resp ->
                    val status = resp.code
                    val resHeaders = resp.headers.toMap()
                    val mime = resp.header("Content-Type", "") ?: ""
                    val size = resp.header("Content-Length", "-1")?.toLongOrNull() ?: -1L
                    val capturedText = captureText(resp.body?.source())
                    val preview = capturedText.take(MAX_PREVIEW_CHARS)

                    detector.requests.find { it.url == url }?.let { old ->
                        detector.updateRequest(url, old.withResponse(status, resHeaders, preview, mime, size))
                    }
                    if (capturedText.isNotBlank()) {
                        if (isStream || mime.contains("mpegurl", true) || mime.contains("dash+xml", true)) {
                            detector.scanManifestForStreams(url, capturedText, "native_resp", currentUrl)
                        } else if (mime.contains("json", true) || mime.contains("javascript", true) || mime.contains("text", true)) {
                            detector.scanTextForStreams(capturedText, "native_resp", currentUrl)
                        }
                    }
                }
            } catch (_: Exception) {
                // Network inspection is best-effort; never affect the WebView's original request.
            } finally {
                activeRequests.decrementAndGet()
            }
        }
    }

    private fun tryAcquireCaptureSlot(limit: Int): Boolean {
        while (true) {
            val active = activeRequests.get()
            if (active >= limit) return false
            if (activeRequests.compareAndSet(active, active + 1)) return true
        }
    }

    private fun captureText(source: okio.BufferedSource?): String {
        if (source == null) return ""
        return try {
            // request(max) fills no more than max bytes even if the server sends a huge body.
            source.request(MAX_CAPTURE_BYTES.toLong())
            val available = minOf(source.buffer.size, MAX_CAPTURE_BYTES.toLong())
            val bytes = source.buffer.readByteArray(available)
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    private fun isLikelyBinaryStatic(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".ico", ".svg", ".woff", ".woff2", ".ttf", ".otf").any(path::endsWith)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme ?: return true
        return scheme !in listOf("http", "https", "about", "data", "blob")
    }

    /** E1: Force re-inject JS (called on SPA navigation) */
    fun forceReInject(view: android.webkit.WebView) {
        lastInjectTime = 0L
        val url = currentUrl
        if (url.isNotBlank() && !url.startsWith("about:")) {
            injectedUrls.remove(url)
            injectJavaScript(view)
        }
    }

    fun cleanup() {
        isDestroyed = true
        scope.cancel()
        injectedUrls.clear()
    }
}

class BrowserChromeClient(
    private val detector: StreamDetector,
    private val onProgressChanged: (Int) -> Unit,
    private val onTitleReceived:   (String) -> Unit
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView, newProgress: Int) = onProgressChanged(newProgress)
    override fun onReceivedTitle(view: WebView, title: String)      = onTitleReceived(title)
    override fun onPermissionRequest(request: PermissionRequest) {
        // Never auto-grant camera/microphone/geolocation-style web permissions to arbitrary pages.
        // The app has no consent UI or matching runtime permission flow for these resources.
        request.deny()
        detector.addLog("warn", "Web permission request denied: ${request.resources.joinToString()}", "permission")
    }
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        // FIX: page's real console.log/warn/error was previously discarded entirely —
        // bare `return true` only suppressed the default Logcat mirroring. Now forwarded
        // into the Console tab (tagged "page" so it's distinguishable from tool-internal logs).
        try {
            val level = when (consoleMessage.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR   -> "error"
                ConsoleMessage.MessageLevel.WARNING -> "warn"
                else -> "info"
            }
            val src = consoleMessage.sourceId()?.substringAfterLast('/')?.take(40) ?: ""
            val loc = if (src.isNotBlank()) "  [$src:${consoleMessage.lineNumber()}]" else ""
            detector.addLog(level, "${consoleMessage.message()}$loc", "page")
        } catch (_: Exception) {}
        return true
    }
}
