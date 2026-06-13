package com.aho.streambrowser.detector

import android.webkit.JavascriptInterface

class StreamJsBridge(
    private val detector:       StreamDetector,
    private val getCurrentUrl:  () -> String
) {
    @JavascriptInterface
    fun onRequest(url: String, source: String, method: String) {
        detector.reportFromJs(url, source, method, getCurrentUrl())
    }

    /** B4: WebSocket event capture */
    @JavascriptInterface
    fun onWebSocket(direction: String, wsUrl: String, data: String) {
        detector.addWebSocketMessage(WebSocketMessage(direction, wsUrl, data))
        // Also scan WS data for stream URLs
        if (direction == "recv" && data.isNotBlank()) {
            detector.scanTextForStreams(data, "ws_recv:$wsUrl", getCurrentUrl())
        }
    }

    /** E1+E2: SPA navigation detected (history.pushState / popstate) */
    @JavascriptInterface
    fun onSpaNavigation(url: String) {
        detector.onSpaNavigate(url)
    }

    /** Crypto key captured (CryptoJS / SubtleCrypto) */
    @JavascriptInterface
    fun onCryptoKey(algorithm: String, key: String, iv: String) {
        if (key.isBlank()) return
        detector.addCryptoKey(CryptoKeyCapture(
            algorithm = algorithm, key = key, iv = iv, pageUrl = getCurrentUrl()
        ))
    }

    /** Encrypted XHR response body */
    @JavascriptInterface
    fun onResponseBody(url: String, statusCode: Int, contentType: String, body: String) {
        detector.addResponseBody(url, statusCode, contentType, body)
    }

    @JavascriptInterface
    fun onStreamFound(url: String, source: String) {
        detector.reportFromJs(url, source, "GET", getCurrentUrl())
    }
}
