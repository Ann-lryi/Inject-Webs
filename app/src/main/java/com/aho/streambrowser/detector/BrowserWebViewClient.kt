package com.aho.streambrowser.detector

import android.graphics.Bitmap
import android.webkit.*
import com.aho.streambrowser.util.RequestBlocker
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class BrowserWebViewClient(
    private val detector: StreamDetector,
    private val blocker: RequestBlocker,
    private val onPageStarted:  (url: String, favicon: Bitmap?) -> Unit,
    private val onPageFinished: (url: String) -> Unit
) : WebViewClient() {

    private var currentUrl = ""
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        currentUrl = url
        // Fix: Only clear network data, keep persistent logs
        detector.clearNetworkData()
        view.evaluateJavascript(HOOK_JS, null)
        onPageStarted(url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String) {
        view.evaluateJavascript(HOOK_JS, null)
        onPageFinished(url)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        // Block ads/trackers
        if (blocker.shouldBlock(url)) {
            blocker.incrementBlocked()
            return WebResourceResponse("text/plain", "utf-8", null)
        }
        detector.interceptRequest(request, currentUrl)

        // Fetch response data asynchronously for non-binary, non-stream requests
        fetchResponseDataAsync(url, request.requestHeaders)

        return null
    }

    /** Make a background OkHttp request to capture response status, headers, and body preview */
    private fun fetchResponseDataAsync(url: String, requestHeaders: Map<String, String>?) {
        // Skip stream URLs (too large, already handled by player)
        val streamType = com.aho.streambrowser.model.StreamItem.detectType(url)
        if (streamType != null) return

        // Skip binary resources that we don't need response data for
        val l = url.lowercase()
        if (l.contains(".png") || l.contains(".jpg") || l.contains(".jpeg") ||
            l.contains(".gif") || l.contains(".webp") || l.contains(".avif") ||
            l.contains(".woff") || l.contains(".woff2") || l.contains(".ttf") ||
            l.contains(".eot") || l.contains(".ico") || l.contains(".svg") ||
            l.contains(".css")) return

        scope.launch {
            try {
                val reqBuilder = Request.Builder().url(url)
                // Forward relevant headers from the original request
                requestHeaders?.forEach { (k, v) ->
                    when (k.lowercase()) {
                        "cookie", "referer", "origin", "authorization", "accept", "accept-language",
                        "accept-encoding", "x-requested-with", "sec-ch-ua", "sec-ch-ua-mobile",
                        "sec-ch-ua-platform", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site" ->
                            reqBuilder.header(k, v)
                    }
                }
                val response = client.newCall(reqBuilder.build()).execute()
                val statusCode = response.code
                val statusText = response.message

                // Capture response headers
                val respHeaders = mutableMapOf<String, String>()
                response.headers.forEach { (k, v) -> respHeaders[k.lowercase()] = v }

                val mimeType = response.header("content-type", "") ?: ""
                val contentLength = response.header("content-length", "-1")?.toLongOrNull() ?: -1L

                // Capture body preview for text-based responses (up to 10KB)
                var bodyPreview = ""
                val isTextLike = mimeType.contains("text") || mimeType.contains("json") ||
                        mimeType.contains("xml") || mimeType.contains("javascript") ||
                        mimeType.contains("html") || mimeType.contains("form") ||
                        url.contains(".json") || url.contains(".xml") ||
                        url.contains(".m3u8") || url.contains(".mpd")

                if (isTextLike) {
                    try {
                        val body = response.body
                        if (body != null) {
                            val source = body.source()
                            source.request(10240L)
                            val bytes = source.readByteArray()
                            bodyPreview = String(bytes, Charsets.UTF_8)
                            body.close()
                        }
                    } catch (_: Exception) {}
                }

                response.close()

                detector.updateResponseData(url, statusCode, statusText, respHeaders,
                    bodyPreview, mimeType, contentLength)
            } catch (_: Exception) {
                // Silently fail - we don't want to crash or affect the WebView
            }
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme ?: return true
        return scheme !in listOf("http", "https", "about", "data", "blob")
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError?) = Unit
}

class BrowserChromeClient(
    private val onProgressChanged: (Int) -> Unit,
    private val onTitleReceived:   (String) -> Unit
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView, newProgress: Int) = onProgressChanged(newProgress)
    override fun onReceivedTitle(view: WebView, title: String)      = onTitleReceived(title)

    // Fix: Don't auto-grant all permissions - only grant media-related permissions
    // for video capture sites. Show a dialog for sensitive permissions.
    override fun onPermissionRequest(request: PermissionRequest) {
        val mediaResources = request.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
            it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        }
        if (mediaResources.isNotEmpty() && mediaResources.size == request.resources.size) {
            // Only media permissions requested - grant them for video sites
            request.grant(mediaResources.toTypedArray())
        } else {
            // Other sensitive permissions (geolocation, etc) - deny by default
            // User can still grant through the website's own permission UI
            request.deny()
        }
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage) = true
}
