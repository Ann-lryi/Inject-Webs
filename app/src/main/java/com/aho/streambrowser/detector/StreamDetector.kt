package com.aho.streambrowser.detector

/**
 * Ultra-Deep JavaScript Injection for Stream Detection
 * 
 * This injection hooks into EVERYTHING to find streams:
 * - All network APIs (fetch, XHR, WebSocket)
 * - All video players (built-in and libraries)
 * - DOM mutations and dynamic content
 * - Obfuscated/encoded URLs
 * - iframes and shadow DOMs
 * - Memory and prototype chains
 * - Service workers and cache
 */
val HOOK_JS = """
(function() {
    'use strict';
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    if (window.__sb_injected_v2) return;
    window.__sb_injected_v2 = true;
    
    var __sb = {
        config: {
            maxUrls: 2000,
            debounceMs: 50,
            scanIntervals: [300, 800, 1500, 3000, 6000, 12000, 25000],
            deepScanDelay: 500,
            iframeDepth: 3,
            verbose: false,
            logLevel: 'error' // 'debug', 'info', 'warn', 'error'
        },
        state: {
            urls: new Set(),
            streams: new Map(),
            lastReport: 0,
            injectedFrames: new WeakSet(),
            hookedPlayers: new WeakSet(),
            hookedAPIs: new WeakSet()
        },
        timers: [],
        observers: [],
        hooks: []
    };
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    function log(level, msg, data) {
        var levels = ['debug', 'info', 'warn', 'error'];
        if (levels.indexOf(level) < levels.indexOf(__sb.config.logLevel)) return;
        var prefix = '[SB:' + level.toUpperCase() + ']';
        if (__sb.config.verbose) {
            console[level === 'debug' ? 'log' : level](prefix, msg, data || '');
        }
    }
    
    function normalizeUrl(url) {
        if (!url || typeof url !== 'string') return null;
        url = url.trim();
        if (!url) return null;
        if (url.startsWith('//')) url = location.protocol + url;
        // Handle blob URLs
        if (url.startsWith('blob:')) return url;
        // Only accept http/https
        if (!url.match(/^https?:\/\//i)) return null;
        return url;
    }
    
    function report(url, source, method) {
        method = method || 'GET';
        try {
            url = normalizeUrl(url);
            if (!url) return;
            
            // Deduplicate
            if (__sb.state.urls.has(url)) return;
            
            // Memory management
            if (__sb.state.urls.size >= __sb.config.maxUrls) {
                var first = __sb.state.urls.values().next().value;
                __sb.state.urls.delete(first);
            }
            
            __sb.state.urls.add(url);
            
            // Debounce
            var now = Date.now();
            if (now - __sb.state.lastReport < __sb.config.debounceMs) {
                clearTimeout(__sb._debounceTimer);
                __sb._debounceTimer = setTimeout(function() {
                    __sb.state.lastReport = Date.now();
                    SBridge.onRequest(url, source, method);
                }, __sb.config.debounceMs);
            } else {
                __sb.state.lastReport = now;
                SBridge.onRequest(url, source, method);
            }
            
            log('debug', 'REPORT: ' + source, url);
        } catch(e) {
            log('error', 'Report failed', e);
        }
    }
    
    // ── Deep URL Extraction from text ────────────────────────────────────────
    function extractUrls(text, source) {
        if (!text || typeof text !== 'string' || text.length > 500000) return;
        
        // Patterns for various streaming URLs
        var patterns = [
            // Standard streaming extensions
            [/\b(https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/gi, 'm3u8'],
            [/\b(https?:\/\/[^\s"'<>]+\.mpd[^\s"'<>]*)/gi, 'mpd'],
            [/\b(https?:\/\/[^\s"'<>]+\.mp4[^\s"'<>]*)/gi, 'mp4'],
            [/\b(https?:\/\/[^\s"'<>]+\.webm[^\s"'<>]*)/gi, 'webm'],
            [/\b(https?:\/\/[^\s"'<>]+\.m4s[^\s"'<>]*)/gi, 'm4s'],
            [/\b(https?:\/\/[^\s"'<>]+\.ts[^\s"'<>]*)/gi, 'ts'],
            [/\b(https?:\/\/[^\s"'<>]+\.flv[^\s"'<>]*)/gi, 'flv'],
            [/\b(https?:\/\/[^\s"'<>]+\.mkv[^\s"'<>]*)/gi, 'mkv'],
            
            // Encoded URLs
            [/(?:src|source|file|url|stream|playlist|manifest|video)["']?\\s*[:=]\\s*["']([^"']+)/gi, 'js_prop'],
            [/(?:src|source|file|url|stream|playlist|manifest)["']?\\s*[:=]\\s*decodeURIComponent\\(["']([^"']+)/gi, 'encoded'],
            
            // Base64 encoded URLs
            [/data:[^;]+;base64,[A-Za-z0-9+/=]+/g, 'base64_data'],
            
            // JSON-style URLs
            [/"(https?:\/\/[^"]+\\.(?:m3u8|mpd|mp4|webm|flv|mkv))"/gi, 'json_url'],
            
            // Chunk/Segment patterns (HLS/DASH)
            [/\b(chunklist|segment|media|playlist)[-_](?:url|source|path)?["']?\\s*[:=]\\s*["']([^"']+)/gi, 'chunk'],
            
            // Tokenized URLs
            [/token[=:]["']([^"']+)["']/gi, 'token_url'],
            
            // CDN patterns
            [/(?:cdn|cloudfront|akamai|cloudflare)[.-][a-z0-9.-]+\\.[a-z]{2,}[^\s"'<>]*/gi, 'cdn_url'],
            
            // API response patterns
            [/"(https?:\/\/[^"]*(?:stream|video|media|content|play)[^"]*)"/gi, 'api_url']
        ];
        
        patterns.forEach(function(p) {
            try {
                var regex = new RegExp(p[0].source, p[0].flags);
                var match;
                while ((match = regex.exec(text)) !== null) {
                    if (match[1]) {
                        // Try to decode if needed
                        var url = match[1];
                        try {
                            if (url.includes('decodeURIComponent')) {
                                url = eval(url);
                            }
                        } catch(e) {}
                        
                        // Decode base64 if looks like it
                        if (url.startsWith('data:')) {
                            try {
                                var base64Idx = url.indexOf(',');
                                if (base64Idx > 0) {
                                    var decoded = atob(url.substring(base64Idx + 1));
                                    if (decoded.startsWith('http')) url = decoded;
                                }
                            } catch(e) {}
                        }
                        
                        report(url, 'extract_' + p[1], 'GET');
                    }
                }
            } catch(e) {}
        });
    }
    
    // ── Scan Element Deeply ───────────────────────────────────────────────────
    function deepScanElement(el, source) {
        if (!el) return;
        
        try {
            // Skip if already processed
            if (el.__sb_scanned) return;
            el.__sb_scanned = true;
            
            // Skip tiny elements
            var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : {width: 0, height: 0};
            if (rect.width < 10 && rect.height < 10) return;
            
            var candidates = [];
            
            // 1. Direct src/href
            if (el.src && el.src.startsWith('http')) candidates.push(el.src);
            if (el.href && el.href.startsWith('http')) candidates.push(el.href);
            
            // 2. All data-* attributes
            var attrs = el.attributes;
            for (var i = 0; i < attrs.length; i++) {
                var attr = attrs[i];
                if (attr.name.startsWith('data-')) {
                    var val = attr.value;
                    if (val && typeof val === 'string') {
                        // Check if it's a URL
                        if (val.match(/^https?:\/\//i)) {
                            candidates.push(val);
                        } else if (val.length > 20) {
                            // Might be encoded
                            candidates.push(val);
                        }
                    }
                }
            }
            
            // 3. Inline JSON/config in text
            var textContent = el.textContent || '';
            if (textContent.length > 10 && textContent.length < 100000) {
                candidates.push(textContent);
            }
            
            // 4. InnerHTML for video/source elements
            if (el.tagName === 'VIDEO' || el.tagName === 'SOURCE') {
                candidates.push(el.innerHTML || '');
                
                // 5. querySelectorAll for nested sources
                try {
                    el.querySelectorAll('source, track').forEach(function(src) {
                        if (src.src) candidates.push(src.src);
                        var srcset = src.getAttribute('srcset');
                        if (srcset) candidates.push(srcset);
                    });
                } catch(e) {}
            }
            
            // 6. Computed styles (background, etc)
            try {
                var styles = window.getComputedStyle(el);
                var bg = styles.backgroundImage;
                if (bg && bg.startsWith('url')) {
                    var match = bg.match(/url\\(?["']([^"']+)["']/);
                    if (match && match[1]) candidates.push(match[1]);
                }
            } catch(e) {}
            
            // 7. Check poster, preload attributes
            if (el.poster) candidates.push(el.poster);
            
            // 8. nested iframes
            if (el.tagName === 'IFRAME') {
                candidates.push(el.src || '');
                tryPushIframeContent(el);
            }
            
            // Process all candidates
            candidates.forEach(function(c) {
                if (!c || typeof c !== 'string') return;
                if (c.startsWith('http')) {
                    report(c, 'element_' + source, 'GET');
                } else if (c.length > 10) {
                    // Try to extract URLs from text
                    extractUrls(c, 'element_text_' + source);
                }
            });
            
        } catch(e) {
            log('error', 'deepScanElement failed', e);
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // NETWORK API HOOKS
    // ═══════════════════════════════════════════════════════════════════════════
    
    // ── XMLHttpRequest Hook ─────────────────────────────────────────────────
    (function() {
        if (__sb.state.hookedAPIs.has('xhr')) return;
        __sb.state.hookedAPIs.add('xhr');
        
        var XHR = XMLHttpRequest;
        var origOpen = XHR.prototype.open;
        var origSend = XHR.prototype.send;
        var origSetHeader = XHR.prototype.setRequestHeader;
        
        XHR.prototype.open = function(method, url) {
            this.__sb_url = url;
            this.__sb_method = method;
            this.__sb_headers = {};
            return origOpen.apply(this, arguments);
        };
        
        XHR.prototype.setRequestHeader = function(name, value) {
            if (!this.__sb_headers) this.__sb_headers = {};
            this.__sb_headers[name.toLowerCase()] = value;
            return origSetHeader.apply(this, arguments);
        };
        
        XHR.prototype.send = function(body) {
            var xhr = this;
            
            // Report outgoing request
            report(xhr.__sb_url, 'xhr', xhr.__sb_method || 'GET');
            
            // Hook response
            var origOnReadyStateChange = xhr.onreadystatechange;
            xhr.onreadystatechange = function() {
                if (xhr.readyState === 4) {
                    try {
                        // Check content type
                        var ct = xhr.getResponseHeader('Content-Type') || '';
                        if (ct.match(/javascript|json|application\\/x-mpegURL|dash\\+xml|text\\/html/i)) {
                            var resp = xhr.responseText;
                            if (resp && resp.length < 200000) {
                                extractUrls(resp, 'xhr_response');
                            }
                        }
                        
                        // Check for stream headers
                        if (xhr.__sb_headers) {
                            var range = xhr.__sb_headers['range'] || xhr.__sb_headers['accept'];
                            if (range && range.includes('bytes')) {
                                report(xhr.__sb_url, 'xhr_stream', 'GET');
                            }
                        }
                    } catch(e) {}
                }
                if (origOnReadyStateChange) return origOnReadyStateChange.apply(xhr, arguments);
            };
            
            return origSend.apply(this, arguments);
        };
        
        __sb.hooks.push(function() {
            XHR.prototype.open = origOpen;
            XHR.prototype.send = origSend;
            XHR.prototype.setRequestHeader = origSetHeader;
        });
    })();
    
    // ── Fetch Hook ───────────────────────────────────────────────────────────
    (function() {
        if (__sb.state.hookedAPIs.has('fetch')) return;
        __sb.state.hookedAPIs.add('fetch');
        
        var origFetch = window.fetch;
        window.fetch = function(input, init) {
            var url = typeof input === 'string' ? input : (input && input.url);
            var method = (init && init.method) || (input && input.method) || 'GET';
            
            if (url) report(url, 'fetch', method);
            
            var promise = origFetch.apply(this, arguments);
            
            // Track response
            promise.then(function(response) {
                try {
                    var ct = response.headers.get('Content-Type') || '';
                    if (ct.match(/mpegurl|dash\\+xml|javascript|json|text\\/html/i)) {
                        response.clone().text().then(function(text) {
                            if (text && text.length < 200000) {
                                extractUrls(text, 'fetch_response');
                            }
                        }).catch(function() {});
                    }
                } catch(e) {}
            }).catch(function() {});
            
            return promise;
        };
        
        __sb.hooks.push(function() {
            window.fetch = origFetch;
        });
    })();
    
    // ── WebSocket Hook ────────────────────────────────────────────────────────
    (function() {
        if (__sb.state.hookedAPIs.has('ws')) return;
        __sb.state.hookedAPIs.add('ws');
        
        var OrigWS = window.WebSocket;
        window.WebSocket = function(url, protocols) {
            log('debug', 'WebSocket opened', url);
            var ws = new OrigWS(url, protocols);
            
            // Hook send
            var origSend = ws.send.bind(ws);
            ws.send = function(data) {
                log('debug', 'WebSocket send', {url: url, data: data});
                // Look for URLs in sent data
                if (typeof data === 'string') {
                    extractUrls(data, 'ws_send');
                }
                return origSend(data);
            };
            
            // Hook message
            ws.addEventListener('message', function(e) {
                log('debug', 'WebSocket message', {url: url, data: e.data});
                if (typeof e.data === 'string') {
                    extractUrls(e.data, 'ws_message');
                }
            });
            
            return ws;
        };
        
        ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function(c) {
            window.WebSocket[c] = OrigWS[c];
        });
        window.WebSocket.prototype = OrigWS.prototype;
        
        __sb.hooks.push(function() {
            window.WebSocket = OrigWS;
        });
    })();
    
    // ═══════════════════════════════════════════════════════════════════════════
    // VIDEO/AUDIO PLAYER HOOKS
    // ═══════════════════════════════════════════════════════════════════════════
    
    // ── HTMLMediaElement Hook (Video/Audio) ─────────────────────────────────
    (function() {
        if (__sb.state.hookedAPIs.has('media')) return;
        __sb.state.hookedAPIs.add('media');
        
        var proto = HTMLMediaElement.prototype;
        
        // Hook src setter
        var origSrcDesc = Object.getOwnPropertyDescriptor(proto, 'src');
        if (origSrcDesc && origSrcDesc.set) {
            Object.defineProperty(proto, 'src', {
                get: function() { return origSrcDesc.get.call(this); },
                set: function(v) {
                    if (v) report(v, 'media_src', 'GET');
                    return origSrcDesc.set.call(this, v);
                },
                configurable: true
            });
        }
        
        // Hook load
        var origLoad = proto.load;
        proto.load = function() {
            if (this.src) report(this.src, 'media_load', 'GET');
            return origLoad.apply(this, arguments);
        };
        
        // Hook play/pause for stream detection
        proto.play = (function(orig) {
            return function() {
                if (this.src) report(this.src, 'media_play', 'GET');
                return orig.apply(this, arguments);
            };
        })(proto.play);
        
        // Hook addTextTrack (sometimes used for manifests)
        var origAddTextTrack = proto.addTextTrack;
        proto.addTextTrack = function() {
            log('debug', 'addTextTrack called');
            return origAddTextTrack.apply(this, arguments);
        };
        
        // Monitor src changes via attribute
        var origSetAttr = Element.prototype.setAttribute;
        Element.prototype.setAttribute = function(name, value) {
            var result = origSetAttr.apply(this, arguments);
            if (this.tagName === 'VIDEO' || this.tagName === 'AUDIO' || this.tagName === 'SOURCE') {
                if ((name === 'src' || name.startsWith('data-')) && value && value.startsWith('http')) {
                    report(value, 'attr_' + name, 'GET');
                }
            }
            return result;
        };
        
        __sb.hooks.push(function() {
            if (origSrcDesc) Object.defineProperty(proto, 'src', origSrcDesc);
            proto.load = origLoad;
            Element.prototype.setAttribute = origSetAttr;
        });
    })();
    
    // ── HLS.js Hook ─────────────────────────────────────────────────────────
    (function() {
        var pollInterval = setInterval(function() {
            if (window.Hls && !__sb.state.hookedPlayers.has('hls')) {
                __sb.state.hookedPlayers.add('hls');
                
                var HlsClass = window.Hls;
                var origHls = window.Hls;
                
                // Hook Hls constructor/setter
                Object.defineProperty(window, 'Hls', {
                    get: function() { return window.__sb_hls; },
                    set: function(Hls) {
                        window.__sb_hls = Hls;
                        
                        if (Hls.isSupported && Hls.isSupported()) {
                            // Hook loadSource
                            var origLoadSource = Hls.prototype.loadSource;
                            Hls.prototype.loadSource = function(url) {
                                report(url, 'hls_loadsource', 'GET');
                                return origLoadSource.apply(this, [url]);
                            };
                            
                            // Hook attachMedia
                            var origAttachMedia = Hls.prototype.attachMedia;
                            Hls.prototype.attachMedia = function(media) {
                                var result = origAttachMedia ? origAttachMedia.apply(this, arguments) : undefined;
                                // Start observing media element
                                if (media) observeMediaElement(media);
                                return result;
                            };
                            
                            // Hook destroy
                            var origDestroy = Hls.prototype.destroy;
                            Hls.prototype.destroy = function() {
                                log('debug', 'Hls.destroy called');
                                return origDestroy ? origDestroy.apply(this, arguments) : undefined;
                            };
                        }
                        
                        return Hls;
                    },
                    configurable: true
                });
                
                clearInterval(pollInterval);
            }
        }, 200);
        
        __sb.timers.push(pollInterval);
    })();
    
    // ── Dash.js Hook ────────────────────────────────────────────────────────
    (function() {
        var pollInterval = setInterval(function() {
            if (window.dashjs && !__sb.state.hookedPlayers.has('dash')) {
                __sb.state.hookedPlayers.add('dash');
                
                var dashjs = window.dashjs;
                
                // Hook MediaPlayer factory
                if (dashjs.MediaPlayer) {
                    var origCreate = dashjs.MediaPlayer.prototype.create;
                    dashjs.MediaPlayer.prototype.create = function() {
                        var player = origCreate.apply(this, arguments);
                        
                        // Hook load
                        if (player.load) {
                            var origLoad = player.load.bind(player);
                            player.load = function(url) {
                                if (url) report(url, 'dash_load', 'GET');
                                return origLoad(url);
                            };
                        }
                        
                        // Hook attachSource
                        if (player.attachSource) {
                            var origAttachSource = player.attachSource.bind(player);
                            player.attachSource = function(url) {
                                if (url) report(url, 'dash_attach', 'GET');
                                return origAttachSource(url);
                            };
                        }
                        
                        return player;
                    };
                }
                
                clearInterval(pollInterval);
            }
        }, 200);
        
        __sb.timers.push(pollInterval);
    })();
    
    // ── Shaka Player Hook ───────────────────────────────────────────────────
    (function() {
        var pollInterval = setInterval(function() {
            if (window.shaka && !__sb.state.hookedPlayers.has('shaka')) {
                __sb.state.hookedPlayers.add('shaka');
                
                var ShakaPlayer = window.shaka.Player;
                
                // Hook shaka.Player constructor
                Object.defineProperty(window.shaka, 'Player', {
                    get: function() { return window.__sb_shaka_player; },
                    set: function(Player) {
                        window.__sb_shaka_player = Player;
                        
                        // Hook load method
                        var origLoad = Player.prototype.load;
                        Player.prototype.load = function(uri, startTime, manifestType) {
                            if (uri) report(uri, 'shaka_load', 'GET');
                            return origLoad ? origLoad.apply(this, arguments) : Promise.resolve();
                        };
                        
                        // Hook attach
                        var origAttach = Player.prototype.attach;
                        Player.prototype.attach = function(media) {
                            if (media) observeMediaElement(media);
                            return origAttach ? origAttach.apply(this, arguments) : Promise.resolve();
                        };
                        
                        return Player;
                    },
                    configurable: true
                });
                
                clearInterval(pollInterval);
            }
        }, 200);
        
        __sb.timers.push(pollInterval);
    })();
    
    // ── Video.js Hook ────────────────────────────────────────────────────────
    (function() {
        var pollInterval = setInterval(function() {
            if (window.videojs && !__sb.state.hookedPlayers.has('videojs')) {
                __sb.state.hookedPlayers.add('videojs');
                
                var origVideojs = window.videojs;
                window.videojs = function(id, options, ready) {
                    var player = origVideojs.apply(this, arguments);
                    
                    if (options && options.sources) {
                        options.sources.forEach(function(s) {
                            if (s.src) report(s.src, 'videojs_sources', 'GET');
                        });
                    }
                    
                    // Hook src method
                    if (player.src) {
                        var origSrc = player.src.bind(player);
                        player.src = function(src) {
                            if (src) {
                                if (typeof src === 'string') {
                                    report(src, 'videojs_src', 'GET');
                                } else if (src.src) {
                                    report(src.src, 'videojs_src', 'GET');
                                } else if (Array.isArray(src)) {
                                    src.forEach(function(s) {
                                        if (s.src) report(s.src, 'videojs_src', 'GET');
                                    });
                                }
                            }
                            return origSrc(src);
                        };
                    }
                    
                    return player;
                };
                
                // Copy prototype and static methods
                window.videojs.prototype = origVideojs.prototype;
                Object.keys(origVideojs).forEach(function(k) {
                    window.videojs[k] = origVideojs[k];
                });
                
                clearInterval(pollInterval);
            }
        }, 200);
        
        __sb.timers.push(pollInterval);
    })();
    
    // ── Clappr Hook ─────────────────────────────────────────────────────────
    (function() {
        var pollInterval = setInterval(function() {
            if (window.Clappr && !__sb.state.hookedPlayers.has('clappr')) {
                __sb.state.hookedPlayers.add('clappr');
                
                var origPlayer = window.Clappr.Player;
                window.Clappr.Player = function(options) {
                    var player = new origPlayer(options);
                    
                    if (options && options.source) {
                        report(options.source, 'clappr_source', 'GET');
                    }
                    
                    // Hook play method
                    if (player.play) {
                        var origPlay = player.play.bind(player);
                        player.play = function(src) {
                            if (src) report(src, 'clappr_play', 'GET');
                            return origPlay(src);
                        };
                    }
                    
                    return player;
                };
                
                window.Clappr.Player.prototype = origPlayer.prototype;
                Object.keys(origPlayer).forEach(function(k) {
                    window.Clappr.Player[k] = origPlayer[k];
                });
                
                clearInterval(pollInterval);
            }
        }, 200);
        
        __sb.timers.push(pollInterval);
    })();
    
    // ── JWPlayer Hook ────────────────────────────────────────────────────────
    (function() {
        var pollInterval = setInterval(function() {
            if (window.jwplayer && !__sb.state.hookedPlayers.has('jwplayer')) {
                __sb.state.hookedPlayers.add('jwplayer');
                
                var origJwplayer = window.jwplayer;
                window.jwplayer = function(id) {
                    var player = origJwplayer(id);
                    
                    // Hook setup
                    var origSetup = player.setup ? player.setup.bind(player) : null;
                    if (origSetup) {
                        player.setup = function(config) {
                            if (config) {
                                if (config.file) report(config.file, 'jwplayer_file', 'GET');
                                if (config.sources) {
                                    config.sources.forEach(function(s) {
                                        if (s.file) report(s.file, 'jwplayer_sources', 'GET');
                                    });
                                }
                                if (config.playlist) {
                                    config.playlist.forEach(function(item) {
                                        if (item.file) report(item.file, 'jwplayer_playlist', 'GET');
                                        if (item.sources) {
                                            item.sources.forEach(function(s) {
                                                if (s.file) report(s.file, 'jwplayer_sources', 'GET');
                                            });
                                        }
                                    });
                                }
                            }
                            var result = origSetup(config);
                            // Hook the returned player
                            hookJwplayerInstance(result);
                            return result;
                        };
                    }
                    
                    hookJwplayerInstance(player);
                    return player;
                };
                
                // Copy static methods
                window.jwplayer.version = origJwplayer.version;
                window.jwplayer.defaults = origJwplayer.defaults;
                
                clearInterval(pollInterval);
            }
        }, 200);
        
        function hookJwplayerInstance(player) {
            if (!player || __sb.state.hookedPlayers.has(player)) return;
            __sb.state.hookedPlayers.add(player);
            
            // Hook load/ad
            var origLoad = player.load ? player.load.bind(player) : null;
            if (origLoad) {
                player.load = function(playlist) {
                    if (playlist) {
                        if (typeof playlist === 'string') {
                            report(playlist, 'jwplayer_load', 'GET');
                        } else if (playlist.file) {
                            report(playlist.file, 'jwplayer_load', 'GET');
                        } else if (playlist.sources) {
                            playlist.sources.forEach(function(s) {
                                if (s.file) report(s.file, 'jwplayer_load', 'GET');
                            });
                        }
                    }
                    return origLoad(playlist);
                };
            }
        }
        
        __sb.timers.push(pollInterval);
    })();
    
    // ── Bitmovin Player Hook ────────────────────────────────────────────────
    (function() {
        var pollInterval = setInterval(function() {
            if (window.bitmovin && !__sb.state.hookedPlayers.has('bitmovin')) {
                __sb.state.hookedPlayers.add('bitmovin');
                log('debug', 'Bitmovin player detected');
                clearInterval(pollInterval);
            }
        }, 500);
        
        __sb.timers.push(pollInterval);
    })();
    
    // ── THEOplayer Hook ─────────────────────────────────────────────────────
    (function() {
        var pollInterval = setInterval(function() {
            if (window.THEOplayer && !__sb.state.hookedPlayers.has('theoplayer')) {
                __sb.state.hookedPlayers.add('theoplayer');
                log('debug', 'THEOplayer detected');
                clearInterval(pollInterval);
            }
        }, 500);
        
        __sb.timers.push(pollInterval);
    })();
    
    // ── MediaSource API Hook ────────────────────────────────────────────────
    (function() {
        if (__sb.state.hookedAPIs.has('mediasource')) return;
        __sb.state.hookedAPIs.add('mediasource');
        
        var OrigMediaSource = window.MediaSource;
        if (OrigMediaSource) {
            window.MediaSource = function() {
                var ms = new OrigMediaSource();
                
                var origAddBuffer = ms.addSourceBuffer.bind(ms);
                ms.addSourceBuffer = function(type) {
                    log('debug', 'MediaSource.addSourceBuffer', type);
                    // MSE usage detected - could be adaptive streaming
                    if (type && (type.includes('mp4') || type.includes('webm') || type.includes('mpeg'))) {
                        // This might be a streaming scenario
                    }
                    return origAddBuffer(type);
                };
                
                return ms;
            };
            
            window.MediaSource.prototype = OrigMediaSource.prototype;
            ['isTypeSupported', 'addSourceBuffer', 'removeSourceBuffer', 'endOfStream'].forEach(function(m) {
                window.MediaSource[m] = OrigMediaSource[m];
            });
            
            __sb.hooks.push(function() {
                window.MediaSource = OrigMediaSource;
            });
        }
    })();
    
    // ── SourceBuffer Hook ───────────────────────────────────────────────────
    (function() {
        if (__sb.state.hookedAPIs.has('sourcebuffer')) return;
        __sb.state.hookedAPIs.add('sourcebuffer');
        
        var OrigSB = window.SourceBuffer;
        if (OrigSB) {
            // Hook appendBuffer - sometimes used for streaming
            var origAppend = OrigSB.prototype.appendBuffer;
            if (origAppend) {
                OrigSB.prototype.appendBuffer = function(data) {
                    log('debug', 'SourceBuffer.appendBuffer', data ? data.byteLength : 0);
                    return origAppend.apply(this, arguments);
                };
            }
            
            __sb.hooks.push(function() {
                if (origAppend) OrigSB.prototype.appendBuffer = origAppend;
            });
        }
    })();
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DOM OBSERVERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    // ── MutationObserver for Dynamic Content ────────────────────────────────
    (function() {
        try {
            var observer = new MutationObserver(function(mutations) {
                mutations.forEach(function(mutation) {
                    // Added nodes
                    mutation.addedNodes.forEach(function(node) {
                        if (node.nodeType !== 1) return; // Skip non-elements
                        
                        // Direct video/audio elements
                        if (node.tagName === 'VIDEO' || node.tagName === 'AUDIO') {
                            deepScanElement(node, 'mutation_direct');
                        }
                        
                        // Check for player containers
                        try {
                            if (node.matches && (
                                node.matches('.video-js, .vjs-tech, .player, . Clappr, [data-player]') ||
                                node.className && typeof node.className === 'string' && 
                                (node.className.includes('player') || node.className.includes('video'))
                            )) {
                                deepScanElement(node, 'mutation_player');
                            }
                        } catch(e) {}
                        
                        // Check children
                        try {
                            node.querySelectorAll && node.querySelectorAll('video, audio, source, iframe, [data-video], [data-src], [data-hls], [data-m3u8], [data-mpd]').forEach(function(el) {
                                deepScanElement(el, 'mutation_child');
                            });
                        } catch(e) {}
                    });
                    
                    // Modified attributes
                    if (mutation.type === 'attributes') {
                        var target = mutation.target;
                        if (target.tagName === 'VIDEO' || target.tagName === 'AUDIO') {
                            deepScanElement(target, 'mutation_attr');
                        }
                    }
                });
            });
            
            observer.observe(document.documentElement, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['src', 'data-src', 'data-video', 'data-hls']
            });
            
            __sb.observers.push(observer);
        } catch(e) {
            log('error', 'MutationObserver setup failed', e);
        }
    })();
    
    // ── IntersectionObserver for Lazy Loading ────────────────────────────────
    (function() {
        try {
            var observedEls = new WeakSet();
            
            var observer = new IntersectionObserver(function(entries) {
                entries.forEach(function(entry) {
                    if (entry.isIntersecting) {
                        var el = entry.target;
                        if (el.tagName === 'VIDEO' || el.tagName === 'AUDIO') {
                            deepScanElement(el, 'intersection');
                        }
                    }
                });
            }, { threshold: 0.01 });
            
            // Observe existing media elements
            function observeMedia() {
                try {
                    document.querySelectorAll('video, audio').forEach(function(el) {
                        if (!observedEls.has(el)) {
                            observedEls.add(el);
                            observer.observe(el);
                        }
                    });
                } catch(e) {}
            }
            
            observeMedia();
            
            // Keep observing
            var interval = setInterval(observeMedia, 3000);
            __sb.timers.push(interval);
            __sb.observers.push(observer);
        } catch(e) {
            log('error', 'IntersectionObserver setup failed', e);
        }
    })();
    
    // ── iframe Content Hook ─────────────────────────────────────────────────
    function tryPushIframeContent(iframe) {
        try {
            if (iframe.__sb_iframe_pushed) return;
            iframe.__sb_iframe_pushed = true;
            
            var iframeDoc = iframe.contentDocument || iframe.contentWindow.document;
            if (!iframeDoc) return;
            
            log('debug', 'Scanning iframe content');
            
            // Scan scripts
            iframeDoc.querySelectorAll('script:not([src])').forEach(function(s) {
                extractUrls(s.textContent, 'iframe_script');
            });
            
            // Scan videos
            iframeDoc.querySelectorAll('video, audio').forEach(function(v) {
                deepScanElement(v, 'iframe_media');
            });
            
            // Scan iframes (nested)
            iframeDoc.querySelectorAll('iframe').forEach(function(nested) {
                if (iframe.__sb_depth < __sb.config.iframeDepth) {
                    iframe.__sb_depth = (iframe.__sb_depth || 0) + 1;
                    tryPushIframeContent(nested);
                }
            });
        } catch(e) {
            // Cross-origin iframe, can't access
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SHADOW DOM SUPPORT
    // ═══════════════════════════════════════════════════════════════════════════
    
    (function() {
        var origAttachShadow = Element.prototype.attachShadow;
        if (origAttachShadow) {
            Element.prototype.attachShadow = function(mode) {
                var shadowRoot = origAttachShadow.call(this, mode);
                
                // Observe shadow DOM
                try {
                    var observer = new MutationObserver(function(mutations) {
                        mutations.forEach(function(m) {
                            m.addedNodes.forEach(function(node) {
                                if (node.nodeType !== 1) return;
                                deepScanElement(node, 'shadow_dom');
                            });
                        });
                    });
                    
                    observer.observe(shadowRoot, { childList: true, subtree: true });
                    __sb.observers.push(observer);
                } catch(e) {}
                
                return shadowRoot;
            };
            
            __sb.hooks.push(function() {
                Element.prototype.attachShadow = origAttachShadow;
            });
        }
    })();
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SERVICE WORKER INTERCEPTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    (function() {
        if ('serviceWorker' in navigator) {
            var origRegister = navigator.serviceWorker.register;
            navigator.serviceWorker.register = function(url, options) {
                log('debug', 'ServiceWorker registered', url);
                report(url, 'serviceworker', 'GET');
                return origRegister.apply(this, arguments);
            };
            
            __sb.hooks.push(function() {
                navigator.serviceWorker.register = origRegister;
            });
        }
    })();
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CRYPTO/DECODING UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    // ── Base64 Decode ───────────────────────────────────────────────────────
    function tryBase64Decode(str) {
        try {
            return atob(str);
        } catch(e) {
            return null;
        }
    }
    
    // ── URL Decode ──────────────────────────────────────────────────────────
    function tryUrlDecode(str) {
        try {
            return decodeURIComponent(str);
        } catch(e) {
            try {
                return decodeURI(str);
            } catch(e2) {
                return null;
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MEDIA ELEMENT OBSERVER HELPER
    // ═══════════════════════════════════════════════════════════════════════════
    
    function observeMediaElement(media) {
        if (!media || media.__sb_observed) return;
        media.__sb_observed = true;
        
        // Watch for src changes
        var observer = new MutationObserver(function(mutations) {
            mutations.forEach(function(m) {
                if (m.attributeName === 'src' && media.src) {
                    report(media.src, 'media_observer_src', 'GET');
                }
            });
        });
        
        observer.observe(media, { attributes: true, attributeFilter: ['src'] });
        __sb.observers.push(observer);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIAL SCAN
    // ═══════════════════════════════════════════════════════════════════════════
    
    function initialScan() {
        log('info', 'Starting initial deep scan');
        
        // 1. Scan all scripts
        document.querySelectorAll('script:not([src])').forEach(function(s) {
            extractUrls(s.textContent, 'initial_script');
        });
        
        // 2. Scan all media elements
        document.querySelectorAll('video, audio').forEach(function(v) {
            deepScanElement(v, 'initial_media');
        });
        
        // 3. Scan all iframes
        document.querySelectorAll('iframe').forEach(function(iframe) {
            deepScanElement(iframe, 'initial_iframe');
        });
        
        // 4. Scan data-* attributes globally
        document.querySelectorAll('[data-video], [data-src], [data-hls], [data-m3u8], [data-mpd], [data-stream], [data-url]').forEach(function(el) {
            deepScanElement(el, 'initial_data_attr');
        });
        
        // 5. Scan inline styles and backgrounds
        document.querySelectorAll('[style*="url"]').forEach(function(el) {
            try {
                var bg = el.style.backgroundImage;
                if (bg) extractUrls(bg, 'initial_style');
            } catch(e) {}
        });
        
        log('info', 'Initial scan complete, found ' + __sb.state.urls.size + ' URLs');
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCHEDULED DEEP SCANS
    // ═══════════════════════════════════════════════════════════════════════════
    
    function deepScan(label) {
        log('debug', 'Deep scan: ' + label);
        
        // Re-scan all scripts (SPAs may have loaded new ones)
        document.querySelectorAll('script:not([src])').forEach(function(s) {
            if (!s.__sb_scanned) {
                extractUrls(s.textContent, 'deep_script_' + label);
            }
        });
        
        // Re-scan all media
        document.querySelectorAll('video, audio').forEach(function(v) {
            v.__sb_scanned = false; // Reset flag for re-scan
            deepScanElement(v, 'deep_media_' + label);
        });
        
        // Re-scan data attributes
        document.querySelectorAll('[data-video], [data-src], [data-hls], [data-m3u8], [data-mpd]').forEach(function(el) {
            el.__sb_scanned = false;
            deepScanElement(el, 'deep_data_' + label);
        });
        
        // Scan AJAX/fetch response caches if available
        try {
            // Look for common state management stores
            if (window.__NEXT_DATA__) {
                extractUrls(JSON.stringify(window.__NEXT_DATA__), 'next_data');
            }
            if (window.__PRELOADED_STATE__) {
                extractUrls(JSON.stringify(window.__PRELOADED_STATE__), 'preloaded_state');
            }
            if (window.__INITIAL_STATE__) {
                extractUrls(JSON.stringify(window.__INITIAL_STATE__), 'initial_state');
            }
        } catch(e) {}
    }
    
    // Schedule deep scans
    __sb.config.scanIntervals.forEach(function(delay, idx) {
        var timer = setTimeout(function() {
            deepScan('scheduled_' + delay);
        }, delay);
        __sb.timers.push(timer);
    });
    
    // Periodic rescan
    var periodicTimer = setInterval(function() {
        deepScan('periodic');
    }, 30000);
    __sb.timers.push(periodicTimer);
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ERROR HANDLER HOOK
    // ═══════════════════════════════════════════════════════════════════════════
    
    window.addEventListener('error', function(e) {
        // Sometimes stream URLs appear in error messages
        if (e.message && typeof e.message === 'string') {
            extractUrls(e.message, 'error_message');
        }
        
        // Check for 404/failed stream URLs
        if (e.filename && e.filename.match(/\\.(m3u8|mpd|mp4|webm|ts|m4s)(?:\\?|$)/i)) {
            report(e.filename, 'error_file', 'GET');
        }
    }, true);
    
    // Handle unhandled promise rejections
    window.addEventListener('unhandledrejection', function(e) {
        if (e.reason && typeof e.reason === 'string') {
            extractUrls(e.reason, 'rejection_reason');
        }
    });
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BEFOREUNLOAD CLEANUP
    // ═══════════════════════════════════════════════════════════════════════════
    
    window.addEventListener('beforeunload', function() {
        cleanup();
    });
    
    function cleanup() {
        // Clear timers
        __sb.timers.forEach(function(t) {
            clearTimeout(t);
            clearInterval(t);
        });
        __sb.timers = [];
        
        // Disconnect observers
        __sb.observers.forEach(function(o) {
            try { o.disconnect(); } catch(e) {}
        });
        __sb.observers = [];
        
        // Restore hooks
        __sb.hooks.forEach(function(restore) {
            try { restore(); } catch(e) {}
        });
        __sb.hooks = [];
        
        log('debug', 'Cleanup complete');
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // START
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Run initial scan after DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialScan);
    } else {
        setTimeout(initialScan, __sb.config.deepScanDelay);
    }
    
    log('info', 'Ultra-Deep Injection v2 initialized');
    log('debug', 'Config:', __sb.config);
    
})();
""".trimIndent()
