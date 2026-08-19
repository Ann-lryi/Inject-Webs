package com.aho.streambrowser.detector

import android.webkit.JavascriptInterface
import android.util.Log

class StreamJsBridge(
    private val detector:       StreamDetector,
    private val getCurrentUrl:  () -> String
) {
    // BỘ LỌC CHỐNG XSS & INJECTION
    private fun sanitizeInput(input: String): String {
        // Chỉ cho phép các ký tự an toàn. Drop các thẻ Script/HTML
        if (input.contains("<script>") || input.contains("javascript:")) return "BLOCKED_XSS"
        return input.replace(Regex("[<>\"']"), "")
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("ws://") || url.startsWith("wss://") || url.startsWith("blob:")
    }

    /**
     * Defense-in-depth: addJavascriptInterface exposes these methods to EVERY frame/script
     * loaded in the WebView, including third-party iframes and ad scripts. All entry points
     * already sanitize their inputs; we additionally rate-limit calls so a malicious/compromised
     * page cannot flood the detector (which runs on the binder/UI thread) to cause an ANR.
     */
    private var callCount = 0
    private var windowStart = 0L
    @Synchronized private fun rateLimit(): Boolean {
        val now = System.currentTimeMillis()
        if (now - windowStart > 1000L) { windowStart = now; callCount = 0 }
        return ++callCount <= MAX_CALLS_PER_SECOND
    }
    companion object { private const val MAX_CALLS_PER_SECOND = 250 }

    @JavascriptInterface
    fun onRequest(url: String, source: String, method: String) {
        if (!rateLimit()) return
        val sUrl = sanitizeInput(url)
        if (!isValidUrl(sUrl)) return
        detector.reportFromJs(sUrl, sanitizeInput(source), sanitizeInput(method), getCurrentUrl())
    }

    /** Fix: request body (XHR .send()/fetch init.body) + headers set via setRequestHeader/fetch
     *  init.headers — both were already available at the hook call site but were never sent
     *  across the bridge. NOT sanitized (same precedent as onResponseBody below): body/headers
     *  are often JSON, and sanitizeInput's quote-stripping would corrupt that structure. */
    @JavascriptInterface
    fun onRequestPayload(url: String, headersJson: String, body: String) {
        if (!rateLimit()) return
        val sUrl = sanitizeInput(url)
        if (!isValidUrl(sUrl)) return
        detector.updateRequestPayload(sUrl, headersJson, body)
    }

    /** B4: WebSocket event capture */
    @JavascriptInterface
    fun onWebSocket(direction: String, wsUrl: String, data: String) {
        if (!rateLimit()) return
        val sWsUrl = sanitizeInput(wsUrl)
        if (!isValidUrl(sWsUrl)) return
        
        val sData = sanitizeInput(data)
        detector.addWebSocketMessage(WebSocketMessage(sanitizeInput(direction), sWsUrl, sData))
        
        if (direction == "recv" && sData.isNotBlank()) {
            detector.scanTextForStreams(sData, "ws_recv:$sWsUrl", getCurrentUrl())
        }
    }

    /** E1+E2: SPA navigation detected (history.pushState / popstate) */
    @JavascriptInterface
    fun onSpaNavigation(url: String) {
        if (!rateLimit()) return
        detector.onSpaNavigate(sanitizeInput(url))
    }

    /** CỰC KHỦNG: Crypto API Hooking Capture (SubtleCrypto / CryptoJS) */
    @JavascriptInterface
    fun onCryptoKeyIntercepted(algo: String, hexKey: String, hexIv: String) {
        if (!rateLimit()) return
        val safeAlgo = sanitizeInput(algo)
        val safeKey = sanitizeInput(hexKey)
        val safeIv = sanitizeInput(hexIv)
        
        Log.w("GOD_MODE_BRIDGE", "BẮT QUẢ TANG AES KEY TRỰC TIẾP TỪ RAM TRÌNH DUYỆT! Algo: $safeAlgo | Key: $safeKey")
        
        val capture = CryptoKeyCapture(
            algorithm = safeAlgo,
            key = safeKey,
            iv = safeIv,
            pageUrl = getCurrentUrl()
        )
        detector.addCryptoKey(capture)
    }

    @JavascriptInterface
    fun onCryptoKey(algorithm: String, key: String, iv: String) {
        if (!rateLimit()) return
        if (key.isBlank()) return
        detector.addCryptoKey(CryptoKeyCapture(
            algorithm = algorithm, key = key, iv = iv, pageUrl = getCurrentUrl()
        ))
    }

    /** Encrypted XHR response body */
    @JavascriptInterface
    fun onResponseBody(url: String, statusCode: Int, contentType: String, body: String) {
        if (!rateLimit()) return
        detector.addResponseBody(url, statusCode, contentType, body)
    }

    @JavascriptInterface
    fun onWasmDetected(url: String) {
        if (!rateLimit()) return
        detector.onWasmDetected(sanitizeInput(url))
    }

    @JavascriptInterface
    fun onServiceWorkerDetected(scriptUrl: String) {
        if (!rateLimit()) return
        detector.onServiceWorkerDetected(sanitizeInput(scriptUrl))
    }

    @JavascriptInterface
    fun onStreamFound(url: String, source: String) {
        if (!rateLimit()) return
        detector.reportFromJs(url, source, "GET", getCurrentUrl())
    }
}
