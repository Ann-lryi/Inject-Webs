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
}
