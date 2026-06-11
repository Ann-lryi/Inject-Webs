package com.aho.streambrowser.detector

import android.webkit.JavascriptInterface

class StreamJsBridge(
    private val detector: StreamDetector,
    private val getCurrentUrl: () -> String
) {
    /** Network request detected */
    @JavascriptInterface
    fun onRequest(url: String, source: String, method: String) {
        detector.reportFromJs(url, source, method, getCurrentUrl())
    }

    /** Crypto key captured (CryptoJS / SubtleCrypto) */
    @JavascriptInterface
    fun onCryptoKey(algorithm: String, key: String, iv: String) {
        if (key.isBlank()) return
        detector.addCryptoKey(CryptoKeyCapture(
            algorithm = algorithm,
            key       = key,
            iv        = iv,
            pageUrl   = getCurrentUrl()
        ))
    }

    /** Encrypted response body captured from XHR */
    @JavascriptInterface
    fun onResponseBody(url: String, statusCode: Int, contentType: String, body: String) {
        detector.addResponseBody(url, statusCode, contentType, body)
    }

    // Legacy compat
    @JavascriptInterface
    fun onStreamFound(url: String, source: String) {
        detector.reportFromJs(url, source, "GET", getCurrentUrl())
    }
}
