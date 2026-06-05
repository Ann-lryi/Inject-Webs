package com.aho.streambrowser.util

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible

/**
 * Extension functions for common operations
 */

// View Extensions
fun View.show() { isVisible = true }
fun View.hide() { isVisible = false }
fun View.toggleVisibility() { isVisible = !isVisible }

// Toast Extensions
fun Context.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

// String Extensions
fun String.isValidUrl(): Boolean {
    return this.isNotBlank() && 
           (this.startsWith("http://") || this.startsWith("https://"))
}

fun String.extractDomain(): String {
    return try {
        val regex = Regex("https?://([^/]+)")
        regex.find(this)?.groupValues?.get(1) ?: this
    } catch (e: Exception) {
        this
    }
}

fun String.truncate(maxLength: Int, suffix: String = "..."): String {
    return if (this.length > maxLength) {
        this.take(maxLength - suffix.length) + suffix
    } else {
        this
    }
}

// URL Extensions
fun String.normalizeUrl(): String {
    var url = this.trim()
    if (url.startsWith("//")) {
        url = "https:$url"
    }
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        url = "https://$url"
    }
    return url
}

fun String.extractPath(): String {
    return try {
        val url = java.net.URL(this)
        url.path + (url.query?.let { "?$it" } ?: "")
    } catch (e: Exception) {
        this
    }
}

// Collection Extensions
fun <T> List<T>.takeLast(n: Int): List<T> {
    return if (size <= n) this else subList(size - n, size)
}

// Map Extensions
fun <K, V> Map<K, V>.toQueryString(): String {
    return entries.joinToString("&") { (k, v) -> "$k=$v" }
}

fun String.parseQueryString(): Map<String, String> {
    if (isBlank() || !contains("=")) return emptyMap()
    return split("&").mapNotNull { pair ->
        val parts = pair.split("=", limit = 2)
        if (parts.size == 2) {
            try {
                java.net.URLDecoder.decode(parts[0], "UTF-8") to 
                java.net.URLDecoder.decode(parts[1], "UTF-8")
            } catch (_: Exception) {
                parts[0] to parts[1]
            }
        } else null
    }.toMap()
}

// Time Extensions
fun Long.toReadableTime(): String {
    val seconds = this / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

fun Long.toTimestamp(): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(this))
}

// Validation Extensions
fun String.isNotBlankOrNull(): Boolean = !isNullOrBlank()
fun String?.orEmpty(default: String = ""): String = this ?: default
