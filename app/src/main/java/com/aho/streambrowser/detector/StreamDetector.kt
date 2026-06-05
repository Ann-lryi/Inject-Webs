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
    if (window.__sb_injected) return;
    window.__sb_injected = true;
    var __sb_config = {
        scanIntervals: [500, 1500, 3000, 6000, 10000, 20000],
        maxUrls: 1000,
        debounceMs: 100,
        verbose: false
    };
    var __sb_urls = new Set();
    var __sb_lastReport = 0;
    var __sb_debounceTimer = null;
    var __sb_mutationObserver = null;
    var __sb_intersectionObserver = null;

    // ── Utility Functions ─────────────────────────────────────────────────────
    function normalizeUrl(url) {
        if (!url || typeof url !== 'string') return null;
        url = url.trim();
        if (!url) return null;
        if (url.startsWith('//')) url = 'https:' + url;
        if (!url.startsWith('http')) return null;
        return url;
    }

    function debounceReport(url, src, method) {
        var now = Date.now();
        if (now - __sb_lastReport < __sb_config.debounceMs) {
            clearTimeout(__sb_debounceTimer);
            __sb_debounceTimer = setTimeout(function() {
                __sb_lastReport = Date.now();
                __report(url, src, method);
            }, __sb_config.debounceMs);
        } else {
            __sb_lastReport = now;
            __report(url, src, method);
        }
    }

    function __report(url, src, method) {
        try {
            url = normalizeUrl(url);
            if (!url) return;
            if (__sb_urls.has(url)) return;
            if (__sb_urls.size >= __sb_config.maxUrls) {
                var first = __sb_urls.values().next().value;
                __sb_urls.delete(first);
            }
            __sb_urls.add(url);
            if (__sb_config.verbose) console.log('[SB] ' + src + ': ' + url);
            SBridge.onRequest(url, src || 'js', method || 'GET');
        } catch(e) { if (__sb_config.verbose) console.error('[SB] Report error:', e); }
    }

    function extractFromText(text, pattern, src) {
        if (!text || typeof text !== 'string') return;
        try {
            var regex = new RegExp(pattern, 'gi');
            var match;
            while ((match = regex.exec(text)) !== null) {
                if (match[1]) debounceReport(match[1], src, 'GET');
            }
        } catch(e) {}
    }

    function scanElement(el, src) {
        if (!el) return;
        var candidates = [];
        // Direct src
        if (el.src) candidates.push(el.src);
        // data-src, data-srcset
        ['data-src', 'data-video', 'data-url', 'data-hls', 'data-m3u8', 'data-mpd', 'data-stream'].forEach(function(attr) {
            var val = el.getAttribute(attr);
            if (val) candidates.push(val);
        });
        // source elements inside
        if (el.tagName === 'VIDEO' || el.tagName === 'AUDIO') {
            el.querySelectorAll('source').forEach(function(src) {
                if (src.src) candidates.push(src.src);
                ['data-src', 'data-video'].forEach(function(attr) {
                    var val = src.getAttribute(attr);
                    if (val) candidates.push(val);
                });
            });
        }
        // textContent for inline configs
        if (el.tagName === 'SCRIPT' || el.tagName === 'DIV' || el.tagName === 'VIDEO') {
            candidates.push(el.textContent || '');
        }
        candidates.forEach(function(c) {
            if (typeof c === 'string' && c.startsWith('http')) {
                debounceReport(c, src, 'GET');
            } else if (typeof c === 'string' && c.length > 10) {
                extractFromText(c, '["\']([^\"\']+\\.m3u8[^\"\']*)["\']', src);
                extractFromText(c, '["\']([^\"\']+\\.mpd[^\"\']*)["\']', src);
                extractFromText(c, '["\']([^\"\']+\\.mp4[^\"\']*)["\']', src);
                extractFromText(c, '["\']([^\"\']+\\.webm[^\"\']*)["\']', src);
                extractFromText(c, '["\']([^\"\']+\\.flv[^\"\']*)["\']', src);
            }
        });
    }

    function deepScan(src) {
        try {
            // Scan video/audio elements
            document.querySelectorAll('video, audio').forEach(function(el) { scanElement(el, src); });
            // Scan scripts for stream configs
            var patterns = [
                ['["\'](https?://[^"\']+\\.m3u8[^"\']*)["\']', 'script_m3u8'],
                ['["\'](https?://[^"\']+\\.mpd[^"\']*)["\']', 'script_mpd'],
                ['["\'](https?://[^"\']+\\.mp4[^"\']*)["\']', 'script_mp4'],
                ['["\'](https?://[^"\']+\\.webm[^"\']*)["\']', 'script_webm'],
                ['["\'](https?://[^"\']+\\.flv[^"\']*)["\']', 'script_flv'],
                ['(?:file|source|hls|dash|src|playlist|stream)["\']\\s*:\\s*["\']([^"\']+)["\']', 'script_config']
            ];
            document.querySelectorAll('script:not([src])').forEach(function(s) {
                var txt = s.textContent || '';
                patterns.forEach(function(p) { extractFromText(txt, p[0], p[1]); });
            });
            // Scan data attributes on any element
            document.querySelectorAll('[data-video], [data-src], [data-hls], [data-m3u8], [data-mpd], [data-stream]').forEach(function(el) {
                ['data-video', 'data-src', 'data-hls', 'data-m3u8', 'data-mpd', 'data-stream'].forEach(function(attr) {
                    var val = el.getAttribute(attr);
                    if (val && typeof val === 'string' && val.startsWith('http')) debounceReport(val, 'data_attr', 'GET');
                });
            });
        } catch(e) { if (__sb_config.verbose) console.error('[SB] Scan error:', e); }
    }

    // ── 1. UA Spoofing & Anti-Detection ─────────────────────────────────────
    (function() {
        try {
            // Remove automation flags
            Object.defineProperty(navigator, 'webdriver', { get: function() { return false; }, configurable: true, set: function() {} });
            // Enhance languages
            Object.defineProperty(navigator, 'languages', { get: function() { return ['vi-VN', 'vi', 'en-US', 'en', 'fr-FR', 'fr', 'de-DE', 'de']; }, configurable: true });
            // Remove automation traces
            ['callPhantom', '_phantom', '__nightmare', 'Buffer', 'domAutomation', 'domAutomationController', '__webdriver_script_fn', '__webdriver_script_func', '__webdriver_script_fn'].forEach(function(k) {
                try { delete window[k]; } catch(e) {}
            });
            // Fake chrome object
            if (!window.chrome) window.chrome = {};
            if (!window.chrome.runtime) window.chrome.runtime = { id: undefined };
            if (!window.chrome.csi) window.chrome.csi = function() {};
            if (!window.chrome.loadTimes) window.chrome.loadTimes = function() {};
            // Remove plugins that indicate automation
            if (navigator.plugins) {
                var phantom = navigator.plugins.namedItem('phantomjs');
                if (phantom) Object.defineProperty(navigator, 'plugins', { get: function() { return []; }, configurable: true });
            }
            // Remove languages mismatch
            var langs = navigator.languages;
            if (langs && langs[0] === 'und') {
                Object.defineProperty(navigator, 'language', { get: function() { return 'vi-VN'; }, configurable: true });
            }
            // Canvas anti-detection (spoof random seed)
            var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
            HTMLCanvasElement.prototype.toDataURL = function() {
                try {
                    var ctx = this.getContext('2d');
                    if (ctx) {
                        var imgData = ctx.getImageData(0, 0, this.width, this.height);
                        for (var i = 0; i < imgData.data.length; i += 100) {
                            imgData.data[i] = (imgData.data[i] + Math.random()) % 256;
                        }
                        ctx.putImageData(imgData, 0, 0);
                    }
                } catch(e) {}
                return origToDataURL.apply(this, arguments);
            };
        } catch(e) {}
    })();

    // ── 2. XHR Interception ─────────────────────────────────────────────────
    (function() {
        var origXHROpen = XMLHttpRequest.prototype.open;
        var origXHRSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(method, url) {
            this.__sb_url = url;
            this.__sb_method = method;
            return origXHROpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function(body) {
            var xhr = this;
            debounceReport(xhr.__sb_url, 'xhr', xhr.__sb_method || 'GET');
            // Track response
            var origOnReadyStateChange = xhr.onreadystatechange;
            xhr.onreadystatechange = function() {
                if (xhr.readyState === 4 && xhr.status === 200) {
                    try {
                        var ct = xhr.getResponseHeader('Content-Type') || '';
                        if (ct.includes('application/x-mpegURL') || ct.includes('application/dash+xml') || 
                            ct.includes('video/') || ct.includes('audio/')) {
                            var resp = xhr.responseText;
                            if (resp && resp.length < 100000) {
                                extractFromText(resp, '["\'](https?://[^"\']+\\.m3u8[^"\']*)["\']', 'xhr_response');
                                extractFromText(resp, '["\'](https?://[^"\']+\\.mpd[^"\']*)["\']', 'xhr_response');
                            }
                        }
                    } catch(e) {}
                }
                if (origOnReadyStateChange) return origOnReadyStateChange.apply(xhr, arguments);
            };
            return origXHRSend.apply(this, arguments);
        };
        // Intercept setRequestHeader
        var origSetReqHeader = XMLHttpRequest.prototype.setRequestHeader;
        XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
            if (!this.__sb_headers) this.__sb_headers = {};
            this.__sb_headers[name.toLowerCase()] = value;
            return origSetReqHeader.apply(this, arguments);
        };
    })();

    // ── 3. Fetch Interception ────────────────────────────────────────────────
    (function() {
        var origFetch = window.fetch;
        window.fetch = function(input, init) {
            var url = typeof input === 'string' ? input : (input && input.url);
            var method = (init && init.method) || (input && input.method) || 'GET';
            if (url) debounceReport(url, 'fetch', method);
            var promise = origFetch.apply(this, arguments);
            // Track response for fetch
            promise.then(function(response) {
                try {
                    var ct = response.headers.get('Content-Type') || '';
                    if (ct.includes('application/x-mpegURL') || ct.includes('application/dash+xml')) {
                        response.clone().text().then(function(text) {
                            if (text && text.length < 100000) {
                                extractFromText(text, '["\'](https?://[^"\']+\\.m3u8[^"\']*)["\']', 'fetch_response');
                                extractFromText(text, '["\'](https?://[^"\']+\\.mpd[^"\']*)["\']', 'fetch_response');
                            }
                        }).catch(function() {});
                    }
                } catch(e) {}
            }).catch(function() {});
            return promise;
        };
    })();

    // ── 4. Media Element Hooks ──────────────────────────────────────────────
    (function() {
        // Hook src setter
        var origSrcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
        if (origSrcDesc && origSrcDesc.set) {
            Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                get: function() { return origSrcDesc.get.call(this); },
                set: function(v) { 
                    if (v) debounceReport(v, 'media_src', 'GET'); 
                    return origSrcDesc.set.call(this, v); 
                }
            });
        }
        // Hook load() to catch programmatic loads
        var origLoad = HTMLMediaElement.prototype.load;
        HTMLMediaElement.prototype.load = function() {
            if (this.src) debounceReport(this.src, 'media_load', 'GET');
            return origLoad.apply(this, arguments);
        };
        // Hook currentSrc
        var origCurrentSrc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'currentSrc');
        Object.defineProperty(HTMLMediaElement.prototype, 'currentSrc', {
            get: function() {
                var val = origCurrentSrc ? origCurrentSrc.get.call(this) : this.getAttribute('src');
                if (val && val.startsWith('http')) debounceReport(val, 'media_currentsrc', 'GET');
                return val;
            }
        });
        // Hook setAttribute
        var origSetAttr = Element.prototype.setAttribute;
        Element.prototype.setAttribute = function(name, value) {
            if (this.tagName === 'VIDEO' || this.tagName === 'AUDIO' || this.tagName === 'SOURCE') {
                if (name === 'src' || name === 'data-src' || name === 'data-video' || name.indexOf('data-') === 0) {
                    if (value && typeof value === 'string' && value.startsWith('http')) {
                        debounceReport(value, 'dom_attr', 'GET');
                    }
                }
            }
            return origSetAttr.apply(this, arguments);
        };
    })();

    // ── 5. Video.js Hook ────────────────────────────────────────────────────
    (function() {
        var pollCount = 0;
        function checkVideoJS() {
            try {
                // Hook videojs global function
                if (window.videojs) {
                    Object.defineProperty(window, '__sb_videojs', {
                        get: function() { return window.videojs; },
                        configurable: true
                    });
                    // Hook when videojs is called
                    window.videojs.getPlayer = (function(orig) {
                        return function(id) {
                            var player = orig.apply(this, arguments);
                            hookPlayer(player);
                            return player;
                        };
                    })(window.videojs.getPlayer || function(x) { return null; });
                }
                // Scan video.js players
                document.querySelectorAll('.video-js, .vjs-tech, video').forEach(function(el) {
                    var player = el.player || (window.videojs && window.videojs(el));
                    hookPlayer(player);
                });
            } catch(e) {}
            if (pollCount++ < 10) setTimeout(checkVideoJS, 1000);
        }
        function hookPlayer(player) {
            if (!player || !player.src || typeof player.src !== 'function') return;
            // Hook src method
            var origSrc = player.src;
            player.src = (function(orig) {
                return function(src) {
                    if (src && typeof src === 'string' && src.startsWith('http')) debounceReport(src, 'player_src', 'GET');
                    return orig.apply(this, arguments);
                };
            })(origSrc);
            // Hook sources
            var origLoad = player.load;
            player.load = (function(orig) {
                return function() {
                    try {
                        if (player.currentSources) {
                            player.currentSources.forEach(function(s) {
                                if (s.src) debounceReport(s.src, 'player_sources', 'GET');
                            });
                        }
                    } catch(e) {}
                    return orig.apply(this, arguments);
                };
            })(origLoad);
            // Initial scan
            try {
                if (player.currentSources) {
                    player.currentSources.forEach(function(s) {
                        if (s.src) debounceReport(s.src, 'player_init', 'GET');
                    });
                }
            } catch(e) {}
        }
        checkVideoJS();
    })();

    // ── 6. HLS.js Hook ──────────────────────────────────────────────────────
    (function() {
        function hookHlsJs() {
            try {
                if (window.Hls) {
                    var origHls = window.Hls;
                    window.__sb_hls = origHls;
                    Object.defineProperty(window, 'Hls', {
                        get: function() { return window.__sb_hls; },
                        set: function(v) {
                            window.__sb_hls = v;
                            if (v && v.isSupported && v.isSupported()) {
                                var origLoadSource = v.prototype.loadSource;
                                v.prototype.loadSource = function(url) {
                                    debounceReport(url, 'hlsjs_loadsource', 'GET');
                                    return origLoadSource.apply(this, [url]);
                                };
                                var origLoadLevel = v.prototype.loadLevel;
                                v.prototype.loadLevel = function(level) {
                                    if (level !== undefined) {
                                        try {
                                            var levelDetails = this.levels[level];
                                            if (levelDetails && levelDetails.url) {
                                                debounceReport(levelDetails.url, 'hlsjs_level', 'GET');
                                            }
                                        } catch(e) {}
                                    }
                                    return origLoadLevel ? origLoadLevel.apply(this, arguments) : level;
                                };
                            }
                            return v;
                        },
                        configurable: true
                    });
                }
            } catch(e) {}
            setTimeout(hookHlsJs, 500);
        }
        hookHlsJs();
    })();

    // ── 7. Dash.js / Shaka Hook ─────────────────────────────────────────────
    (function() {
        function hookDashJs() {
            try {
                if (window.dashjs) {
                    var player = window.dashjs.dashjs;
                    if (player && player.MediaPlayer) {
                        Object.defineProperty(window, '__sb_dashjs', {
                            get: function() { return window.dashjs; },
                            set: function(v) { return window.dashjs = v; },
                            configurable: true
                        });
                        // Hook initialize
                        var origCreate = player.MediaPlayer.prototype.create;
                        player.MediaPlayer.prototype.create = function() {
                            var mp = origCreate.apply(this, arguments);
                            hookDashPlayer(mp);
                            return mp;
                        };
                    }
                }
            } catch(e) {}
            // Shaka Player
            try {
                if (window.shaka) {
                    Object.defineProperty(window, '__sb_shaka', {
                        get: function() { return window.shaka; },
                        configurable: true
                    });
                    var origInstall = window.shaka.Player.prototype.init;
                    window.shaka.Player.prototype.init = function() {
                        var result = origInstall.apply(this, arguments);
                        hookShakaPlayer(this);
                        return result;
                    };
                }
            } catch(e) {}
            setTimeout(hookDashJs, 500);
        }
        function hookDashPlayer(player) {
            if (!player) return;
            var origAttach = player.attachView || player.setVolume;
            player.attachSource = (function(orig) {
                return function(url) {
                    if (url) debounceReport(url, 'dashjs_source', 'GET');
                    return orig ? orig.apply(this, arguments) : url;
                };
            })(origAttach);
        }
        function hookShakaPlayer(player) {
            if (!player || !player.load) return;
            var origLoad = player.load;
            player.load = (function(orig) {
                return function(uri, startTime, manifestType) {
                    if (uri) debounceReport(uri, 'shaka_load', 'GET');
                    return orig.apply(this, arguments);
                };
            })(origLoad);
        }
        hookDashJs();
    })();

    // ── 8. Clappr Hook ──────────────────────────────────────────────────────
    (function() {
        function hookClappr() {
            try {
                if (window.Clappr) {
                    Object.defineProperty(window, '__sb_clappr', {
                        get: function() { return window.Clappr; },
                        configurable: true
                    });
                    var origPlayer = window.Clappr.Player;
                    window.Clappr.Player = (function(orig) {
                        return function(options) {
                            var player = new orig(options);
                            if (options && options.source) {
                                debounceReport(options.source, 'clappr_source', 'GET');
                            }
                            return player;
                        };
                    })(origPlayer);
                }
                // Scan clappr containers
                document.querySelectorAll('[data-player], .clappr-container, .player-container').forEach(function(el) {
                    var src = el.getAttribute('data-source') || el.getAttribute('data-video');
                    if (src && src.startsWith('http')) debounceReport(src, 'clappr_dom', 'GET');
                });
            } catch(e) {}
            setTimeout(hookClappr, 1000);
        }
        hookClappr();
    })();

    // ── 9. Plyr Hook ───────────────────────────────────────────────────────
    (function() {
        function hookPlyr() {
            try {
                if (window.Plyr) {
                    Object.defineProperty(window, '__sb_plyr', {
                        get: function() { return window.Plyr; },
                        configurable: true
                    });
                }
            } catch(e) {}
            setTimeout(hookPlyr, 1000);
        }
        hookPlyr();
    })();

    // ── 10. Blob URL Tracking ──────────────────────────────────────────────
    (function() {
        var origCreateObjectURL = URL.createObjectURL;
        URL.createObjectURL = function(blob) {
            var url = origCreateObjectURL.apply(this, arguments);
            if (url && url.startsWith('blob:')) {
                // Queue a check for this blob
                setTimeout(function() {
                    try {
                        // Try to find what created this blob
                        var iframes = document.querySelectorAll('iframe');
                        iframes.forEach(function(iframe) {
                            try {
                                var doc = iframe.contentDocument || iframe.contentWindow.document;
                                doc.querySelectorAll('video, audio').forEach(function(media) {
                                    if (media.currentSrc && media.currentSrc === url) {
                                        // Found it, but blob URLs are usually internal
                                    }
                                });
                            } catch(e) {}
                        });
                    } catch(e) {}
                }, 100);
            }
            return url;
        };
        var origRevoke = URL.revokeObjectURL;
        URL.revokeObjectURL = function(url) {
            return origRevoke.apply(this, arguments);
        };
    })();

    // ── 11. MediaSource API Hook ───────────────────────────────────────────
    (function() {
        try {
            var origMediaSource = window.MediaSource;
            if (origMediaSource) {
                window.MediaSource = function() {
                    var ms = new origMediaSource();
                    // Hook addSourceBuffer
                    var origAddSourceBuffer = ms.addSourceBuffer;
                    ms.addSourceBuffer = function(type) {
                        var buffer = origAddSourceBuffer.apply(this, arguments);
                        // Track mime type for stream detection
                        if (type && (type.includes('mpeg') || type.includes('mp4') || type.includes('webm'))) {
                            // MediaSource usage detected
                        }
                        return buffer;
                    };
                    return ms;
                };
                window.MediaSource.prototype = origMediaSource.prototype;
            }
        } catch(e) {}
    })();

    // ── 12. MutationObserver for Dynamic Content ───────────────────────────
    (function() {
        try {
            __sb_mutationObserver = new MutationObserver(function(mutations) {
                mutations.forEach(function(mutation) {
                    mutation.addedNodes.forEach(function(node) {
                        if (node.nodeType === 1) { // Element
                            if (node.tagName === 'VIDEO' || node.tagName === 'AUDIO') {
                                scanElement(node, 'mutation');
                            }
                            // Check for player containers
                            if (node.querySelectorAll) {
                                node.querySelectorAll('video, audio, source, [data-video], [data-src]').forEach(function(el) {
                                    scanElement(el, 'mutation_child');
                                });
                            }
                        }
                    });
                });
            });
            __sb_mutationObserver.observe(document.documentElement, {
                childList: true,
                subtree: true
            });
        } catch(e) {}
    })();

    // ── 13. IntersectionObserver for Lazy Loading ──────────────────────────
    (function() {
        try {
            __sb_intersectionObserver = new IntersectionObserver(function(entries) {
                entries.forEach(function(entry) {
                    if (entry.isIntersecting) {
                        var el = entry.target;
                        if (el.tagName === 'VIDEO' || el.tagName === 'AUDIO') {
                            scanElement(el, 'intersection');
                        }
                    }
                });
            }, { threshold: 0.1 });
            // Observe existing and future media elements
            function observeMedia() {
                document.querySelectorAll('video, audio').forEach(function(el) {
                    try { __sb_intersectionObserver.observe(el); } catch(e) {}
                });
            }
            observeMedia();
            // Keep observing
            setInterval(observeMedia, 5000);
        } catch(e) {}
    })();

    // ── 14. WebSocket Interception (for some streaming protocols) ──────────
    (function() {
        try {
            var OrigWS = window.WebSocket;
            window.WebSocket = function(url, protocols) {
                // Note: WebSocket URLs are often different from stream URLs
                // But some streaming servers use WS/WSS
                if (__sb_config.verbose) console.log('[SB] WebSocket:', url);
                var ws = new OrigWS(url, protocols);
                return ws;
            };
            window.WebSocket.prototype = OrigWS.prototype;
            window.WebSocket.CONNECTING = OrigWS.CONNECTING;
            window.WebSocket.OPEN = OrigWS.OPEN;
            window.WebSocket.CLOSING = OrigWS.CLOSING;
            window.WebSocket.CLOSED = OrigWS.CLOSED;
        } catch(e) {}
    })();

    // ── 15. Scheduled Deep Scans ────────────────────────────────────────────
    __sb_config.scanIntervals.forEach(function(delay) {
        setTimeout(function() { deepScan('scheduled_' + delay); }, delay);
    });

    // ── 16. Periodic Rescan for SPAs ───────────────────────────────────────
    setInterval(function() { deepScan('periodic'); }, 30000);

    // ── 17. Hook onerror for script-level stream errors ────────────────────
    window.addEventListener('error', function(e) {
        try {
            if (e.message && typeof e.message === 'string') {
                // Check if error message contains URL hints
                extractFromText(e.message, '(https?://[^\\s]+\\.(m3u8|mpd|mp4))', 'error_msg');
            }
        } catch(err) {}
    }, true);

    if (__sb_config.verbose) console.log('[SB] Deep injection initialized');
})();
""".trimIndent()

class StreamDetector(private val context: Context? = null) {
    // Use HashSet for O(1) lookup instead of O(n) list iteration
    private val _streamsSet   = mutableSetOf<String>()
    private val _requestsSet  = mutableSetOf<String>()
    private val _streams     = mutableListOf<StreamItem>()
    private val _requests    = mutableListOf<NetworkRequest>()
    
    // Pre-compiled noise patterns
    private val noiseExtensions = setOf(
        ".css", ".woff", ".woff2", ".ttf", ".eot", ".ico", ".svg",
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".bmp",
        ".json", ".xml", ".txt", ".html", ".htm"
    )
    private val noiseDomains = setOf(
        "google-analytics", "googletagmanager", "facebook.com/tr",
        "doubleclick", "beacon", "telemetry", "hotjar", "clarity.ms",
        "segment.io", "mixpanel.com", "amplitude.com", "heap.io"
    )

    val streams:  List<StreamItem>     get() = synchronized(this) { _streams.toList()  }
    val requests: List<NetworkRequest> get() = synchronized(this) { _requests.toList() }

    var onStreamFound:  ((StreamItem)     -> Unit)? = null
    var onRequestAdded: ((NetworkRequest) -> Unit)? = null

    // Persistent state
    val consoleLog     = StringBuilder("// Console\n")
    val deepLog        = StringBuilder("// Deep Inject\n")
    val consoleHistory = mutableListOf<String>()
    
    // Memory limit
    companion object {
        private const val MAX_STREAMS  = 200
        private const val MAX_REQUESTS = 1000
    }

    fun interceptRequest(request: WebResourceRequest, pageUrl: String) {
        val url = request.url?.toString() ?: return
        val method = request.method ?: "GET"
        val hdrs = request.requestHeaders ?: emptyMap()
        
        // Fast noise check
        if (isNoise(url)) return
        
        // O(1) duplicate check
        synchronized(this) {
            if (_requestsSet.contains(url)) return
            _requestsSet.add(url)
        }
        
        val streamType = StreamItem.detectType(url)
        val req = NetworkRequest(
            url = url, 
            method = method, 
            headers = hdrs, 
            pageUrl = pageUrl,
            isStream = streamType != null, 
            streamType = streamType
        )
        addRequest(req)
        
        if (streamType != null) {
            addStream(StreamItem(
                url = url, 
                type = streamType, 
                source = "network", 
                referer = pageUrl
            ))
        }
    }

    fun reportFromJs(url: String, source: String, method: String, referer: String) {
        if (url.isBlank()) return
        
        // Fast noise check
        if (isNoise(url)) return
        
        // O(1) duplicate check
        synchronized(this) {
            if (_requestsSet.contains(url)) return
            _requestsSet.add(url)
        }
        
        val streamType = StreamItem.detectType(url)
        val req = NetworkRequest(
            url = url, 
            method = method, 
            headers = emptyMap(), 
            pageUrl = referer,
            isStream = streamType != null, 
            streamType = streamType
        )
        addRequest(req)
        
        if (streamType != null) {
            addStream(StreamItem(
                url = url, 
                type = streamType, 
                source = source, 
                referer = referer
            ))
        }
    }

    @Synchronized fun updateRequest(url: String, updated: NetworkRequest) {
        val idx = _requests.indexOfFirst { it.url == url }
        if (idx >= 0) {
            _requests[idx] = updated
        }
    }

    @Synchronized private fun addRequest(req: NetworkRequest) {
        _requests.add(0, req)
        
        // Trim if over limit
        if (_requests.size > MAX_REQUESTS) {
            val removed = _requests.removeAt(_requests.lastIndex)
            _requestsSet.remove(removed.url)
        }
        
        onRequestAdded?.invoke(req)
    }

    @Synchronized private fun addStream(item: StreamItem) {
        if (_streamsSet.contains(item.url)) return
        
        _streamsSet.add(item.url)
        _streams.add(0, item)
        
        // Trim if over limit
        if (_streams.size > MAX_STREAMS) {
            val removed = _streams.removeAt(_streams.lastIndex)
            _streamsSet.remove(removed.url)
        }
        
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

    fun clear() = synchronized(this) {
        _streams.clear()
        _requests.clear()
        _streamsSet.clear()
        _requestsSet.clear()
    }
    
    fun streamCount()  = _streams.size
    fun requestCount() = _requests.size

    private fun isNoise(url: String): Boolean {
        val lower = url.lowercase()
        
        // Check extensions first (most common)
        val lastDot = lower.lastIndexOf('.')
        if (lastDot > 0) {
            val ext = lower.substring(lastDot)
            if (ext in noiseExtensions) return true
        }
        
        // Check domains
        return noiseDomains.any { lower.contains(it) }
    }
}
