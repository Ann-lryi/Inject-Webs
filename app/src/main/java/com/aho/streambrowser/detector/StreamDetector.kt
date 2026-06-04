package com.aho.streambrowser.detector

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import android.webkit.WebResourceRequest
import com.aho.streambrowser.model.NetworkRequest
import com.aho.streambrowser.model.StreamItem

val HOOK_JS = """
(function() {
    if (window.__sb_hooked) return;
    window.__sb_hooked = true;

    // ── UA Spoof: override navigator to bypass site detection ─────────
    try {
        var _ua = navigator.userAgent;
        Object.defineProperty(navigator, 'webdriver',    { get: function(){ return false; }, configurable: true });
        Object.defineProperty(navigator, 'languages',    { get: function(){ return ['vi-VN','vi','en-US','en']; }, configurable: true });
        ['callPhantom','_phantom','__nightmare','Buffer','domAutomation','domAutomationController'].forEach(function(k){ try{ delete window[k]; }catch(e){} });
        if (!window.chrome) { window.chrome = { runtime: {}, app: {}, csi: function(){}, loadTimes: function(){} }; }
    } catch(e) {}

    // ── Stream detection hooks ─────────────────────────────────────────────────
    function report(url, src, method) {
        try {
            if (!url || typeof url !== 'string') return;
            url = url.trim();
            if (!url.startsWith('http') && !url.startsWith('//')) return;
            if (url.startsWith('//')) url = 'https:' + url;
            SBridge.onRequest(url, src || 'js', method || 'GET');
        } catch(e) {}
    }
    function reportResponse(url, status, headers, body) {
        try {
            if (!url || typeof url !== 'string') return;
            url = url.trim();
            if (!url.startsWith('http') && !url.startsWith('//')) return;
            if (url.startsWith('//')) url = 'https:' + url;
            var hdrJson = '';
            try { hdrJson = JSON.stringify(headers || {}); } catch(e) {}
            var preview = '';
            try { preview = (body || '').substring(0, 10240); } catch(e) {}
            SBridge.onResponse(url, status || 0, hdrJson, preview);
        } catch(e) {}
    }
    function reportBody(url, body) {
        try {
            if (!url || typeof url !== 'string') return;
            url = url.trim();
            if (!url.startsWith('http') && !url.startsWith('//')) return;
            if (url.startsWith('//')) url = 'https:' + url;
            var b = '';
            try { b = (typeof body === 'string') ? body.substring(0, 10240) : JSON.stringify(body).substring(0, 10240); } catch(e) {}
            if (b) SBridge.onRequestBody(url, b);
        } catch(e) {}
    }

    // ── Hook XMLHttpRequest with response + body capture ───────────────────
    var _open = XMLHttpRequest.prototype.open;
    var _send = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url) {
        report(url,'xhr',method);
        this.__sb_method = method;
        this.__sb_url = url;
        return _open.apply(this,arguments);
    };
    XMLHttpRequest.prototype.send = function(body) {
        var url = this.__sb_url;
        var method = (this.__sb_method || 'GET').toUpperCase();
        // Capture POST/PUT body
        if (body && (method === 'POST' || method === 'PUT' || method === 'PATCH')) {
            reportBody(url, body);
        }
        // Capture response on load
        this.addEventListener('load', function() {
            try {
                var hdrs = {};
                var rawHdr = this.getAllResponseHeaders();
                if (rawHdr) {
                    rawHdr.trim().split(/[\r\n]+/).forEach(function(line) {
                        var parts = line.split(': ', 2);
                        if (parts.length === 2) hdrs[parts[0].toLowerCase()] = parts[1];
                    });
                }
                var bodyPreview = '';
                try { bodyPreview = this.responseText.substring(0, 10240); } catch(e) {}
                reportResponse(this.responseURL || url, this.status, hdrs, bodyPreview);
            } catch(e) {}
        });
        return _send.apply(this,arguments);
    };

    // ── Hook Fetch with response + body capture ────────────────────────────
    var _fetch = window.fetch;
    window.fetch = function(input, init) {
        var url = typeof input==='string'?input:(input&&input.url)||'';
        var m = (init&&init.method)||'GET';
        report(url,'fetch',m);
        // Capture POST/PUT body
        var body = init && init.body;
        if (body && (m.toUpperCase() === 'POST' || m.toUpperCase() === 'PUT' || m.toUpperCase() === 'PATCH')) {
            reportBody(url, body);
        }
        return _fetch.apply(this,arguments).then(function(r) {
            // Clone and capture response body preview
            try {
                var clone = r.clone();
                var respHdrs = {};
                r.headers.forEach(function(v, k) { respHdrs[k.toLowerCase()] = v; });
                clone.text().then(function(txt) {
                    reportResponse(r.url || url, r.status, respHdrs, txt.substring(0, 10240));
                }).catch(function() {
                    reportResponse(r.url || url, r.status, respHdrs, '');
                });
            } catch(e) {
                try {
                    var respHdrs2 = {};
                    r.headers.forEach(function(v, k) { respHdrs2[k.toLowerCase()] = v; });
                    reportResponse(r.url || url, r.status, respHdrs2, '');
                } catch(e2) {}
            }
            return r;
        });
    };

    // ── Hook HTMLMediaElement.src ──────────────────────────────────────────
    var mDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');
    if (mDesc&&mDesc.set) Object.defineProperty(HTMLMediaElement.prototype,'src',{get:mDesc.get,set:function(v){report(v,'media_src','GET');mDesc.set.call(this,v);}});

    // ── Hook setAttribute for video/source ─────────────────────────────────
    var _setAttr = Element.prototype.setAttribute;
    Element.prototype.setAttribute = function(name,value) {
        if ((name==='src'||name==='data-src')&&(this.tagName==='VIDEO'||this.tagName==='SOURCE')) report(value,'dom_attr','GET');
        return _setAttr.apply(this,arguments);
    };

    // ── Hook jwplayer ─────────────────────────────────────────────────────
    Object.defineProperty(window,'jwplayer',{configurable:true,get:function(){return window.__jwp;},set:function(jwp){window.__jwp=jwp;if(!jwp||!jwp.prototype)return;var orig=jwp.prototype.setup;jwp.prototype.setup=function(cfg){try{[cfg.file,cfg.sources,cfg.playlist].forEach(function(s){if(typeof s==='string')report(s,'jwplayer','GET');else if(Array.isArray(s))s.forEach(function(i){if(i&&i.file)report(i.file,'jwplayer','GET');});});}catch(e){}return orig?orig.apply(this,arguments):this;};}});

    // ── Delayed DOM scans ─────────────────────────────────────────────────
    [1000,3000,6000].forEach(function(t){setTimeout(function(){
        document.querySelectorAll('video,source').forEach(function(el){var s=el.src||el.getAttribute('src')||el.getAttribute('data-src');if(s)report(s,'dom_scan','GET');});
        var pats=[/["'](https?:\/\/[^"']+\.m3u8[^"']*?)["']/g,/["'](https?:\/\/[^"']+\.mp4[^"']*?)["']/g,/["'](https?:\/\/[^"']+\.mpd[^"']*?)["']/g,/file\s*:\s*["'](https?:\/\/[^"']+?)["']/g,/source\s*:\s*["'](https?:\/\/[^"']+?)["']/g,/hls\s*:\s*["'](https?:\/\/[^"']+?)["']/g];
        document.querySelectorAll('script:not([src])').forEach(function(s){var txt=s.textContent||'';pats.forEach(function(re){re.lastIndex=0;var m;while((m=re.exec(txt))!==null)report(m[1],'script_scan','GET');});});
    },t);});
})();
""".trimIndent()

