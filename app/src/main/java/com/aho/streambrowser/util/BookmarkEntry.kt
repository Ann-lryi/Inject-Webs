package com.aho.streambrowser.util

data class BookmarkEntry(
    val url: String,
    val title: String,
    val isBookmark: Boolean,   // false = history
    val timestamp: Long = System.currentTimeMillis()
)
