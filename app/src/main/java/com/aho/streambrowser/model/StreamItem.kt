package com.aho.streambrowser.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class StreamType { 
    HLS, 
    MP4, 
    DASH, 
    FLV,
    WEBM,      // WebM format
    M3U9,      // Non-standard ".m3u9" obfuscated-HLS extension used by some sites
    WEBSOCKET, // WebSocket streaming
    RTMP,      // RTMP streams
    OTHER      // Unknown/other
}

enum class StreamQuality {
    P240, P360, P480, P720, P1080, P1440, P4K, UNKNOWN;
    
    companion object {
        fun fromUrl(url: String): StreamQuality {
            val lower = url.lowercase()
            return when {
                lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> P4K
                lower.contains("1440") || lower.contains("qhd")                          -> P1440
                lower.contains("1080") || lower.contains("fhd")                          -> P1080
                lower.contains("720")  || lower.contains("hd")                            -> P720
                lower.contains("480")  || lower.contains("sd")                            -> P480
                lower.contains("360")                                                     -> P360
                lower.contains("240")                                                     -> P240
                else                                                                     -> UNKNOWN
            }
        }
    }
}

@Parcelize
data class StreamItem(
    val url: String,
    val type: StreamType,
    val source: String,
    val referer: String = "",
    val foundAt: Long = System.currentTimeMillis(),
    val quality: StreamQuality = StreamQuality.UNKNOWN,
    val codec: String? = null,
    val bitrate: Int? = null
) : Parcelable {

    val label get() = when (type) {
        StreamType.HLS       -> "HLS/M3U8"
        StreamType.MP4       -> "MP4"
        StreamType.DASH      -> "DASH/MPD"
        StreamType.FLV       -> "FLV"
        StreamType.WEBM      -> "WebM"
        StreamType.M3U9      -> "M3U9"
        StreamType.WEBSOCKET -> "WebSocket"
        StreamType.RTMP      -> "RTMP"
        StreamType.OTHER     -> "Stream"
    }
    
    val qualityLabel: String get() = when (quality) {
        StreamQuality.P240   -> "240p"
        StreamQuality.P360   -> "360p"
        StreamQuality.P480   -> "480p"
        StreamQuality.P720   -> "720p"
        StreamQuality.P1080  -> "1080p"
        StreamQuality.P1440  -> "1440p"
        StreamQuality.P4K    -> "4K"
        StreamQuality.UNKNOWN -> ""
    }
    
    val displayName: String get() {
        val parts = mutableListOf<String>()
        if (qualityLabel.isNotEmpty()) parts.add(qualityLabel)
        parts.add(label)
        if (codec != null) parts.add(codec!!)
        return parts.joinToString(" ")
    }

    companion object {
        fun fromUrl(url: String, referer: String = "", source: String = "network"): StreamItem? {
            val type = detectType(url) ?: return null
            return StreamItem(
                url = url, 
                type = type, 
                source = source, 
                referer = referer,
                quality = StreamQuality.fromUrl(url),
                codec = detectCodec(url)
            )
        }

        fun detectType(url: String): StreamType? {
            if (url.isBlank()) return null
            
            val l = url.lowercase()
            val path = runCatching { java.net.URL(l).path }.getOrDefault(l)
            
            // Primary checks — clear file extensions first (highest confidence).
            val byExtension = when {
                l.contains(".m3u9") -> StreamType.M3U9
                l.contains(".m3u8") || l.contains(".m3u") -> StreamType.HLS
                l.contains(".mpd") -> StreamType.DASH
                l.contains(".mp4") || l.contains(".m4v") || l.contains(".mov") -> StreamType.MP4
                l.contains(".flv") -> StreamType.FLV
                l.contains(".webm") || l.contains(".mkv") -> StreamType.WEBM
                l.contains("wss://") || l.contains("ws://") -> StreamType.WEBSOCKET
                l.contains("rtmp://") || l.contains("rtmps://") -> StreamType.RTMP
                else -> null
            }
            if (byExtension != null) return byExtension

            // Secondary checks — path-based (CDN layouts). Scoped to the URL PATH so a query
            // string or host containing "/hls/" doesn't trigger a false positive.
            val byPath = when {
                path.contains("/hls/") || path.contains("manifest/m3u8") ||
                    path.contains("/dash/") || path.contains("manifest/dash") ->
                    if (path.contains("dash")) StreamType.DASH else StreamType.HLS
                path.contains("/flv/") -> StreamType.FLV
                else -> null
            }
            if (byPath != null) return byPath

            // Weak heuristics — require corroborating signals, because single-keyword matches
            // like "/video/" or "videos/...com" previously misclassified arbitrary
            // REST/CDN URLs (tracking, thumbnails, APIs).
            val hasManifestWord = listOf("manifest", "master", "playlist", "chunklist").any { l.contains(it) }
            val isSegment = path.endsWith(".ts") || path.contains("/segment") || l.contains("segment_")
            val hasStreamWord = l.contains("stream") || l.contains("bitrate")
            val hasToken = l.contains("token") || l.contains("sign=")
            val isHttp = l.startsWith("http")
            return when {
                // HLS: a media-segment-shaped URL (.ts / segment_), OR a manifest word plus
                // another stream/token signal, OR a stream/play endpoint carrying both a token
                // and a bitrate/stream keyword (common CDN signed-URL shape).
                isHttp && (
                    isSegment ||
                        (hasManifestWord && (hasToken || hasStreamWord)) ||
                        (hasStreamWord && hasToken)
                    ) -> StreamType.HLS
                // MP4: explicit /cdn/ video asset path, not any random "/video/"
                isHttp && path.contains("/cdn/") && path.contains("/video/") -> StreamType.MP4
                else -> null
            }
        }
        
        private fun detectCodec(url: String): String? {
            val lower = url.lowercase()
            return when {
                lower.contains("avc") || lower.contains("h264") -> "H.264"
                lower.contains("hevc") || lower.contains("h265") || lower.contains("hdr") -> "H.265/HEVC"
                lower.contains("vp9") -> "VP9"
                lower.contains("vp8") -> "VP8"
                lower.contains("av1") -> "AV1"
                lower.contains("aac") || lower.contains("mp4a") -> "AAC"
                lower.contains("mp3") || lower.contains("mp3") -> "MP3"
                lower.contains("opus") -> "Opus"
                lower.contains("vorbis") -> "Vorbis"
                else -> null
            }
        }
    }
}