class StreamDetector(private val context: Context? = null) {
    private val _streams  = mutableListOf<StreamItem>()
    private val _requests = mutableListOf<NetworkRequest>()

    val streams:  List<StreamItem>     get() = synchronized(this) { _streams.toList()  }
    val requests: List<NetworkRequest> get() = synchronized(this) { _requests.toList() }

    var onStreamFound:  ((StreamItem)     -> Unit)? = null
    var onRequestAdded: ((NetworkRequest) -> Unit)? = null

    // Persistent state - NOT cleared on page navigation
    val consoleLog     = StringBuilder("// Console\n")
    val deepLog        = StringBuilder("// Deep Inject\n")
    val consoleHistory = mutableListOf<String>()

    fun interceptRequest(request: WebResourceRequest, pageUrl: String) {
        val url    = request.url.toString()
        val method = request.method ?: "GET"
        val hdrs   = request.requestHeaders ?: emptyMap()
        if (isNoise(url)) return
        val streamType = StreamItem.detectType(url)
        val req = NetworkRequest(url=url, method=method, headers=hdrs, pageUrl=pageUrl,
                                 isStream=streamType!=null, streamType=streamType, source="network")
        addRequest(req)
        if (streamType != null)
            addStream(StreamItem(url=url, type=streamType, source="network", referer=pageUrl))
    }

