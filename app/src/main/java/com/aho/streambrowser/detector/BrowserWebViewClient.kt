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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Lightweight OkHttp client for fetching response data (separate from WebView requests)
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        currentUrl = url
        detector.clear()
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

        // ── Fetch response data asynchronously via OkHttp ──
        // Only for interesting requests (not noise, already filtered by interceptor)
        // This runs in background, doesn't block WebView
        fetchResponseDataAsync(url, request.requestHeaders ?: emptyMap(), request.method ?: "GET")

        return null
    }

    /** Make a parallel OkHttp request to capture response headers and body preview */
    private fun fetchResponseDataAsync(url: String, reqHeaders: Map<String, String>, method: String) {
        scope.launch {
            try {
                val requestBuilder = Request.Builder().url(url)
                // Copy relevant request headers (avoid duplicates that OkHttp manages)
                val skipHeaders = setOf("host", "connection", "content-length", "accept-encoding", "transfer-encoding")
                reqHeaders.forEach { (k, v) ->
                    if (k.lowercase() !in skipHeaders) {
                        requestBuilder.addHeader(k, v)
                    }
                }
                when (method.uppercase()) {
                    "GET" -> requestBuilder.get()
                    "HEAD" -> requestBuilder.head()
                    // For POST/PUT etc. we just do GET to capture basic response info
                    else -> requestBuilder.get()
                }

                val call = okHttpClient.newCall(requestBuilder.build())
                val response = call.execute()

                val statusCode = response.code
                val resHeaders = response.headers.toMap()
                val mimeType = response.header("Content-Type", "") ?: ""
                val contentLen = response.header("Content-Length", "-1")?.toLongOrNull() ?: -1L

                // Read body preview (first 4KB max)
                val bodyPreview = try {
                    val body = response.body
                    if (body != null) {
                        val source = body.source()
                        source.request(4096L)
                        val bytes = source.readByteArray()
                        // Try to decode as UTF-8, fallback to hex for binary
                        try {
                            String(bytes, Charsets.UTF_8).take(4000)
                        } catch (_: Exception) {
                            "(${bytes.size} bytes binary data)"
                        }
                    } else ""
                } catch (_: Exception) { "" }

                response.close()

                // Update the request in detector with response data
                val existingReq = detector.requests.find { it.url == url }
                if (existingReq != null) {
                    val updated = existingReq.withResponse(
                        statusCode = statusCode,
                        responseHeaders = resHeaders,
                        responseBodyPreview = bodyPreview,
                        mimeType = mimeType,
                        contentLength = contentLen
                    )
                    detector.updateRequest(url, updated)
                }
            } catch (_: Exception) {
                // Silently fail - response data is best-effort
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
    override fun onPermissionRequest(request: PermissionRequest)    = request.grant(request.resources)
    override fun onConsoleMessage(consoleMessage: ConsoleMessage)   = true
}
