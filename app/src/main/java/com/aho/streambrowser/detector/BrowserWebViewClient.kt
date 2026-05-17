package com.aho.streambrowser.detector

import android.graphics.Bitmap
import android.webkit.*
import com.aho.streambrowser.util.RequestBlocker

class BrowserWebViewClient(
    private val detector: StreamDetector,
    private val blocker: RequestBlocker,
    private val onPageStarted:  (url: String, favicon: Bitmap?) -> Unit,
    private val onPageFinished: (url: String) -> Unit
) : WebViewClient() {

    private var currentUrl = ""

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
        return null
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
