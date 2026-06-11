package com.aho.streambrowser.detector

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.WebResourceRequest
import com.aho.streambrowser.model.NetworkRequest
import com.aho.streambrowser.model.StreamItem

// ── Data classes ─────────────────────────────────────────────────────────────

data class CryptoKeyCapture(
    val algorithm: String,
    val key:       String,
    val iv:        String  = "",
    val pageUrl:   String  = "",
    val timestamp: Long    = System.currentTimeMillis()
)

data class ResponseBodyCapture(
    val url:         String,
    val statusCode:  Int,
    val contentType: String,
    val body:        String,
    val timestamp:   Long = System.currentTimeMillis()
)

// ── Protected domains ─────────────────────────────────────────────────────────

val PROTECTED_DOMAINS = listOf(
    "streamc.xyz", "streamc.com", "vidsrc", "vidsrc.in", "vidsrc.io",
    "streamwish", "streamwish.com", "wishfast", "dood", "dood.to",
    "embedsr", "srsone", "123movies", "putlocker", "fmovies",
    "gogohd", "gogohd.net", "superembed", "moviehab"
)

// ── HOOK JS ──────────────────────────────────────────────────────────────────

val HOOK_JS = """
(function() {
    'use strict';
    if (window.__sb_injected_v4) return 'ok';
    window.__sb_injected_v4 = true;

    var __sb_protected = (function() {
        var h = window.location.hostname.toLowerCase();
        var p = %(PROTECTED_DOMAINS)s;
        for (var i = 0; i < p.length; i++) { if (h.indexOf(p[i]) !== -1) return true; }
        return false;
    })();

    var __sb = {
        config: { maxUrls: 500, debounceMs: 80, iframeDepth: 2 },
        state: {
            urls: new Set(), lastReport: 0, lastDeepScan: 0,
            injectedFrames: new WeakSet(),
            hookedPlayers: new WeakSet(),
            hookedAPIs: new WeakSet(),
            scanCount: 0
        },
        timers: [], observers: [], hooks: []
    };

    // ── Anti-fingerprinting ──────────────────────────────────────────────────
    (function() {
        try {
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array;
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise;
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol;
            Object.defineProperty(navigator, 'webdriver', { get: function() { return false; }, configurable: true });
            if (!__sb_protected) {
                Object.defineProperties(navigator, {
                    'plugins':  { get: function() { return [{name:'Chrome PDF Plugin'},{name:'Native Client'}]; }, configurable: true },
                    'languages':{ get: function() { return ['vi-VN','vi','en-US','en']; }, configurable: true },
                    'platform': { get: function() { return 'Win32'; }, configurable: true },
                    'hardwareConcurrency': { get: function() { return 8; }, configurable: true }
                });
                if (!window.chrome) window.chrome = { runtime: {} };
            }
        } catch(e) {}
    })();

    // ── Utilities ────────────────────────────────────────────────────────────
    function normalizeUrl(url) {
        if (!url || typeof url !== 'string') return null;
        url = url.trim();
        if (url.startsWith('//')) url = location.protocol + url;
        if (url.startsWith('blob:')) return url;
        if (!url.match(/^https?:\/\//i)) return null;
        return url;
    }

    function report(url, source, method) {
        try {
            url = normalizeUrl(url);
            if (!url) return;
            if (__sb.state.urls.has(url)) return;
            if (__sb.state.urls.size >= __sb.config.maxUrls) {
                var first = __sb.state.urls.values().next().value;
                __sb.state.urls.delete(first);
            }
            __sb.state.urls.add(url);
            var now = Date.now();
            if (now - __sb.state.lastReport < __sb.config.debounceMs) {
                clearTimeout(__sb._dt);
                __sb._dt = setTimeout(function() {
                    __sb.state.lastReport = Date.now();
                    SBridge.onRequest(url, source, method || 'GET');
                }, __sb.config.debounceMs);
            } else {
                __sb.state.lastReport = now;
                SBridge.onRequest(url, source, method || 'GET');
            }
        } catch(e) {}
    }

    function extractUrls(text, source) {
        if (!text || typeof text !== 'string' || text.length > 500000) return;
        var pats = [
            /\b(https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/gi,
            /\b(https?:\/\/[^\s"'<>]+\.mpd[^\s"'<>]*)/gi,
            /\b(https?:\/\/[^\s"'<>]+\.mp4[^\s"'<>]*)/gi,
            /\b(https?:\/\/[^\s"'<>]+\.webm[^\s"'<>]*)/gi,
            /\b(https?:\/\/[^\s"'<>]+\.flv[^\s"'<>]*)/gi,
            /"(https?:\/\/[^"]+\.(?:m3u8|mpd|mp4|webm|flv)[^"]*)"/gi
        ];
        pats.forEach(function(pat) {
            try {
                var r = new RegExp(pat.source, pat.flags), m;
                while ((m = r.exec(text)) !== null) { if (m[1]) report(m[1], 'extract_' + source, 'GET'); }
            } catch(e) {}
        });
    }

    // ── XHR Hook ─────────────────────────────────────────────────────────────
    (function() {
        if (__sb.state.hookedAPIs.has('xhr')) return;
        __sb.state.hookedAPIs.add('xhr');
        var XHR = XMLHttpRequest;
        var oOpen = XHR.prototype.open, oSend = XHR.prototype.send, oSetHdr = XHR.prototype.setRequestHeader;
        XHR.prototype.open = function(m, url) { this.__sb_url = url; this.__sb_method = m; return oOpen.apply(this, arguments); };
        XHR.prototype.setRequestHeader = function(n, v) {
            if (!this.__sb_headers) this.__sb_headers = {};
            this.__sb_headers[n.toLowerCase()] = v;
            return oSetHdr.apply(this, arguments);
        };
        XHR.prototype.send = function(body) {
            var xhr = this;
            report(xhr.__sb_url, 'xhr', xhr.__sb_method || 'GET');
            var origCb = xhr.onreadystatechange;
            xhr.onreadystatechange = function() {
                if (xhr.readyState === 4) {
                    try {
                        var ct = xhr.getResponseHeader('Content-Type') || '';
                        var resp = xhr.responseText;
                        if (resp && resp.length < 300000) {
                            // Detect encrypted hex response (IV:CIPHERTEXT pattern — e.g. x.mimix.cc)
                            var trimmed = resp.trim();
                            if (/^[0-9a-fA-F]{32}:[0-9a-fA-F]{64,}$/.test(trimmed)) {
                                try { SBridge.onResponseBody(xhr.__sb_url || '', xhr.status, ct, trimmed); } catch(e) {}
                            }
                            // Scan for stream URLs
                            if (ct.match(/javascript|json|mpegURL|dash\+xml|text\/html/i)) {
                                extractUrls(resp, 'xhr_resp');
                            }
                        }
                    } catch(e) {}
                }
                if (origCb) return origCb.apply(xhr, arguments);
            };
            return oSend.apply(this, arguments);
        };
        __sb.hooks.push(function() {
            XHR.prototype.open = oOpen; XHR.prototype.send = oSend; XHR.prototype.setRequestHeader = oSetHdr;
        });
    })();

    // ── Fetch Hook ───────────────────────────────────────────────────────────
    (function() {
        if (__sb.state.hookedAPIs.has('fetch')) return;
        __sb.state.hookedAPIs.add('fetch');
        var oFetch = window.fetch;
        window.fetch = function(input, init) {
            var url = typeof input === 'string' ? input : (input && input.url);
            var method = (init && init.method) || (input && input.method) || 'GET';
            if (url) report(url, 'fetch', method);
            var prom = oFetch.apply(this, arguments);
            prom.then(function(r) {
                try {
                    var ct = r.headers.get('Content-Type') || '';
                    if (ct.match(/mpegurl|dash\+xml|javascript|json|text\/html/i)) {
                        r.clone().text().then(function(t) { if (t && t.length < 300000) extractUrls(t, 'fetch_resp'); }).catch(function(){});
                    }
                } catch(e) {}
            }).catch(function(){});
            return prom;
        };
        __sb.hooks.push(function() { window.fetch = oFetch; });
    })();

    // ── HTMLMediaElement Hook ────────────────────────────────────────────────
    (function() {
        if (__sb.state.hookedAPIs.has('media')) return;
        __sb.state.hookedAPIs.add('media');
        var proto = HTMLMediaElement.prototype;
        var sd = Object.getOwnPropertyDescriptor(proto, 'src');
        if (sd && sd.set) {
            Object.defineProperty(proto, 'src', {
                get: function() { return sd.get.call(this); },
                set: function(v) { if (v) report(v, 'media_src', 'GET'); return sd.set.call(this, v); },
                configurable: true
            });
        }
        var origLoad = proto.load;
        proto.load = function() { if (this.src) report(this.src, 'media_load', 'GET'); return origLoad.apply(this, arguments); };
        var origSetAttr = Element.prototype.setAttribute;
        Element.prototype.setAttribute = function(n, v) {
            var r = origSetAttr.apply(this, arguments);
            if ((this.tagName === 'VIDEO' || this.tagName === 'AUDIO' || this.tagName === 'SOURCE') &&
                (n === 'src' || n.startsWith('data-')) && v && v.startsWith('http')) {
                report(v, 'attr_' + n, 'GET');
            }
            return r;
        };
        __sb.hooks.push(function() {
            if (sd) Object.defineProperty(proto, 'src', sd);
            proto.load = origLoad;
            Element.prototype.setAttribute = origSetAttr;
        });
    })();

    // ── HLS.js Hook ──────────────────────────────────────────────────────────
    (function() {
        var t = setInterval(function() {
            if (window.Hls && !__sb.state.hookedPlayers.has('hls')) {
                __sb.state.hookedPlayers.add('hls');
                var oLS = Hls.prototype.loadSource;
                Hls.prototype.loadSource = function(url) { report(url, 'hls_js', 'GET'); return oLS.apply(this, arguments); };
                clearInterval(t);
            }
        }, 200);
        __sb.timers.push(t);
    })();

    // ── Dash.js Hook ──────────────────────────────────────────────────────────
    (function() {
        var t = setInterval(function() {
            if (window.dashjs && !__sb.state.hookedPlayers.has('dash')) {
                __sb.state.hookedPlayers.add('dash');
                try {
                    if (dashjs.MediaPlayer) {
                        var oCreate = dashjs.MediaPlayer.prototype.create;
                        dashjs.MediaPlayer.prototype.create = function() {
                            var p = oCreate.apply(this, arguments);
                            if (p.attachSource) { var oA = p.attachSource; p.attachSource = function(url) { report(url, 'dash_attach', 'GET'); return oA.apply(this, arguments); }; }
                            return p;
                        };
                    }
                } catch(e) {}
                clearInterval(t);
            }
        }, 200);
        __sb.timers.push(t);
    })();

    // ── Video.js Hook ─────────────────────────────────────────────────────────
    (function() {
        var t = setInterval(function() {
            if (window.videojs && !__sb.state.hookedPlayers.has('videojs')) {
                __sb.state.hookedPlayers.add('videojs');
                var oVjs = window.videojs;
                window.videojs = function(id, opts, ready) {
                    if (opts && opts.sources) opts.sources.forEach(function(s) { if (s.src) report(s.src, 'videojs_src', 'GET'); });
                    var p = oVjs.apply(this, arguments);
                    if (p && p.src) { var oS = p.src; p.src = function(v) { if (v) { var u = typeof v === 'string' ? v : (v && v.src); if (u) report(u, 'videojs_src', 'GET'); } return oS.apply(this, arguments); }; }
                    return p;
                };
                Object.keys(oVjs).forEach(function(k) { try { window.videojs[k] = oVjs[k]; } catch(e) {} });
                clearInterval(t);
            }
        }, 200);
        __sb.timers.push(t);
    })();

    // ── JWPlayer Hook ─────────────────────────────────────────────────────────
    (function() {
        var t = setInterval(function() {
            if (window.jwplayer && !__sb.state.hookedPlayers.has('jwplayer')) {
                __sb.state.hookedPlayers.add('jwplayer');
                var oJw = window.jwplayer;
                window.jwplayer = function(id) {
                    var p = oJw(id);
                    if (p && p.setup) { var oS = p.setup; p.setup = function(cfg) {
                        if (cfg) {
                            if (cfg.file) report(cfg.file, 'jwplayer_file', 'GET');
                            if (cfg.sources) cfg.sources.forEach(function(s) { if (s.file) report(s.file, 'jwplayer_src', 'GET'); });
                        }
                        return oS.apply(this, arguments);
                    }; }
                    return p;
                };
                window.jwplayer.version = oJw.version;
                clearInterval(t);
            }
        }, 200);
        __sb.timers.push(t);
    })();

    // ── ArtPlayer Hook ────────────────────────────────────────────────────────
    // Dùng bởi x.haiten.org, nhiều site streaming châu Á
    (function() {
        var t = setInterval(function() {
            if (window.Artplayer && !__sb.state.hookedPlayers.has('artplayer')) {
                __sb.state.hookedPlayers.add('artplayer');
                var orig = window.Artplayer;
                window.Artplayer = function(option) {
                    if (option) {
                        if (option.url) report(option.url, 'artplayer_url', 'GET');
                        // Hook customType (dùng cho HLS.js, flv.js, etc. trong ArtPlayer)
                        if (option.customType) {
                            Object.keys(option.customType).forEach(function(type) {
                                var fn = option.customType[type];
                                option.customType[type] = function(video, url) {
                                    if (url) report(url, 'artplayer_custom_' + type, 'GET');
                                    return fn && fn.apply(this, arguments);
                                };
                            });
                        }
                    }
                    var instance;
                    try { instance = new orig(option); } catch(e) { throw e; }
                    try {
                        if (instance && instance.on) {
                            // Bắt loadstart để lấy URL video thực (sau khi blob được tạo)
                            instance.on('video:loadstart', function() {
                                var src = instance.video && instance.video.currentSrc;
                                if (src && !src.startsWith('blob:')) report(src, 'artplayer_loadstart', 'GET');
                            });
                            instance.on('video:src', function(url) {
                                if (url && !url.startsWith('blob:')) report(url, 'artplayer_src_event', 'GET');
                            });
                        }
                    } catch(e) {}
                    return instance;
                };
                try {
                    Object.keys(orig).forEach(function(k) { try { window.Artplayer[k] = orig[k]; } catch(e) {} });
                    window.Artplayer.prototype = orig.prototype;
                } catch(e) {}
                clearInterval(t);
            }
        }, 200);
        __sb.timers.push(t);
    })();

    // ── Plyr Hook ─────────────────────────────────────────────────────────────
    (function() {
        var t = setInterval(function() {
            if (window.Plyr && !__sb.state.hookedPlayers.has('plyr')) {
                __sb.state.hookedPlayers.add('plyr');
                var orig = window.Plyr;
                window.Plyr = function(target, opts) {
                    if (opts && opts.sources) opts.sources.forEach(function(s) { if (s.src) report(s.src, 'plyr_source', 'GET'); });
                    var p = new orig(target, opts);
                    try { if (p.media && p.media.src) report(p.media.src, 'plyr_media', 'GET'); } catch(e) {}
                    return p;
                };
                window.Plyr.prototype = orig.prototype;
                Object.keys(orig).forEach(function(k) { try { window.Plyr[k] = orig[k]; } catch(e) {} });
                clearInterval(t);
            }
        }, 200);
        __sb.timers.push(t);
    })();

    // ── CryptoJS Hook ──────────────────────────────────────────────────────────
    // Bắt AES decrypt key — quan trọng cho sites mã hoá stream URL (như x.mimix.cc)
    (function() {
        var t = setInterval(function() {
            if (window.CryptoJS && !__sb.state.hookedPlayers.has('cryptojs')) {
                __sb.state.hookedPlayers.add('cryptojs');
                var cjs = window.CryptoJS;
                function keyToStr(k) {
                    if (!k) return '';
                    if (typeof k === 'string') return k;
                    try { return cjs.enc.Hex.stringify(k); } catch(e) {}
                    try { return cjs.enc.Utf8.stringify(k); } catch(e) {}
                    return String(k);
                }
                if (cjs.AES) {
                    // Hook decrypt — bắt key + IV
                    var oDecrypt = cjs.AES.decrypt;
                    cjs.AES.decrypt = function(cipher, key, cfg) {
                        try {
                            var kStr  = keyToStr(key);
                            var ivStr = (cfg && cfg.iv) ? keyToStr(cfg.iv) : '';
                            if (kStr) SBridge.onCryptoKey('CryptoJS.AES.decrypt', kStr, ivStr);
                        } catch(e) {}
                        return oDecrypt.apply(this, arguments);
                    };
                    // Hook encrypt — bắt key
                    var oEncrypt = cjs.AES.encrypt;
                    cjs.AES.encrypt = function(msg, key, cfg) {
                        try {
                            var kStr = keyToStr(key);
                            if (kStr) SBridge.onCryptoKey('CryptoJS.AES.encrypt', kStr, '');
                        } catch(e) {}
                        return oEncrypt.apply(this, arguments);
                    };
                }
                clearInterval(t);
            }
        }, 200);
        __sb.timers.push(t);
    })();

    // ── SubtleCrypto Hook ───────────────────────────────────────────────────────
    // Bắt Web Crypto API keys + IV từ decrypt calls
    (function() {
        if (!window.crypto || !window.crypto.subtle) return;
        if (__sb.state.hookedAPIs.has('subtle')) return;
        __sb.state.hookedAPIs.add('subtle');
        var sub = window.crypto.subtle;
        function toHex(buf) {
            var arr = buf instanceof ArrayBuffer ? new Uint8Array(buf) : new Uint8Array(buf.buffer || buf);
            return Array.from(arr).map(function(b) { return ('0' + b.toString(16)).slice(-2); }).join('');
        }
        var oImport = sub.importKey.bind(sub);
        sub.importKey = function(fmt, keyData, alg, extractable, usages) {
            try {
                if (keyData instanceof ArrayBuffer || ArrayBuffer.isView(keyData)) {
                    var hex = toHex(keyData);
                    if (hex.length >= 32) SBridge.onCryptoKey('SubtleCrypto.importKey', hex, JSON.stringify(alg));
                }
            } catch(e) {}
            return oImport.apply(this, arguments);
        };
        var oDecrypt = sub.decrypt.bind(sub);
        sub.decrypt = function(alg, key, data) {
            try {
                if (alg && alg.iv) {
                    var iv = alg.iv instanceof ArrayBuffer ? new Uint8Array(alg.iv) :
                             ArrayBuffer.isView(alg.iv) ? new Uint8Array(alg.iv.buffer, alg.iv.byteOffset, alg.iv.byteLength) : null;
                    if (iv) SBridge.onCryptoKey('SubtleCrypto.decrypt.iv', toHex(iv), alg.name || '');
                }
            } catch(e) {}
            return oDecrypt.apply(this, arguments);
        };
        __sb.hooks.push(function() { sub.importKey = oImport; sub.decrypt = oDecrypt; });
    })();

    // ── MediaSource Hook ─────────────────────────────────────────────────────
    (function() {
        if (!window.MediaSource || __sb.state.hookedAPIs.has('mse')) return;
        __sb.state.hookedAPIs.add('mse');
        var oMS = window.MediaSource;
        window.MediaSource = function() {
            var ms = new oMS();
            var oAdd = ms.addSourceBuffer.bind(ms);
            ms.addSourceBuffer = function(type) {
                if (type && (type.includes('mp4') || type.includes('webm') || type.includes('mpeg')))
                    report(location.href + '#mse:' + type, 'mse_type', 'GET');
                return oAdd(type);
            };
            return ms;
        };
        window.MediaSource.prototype = oMS.prototype;
        window.MediaSource.isTypeSupported = oMS.isTypeSupported.bind(oMS);
        __sb.hooks.push(function() { window.MediaSource = oMS; });
    })();

    // ── MutationObserver ──────────────────────────────────────────────────────
    (function() {
        var batch = [], timer = null;
        function processBatch() {
            timer = null;
            var nodes = new Set();
            batch.forEach(function(m) {
                m.addedNodes.forEach(function(n) {
                    if (n.nodeType !== 1) return;
                    if (n.tagName === 'VIDEO' || n.tagName === 'AUDIO' || n.tagName === 'IFRAME' || n.tagName === 'SOURCE') nodes.add(n);
                    try { n.querySelectorAll && n.querySelectorAll('video,audio,source,iframe').forEach(function(el) { nodes.add(el); }); } catch(e) {}
                });
                if (m.type === 'attributes' && (m.target.tagName === 'VIDEO' || m.target.tagName === 'AUDIO')) nodes.add(m.target);
            });
            nodes.forEach(function(n) {
                var src = n.src || n.getAttribute('src') || n.getAttribute('data-src') || n.getAttribute('data-hls');
                if (src && src.startsWith('http')) report(src, 'mutation_' + n.tagName.toLowerCase(), 'GET');
            });
            batch = [];
        }
        var obs = new MutationObserver(function(ms) {
            batch.push.apply(batch, ms);
            if (!timer) timer = setTimeout(processBatch, 300);
        });
        obs.observe(document.documentElement, {
            childList: true, subtree: true, attributes: true,
            attributeFilter: ['src', 'data-src', 'data-video', 'data-hls', 'data-m3u8']
        });
        __sb.observers.push(obs);
    })();

    // ── Shadow DOM ────────────────────────────────────────────────────────────
    (function() {
        var oAS = Element.prototype.attachShadow;
        if (!oAS) return;
        Element.prototype.attachShadow = function(mode) {
            var sr = oAS.call(this, mode);
            try {
                var obs = new MutationObserver(function(ms) {
                    ms.forEach(function(m) {
                        m.addedNodes.forEach(function(n) {
                            if (n.nodeType !== 1) return;
                            var src = n.src || n.getAttribute('src');
                            if (src && src.startsWith('http')) report(src, 'shadow_dom', 'GET');
                        });
                    });
                });
                obs.observe(sr, { childList: true, subtree: true });
                __sb.observers.push(obs);
            } catch(e) {}
            return sr;
        };
        __sb.hooks.push(function() { Element.prototype.attachShadow = oAS; });
    })();

    // ── Scheduled deep scans ──────────────────────────────────────────────────
    function deepScan() {
        if (__sb.state.scanCount > 30) return;
        __sb.state.scanCount++;
        document.querySelectorAll('script:not([src])').forEach(function(s) {
            if (!s.__sb_s) { s.__sb_s = true; extractUrls(s.textContent, 'deep_script'); }
        });
        document.querySelectorAll('video,audio').forEach(function(v) {
            var src = v.src || v.getAttribute('src') || v.currentSrc;
            if (src && src.startsWith('http') && !src.startsWith('blob:')) report(src, 'deep_media', 'GET');
        });
        // Scan common state stores
        try { if (window.__NEXT_DATA__) extractUrls(JSON.stringify(window.__NEXT_DATA__), 'next_data'); } catch(e) {}
        try { if (window.__INITIAL_STATE__) extractUrls(JSON.stringify(window.__INITIAL_STATE__), 'initial_state'); } catch(e) {}
    }
    [800, 2500, 6000, 15000].forEach(function(d) {
        var t = setTimeout(deepScan, d);
        __sb.timers.push(t);
    });

    // ── Initial scan ─────────────────────────────────────────────────────────
    function initialScan() {
        document.querySelectorAll('script:not([src])').forEach(function(s) { extractUrls(s.textContent, 'init_script'); });
        document.querySelectorAll('video,audio').forEach(function(v) {
            var src = v.src || v.getAttribute('src') || v.currentSrc;
            if (src && src.startsWith('http')) report(src, 'init_media', 'GET');
        });
        document.querySelectorAll('[data-video],[data-src],[data-hls],[data-m3u8]').forEach(function(el) {
            var src = el.getAttribute('data-video') || el.getAttribute('data-src') || el.getAttribute('data-hls') || el.getAttribute('data-m3u8');
            if (src && src.startsWith('http')) report(src, 'init_data_attr', 'GET');
        });
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────
    window.addEventListener('beforeunload', function() {
        __sb.timers.forEach(function(t) { clearTimeout(t); clearInterval(t); });
        __sb.observers.forEach(function(o) { try { o.disconnect(); } catch(e) {} });
        __sb.hooks.forEach(function(fn) { try { fn(); } catch(e) {} });
    });

    // ── Start ─────────────────────────────────────────────────────────────────
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialScan);
    } else {
        setTimeout(initialScan, 200);
    }

    return 'ok';
})();
""".trimIndent()

