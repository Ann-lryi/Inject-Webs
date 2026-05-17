package com.aho.streambrowser.util

import android.content.Context

object UserAgentManager {
    val presets = listOf(
        "Chrome Android"  to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Chrome Desktop"  to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Chrome Mac"      to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Firefox Desktop" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
        "Safari iOS"      to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
        "Smart TV"        to "Mozilla/5.0 (SMART-TV; Linux; Tizen 7.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/7.0 TV Safari/538.1",
        "Googlebot"       to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Curl/wget"       to "curl/8.6.0",
    )

    fun save(context: Context, ua: String) =
        context.getSharedPreferences("stream_browser_data", Context.MODE_PRIVATE)
            .edit().putString("custom_ua", ua).apply()

    fun load(context: Context): String =
        context.getSharedPreferences("stream_browser_data", Context.MODE_PRIVATE)
            .getString("custom_ua", presets[0].second) ?: presets[0].second
}
