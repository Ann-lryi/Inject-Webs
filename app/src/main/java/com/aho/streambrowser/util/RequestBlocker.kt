package com.aho.streambrowser.util

import android.content.Context
import android.content.SharedPreferences

class RequestBlocker(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("stream_browser_data", Context.MODE_PRIVATE)

    // Built-in ad/tracker patterns
    private val builtinPatterns = listOf(
        "doubleclick.net", "googlesyndication.com", "googletagmanager.com",
        "google-analytics.com", "facebook.com/tr", "connect.facebook.net",
        "scorecardresearch.com", "quantserve.com", "outbrain.com", "taboola.com",
        "adnxs.com", "rubiconproject.com", "pubmatic.com", "criteo.com",
        "amazon-adsystem.com", "advertising.com", "adsrvr.org", "moatads.com"
    )

    // Fix: Cache custom patterns to avoid reading SharedPreferences on every request
    @Volatile
    private var cachedCustomPatterns: List<String>? = null

    fun getCustomPatterns(): List<String> {
        cachedCustomPatterns?.let { return it }
        val raw = prefs.getString("block_patterns", "") ?: ""
        val patterns = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        cachedCustomPatterns = patterns
        return patterns
    }

    fun addPattern(pattern: String) {
        val list = getCustomPatterns().toMutableList()
        if (!list.contains(pattern)) {
            list.add(0, pattern)
            prefs.edit().putString("block_patterns", list.joinToString("\n")).apply()
        }
        cachedCustomPatterns = null // Invalidate cache
    }

    fun removePattern(pattern: String) {
        val list = getCustomPatterns().filter { it != pattern }
        prefs.edit().putString("block_patterns", list.joinToString("\n")).apply()
        cachedCustomPatterns = null // Invalidate cache
    }

    fun isBuiltinEnabled(): Boolean = prefs.getBoolean("builtin_block", true)
    fun setBuiltinEnabled(v: Boolean) = prefs.edit().putBoolean("builtin_block", v).apply()

    fun shouldBlock(url: String): Boolean {
        val lower = url.lowercase()
        if (isBuiltinEnabled() && builtinPatterns.any { lower.contains(it) }) return true
        return getCustomPatterns().any { lower.contains(it.lowercase()) }
    }

    var blockedCount: Int
        get() = prefs.getInt("blocked_count", 0)
        set(v) = prefs.edit().putInt("blocked_count", v).apply()

    fun incrementBlocked() { blockedCount++ }
}