// ── StreamDetector ────────────────────────────────────────────────────────────

class StreamDetector(private val context: Context? = null) {
    private val _streams       = mutableListOf<StreamItem>()
    private val _requests      = mutableListOf<NetworkRequest>()
    private val _cryptoKeys    = mutableListOf<CryptoKeyCapture>()
    private val _responseBodies = mutableListOf<ResponseBodyCapture>()

    val streams:        List<StreamItem>          get() = synchronized(this) { _streams.toList()        }
    val requests:       List<NetworkRequest>      get() = synchronized(this) { _requests.toList()       }
    val cryptoKeys:     List<CryptoKeyCapture>    get() = synchronized(this) { _cryptoKeys.toList()     }
    val responseBodies: List<ResponseBodyCapture> get() = synchronized(this) { _responseBodies.toList() }

    var onStreamFound:  ((StreamItem)     -> Unit)? = null
    var onRequestAdded: ((NetworkRequest) -> Unit)? = null

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

    fun addCryptoKey(capture: CryptoKeyCapture) {
        synchronized(this) {
            if (_cryptoKeys.none { it.key == capture.key && it.algorithm == capture.algorithm }) {
                _cryptoKeys.add(0, capture)
                if (_cryptoKeys.size > 50) _cryptoKeys.removeAt(_cryptoKeys.lastIndex)
            }
        }
    }

