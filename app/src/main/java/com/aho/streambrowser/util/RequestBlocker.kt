package com.aho.streambrowser.util

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

class RequestBlocker(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("stream_browser_data", Context.MODE_PRIVATE)

    // Built-in patterns luôn bật (ads/tracker phổ biến)
    private val builtinPatterns = listOf(
        "doubleclick.net", "googlesyndication.com", "googletagmanager.com",
        "google-analytics.com", "facebook.com/tr", "connect.facebook.net",
        "scorecardresearch.com", "quantserve.com", "outbrain.com", "taboola.com",
        "adnxs.com", "rubiconproject.com", "pubmatic.com", "criteo.com",
        "amazon-adsystem.com", "advertising.com", "adsrvr.org", "moatads.com",
        "adservice.google.com", "pagead2.googlesyndication.com", "static.ads-twitter.com",
        "analytics.tiktok.com", "ads-api.twitter.com", "pixel.facebook.com",
        "bat.bing.com", "cm.g.doubleclick.net", "ads.yahoo.com",
        "advertising.amazon.com", "amazonservices.com", "media.net"
    )

    private var customPatternsCache: List<String> = emptyList()
    
    // LRU Cache for URL blocking results (thread-safe)
    private val urlCache = object : LinkedHashMap<String, Boolean>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > 500
        }
    }
    private val cacheLock = Any()
    
    // Pre-convert builtin patterns to lowercase once
    private val builtinPatternsLower = builtinPatterns.map { it.lowercase() }

    init {
        reloadCache()
    }

    private fun reloadCache() {
        val raw = prefs.getString("block_patterns", "") ?: ""
        customPatternsCache = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        // Clear cache when patterns change
        synchronized(cacheLock) {
            urlCache.clear()
        }
    }

    fun getCustomPatterns(): List<String> = customPatternsCache

    fun addPattern(pattern: String) {
        if (pattern.isBlank()) return
        val trimmed = pattern.trim()
        if (!customPatternsCache.contains(trimmed)) {
            val list = customPatternsCache.toMutableList()
            list.add(0, trimmed)
            prefs.edit().putString("block_patterns", list.joinToString("\n")).apply()
            reloadCache()
        }
    }

    fun removePattern(pattern: String) {
        val list = customPatternsCache.filter { it != pattern }
        prefs.edit().putString("block_patterns", list.joinToString("\n")).apply()
        reloadCache()
    }

    fun isBuiltinEnabled(): Boolean = prefs.getBoolean("builtin_block", true)
    fun setBuiltinEnabled(v: Boolean) {
        prefs.edit().putBoolean("builtin_block", v).apply()
        // Clear cache when toggling builtin
        synchronized(cacheLock) {
            urlCache.clear()
        }
    }

    fun shouldBlock(url: String): Boolean {
        if (url.isBlank()) return false
        
        // Check cache first (thread-safe)
        synchronized(cacheLock) {
            urlCache[url]?.let { return it }
        }
        
        val result = checkShouldBlock(url)
        
        // Store in cache
        synchronized(cacheLock) {
            urlCache[url] = result
        }
        
        return result
    }
    
    private fun checkShouldBlock(url: String): Boolean {
        val lower = url.lowercase()
        
        // Check builtin patterns first (most common)
        if (isBuiltinEnabled() && builtinPatternsLower.any { lower.contains(it) }) {
            return true
        }
        
        // Check custom patterns
        val customPatternsLower = customPatternsCache.map { it.lowercase() }
        return customPatternsLower.any { lower.contains(it) }
    }

    var blockedCount: Int
        get() = prefs.getInt("blocked_count", 0)
        set(v) = prefs.edit().putInt("blocked_count", v).apply()

    fun incrementBlocked() { blockedCount++ }
    
    fun clearCache() {
        synchronized(cacheLock) {
            urlCache.clear()
        }
    }
}
