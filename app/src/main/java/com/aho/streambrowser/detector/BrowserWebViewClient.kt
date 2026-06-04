package com.aho.streambrowser.detector

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import com.aho.streambrowser.util.RequestBlocker
import com.aho.streambrowser.util.UserAgentManager

class BrowserWebViewClient(
    private val detector: StreamDetector,
    private val blocker: RequestBlocker,
    private val onPageStarted:  (url: String, favicon: Bitmap?) -> Unit,
    private val onPageFinished: (url: String) -> Unit
) : WebViewClient() {

    private var currentUrl = ""

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        currentUrl = url
        detector.softClear()
        // Inject stream detection hooks first
        view.evaluateJavascript(HOOK_JS, null)
        // Then inject UA override to ensure it's active from the start
        injectUaEarly(view)
        onPageStarted(url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String) {
        onPageFinished(url)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
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

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        handler?.cancel()
    }

    /**
     * Inject UA override early in page lifecycle (onPageStarted)
     * so navigator.userAgent is already spoofed before any JS runs.
     * This is crucial for sites that check UA in inline scripts.
     */
    private fun injectUaEarly(view: WebView) {
        val ua = view.settings.userAgentString ?: return
        val isMobile = UserAgentManager.isMobileUA(ua)
        val isChrome = UserAgentManager.isChromeUA(ua)
        val major = UserAgentManager.extractMajorVersion(ua)
        val platform = UserAgentManager.getPlatform(ua)
        val vendor = UserAgentManager.getVendor(ua)
        val secChUaPlatform = UserAgentManager.buildSecChUaPlatform(ua)
        val mobileStr = if (isMobile) "?1" else "?0"

        val js = """
(function() {
    var ua = ${ua.let { "\"${it.replace("\"", "\\\"")}\"" }};
    var platform = "$platform";
    var vendor = "$vendor";
    var major = "$major";
    var secChUaPlatform = $secChUaPlatform;
    var isMobile = $isMobile;
    try {
        Object.defineProperty(navigator, 'userAgent', { get: function(){ return ua; }, configurable: true });
        Object.defineProperty(navigator, 'platform', { get: function(){ return platform; }, configurable: true });
        Object.defineProperty(navigator, 'vendor', { get: function(){ return vendor; }, configurable: true });
        Object.defineProperty(navigator, 'appVersion', { get: function(){ return ua.replace('Mozilla/', ''); }, configurable: true });
        Object.defineProperty(navigator, 'webdriver', { get: function(){ return false; }, configurable: true });
        Object.defineProperty(navigator, 'languages', { get: function(){ return ['vi-VN','vi','en-US','en']; }, configurable: true });
        Object.defineProperty(navigator, 'hardwareConcurrency', { get: function(){ return isMobile ? 8 : 8; }, configurable: true });
        Object.defineProperty(navigator, 'deviceMemory', { get: function(){ return 8; }, configurable: true });
        Object.defineProperty(navigator, 'maxTouchPoints', { get: function(){ return isMobile ? 5 : 0; }, configurable: true });
        try { navigator.userAgent.toString = function() { return ua; }; } catch(e) {}
        try {
            Object.defineProperty(navigator, 'userAgentData', {
                get: function() {
                    return {
                        brands: [{brand:"Chromium",version:major},{brand:"Google Chrome",version:major},{brand:"Not-A.Brand",version:"99"}],
                        mobile: isMobile,
                        platform: secChUaPlatform.replace(/"/g,''),
                        getHighEntropyValues: function(hints) {
                            return Promise.resolve({
                                brands: this.brands, mobile: this.mobile,
                                platform: this.platform, platformVersion: "14.0.0",
                                architecture: isMobile ? "arm" : "x86", bitness: "64",
                                model: isMobile ? "Pixel 8" : "", uaFullVersion: major+".0.0.0", wow64: false
                            });
                        }
                    };
                }, configurable: true
            });
        } catch(e) {}
        ['callPhantom','_phantom','__nightmare','Buffer','domAutomation','domAutomationController','cdc_adoQpoasnfa76pfcZLmcfl_Array'].forEach(function(k){ try{ delete window[k]; }catch(e){} });
        try { delete window.navigator.__proto__.webdriver; } catch(e) {}
        if (!window.chrome) window.chrome = { runtime: {}, app: {}, csi: function(){}, loadTimes: function(){} };
    } catch(e) {}
})();
""".trimIndent()
        view.evaluateJavascript(js, null)
    }
}

class BrowserChromeClient(
    private val onProgressChanged: (Int) -> Unit,
    private val onTitleReceived:   (String) -> Unit
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView, newProgress: Int) = onProgressChanged(newProgress)
    override fun onReceivedTitle(view: WebView, title: String)      = onTitleReceived(title)
    override fun onPermissionRequest(request: PermissionRequest) {
        val safe = request.resources.filter {
            it != PermissionRequest.RESOURCE_VIDEO_CAPTURE &&
            it != PermissionRequest.RESOURCE_AUDIO_CAPTURE &&
            it != PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        }
        if (safe.isNotEmpty()) { request.grant(safe.toTypedArray()) }
        else { request.deny() }
    }
    override fun onConsoleMessage(consoleMessage: ConsoleMessage)   = true
}
