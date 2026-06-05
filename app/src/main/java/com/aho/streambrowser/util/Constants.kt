package com.aho.streambrowser.util

/**
 * Constants used throughout the application
 */
object Constants {
    // WebView Settings
    const val DEFAULT_HOME_URL = "https://www.google.com"
    const val SEARCH_URL_TEMPLATE = "https://www.google.com/search?q=%s"
    
    // Performance
    const val MAX_STREAMS = 200
    const val MAX_REQUESTS = 1000
    const val MAX_HISTORY = 200
    const val URL_CACHE_SIZE = 500
    const val JS_INJECT_COOLDOWN_MS = 500L
    const val MAX_CONCURRENT_REQUESTS = 10
    
    // Timing
    const val PAGE_LOAD_TIMEOUT_MS = 30000L
    const val DEBOUNCE_DELAY_MS = 100L
    
    // Storage
    const val PREFS_NAME = "stream_browser_data"
    const val PREFS_BOOKMARKS = "sb_bookmarks"
    const val KEY_BOOKMARKS = "bookmarks"
    const val KEY_HISTORY = "history"
    const val KEY_BLOCK_PATTERNS = "block_patterns"
    const val KEY_BUILTIN_BLOCK = "builtin_block"
    const val KEY_BLOCKED_COUNT = "blocked_count"
    
    // Network
    const val CONNECT_TIMEOUT_SEC = 5
    const val READ_TIMEOUT_SEC = 5
    const val WRITE_TIMEOUT_SEC = 5
    const val BODY_PREVIEW_SIZE = 4096
    
    // MIME Types
    const val MIME_M3U8 = "application/x-mpegURL"
    const val MIME_MPD = "application/dash+xml"
    const val MIME_MP4 = "video/mp4"
    const val MIME_WEBM = "video/webm"
    const val MIME_FLV = "video/x-flv"
    
    // UI
    const val TOAST_DURATION_SHORT = 1500
    const val TOAST_DURATION_LONG = 3000
    
    // Tags
    const val TAG_DEV_TOOLS = "devtools"
    const val TAG_STREAMS = "streams"
    
    // Animation
    const val ANIMATION_DURATION_MS = 200L
}
