package com.aho.streambrowser.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class StreamType { HLS, MP4, DASH, FLV, OTHER }

@Parcelize
data class StreamItem(
    val url: String,
    val type: StreamType,
    val source: String,
    val referer: String = "",
    val foundAt: Long = System.currentTimeMillis()
) : Parcelable {

    val label get() = when (type) {
        StreamType.HLS   -> "HLS/M3U8"
        StreamType.MP4   -> "MP4"
        StreamType.DASH  -> "DASH/MPD"
        StreamType.FLV   -> "FLV"
        StreamType.OTHER -> "Stream"
    }

    companion object {
        fun fromUrl(url: String, referer: String = "", source: String = "network"): StreamItem? {
            val type = detectType(url) ?: return null
            return StreamItem(url = url, type = type, source = source, referer = referer)
        }

        fun detectType(url: String): StreamType? {
            val l = url.lowercase()
            // Query string / fragment should not influence extension-based detection
            val path = l.substringBefore("?").substringBefore("#")
            return when {
                path.endsWith(".m3u8") || path.endsWith(".m3u") && !l.contains(".mp3") -> StreamType.HLS
                path.endsWith(".mp4")  || path.endsWith(".m4v")                       -> StreamType.MP4
                path.endsWith(".mpd")                                                    -> StreamType.DASH
                path.endsWith(".flv")                                                    -> StreamType.FLV
                // .ts segment – only if in a clear HLS context
                path.endsWith(".ts") && !l.contains("typescript")
                    && (l.contains("m3u8") || l.contains("/hls/") || l.contains("segment")) -> StreamType.HLS
                // Heuristic cho CDN stream không có extension rõ – stricter matching
                l.contains(".m3u8")                                                      -> StreamType.HLS
                l.contains("/hls/") && l.startsWith("http")                              -> StreamType.HLS
                l.contains("manifest/") && l.startsWith("http") && l.contains("m3u8")     -> StreamType.HLS
                l.contains("/dash/") && l.startsWith("http") && l.contains(".mpd")        -> StreamType.DASH
                else                                                                     -> null
            }
        }
    }
}