    fun reportFromJs(url: String, source: String, method: String, referer: String) {
        val streamType = StreamItem.detectType(url)
        val req = NetworkRequest(url=url, method=method, headers=emptyMap(), pageUrl=referer,
                                 isStream=streamType!=null, streamType=streamType, source=source)
        addRequest(req)
        if (streamType != null)
            addStream(StreamItem(url=url, type=streamType, source=source, referer=referer))
    }

    /** Update an existing request with response data from JS hooks */
    fun updateResponseFromJs(url: String, statusCode: Int, headersJson: String, bodyPreview: String) {
        synchronized(this) {
            val req = _requests.find { it.url == url } ?: return
            req.statusCode = statusCode
            req.statusText = if (statusCode in 200..299) "OK" else "Error"
            req.responseBodyPreview = bodyPreview
            try {
                if (headersJson.isNotEmpty()) {
                    val map = mutableMapOf<String, String>()
                    val obj = org.json.JSONObject(headersJson)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = obj.getString(k)
                    }
                    req.responseHeaders = map
                }
            } catch (_: Exception) {}
        }
    }

    /** Update an existing request with POST body from JS hooks */
    fun updateRequestBodyFromJs(url: String, body: String) {
        synchronized(this) {
            val req = _requests.find { it.url == url } ?: return
            req.requestBody = body
        }
    }

    /** Update an existing request with response data from OkHttp */
    @Synchronized fun updateResponseData(url: String, statusCode: Int, statusText: String,
                                          responseHeaders: Map<String, String>, bodyPreview: String,
                                          mimeType: String, contentLength: Long) {
        val req = _requests.find { it.url == url } ?: return
        req.statusCode = statusCode
        req.statusText = statusText
        req.responseHeaders = responseHeaders
        req.responseBodyPreview = bodyPreview
        req.mimeType = mimeType
        req.contentLength = contentLength
    }

    @Synchronized private fun addRequest(req: NetworkRequest) {
        if (_requests.any { it.url == req.url }) return
        _requests.add(0, req)
        if (_requests.size > 500) _requests.removeAt(_requests.lastIndex)
        onRequestAdded?.invoke(req)
    }

    @Synchronized private fun addStream(item: StreamItem) {
        if (_streams.any { it.url == item.url }) return
        _streams.add(0, item)
        onStreamFound?.invoke(item)
        vibrateOnStream()
    }

    private fun vibrateOnStream() {
        context ?: return
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(80)
            }
        } catch (_: Exception) {}
    }

    /** Clear only network data (streams + requests) on page navigation */
    fun clearNetworkData() = synchronized(this) { _streams.clear(); _requests.clear() }

    /** Clear everything including console logs - user explicitly presses clear */
    fun clearAll() = synchronized(this) {
        _streams.clear()
        _requests.clear()
        consoleLog.clear(); consoleLog.append("// Console\n")
        deepLog.clear(); deepLog.append("// Deep Inject\n")
        consoleHistory.clear()
    }

    /** Clear only streams list */
    fun clearStreams() = synchronized(this) { _streams.clear() }

    /** Clear only requests list */
    fun clearRequests() = synchronized(this) { _requests.clear() }

    fun streamCount()  = synchronized(this) { _streams.size  }
    fun requestCount() = synchronized(this) { _requests.size }

    private fun isNoise(url: String): Boolean {
        val l = url.lowercase()
        return l.contains(".css")||l.contains(".woff")||l.contains(".woff2")||l.contains(".ttf")||
               l.contains(".eot")||l.contains(".ico")||l.contains(".svg")||l.contains(".png")||
               l.contains(".jpg")||l.contains(".jpeg")||l.contains(".gif")||l.contains(".webp")||
               l.contains(".avif")||l.contains("google-analytics")||l.contains("googletagmanager")||
               l.contains("facebook.com/tr")||l.contains("doubleclick")||l.contains("beacon")||
               l.contains("telemetry")||l.contains("hotjar")||l.contains("clarity.ms")
    }
}
