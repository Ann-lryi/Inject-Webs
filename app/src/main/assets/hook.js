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
            scanCount: 0,
            pending: []
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

    // ── Anti-fingerprint: hide native-API hook signatures from Function.prototype.toString ──
    // A page can detect that XHR/fetch/WebSocket/etc. have been tampered with by calling
    // fn.toString() — real native browser methods return "function x() { [native code] }",
    // a monkey-patched replacement returns its actual JS source. Every native-API hook below
    // (not the JS-library hooks like CryptoJS/player libs, which were never "native" to begin
    // with and have nothing to hide) wraps its replacement in N(...) to register it here.
    // MUST run before any hook below so N() exists when they need it.
    var __sbNative = new WeakSet();
    function N(fn) { try { __sbNative.add(fn); } catch(e) {} return fn; }
    (function() {
        try {
            var origToString = Function.prototype.toString;
            Function.prototype.toString = N(function() {
                if (__sbNative.has(this)) return 'function ' + (this.name || '') + '() { [native code] }';
                return origToString.call(this);
            });
        } catch(e) {}
    })();

    // ── Canvas fingerprint noise ─────────────────────────────────────────────
    // Anti-bot services commonly fingerprint via canvas render output — identical input
    // pixels hash differently across real GPU/driver combos but often identically across
    // automated/emulated environments, making it a strong bot signal. Standard countermeasure
    // (same one Brave/Tor Browser use): inject imperceptible per-pixel noise into the OUTPUT
    // of getImageData/toDataURL so the hash changes. Uses an off-screen temp canvas for
    // toDataURL so the page's own visible canvas is never mutated — only what's read OUT.
    (function() {
        try {
            if (__sb_protected || !window.CanvasRenderingContext2D || !window.HTMLCanvasElement) return;
            var noisify = function(imageData) {
                var d = imageData.data;
                for (var i = 0; i < d.length; i += 4) {
                    var n = ((i >> 2) % 7) - 3;
                    d[i]   = Math.max(0, Math.min(255, d[i]   + n));
                    d[i+1] = Math.max(0, Math.min(255, d[i+1] + n));
                    d[i+2] = Math.max(0, Math.min(255, d[i+2] + n));
                }
                return imageData;
            };
            var CtxProto = CanvasRenderingContext2D.prototype;
            var oGetImageData = CtxProto.getImageData;
            CtxProto.getImageData = N(function() {
                var result = oGetImageData.apply(this, arguments);
                try { return noisify(result); } catch(e) { return result; }
            });
            var CanvasProto = HTMLCanvasElement.prototype;
            var oToDataURL = CanvasProto.toDataURL;
            CanvasProto.toDataURL = N(function() {
                try {
                    var w = this.width, h = this.height;
                    if (w > 0 && h > 0 && w * h < 4000000) {
                        var tmp = document.createElement('canvas');
                        tmp.width = w; tmp.height = h;
                        var tctx = tmp.getContext('2d');
                        tctx.drawImage(this, 0, 0);
                        var id = oGetImageData.call(tctx, 0, 0, w, h);
                        tctx.putImageData(noisify(id), 0, 0);   // safe: tmp is off-screen, never shown
                        return oToDataURL.apply(tmp, arguments);
                    }
                } catch(e) {}
                return oToDataURL.apply(this, arguments);
            });
        } catch(e) {}
    })();

    // ── Utilities ────────────────────────────────────────────────────────────
    function normalizeUrl(url) {
        if (!url || typeof url !== 'string') return null;
        url = url.trim()
            .replace(/\\u0026/g, '&')
            .replace(/\\\//g, '/')
            .replace(/&amp;/g, '&');
        if (!url) return null;
        if (url.startsWith('blob:')) return url;
        try { return new URL(url, location.href).href; } catch(e) {}
        return null;
    }

    function looksLikeStreamPath(url) {
        return /(?:\.m3u8?|\.m3u9|\.mpd|\.mp4|\.m4v|\.webm|\.mkv|\.flv)(?:[?#]|$)/i.test(url) ||
               /(?:manifest|master|playlist|chunklist|index)\b/i.test(url);
    }

    function reportCandidate(url, source, method) {
        var normalized = normalizeUrl(url);
        if (normalized && looksLikeStreamPath(normalized)) report(normalized, source, method || 'GET');
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
            __sb.state.pending.push({ u: url, s: source, m: method || 'GET' });
            if (!__sb._dt) {
                __sb._dt = setTimeout(function() {
                    __sb._dt = null;
                    var batch = __sb.state.pending.splice(0, __sb.state.pending.length);
                    batch.forEach(function(item) {
                        try { SBridge.onRequest(item.u, item.s, item.m); } catch(e) {}
                    });
                }, __sb.config.debounceMs);
            }
        } catch(e) {}
    }

    function extractUrls(text, source) {
        if (!text || typeof text !== 'string' || text.length > 500000) return;
        text = text.replace(/\\u0026/g, '&').replace(/\\\//g, '/').replace(/&amp;/g, '&');
        var pats = [
            /\b((?:https?:)?\/\/[^\s"'<>]+\.(?:m3u8?|m3u9|mpd|mp4|m4v|webm|mkv|flv)[^\s"'<>]*)/gi,
            /["']([^"'<>\s]+\.(?:m3u8?|m3u9|mpd|mp4|m4v|webm|mkv|flv)(?:[^"'<>\s]*))["']/gi,
            /["']([^"'<>\s]*(?:manifest|master|playlist|chunklist|index)[^"'<>\s]*)["']/gi
        ];
        pats.forEach(function(pat) {
            try {
                var r = new RegExp(pat.source, pat.flags), m;
                while ((m = r.exec(text)) !== null) { if (m[1]) reportCandidate(m[1], 'extract_' + source, 'GET'); }
            } catch(e) {}
        });
    }

    // ── XHR Hook ─────────────────────────────────────────────────────────────
    (function() {
        if (__sb.state.hookedAPIs.has('xhr')) return;
        __sb.state.hookedAPIs.add('xhr');
        var XHR = XMLHttpRequest;
        var oOpen = XHR.prototype.open, oSend = XHR.prototype.send, oSetHdr = XHR.prototype.setRequestHeader;
        XHR.prototype.open = N(function(m, url) { this.__sb_url = url; this.__sb_method = m; return oOpen.apply(this, arguments); });
        XHR.prototype.setRequestHeader = N(function(n, v) {
            if (!this.__sb_headers) this.__sb_headers = {};
            this.__sb_headers[n.toLowerCase()] = v;
            return oSetHdr.apply(this, arguments);
        });
        XHR.prototype.send = N(function(body) {
            var xhr = this;
            report(xhr.__sb_url, 'xhr', xhr.__sb_method || 'GET');
            // Fix: forward the request body/headers we already captured — was discarded before
            try {
                if (body || (xhr.__sb_headers && Object.keys(xhr.__sb_headers).length)) {
                    var reqBodyStr = typeof body === 'string' ? body.substring(0, 4000) : (body ? '[non-text body]' : '');
                    SBridge.onRequestPayload(xhr.__sb_url || '', JSON.stringify(xhr.__sb_headers || {}), reqBodyStr);
                }
            } catch(e) {}
            var origCb = xhr.onreadystatechange;
            xhr.onreadystatechange = function() {
                if (xhr.readyState === 4) {
                    try {
                        var ct = xhr.getResponseHeader('Content-Type') || '';
                        var resp = xhr.responseText;
                        if (resp && resp.length < 300000) {
                            // Detect encrypted hex response (IV:CIPHERTEXT pattern — e.g. x.mimix.cc)
                            var trimmed = resp.trim();
                            var isEncHex = /^[0-9a-fA-F]{32}:[0-9a-fA-F]{64,}$/.test(trimmed);
                            // Fix: was ONLY the hex:hex pattern above — general JSON API responses
                            // (by far the most common "get stream URL" shape) were never captured.
                            var isJsonLike = ct.match(/json/i) || trimmed.charAt(0) === '{' || trimmed.charAt(0) === '[';
                            if (isEncHex || isJsonLike) {
                                try { SBridge.onResponseBody(xhr.__sb_url || '', xhr.status, ct, trimmed.substring(0, 50000)); } catch(e) {}
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
        });
        __sb.hooks.push(function() {
            XHR.prototype.open = oOpen; XHR.prototype.send = oSend; XHR.prototype.setRequestHeader = oSetHdr;
        });
    })();

    // ── Fetch Hook ───────────────────────────────────────────────────────────
    (function() {
        if (__sb.state.hookedAPIs.has('fetch')) return;
        __sb.state.hookedAPIs.add('fetch');
        var oFetch = window.fetch;
        window.fetch = N(function(input, init) {
            var url = typeof input === 'string' ? input : (input && input.url);
            var method = (init && init.method) || (input && input.method) || 'GET';
            if (url) report(url, 'fetch', method);
            // Fix: forward request body/headers — fetch() has this in `init`, was never sent
            try {
                if (url && init && (init.body || init.headers)) {
                    var reqBodyStr = typeof init.body === 'string' ? init.body.substring(0, 4000) : (init.body ? '[non-text body]' : '');
                    var hdrs = {};
                    if (init.headers) {
                        try { if (init.headers.forEach) init.headers.forEach(function(v, k) { hdrs[k] = v; }); else hdrs = init.headers; } catch(e) {}
                    }
                    SBridge.onRequestPayload(url, JSON.stringify(hdrs), reqBodyStr);
                }
            } catch(e) {}
            var prom = oFetch.apply(this, arguments);
            prom.then(function(r) {
                try {
                    var ct = r.headers.get('Content-Type') || '';
                    if (ct.match(/mpegurl|dash\+xml|javascript|json|text\/html/i)) {
                        r.clone().text().then(function(t) {
                            if (t && t.length < 300000) {
                                extractUrls(t, 'fetch_resp');
                                // Fix: fetch never captured response bodies before — only XHR did
                                var trimmed = t.trim();
                                var isJsonLike = ct.match(/json/i) || trimmed.charAt(0) === '{' || trimmed.charAt(0) === '[';
                                if (isJsonLike) {
                                    try { SBridge.onResponseBody(url || '', r.status, ct, trimmed.substring(0, 50000)); } catch(e) {}
                                }
                            }
                        }).catch(function(){});
                    }
                } catch(e) {}
            }).catch(function(){});
            return prom;
        });
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
                set: N(function(v) { if (v) report(v, 'media_src', 'GET'); return sd.set.call(this, v); }),
                configurable: true
            });
        }
        var origLoad = proto.load;
        proto.load = N(function() { if (this.src) report(this.src, 'media_load', 'GET'); return origLoad.apply(this, arguments); });
        var origSetAttr = Element.prototype.setAttribute;
        Element.prototype.setAttribute = N(function(n, v) {
            var r = origSetAttr.apply(this, arguments);
            if ((this.tagName === 'VIDEO' || this.tagName === 'AUDIO' || this.tagName === 'SOURCE') &&
                (n === 'src' || n.startsWith('data-')) && v) {
                reportCandidate(v, 'attr_' + n, 'GET');
            }
            return r;
        });
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
        sub.importKey = N(function(fmt, keyData, alg, extractable, usages) {
            try {
                if (keyData instanceof ArrayBuffer || ArrayBuffer.isView(keyData)) {
                    var hex = toHex(keyData);
                    if (hex.length >= 32) SBridge.onCryptoKey('SubtleCrypto.importKey', hex, JSON.stringify(alg));
                }
            } catch(e) {}
            return oImport.apply(this, arguments);
        });
        var oDecrypt = sub.decrypt.bind(sub);
        sub.decrypt = N(function(alg, key, data) {
            try {
                if (alg && alg.iv) {
                    var iv = alg.iv instanceof ArrayBuffer ? new Uint8Array(alg.iv) :
                             ArrayBuffer.isView(alg.iv) ? new Uint8Array(alg.iv.buffer, alg.iv.byteOffset, alg.iv.byteLength) : null;
                    if (iv) SBridge.onCryptoKey('SubtleCrypto.decrypt.iv', toHex(iv), alg.name || '');
                }
            } catch(e) {}
            return oDecrypt.apply(this, arguments);
        });
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
                if (src) reportCandidate(src, 'mutation_' + n.tagName.toLowerCase(), 'GET');
                if (n.tagName === 'IFRAME') {
                    tryHookIframe(n);
                    n.addEventListener('load', function() { tryHookIframe(n); });
                }
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
        Element.prototype.attachShadow = N(function(mode) {
            var sr = oAS.call(this, mode);
            try {
                var obs = new MutationObserver(function(ms) {
                    ms.forEach(function(m) {
                        m.addedNodes.forEach(function(n) {
                            if (n.nodeType !== 1) return;
                            var src = n.src || n.getAttribute('src');
                            if (src) reportCandidate(src, 'shadow_dom', 'GET');
                        });
                    });
                });
                obs.observe(sr, { childList: true, subtree: true });
                __sb.observers.push(obs);
            } catch(e) {}
            return sr;
        });
        __sb.hooks.push(function() { Element.prototype.attachShadow = oAS; });
    })();

    // ── WebAssembly detection ─────────────────────────────────────────────────
    // Cannot instrument computation *inside* a .wasm module from injected JS — not possible,
    // same limitation a real desktop browser devtools has. This only flags that a page loaded
    // WASM at all, so if key/token logic can't be found anywhere in the JS hooks above, this
    // tells you why, instead of you spending time hunting for something that was never visible.
    (function() {
        try {
            if (window.WebAssembly && !WebAssembly.__sb_hooked) {
                WebAssembly.__sb_hooked = true;
                if (WebAssembly.instantiate) {
                    var oWasmInst = WebAssembly.instantiate;
                    WebAssembly.instantiate = N(function() {
                        try { SBridge.onWasmDetected(location.href); } catch(e) {}
                        return oWasmInst.apply(this, arguments);
                    });
                }
                if (WebAssembly.instantiateStreaming) {
                    var oWasmInstStream = WebAssembly.instantiateStreaming;
                    WebAssembly.instantiateStreaming = N(function(source) {
                        try {
                            var url = (source && source.url) ? source.url : (typeof source === 'string' ? source : location.href);
                            SBridge.onWasmDetected(url);
                        } catch(e) {}
                        return oWasmInstStream.apply(this, arguments);
                    });
                }
            }
        } catch(e) {}
    })();

    // ── Service Worker detection ──────────────────────────────────────────────
    // A registered Service Worker runs fetch/XHR-equivalent logic in its own global scope
    // (`self`, not `window`) — completely separate from and invisible to every hook above.
    // Cannot intercept from page-injected JS (this is a browser/OS-level registration, not
    // something page JS can instrument), but flags that one exists, same reasoning as WASM.
    (function() {
        try {
            if (navigator.serviceWorker && !navigator.serviceWorker.__sb_hooked) {
                navigator.serviceWorker.__sb_hooked = true;
                var oRegister = navigator.serviceWorker.register;
                if (oRegister) {
                    navigator.serviceWorker.register = N(function(scriptUrl) {
                        try { SBridge.onServiceWorkerDetected(typeof scriptUrl === 'string' ? scriptUrl : location.href); } catch(e) {}
                        return oRegister.apply(this, arguments);
                    });
                }
                // Also catch SWs already active before this hook ran (registered on a previous
                // load, still controlling this page via the browser's SW cache)
                if (navigator.serviceWorker.controller) {
                    try { SBridge.onServiceWorkerDetected(navigator.serviceWorker.controller.scriptURL || location.href); } catch(e) {}
                }
            }
        } catch(e) {}
    })();

    // ── Same-origin iframe hooking ──────────────────────────────────────────────
    // Injection only ever ran in the main frame: onPageStarted/onPageFinished (native side)
    // don't fire for subframe navigations, so embed-iframe players (common on VN streaming
    // sites) were invisible to the XHR/fetch hooks even though their raw resource requests
    // still showed up via native shouldInterceptRequest. Cross-origin iframes throw on
    // .document access (same-origin policy) — that case is expected and silently skipped;
    // there is no way around that from injected JS, same as in a normal desktop browser.
    function hookXhrFetchOn(win, tag) {
        try {
            if (!win || win.__sb_frame_hooked) return;
            win.__sb_frame_hooked = true;
            if (win.XMLHttpRequest) {
                var FXHR = win.XMLHttpRequest;
                var fOpen = FXHR.prototype.open, fSend = FXHR.prototype.send, fSetHdr = FXHR.prototype.setRequestHeader;
                FXHR.prototype.open = N(function(m, url) { this.__sb_url = url; this.__sb_method = m; return fOpen.apply(this, arguments); });
                FXHR.prototype.setRequestHeader = N(function(n, v) {
                    if (!this.__sb_headers) this.__sb_headers = {};
                    this.__sb_headers[n.toLowerCase()] = v;
                    return fSetHdr.apply(this, arguments);
                });
                FXHR.prototype.send = N(function(body) {
                    var xhr = this;
                    report(xhr.__sb_url, tag + '_xhr', xhr.__sb_method || 'GET');
                    try {
                        if (body || (xhr.__sb_headers && Object.keys(xhr.__sb_headers).length)) {
                            var bodyStr = typeof body === 'string' ? body.substring(0, 4000) : (body ? '[non-text body]' : '');
                            SBridge.onRequestPayload(xhr.__sb_url || '', JSON.stringify(xhr.__sb_headers || {}), bodyStr);
                        }
                    } catch(e) {}
                    var origCb = xhr.onreadystatechange;
                    xhr.onreadystatechange = function() {
                        if (xhr.readyState === 4) {
                            try {
                                var ct = xhr.getResponseHeader('Content-Type') || '';
                                var resp = xhr.responseText;
                                if (resp && resp.length < 300000) {
                                    var trimmed = resp.trim();
                                    var isJsonLike = ct.match(/json/i) || trimmed.charAt(0) === '{' || trimmed.charAt(0) === '[';
                                    if (isJsonLike) { try { SBridge.onResponseBody(xhr.__sb_url || '', xhr.status, ct, trimmed.substring(0, 50000)); } catch(e) {} }
                                    if (ct.match(/javascript|json|mpegURL|dash\+xml/i)) extractUrls(resp, tag + '_xhr_resp');
                                }
                            } catch(e) {}
                        }
                        if (origCb) return origCb.apply(xhr, arguments);
                    };
                    return fSend.apply(this, arguments);
                });
            }
            if (win.fetch) {
                var fFetch = win.fetch;
                win.fetch = N(function(input, init) {
                    var url = typeof input === 'string' ? input : (input && input.url);
                    var method = (init && init.method) || (input && input.method) || 'GET';
                    if (url) report(url, tag + '_fetch', method);
                    try {
                        if (url && init && (init.body || init.headers)) {
                            var bodyStr = typeof init.body === 'string' ? init.body.substring(0, 4000) : (init.body ? '[non-text body]' : '');
                            var hdrs = {};
                            if (init.headers) { try { if (init.headers.forEach) init.headers.forEach(function(v, k) { hdrs[k] = v; }); else hdrs = init.headers; } catch(e) {} }
                            SBridge.onRequestPayload(url, JSON.stringify(hdrs), bodyStr);
                        }
                    } catch(e) {}
                    var prom = fFetch.apply(this, arguments);
                    prom.then(function(r) {
                        try {
                            var ct = r.headers.get('Content-Type') || '';
                            if (ct.match(/mpegurl|dash\+xml|javascript|json/i)) {
                                r.clone().text().then(function(t) {
                                    if (t && t.length < 300000) {
                                        extractUrls(t, tag + '_fetch_resp');
                                        var trimmed = t.trim();
                                        if (ct.match(/json/i) || trimmed.charAt(0) === '{' || trimmed.charAt(0) === '[') {
                                            try { SBridge.onResponseBody(url || '', r.status, ct, trimmed.substring(0, 50000)); } catch(e) {}
                                        }
                                    }
                                }).catch(function(){});
                            }
                        } catch(e) {}
                    }).catch(function(){});
                    return prom;
                });
            }
        } catch(e) { /* cross-origin or otherwise inaccessible — expected, skip silently */ }
    }
    function tryHookIframe(iframe) {
        try {
            if (__sb.state.injectedFrames.has(iframe)) return;
            var win = iframe.contentWindow;
            if (!win || !win.document) return;   // throws here for cross-origin — caught below
            __sb.state.injectedFrames.add(iframe);
            hookXhrFetchOn(win, 'iframe');
        } catch(e) { /* cross-origin — cannot hook, same restriction a real browser has */ }
    }
    function scanIframesForHooks() {
        try { document.querySelectorAll('iframe').forEach(function(f) { tryHookIframe(f); }); } catch(e) {}
    }

    // ── Scheduled deep scans ──────────────────────────────────────────────────
    function deepScan() {
        if (__sb.state.scanCount > 30) return;
        __sb.state.scanCount++;
        document.querySelectorAll('script:not([src])').forEach(function(s) {
            if (!s.__sb_s) { s.__sb_s = true; extractUrls(s.textContent, 'deep_script'); }
        });
        document.querySelectorAll('video,audio').forEach(function(v) {
            var src = v.src || v.getAttribute('src') || v.currentSrc;
            if (src && !src.startsWith('blob:')) reportCandidate(src, 'deep_media', 'GET');
        });
        // Scan common state stores
        try { if (window.__NEXT_DATA__) extractUrls(JSON.stringify(window.__NEXT_DATA__), 'next_data'); } catch(e) {}
        try { if (window.__INITIAL_STATE__) extractUrls(JSON.stringify(window.__INITIAL_STATE__), 'initial_state'); } catch(e) {}
        scanIframesForHooks();
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
            if (src) reportCandidate(src, 'init_media', 'GET');
        });
        document.querySelectorAll('[data-video],[data-src],[data-hls],[data-m3u8]').forEach(function(el) {
            var src = el.getAttribute('data-video') || el.getAttribute('data-src') || el.getAttribute('data-hls') || el.getAttribute('data-m3u8');
            if (src) reportCandidate(src, 'init_data_attr', 'GET');
        });
        scanIframesForHooks();
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


    // ── WebSocket Hook (B4) ───────────────────────────────────────────────────
    // Bắt WS connections + messages — một số sites lấy stream token qua WS
    (function() {
        if (__sb.state.hookedAPIs.has('ws')) return;
        __sb.state.hookedAPIs.add('ws');
        var OrigWS = window.WebSocket;
        if (!OrigWS) return;
        window.WebSocket = N(function(url, protocols) {
            try { SBridge.onWebSocket('open', url, ''); } catch(e) {}
            var ws = protocols ? new OrigWS(url, protocols) : new OrigWS(url);
            var origSend = ws.send.bind(ws);
            ws.send = function(data) {
                try {
                    var str = (typeof data === 'string') ? data.substring(0, 500) : '[binary ' + (data.byteLength || 0) + 'b]';
                    SBridge.onWebSocket('send', url, str);
                } catch(e) {}
                return origSend(data);
            };
            ws.addEventListener('message', function(e) {
                try {
                    var str = (typeof e.data === 'string') ? e.data : '[binary]';
                    SBridge.onWebSocket('recv', url, str.substring(0, 800));
                    if (str.length > 10) extractUrls(str, 'ws_recv');
                } catch(e2) {}
            });
            ws.addEventListener('close', function() {
                try { SBridge.onWebSocket('close', url, ''); } catch(e) {}
            });
            return ws;
        });
        try {
            window.WebSocket.prototype = OrigWS.prototype;
            window.WebSocket.CONNECTING = OrigWS.CONNECTING;
            window.WebSocket.OPEN       = OrigWS.OPEN;
            window.WebSocket.CLOSING    = OrigWS.CLOSING;
            window.WebSocket.CLOSED     = OrigWS.CLOSED;
        } catch(e) {}
        __sb.hooks.push(function() { window.WebSocket = OrigWS; });
    })();

    // ── SPA Navigation Hook (E1+E2) ───────────────────────────────────────────
    // Bắt history.pushState/replaceState và popstate — trigger re-inject cho SPAs
    (function() {
        if (__sb.state.hookedAPIs.has('history')) return;
        __sb.state.hookedAPIs.add('history');
        function wrap(method) {
            var orig = history[method];
            history[method] = function(state, title, url) {
                var result = orig.apply(this, arguments);
                try { if (url) SBridge.onSpaNavigation(String(url)); } catch(e) {}
                return result;
            };
            return orig;
        }
        var origPush    = wrap('pushState');
        var origReplace = wrap('replaceState');
        window.addEventListener('popstate', function() {
            try { SBridge.onSpaNavigation(location.href); } catch(e) {}
        });
        __sb.hooks.push(function() {
            history.pushState    = origPush;
            history.replaceState = origReplace;
        });
    })();

    // ── E5: Anti-fingerprint (toString leak prevention) ────────────────────────
    // Implementation moved near the top of this script (must run before any hook below,
    // since every hook now needs N() to exist at the moment it installs itself).

    return 'ok';
})();