    fun addResponseBody(url: String, statusCode: Int, contentType: String, body: String) {
        synchronized(this) {
            if (_responseBodies.none { it.url == url }) {
                _responseBodies.add(0, ResponseBodyCapture(url, statusCode, contentType, body))
                if (_responseBodies.size > 20) _responseBodies.removeAt(_responseBodies.lastIndex)
            }
        }
    }

    @Synchronized fun updateRequest(url: String, updated: NetworkRequest) {
        val idx = _requests.indexOfFirst { it.url == url }
        if (idx >= 0) _requests[idx] = updated
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
        vibrate()
    }

    fun clear() = synchronized(this) {
        _streams.clear(); _requests.clear()
        _cryptoKeys.clear(); _responseBodies.clear()
    }
    fun streamCount()  = synchronized(this) { _streams.size  }
    fun requestCount() = synchronized(this) { _requests.size }
    fun cryptoCount()  = synchronized(this) { _cryptoKeys.size }

    private fun vibrate() {
        context ?: return
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vib.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vib.vibrate(80)
        } catch (_: Exception) {}
    }

    private fun isNoise(url: String): Boolean {
        val l = url.lowercase()
        return l.contains(".css")||l.contains(".woff")||l.contains(".ttf")||l.contains(".eot")||
               l.contains(".ico")||l.contains(".svg")||l.contains(".png")||l.contains(".jpg")||
               l.contains(".jpeg")||l.contains(".gif")||l.contains(".webp")||l.contains(".avif")||
               l.contains("google-analytics")||l.contains("googletagmanager")||
               l.contains("facebook.com/tr")||l.contains("doubleclick")||l.contains("hotjar")||
               l.contains("clarity.ms")||l.contains("beacon")||l.contains("telemetry")
    }
}
