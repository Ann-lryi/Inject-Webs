package com.aho.streambrowser.model

import java.util.concurrent.atomic.AtomicLong

data class NetworkRequest(
    val id: Long = COUNTER.incrementAndGet(),
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val pageUrl: String,
    val isStream: Boolean,
    val streamType: StreamType? = null,
    val timestamp: Long = System.currentTimeMillis(),
    // ── Response info (populated from JS hooks) ──
    val statusCode: Int = 0,
    val responseHeaders: Map<String, String> = emptyMap(),
    val bodyPreview: String = "",
    val contentType: String = "",
    val bodySize: Long = 0,
    val duration: Long = 0,
    // ── Source tracking ──
    val source: String = "network"
) {
    companion object {
        private val COUNTER = AtomicLong(0)
    }
    val host: String get() = runCatching { java.net.URL(url).host }.getOrElse { "" }
    val path: String get() = runCatching { java.net.URL(url).path }.getOrElse { url }
    val tag: String get() = when {
        isStream -> streamType?.name ?: "STREAM"
        url.contains(".js")   -> "JS"
        url.contains(".css")  -> "CSS"
        url.contains(".png") || url.contains(".jpg") || url.contains(".webp") -> "IMG"
        url.contains("api")  -> "API"
        url.contains(".json") -> "JSON"
        url.contains(".m3u8") -> "HLS"
        url.contains(".mp4")  -> "MP4"
        url.contains(".mpd")  -> "DASH"
        url.contains(".flv")  -> "FLV"
        else -> "REQ"
    }
    val tagColor: String get() = when (tag) {
        "HLS","DASH","MP4","FLV","STREAM" -> "#1B5E20"
        "API","JSON" -> "#0D47A1"
        "JS"  -> "#E65100"
        "CSS" -> "#6A1B9A"
        "IMG" -> "#37474F"
        else  -> "#37474F"
    }
    val statusText: String get() = when {
        statusCode == 0 -> ""
        statusCode in 200..299 -> "$statusCode OK"
        statusCode in 300..399 -> "$statusCode Redirect"
        statusCode in 400..499 -> "$statusCode Client Error"
        statusCode in 500..599 -> "$statusCode Server Error"
        else -> "$statusCode"
    }
}
