package com.aho.streambrowser.model

data class NetworkRequest(
    val id: Long = System.currentTimeMillis(),
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val pageUrl: String,
    val isStream: Boolean,
    val streamType: StreamType? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val host: String get() = runCatching { java.net.URL(url).host }.getOrElse { "" }
    val path: String get() = runCatching { java.net.URL(url).path }.getOrElse { url }
    val tag: String get() = when {
        isStream -> streamType?.name ?: "STREAM"
        url.contains(".js")   -> "JS"
        url.contains(".css")  -> "CSS"
        url.contains(".png") || url.contains(".jpg") || url.contains(".webp") -> "IMG"
        url.contains("api")  -> "API"
        url.contains(".json") -> "JSON"
        else -> "REQ"
    }
    val tagColor: String get() = when (tag) {
        "HLS","DASH","MP4","FLV","STREAM" -> "#1B5E20"
        "API","JSON" -> "#0D47A1"
        "JS"  -> "#E65100"
        else  -> "#37474F"
    }
}
