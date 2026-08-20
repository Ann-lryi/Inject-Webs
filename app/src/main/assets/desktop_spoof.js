// Runs at document_start, BEFORE any page script, when Desktop mode is on.
// Goal: make the WebView look like a desktop Chrome to feature-detection based sites.
// Everything is configurable so we stay internally consistent (no Win32 + touchscreen combo).
(function () {
    'use strict';
    if (window.__desktopSpoofed) return;
    window.__desktopSpoofed = true;

    var NAV = {
        ua: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        platform: 'Win32',
        vendor: 'Google Inc.',
        oscpu: undefined,
        language: 'en-US',
        languages: ['en-US', 'en'],
        hardwareConcurrency: 8,
        deviceMemory: 8,
        maxTouchPoints: 0
    };
    var SCREEN = { width: 1920, height: 1080, availWidth: 1920, availHeight: 1040, colorDepth: 24, pixelDepth: 24 };
    var DESKTOP_VIEWPORT = 1280;

    function define(obj, prop, value) {
        try { Object.defineProperty(obj, prop, { get: function () { return value; }, configurable: true }); } catch (e) {}
    }

    // ── navigator ──
    define(Navigator.prototype, 'userAgent', NAV.ua);
    define(Navigator.prototype, 'appVersion', NAV.ua.replace('Mozilla/', ''));
    define(Navigator.prototype, 'platform', NAV.platform);
    define(Navigator.prototype, 'vendor', NAV.vendor);
    define(Navigator.prototype, 'oscpu', NAV.oscpu);
    define(Navigator.prototype, 'language', NAV.language);
    define(Navigator.prototype, 'languages', NAV.languages);
    define(Navigator.prototype, 'hardwareConcurrency', NAV.hardwareConcurrency);
    define(Navigator.prototype, 'deviceMemory', NAV.deviceMemory);
    define(Navigator.prototype, 'maxTouchPoints', NAV.maxTouchPoints);
    define(Navigator.prototype, 'webdriver', false);
    define(Navigator.prototype, 'doNotTrack', null);
    // Bluetooth/USB media devices are mobile-ish; neutralize them
    define(Navigator.prototype, 'bluetooth', undefined);
    define(Navigator.prototype, 'connection', undefined);
    // iPad/iPhone-only
    define(Navigator.prototype, 'standalone', false);

    // ── screen ──
    define(Screen.prototype, 'width', SCREEN.width);
    define(Screen.prototype, 'height', SCREEN.height);
    define(Screen.prototype, 'availWidth', SCREEN.availWidth);
    define(Screen.prototype, 'availHeight', SCREEN.availHeight);
    define(Screen.prototype, 'colorDepth', SCREEN.colorDepth);
    define(Screen.prototype, 'pixelDepth', SCREEN.pixelDepth);
    define(window, 'devicePixelRatio', 1);
    define(window, 'innerWidth', Math.min(DESKTOP_VIEWPORT, SCREEN.width));
    define(window, 'innerHeight', SCREEN.availHeight - 120);
    define(window, 'outerWidth', SCREEN.width);
    define(window, 'outerHeight', SCREEN.height);

    // ── touch: remove all touch constructors so sites see a non-touch desktop ──
    try { delete window.TouchEvent; } catch (e) {}
    try { delete window.ontouchstart; } catch (e) {}
    try {
        if (document && document.documentElement) {
            delete document.documentElement.ontouchstart;
            delete document.ontouchstart;
        }
    } catch (e) {}
    define(window, 'ontouchstart', undefined);
    define(window, 'ontouchend', undefined);
    define(window, 'ontouchmove', undefined);
    define(window, 'ontouchcancel', undefined);

    // ── viewport: force a desktop-width viewport (otherwise a narrow width
    //    tells responsive sites "mobile" even with a desktop UA). ──
    function fixViewport() {
        var v = document.querySelector('meta[name="viewport"]');
        if (!v) { v = document.createElement('meta'); v.name = 'viewport'; document.head && document.head.appendChild(v); }
        v.setAttribute('content', 'width=' + DESKTOP_VIEWPORT);
    }
    if (document.head) fixViewport();
    else document.addEventListener('DOMContentLoaded', fixViewport);

    // ── media query spoofing: `(pointer: coarse)` / `(hover: none)` are strong
    //    mobile signals. Make them report a precise pointer with hover. ──
    if (window.matchMedia) {
        var origMatch = window.matchMedia;
        window.matchMedia = function (q) {
            if (typeof q === 'string') {
                if (/\(\s*pointer\s*:\s*coarse\s*\)/i.test(q)) q = q.replace(/coarse/i, 'fine');
                if (/\(\s*hover\s*:\s*none\s*\)/i.test(q)) q = q.replace(/none/i, 'hover');
                if (/\(\s*pointer\s*\)/i.test(q) && !/fine/.test(q)) q = '(pointer: fine)';
            }
            var mql = origMatch.call(this, q);
            return mql;
        };
    }

    // ── Hide Chrome-only Android/mobile object leaks ──
    try { delete window.Android; } catch (e) {}

    // ── Permissions: geolocation/notifications should not report "denied" in a
    //    way that looks like a restricted mobile WebView. ──
    if (navigator.permissions && navigator.permissions.query) {
        var origQuery = navigator.permissions.query.bind(navigator.permissions);
        navigator.permissions.query = function (p) {
            return origQuery(p).catch(function () { return { state: 'prompt', onchange: null }; });
        };
    }
})();
