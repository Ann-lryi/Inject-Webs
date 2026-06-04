package com.aho.streambrowser.detector

import android.webkit.JavascriptInterface

class StreamJsBridge(
    private val detector: StreamDetector,
    private val getCurrentUrl: () -> String
) {
    @JavascriptInterface
    fun onRequest(url: String, source: String, method: String) {
        detector.reportFromJs(url, source, method, getCurrentUrl())
    }

    /** Called from JS hook with full response data for DevTools-like inspection */
    @JavascriptInterface
    fun onRequestWithResponse(url: String, source: String, method: String,
                              statusCode: Int, responseHeaders: String,
                              bodyPreview: String, contentType: String,
                              duration: Long) {
        detector.reportWithResponse(url, source, method, getCurrentUrl(),
            statusCode, responseHeaders, bodyPreview, contentType, duration)
    }

    // Legacy compat
    @JavascriptInterface
    fun onStreamFound(url: String, source: String) {
        detector.reportFromJs(url, source, "GET", getCurrentUrl())
    }
}
