package com.aho.streambrowser.util

import android.content.Context

object UserAgentManager {
    val presets = listOf(
        // ── Chrome Desktop (latest) ──
        "Chrome 125 Win"  to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Chrome 125 Mac"  to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Chrome 125 Linux" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        // ── Chrome Android (latest) ──
        "Chrome Android"  to "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.113 Mobile Safari/537.36",
        // ── Edge ──
        "Edge Win"        to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0",
        // ── Firefox ──
        "Firefox Win"     to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0",
        "Firefox Android" to "Mozilla/5.0 (Android 14; Mobile; rv:126.0) Gecko/126.0 Firefox/126.0",
        // ── Safari ──
        "Safari macOS"    to "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15",
        "Safari iOS"      to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
        // ── Smart TV ──
        "Smart TV"        to "Mozilla/5.0 (SMART-TV; Linux; Tizen 7.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/7.0 TV Safari/538.1",
        // ── Special ──
        "Googlebot"       to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
    )

    fun save(context: Context, ua: String) =
        context.getSharedPreferences("stream_browser_data", Context.MODE_PRIVATE)
            .edit().putString("custom_ua", ua).apply()

    fun load(context: Context): String =
        context.getSharedPreferences("stream_browser_data", Context.MODE_PRIVATE)
            .getString("custom_ua", presets[0].second) ?: presets[0].second

    /**
     * Parse Chrome version from UA string for Sec-CH-UA Client Hints.
     * Modern sites (Netflix, Disney+, etc.) check Sec-CH-UA via JS.
     */
    fun extractChromeVersion(ua: String): String {
        val match = Regex("Chrome/([\\d.]+)").find(ua)
        return match?.groupValues?.get(1) ?: "125.0.0.0"
    }

    fun extractMajorVersion(ua: String): String {
        return extractChromeVersion(ua).split(".").firstOrNull() ?: "125"
    }

    fun isMobileUA(ua: String): Boolean {
        return ua.contains("Mobile") || ua.contains("Android") || ua.contains("iPhone")
    }

    fun isChromeUA(ua: String): Boolean {
        return ua.contains("Chrome") && !ua.contains("Edg/") && !ua.contains("OPR/")
    }

    fun isEdgeUA(ua: String): Boolean {
        return ua.contains("Edg/")
    }

    fun isFirefoxUA(ua: String): Boolean {
        return ua.contains("Firefox") && !ua.contains("Seamonkey")
    }

    fun isSafariUA(ua: String): Boolean {
        return ua.contains("Safari") && !ua.contains("Chrome")
    }

    fun getPlatform(ua: String): String {
        return when {
            ua.contains("Windows") -> "Win32"
            ua.contains("Mac")     -> "MacIntel"
            ua.contains("Android") -> "Linux armv81"
            ua.contains("iPhone") || ua.contains("iPad") -> "iPhone"
            ua.contains("Linux")   -> "Linux x86_64"
            ua.contains("SMART-TV") -> "Linux armv81"
            else -> "Linux x86_64"
    }
    }

    fun getVendor(ua: String): String {
        return when {
            isChromeUA(ua) -> "Google Inc."
            isSafariUA(ua) -> "Apple Computer, Inc."
            isEdgeUA(ua)   -> "Google Inc."
            else -> ""
        }
    }

    /**
     * Build Sec-CH-UA header value from UA.
     * Format: "Chromium";v="125", "Google Chrome";v="125", "Not-A.Brand";v="99"
     */
    fun buildSecChUa(ua: String): String {
        val major = extractMajorVersion(ua)
        return when {
            isChromeUA(ua) -> "\"Chromium\";v=\"$major\", \"Google Chrome\";v=\"$major\", \"Not-A.Brand\";v=\"99\""
            isEdgeUA(ua) -> "\"Chromium\";v=\"$major\", \"Microsoft Edge\";v=\"$major\", \"Not-A.Brand\";v=\"99\""
            else -> "\"Not-A.Brand\";v=\"99\""
        }
    }

    fun buildSecChUaPlatform(ua: String): String {
        return when {
            ua.contains("Windows") -> "\"Windows\""
            ua.contains("Mac")     -> "\"macOS\""
            ua.contains("Android") -> "\"Android\""
            ua.contains("iPhone") || ua.contains("iPad") -> "\"iOS\""
            ua.contains("Linux")   -> "\"Linux\""
            else -> "\"Unknown\""
        }
    }
}
