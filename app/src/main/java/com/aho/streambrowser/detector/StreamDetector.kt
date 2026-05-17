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

    // ── UA Spoof: override navigator tầng JS để bypass site detection ─────────
    try {
        var _ua = navigator.userAgent;
        // Xoá các dấu hiệu WebView/automation
        Object.defineProperty(navigator, 'webdriver',    { get: function(){ return false; }, configurable: true });
        Object.defineProperty(navigator, 'languages',    { get: function(){ return ['vi-VN','vi','en-US','en']; }, configurable: true });
        // Xoá phantom/nightmare traces
        ['callPhantom','_phantom','__nightmare','Buffer','domAutomation','domAutomationController'].forEach(function(k){ try{ delete window[k]; }catch(e){} });
        // Fake chrome runtime để pass chrome check
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
    var _open = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) { report(url,'xhr',method); return _open.apply(this,arguments); };
    var _fetch = window.fetch;
    window.fetch = function(input, init) {
        var url = typeof input==='string'?input:(input&&input.url); var m=(init&&init.method)||'GET';
        report(url,'fetch',m); return _fetch.apply(this,arguments);
    };
    var mDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');
    if (mDesc&&mDesc.set) Object.defineProperty(HTMLMediaElement.prototype,'src',{get:mDesc.get,set:function(v){report(v,'media_src','GET');mDesc.set.call(this,v);}});
    var _setAttr = Element.prototype.setAttribute;
    Element.prototype.setAttribute = function(name,value) {
        if ((name==='src'||name==='data-src')&&(this.tagName==='VIDEO'||this.tagName==='SOURCE')) report(value,'dom_attr','GET');
        return _setAttr.apply(this,arguments);
    };
    Object.defineProperty(window,'jwplayer',{configurable:true,get:function(){return window.__jwp;},set:function(jwp){window.__jwp=jwp;if(!jwp||!jwp.prototype)return;var orig=jwp.prototype.setup;jwp.prototype.setup=function(cfg){try{[cfg.file,cfg.sources,cfg.playlist].forEach(function(s){if(typeof s==='string')report(s,'jwplayer','GET');else if(Array.isArray(s))s.forEach(function(i){if(i&&i.file)report(i.file,'jwplayer','GET');});});}catch(e){}return orig?orig.apply(this,arguments):this;};}});
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

    // Persistent state
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
                                 isStream=streamType!=null, streamType=streamType)
        addRequest(req)
        if (streamType != null)
            addStream(StreamItem(url=url, type=streamType, source="network", referer=pageUrl))
    }

    fun reportFromJs(url: String, source: String, method: String, referer: String) {
        val streamType = StreamItem.detectType(url)
        val req = NetworkRequest(url=url, method=method, headers=emptyMap(), pageUrl=referer,
                                 isStream=streamType!=null, streamType=streamType)
        addRequest(req)
        if (streamType != null)
            addStream(StreamItem(url=url, type=streamType, source=source, referer=referer))
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

    fun clear() = synchronized(this) { _streams.clear(); _requests.clear() }
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
