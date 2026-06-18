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
    // ── Response fields (populated after OkHttp fetch) ──
    val statusCode: Int = 0,
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBodyPreview: String = "",
    val mimeType: String = "",
    val contentLength: Long = -1L,
    // ── Request payload fields ──
    val requestBody: String = "",
    val queryParameters: Map<String, String> = emptyMap(),
    val referer: String = ""
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
        "HLS"   -> "#10B981"   // emerald (matches HLS chip)
        "MP4"   -> "#3B82F6"   // blue (matches MP4 chip)
        "DASH"  -> "#8B5CF6"   // violet (matches DASH chip)
        "FLV"   -> "#F59E0B"   // orange (matches FLV chip)
        "STREAM"-> "#10B981"
        "API","JSON" -> "#3B82F6"
        "JS"    -> "#F59E0B"
        "CSS"   -> "#8B5CF6"
        "IMG"   -> "#14B8A6"
        else    -> "#6B7280"
    }

    /** Parse query parameters from URL */
    fun parseQueryParams(): Map<String, String> {
        if (queryParameters.isNotEmpty()) return queryParameters
        return runCatching {
            val q = java.net.URL(url).query ?: return@runCatching emptyMap()
            q.split("&").mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    try { java.net.URLDecoder.decode(parts[0], "UTF-8") to java.net.URLDecoder.decode(parts[1], "UTF-8") }
                    catch (_: Exception) { parts[0] to parts[1] }
                } else null
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    /** Create a copy with response data filled in */
    fun withResponse(
        statusCode: Int,
        responseHeaders: Map<String, String>,
        responseBodyPreview: String,
        mimeType: String,
        contentLength: Long
    ): NetworkRequest = copy(
        statusCode = statusCode,
        responseHeaders = responseHeaders,
        responseBodyPreview = responseBodyPreview,
        mimeType = mimeType,
        contentLength = contentLength
    )
}
