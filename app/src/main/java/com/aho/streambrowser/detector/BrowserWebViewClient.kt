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

    private fun fetchResponseAsync(url: String, headers: Map<String, String>, method: String) {
        if (method.uppercase() !in listOf("GET", "HEAD")) return
        // G3: Skip if at capacity — but allow stream URLs through at lower threshold
        val isStream = url.contains(".m3u8") || url.contains(".m3u") || url.contains(".mpd") || url.contains(".m3u9")
        val limit = if (isStream) MAX_CONCURRENT else MAX_CONCURRENT - 2
        if (activeRequests.get() >= limit) {
            if (!capWarningLogged) {
                capWarningLogged = true
                detector.addLog("warn", "Response-fetch cap reached ($MAX_CONCURRENT concurrent) — some response bodies on this page were skipped", "capture")
            }
            return
        }
        activeRequests.incrementAndGet()

        scope.launch {
            try {
                val skipHdrs = setOf("host", "connection", "content-length", "accept-encoding", "transfer-encoding")
                val rb = Request.Builder().url(url)
                headers.forEach { (k, v) -> if (k.lowercase() !in skipHdrs) rb.addHeader(k, v) }
                if (method.uppercase() == "HEAD") rb.head() else rb.get()

                val resp = okHttpClient.newCall(rb.build()).execute()
                val status     = resp.code
                val resHeaders = resp.headers.toMap()
                val mime       = resp.header("Content-Type", "") ?: ""
                val size       = resp.header("Content-Length", "-1")?.toLongOrNull() ?: -1L

                val body = try {
                    val src = resp.body?.source() ?: run { resp.close(); return@launch }
                    src.request(4096L)
                    val bytes = src.readByteArray()
                    resp.close()
                    try { String(bytes, Charsets.UTF_8).take(4000) }
                    catch (_: Exception) { "(${bytes.size} bytes binary)" }
                } catch (_: Exception) { ""; }

                detector.requests.find { it.url == url }?.let { old ->
                    detector.updateRequest(url, old.withResponse(status, resHeaders, body, mime, size))
                }
                if (body.isNotBlank()) {
                    if (isStream || mime.contains("mpegurl", ignoreCase = true) || mime.contains("dash+xml", ignoreCase = true)) {
                        detector.scanManifestForStreams(url, body, "native_resp", currentUrl)
                    } else if (mime.contains("json", ignoreCase = true) || mime.contains("javascript", ignoreCase = true) || mime.contains("text", ignoreCase = true)) {
                        detector.scanTextForStreams(body, "native_resp", currentUrl)
                    }
                }
            } catch (_: Exception) {
            } finally {
                activeRequests.decrementAndGet()
            }
        }
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
    override fun onPermissionRequest(request: PermissionRequest)    = request.grant(request.resources)
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
