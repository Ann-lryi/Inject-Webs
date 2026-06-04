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
            return when {
                // HLS/M3U8 patterns
                l.contains(".m3u8")                                          -> StreamType.HLS
                l.contains("/hls/") && l.startsWith("http")                  -> StreamType.HLS
                l.contains("manifest/") && l.startsWith("http")              -> StreamType.HLS
                l.contains("/live/") && l.contains(".ts")                    -> StreamType.HLS
                l.contains("playlist.m3u8")                                  -> StreamType.HLS
                l.contains("index.m3u8")                                     -> StreamType.HLS
                l.contains("master.m3u8")                                    -> StreamType.HLS

                // MP4 patterns
                l.contains(".mp4")                                           -> StreamType.MP4
                l.contains(".mov")                                           -> StreamType.MP4
                l.contains(".mkv")                                           -> StreamType.MP4
                l.contains(".avi")                                           -> StreamType.MP4
                l.contains(".webm")                                          -> StreamType.MP4

                // DASH patterns
                l.contains(".mpd")                                           -> StreamType.DASH
                l.contains("/dash/") && l.startsWith("http")                 -> StreamType.DASH

                // FLV patterns
                l.contains(".flv")                                           -> StreamType.FLV

                // Heuristic for CDN streams without clear extension
                l.contains("stream") && l.contains("token")
                    && l.startsWith("http")                                  -> StreamType.HLS
                l.contains("/video/") && (l.contains("cdn") || l.contains("media"))
                    && l.startsWith("http")                                  -> StreamType.MP4

                else                                                         -> null
            }
        }
    }
}
