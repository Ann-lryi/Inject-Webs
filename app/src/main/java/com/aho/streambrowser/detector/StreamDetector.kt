package com.aho.streambrowser.detector

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import android.webkit.WebResourceRequest
import com.aho.streambrowser.model.NetworkRequest
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.util.BookmarkManager

val HOOK_JS = """
(function() {
    if (window.__sb_hooked) return;
    window.__sb_hooked = true;

    // ── UA Spoof: override navigator tầng JS để bypass site detection ─────────
    try {
        Object.defineProperty(navigator, 'webdriver',    { get: function(){ return false; }, configurable: true });
        Object.defineProperty(navigator, 'languages',    { get: function(){ return ['vi-VN','vi','en-US','en']; }, configurable: true });
        Object.defineProperty(navigator, 'plugins',      { get: function(){ return [{name:'Chrome PDF Plugin'},{name:'Chrome PDF Viewer'},{name:'Native Client'}]; }, configurable: true });
        ['callPhantom','_phantom','__nightmare','Buffer','domAutomation','domAutomationController'].forEach(function(k){ try{ delete window[k]; }catch(e){} });
        if (!window.chrome) { window.chrome = { runtime: {}, app: {}, csi: function(){}, loadTimes: function(){} }; }
    } catch(e) {}

    // ── Stream detection core ──────────────────────────────────────────────────
    function report(url, src, method) {
        try {
            if (!url || typeof url !== 'string') return;
            url = url.trim();
            if (!url.startsWith('http') && !url.startsWith('//')) return;
            if (url.startsWith('//')) url = 'https:' + url;
            // De-duplicate trong 5 giây
            var key = url + '|' + src;
            if (window.__sb_recent && window.__sb_recent[key]) return;
            if (!window.__sb_recent) window.__sb_recent = {};
            window.__sb_recent[key] = true;
            setTimeout(function(){ delete window.__sb_recent[key]; }, 5000);
            SBridge.onRequest(url, src || 'js', method || 'GET');
        } catch(e) {}
    }

    // ── Hook XHR ──────────────────────────────────────────────────────────────
    var _open = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) { report(url,'xhr',method); return _open.apply(this,arguments); };

    // ── Hook Fetch ────────────────────────────────────────────────────────────
    var _fetch = window.fetch;
    window.fetch = function(input, init) {
        var url = typeof input==='string'?input:(input&&input.url); var m=(init&&init.method)||'GET';
        report(url,'fetch',m); return _fetch.apply(this,arguments);
    };

    // ── Hook HTMLMediaElement.src ─────────────────────────────────────────────
    var mDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');
    if (mDesc&&mDesc.set) Object.defineProperty(HTMLMediaElement.prototype,'src',{get:mDesc.get,set:function(v){report(v,'media_src','GET');mDesc.set.call(this,v);},configurable:true});

    // ── Hook video.srcObject (MediaStream) ────────────────────────────────────
    var srcObjDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'srcObject');
    if (srcObjDesc&&srcObjDesc.set) Object.defineProperty(HTMLMediaElement.prototype,'srcObject',{get:srcObjDesc.get,set:function(v){report('srcObject://'+typeof v,'srcObject','GET');srcObjDesc.set.call(this,v);},configurable:true});

    // ── Hook setAttribute ─────────────────────────────────────────────────────
    var _setAttr = Element.prototype.setAttribute;
    Element.prototype.setAttribute = function(name,value) {
        if ((name==='src'||name==='data-src'||name==='data-source'||name==='data-url')&&(this.tagName==='VIDEO'||this.tagName==='SOURCE'||this.tagName==='IFRAME')) report(value,'dom_attr','GET');
        return _setAttr.apply(this,arguments);
    };

    // ── Hook video.load() ─────────────────────────────────────────────────────
    var _vload = HTMLVideoElement.prototype.load;
    HTMLVideoElement.prototype.load = function() {
        try { var s=this.src||this.getAttribute('src')||this.querySelector('source')?.src; if(s)report(s,'video_load','GET'); } catch(e){}
        return _vload.apply(this,arguments);
    };

    // ── Hook video.play() ─────────────────────────────────────────────────────
    var _vplay = HTMLVideoElement.prototype.play;
    HTMLVideoElement.prototype.play = function() {
        try { var s=this.src||this.getAttribute('src'); if(s)report(s,'video_play','GET'); } catch(e){}
        return _vplay.apply(this,arguments);
    };

    // ── Hook MediaSource.addSourceBuffer (MSE/DASH) ──────────────────────────
    try {
        var _addSB = MediaSource.prototype.addSourceBuffer;
        MediaSource.prototype.addSourceBuffer = function(mime) {
            report('mse://'+encodeURIComponent(mime),'mse','GET');
            return _addSB.apply(this,arguments);
        };
    } catch(e) {}

    // ── Hook EventSource (SSE streams) ────────────────────────────────────────
    try {
        var _ES = window.EventSource;
        if (_ES) window.EventSource = function(url, opts) {
            report(url,'eventsource','GET');
            return new _ES(url, opts);
        };
        window.EventSource.prototype = _ES.prototype;
    } catch(e) {}

    // ── Hook WebSocket (some streams use WS) ──────────────────────────────────
    try {
        var _WS = window.WebSocket;
        if (_WS) window.WebSocket = function(url, protocols) {
            if (url && url.indexOf('.m3u8')===-1 && url.indexOf('.mp4')===-1) report(url,'websocket','GET');
            return protocols ? new _WS(url, protocols) : new _WS(url);
        };
        window.WebSocket.prototype = _WS.prototype;
    } catch(e) {}

    // ── Hook jwplayer ─────────────────────────────────────────────────────────
    Object.defineProperty(window,'jwplayer',{configurable:true,get:function(){return window.__jwp;},set:function(jwp){window.__jwp=jwp;if(!jwp||!jwp.prototype)return;var orig=jwp.prototype.setup;jwp.prototype.setup=function(cfg){try{[cfg.file,cfg.sources,cfg.playlist,cfg.playlistItem].forEach(function(s){if(typeof s==='string')report(s,'jwplayer','GET');else if(Array.isArray(s))s.forEach(function(i){if(i&&i.file)report(i.file,'jwplayer','GET');else if(i&&i.source)report(i.source,'jwplayer','GET');});});}catch(e){}return orig?orig.apply(this,arguments):this;};}});

    // ── Hook videojs ──────────────────────────────────────────────────────────
    try {
        var _vjs = window.videojs;
        if (_vjs) {
            var _vjsOrig = _vjs;
            window.videojs = function(el, opts, ready) {
                try {
                    if (opts && opts.sources) opts.sources.forEach(function(s){if(s&&s.src)report(s.src,'videojs','GET');});
                    if (opts && opts.src) report(opts.src,'videojs','GET');
                } catch(e) {}
                return _vjsOrig.apply(this, arguments);
            };
            Object.assign(window.videojs, _vjsOrig);
        }
    } catch(e) {}

    // ── DOM scan delayed (catch dynamic content) ─────────────────────────────
    [1000,3000,6000,10000].forEach(function(t){setTimeout(function(){
        // Scan video/source elements
        document.querySelectorAll('video,source,iframe').forEach(function(el){
            var s=el.src||el.getAttribute('src')||el.getAttribute('data-src')||el.getAttribute('data-source');
            if(s&&!s.startsWith('blob:'))report(s,'dom_scan','GET');
            // Check poster (sometimes contains useful URLs)
            if(el.getAttribute('poster'))report(el.getAttribute('poster'),'poster','GET');
        });
        // Scan inline scripts for stream URLs
        var pats=[/["'](https?:\/\/[^"']+\.m3u8[^"']*?)["']/g,/["'](https?:\/\/[^"']+\.mp4[^"']*?)["']/g,/["'](https?:\/\/[^"']+\.mpd[^"']*?)["']/g,/["'](https?:\/\/[^"']+\.flv[^"']*?)["']/g,/["'](https?:\/\/[^"']+\.ts[^"']*?)["']/g,/file\s*:\s*["'](https?:\/\/[^"']+?)["']/g,/source\s*:\s*["'](https?:\/\/[^"']+?)["']/g,/hls\s*:\s*["'](https?:\/\/[^"']+?)["']/g,/src\s*:\s*["'](https?:\/\/[^"']+?(?:m3u8|mp4|mpd|flv)[^"']*?)["']/g,/url\s*:\s*["'](https?:\/\/[^"']+?(?:m3u8|mp4|mpd|flv)[^"']*?)["']/g,/playlist\s*:\s*["'](https?:\/\/[^"']+?)["']/g,/stream\s*:\s*["'](https?:\/\/[^"']+?)["']/g,/dash\s*:\s*["'](https?:\/\/[^"']+?)["']/g,/videoUrl\s*:\s*["'](https?:\/\/[^"']+?)["']/g,/playUrl\s*:\s*["'](https?:\/\/[^"']+?)["']/g,/downloadUrl\s*:\s*["'](https?:\/\/[^"']+?)["']/g];
        document.querySelectorAll('script:not([src])').forEach(function(s){var txt=s.textContent||'';pats.forEach(function(re){re.lastIndex=0;var m;while((m=re.exec(txt))!==null)report(m[1],'script_scan','GET');});});
        // Scan object/embed tags
        document.querySelectorAll('object[data],embed[src]').forEach(function(el){var s=el.getAttribute('data')||el.getAttribute('src');if(s)report(s,'embed','GET');});
    },t);});

    // ── MutationObserver (catch dynamically added video elements) ─────────────
    try {
        var observer = new MutationObserver(function(mutations) {
            mutations.forEach(function(mutation) {
                mutation.addedNodes.forEach(function(node) {
                    if (node.nodeType !== 1) return;
                    if (node.tagName === 'VIDEO' || node.tagName === 'SOURCE') {
                        var s = node.src || node.getAttribute('src') || node.getAttribute('data-src');
                        if (s) report(s, 'mutation', 'GET');
                    }
                    // Check child video/source in added node
                    var videos = node.querySelectorAll ? node.querySelectorAll('video,source') : [];
                    videos.forEach(function(el) {
                        var s = el.src || el.getAttribute('src') || el.getAttribute('data-src');
                        if (s) report(s, 'mutation', 'GET');
                    });
                });
            });
        });
        if (document.body) observer.observe(document.body, {childList: true, subtree: true});
        else setTimeout(function(){ observer.observe(document.documentElement, {childList: true, subtree: true}); }, 500);
    } catch(e) {}
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
    val consoleLog     = BoundedStringBuilder("// Console\n", 50000)
    val deepLog        = BoundedStringBuilder("// Deep Inject\n", 50000)
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
        // Persist stream to history
        context?.let { ctx ->
            try {
                BookmarkManager.addStreamHistory(ctx, item.url, item.label, item.source)
            } catch (_: Exception) {}
        }
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
    fun softClear() = synchronized(this) {
        // Keep streams but mark them from previous page
        // Only clear requests to avoid memory bloat
        _requests.clear()
    }
    fun streamCount()  = synchronized(this) { _streams.size  }
    fun requestCount() = synchronized(this) { _requests.size }

    private fun isNoise(url: String): Boolean {
        val l = url.lowercase()
        return l.contains(".css")||l.contains(".woff")||l.contains(".woff2")||l.contains(".ttf")||
               l.contains(".eot")||l.contains(".ico")||l.contains(".svg")||l.contains(".png")||
               l.contains(".jpg")||l.contains(".jpeg")||l.contains(".gif")||l.contains(".webp")||
               l.contains(".avif")||l.contains("google-analytics")||l.contains("googletagmanager")||
               l.contains("facebook.com/tr")||l.contains("doubleclick")||l.contains("beacon")||
               l.contains("telemetry")||l.contains("hotjar")||l.contains("clarity.ms")||
               l.contains(".mp3")||l.contains("recaptcha")||l.contains("firebase")||
               l.contains("crashlytics")||l.contains("sentry.io")||l.contains("analytics")||
               l.contains("pixel")||l.contains("tracking")||l.contains("collect?")||
               l.contains("pushwoosh")||l.contains("onesignal")
    }
}

class BoundedStringBuilder(initial: String = "", private val maxSize: Int = 50000) {
    private val sb = StringBuilder(initial)
    private val prefix = initial
    val length: Int get() = sb.length
    fun append(s: String): BoundedStringBuilder {
        sb.append(s)
        if (sb.length > maxSize) {
            sb.delete(0, sb.length - maxSize)
        }
        return this
    }
    fun clear() { sb.clear(); sb.append(prefix) }
    override fun toString() = sb.toString()
}
