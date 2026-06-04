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

    // Legacy compat
    @JavascriptInterface
    fun onStreamFound(url: String, source: String) {
        detector.reportFromJs(url, source, "GET", getCurrentUrl())
    }

    /** Called from JS hooks when XHR/Fetch response is captured */
    @JavascriptInterface
    fun onResponse(url: String, statusCode: Int, headersJson: String, bodyPreview: String) {
        detector.updateResponseFromJs(url, statusCode, headersJson, bodyPreview)
    }

    /** Called from JS hooks when POST body is captured */
    @JavascriptInterface
    fun onRequestBody(url: String, body: String) {
        detector.updateRequestBodyFromJs(url, body)
    }
}
