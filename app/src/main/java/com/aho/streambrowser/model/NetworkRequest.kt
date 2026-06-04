package com.aho.streambrowser.model

data class NetworkRequest(
    val id: Long = System.currentTimeMillis(),
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val pageUrl: String,
    val isStream: Boolean,
    val streamType: StreamType? = null,
    val timestamp: Long = System.currentTimeMillis(),
    // Response data (populated asynchronously)
    var statusCode: Int = 0,
    var statusText: String = "",
    var responseHeaders: Map<String, String> = emptyMap(),
    var responseBodyPreview: String = "",
    var mimeType: String = "",
    var contentLength: Long = -1L,
    // Request payload (POST body, captured from JS hooks)
    var requestBody: String = "",
    // Source of this request (network, xhr, fetch, etc.)
    val source: String = "network"
) {
    val host: String get() = runCatching { java.net.URL(url).host }.getOrElse { "" }
    val path: String get() = runCatching { java.net.URL(url).path }.getOrElse { url }
    val query: String get() = runCatching { java.net.URL(url).query ?: "" }.getOrElse { "" }

    val tag: String get() = when {
        isStream -> streamType?.name ?: "STREAM"
        url.contains(".js")    -> "JS"
        url.contains(".css")   -> "CSS"
        url.contains(".png") || url.contains(".jpg") || url.contains(".webp") -> "IMG"
        url.contains("api")   -> "API"
        url.contains(".json") -> "JSON"
        else -> "REQ"
    }
    val tagColor: String get() = when (tag) {
        "HLS","DASH","MP4","FLV","STREAM" -> "#1B5E20"
        "API","JSON" -> "#0D47A1"
        "JS"  -> "#E65100"
        else  -> "#37474F"
    }

    /** Parse query string into key-value pairs */
    val queryParameters: List<Pair<String, String>> get() {
        if (query.isEmpty()) return emptyList()
        return query.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                try { parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") }
                catch (_: Exception) { parts[0] to parts[1] }
            } else null
        }
    }

    val hasResponseData: Boolean get() = statusCode > 0
}
