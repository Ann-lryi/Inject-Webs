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

    private var currentUrl = ""
    private var isDestroyed = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Rate limiting for OkHttp requests
    private val activeRequests = AtomicInteger(0)
    private val maxConcurrentRequests = 10
    private val requestQueue = mutableListOf<Job>()
    
    // Performance optimization
    private var lastInjectTime = 0L
    private val injectCooldown = 500L // ms between injections
    private var injectionFailed = false

    // Lightweight OkHttp client for fetching response data (separate from WebView requests)
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false) // Don't retry, it's just for preview
            .build()
    }
    
    // Cache recent successful injections
    private val injectedUrls = mutableSetOf<String>()

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (isDestroyed) return
        
        try {
            currentUrl = url
            
            // Only clear if URL actually changed
            if (url != currentUrl) {
                detector.clear()
            }
            
            // Inject JS with cooldown and deduplication
            val shouldInject = shouldInjectJs(url)
            if (shouldInject) {
                injectJavaScript(view)
            }
            
            onPageStarted(url, favicon)
        } catch (e: Exception) {
            // Graceful degradation
            onPageStarted(url, favicon)
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (isDestroyed) return
        
        try {
            // Re-inject JS on page finish for SPAs (Single Page Applications)
            val shouldReinject = shouldInjectJs(url)
            if (shouldReinject) {
                injectJavaScript(view)
            }
            
            onPageFinished(url)
        } catch (e: Exception) {
            // Graceful degradation
            onPageFinished(url)
        }
    }
    
    private fun shouldInjectJs(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.startsWith("about:") || url.startsWith("data:")) return false
        
        // Check cooldown
        val now = System.currentTimeMillis()
        if (now - lastInjectTime < injectCooldown) return false
        
        // Don't re-inject for same URL
        if (url in injectedUrls) return false
        
        return true
    }
    
    private fun injectJavaScript(view: WebView) {
        try {
            view.evaluateJavascript(HOOK_JS) { success ->
                if (success == "ok" || success == "true") {
                    lastInjectTime = System.currentTimeMillis()
                    injectedUrls.add(currentUrl)
                    injectionFailed = false
                    
                    // Cleanup old injections (keep last 20)
                    if (injectedUrls.size > 20) {
                        injectedUrls.remove(injectedUrls.iterator().next())
                    }
                } else {
                    // Log failure but don't spam retries
                    if (!injectionFailed) {
                        injectionFailed = true
                    }
                }
            }
        } catch (e: Exception) {
            // Graceful degradation - JS injection is not critical
            injectionFailed = true
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (isDestroyed) return null
        
        try {
            val url = request.url?.toString() ?: return null
            
            // Block ads/trackers early
            if (blocker.shouldBlock(url)) {
                blocker.incrementBlocked()
                return createEmptyResponse()
            }
            
            // Intercept for detection
            detector.interceptRequest(request, currentUrl)
            
            // Fetch response data with rate limiting
            fetchResponseDataAsync(url, request.requestHeaders ?: emptyMap(), request.method ?: "GET")
            
            return null
        } catch (e: Exception) {
            // Don't block requests due to errors
            return null
        }
    }
    
    private fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", null)
    }

    /** Make a parallel OkHttp request to capture response headers and body preview */
    private fun fetchResponseDataAsync(url: String, reqHeaders: Map<String, String>, method: String) {
        // Rate limiting
        if (activeRequests.get() >= maxConcurrentRequests) {
            return // Skip this request
        }
        
        // Skip non-GET requests for preview (save bandwidth)
        if (method.uppercase() != "GET" && method.uppercase() != "HEAD") {
            return
        }
        
        activeRequests.incrementAndGet()
        
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
                            "(${bytes.size} bytes binary)"
                        }
                    } else ""
                } catch (_: Exception) { "" }

                response.close()

                // Update the request in detector with response data
                updateRequestInDetector(url, statusCode, resHeaders, bodyPreview, mimeType, contentLen)
            } catch (_: Exception) {
                // Silently fail - response data is best-effort
            } finally {
                activeRequests.decrementAndGet()
            }
        }
    }
    
    private fun updateRequestInDetector(
        url: String, 
        statusCode: Int, 
        resHeaders: Map<String, String>, 
        bodyPreview: String, 
        mimeType: String, 
        contentLen: Long
    ) {
        try {
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
            // Ignore update failures
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        try {
            val scheme = request.url.scheme ?: return true
            return scheme !in listOf("http", "https", "about", "data", "blob")
        } catch (e: Exception) {
            return true
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError?) {
        // Don't crash on errors, just log
        // Subclasses can override for custom error handling
    }
    
    fun cleanup() {
        isDestroyed = true
        scope.cancel()
        injectedUrls.clear()
    }
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
