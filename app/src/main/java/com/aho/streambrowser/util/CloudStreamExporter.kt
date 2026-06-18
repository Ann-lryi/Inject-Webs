package com.aho.streambrowser.util

import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.model.StreamQuality
import com.aho.streambrowser.model.StreamType
object CloudStreamExporter {
    
    /**
     * Convert StreamItem to CloudStream ExtractorLink format
     */
    fun toExtractorLink(stream: StreamItem): String {
        val isM3u8 = stream.type == StreamType.HLS || stream.type == StreamType.DASH
        val quality = when (stream.quality) {
            StreamQuality.P1080 -> "Qualities.P1080"
            StreamQuality.P720 -> "Qualities.P720"
            StreamQuality.P480 -> "Qualities.P480"
            StreamQuality.P360 -> "Qualities.P360"
            StreamQuality.P240 -> "Qualities.P240"
            StreamQuality.P1440 -> "Qualities.P1440"
            StreamQuality.P4K -> "Qualities.P2160"
            StreamQuality.UNKNOWN -> "Qualities.Unknown"
        }
        
        return """
callback(
    ExtractorLink(
        source   = "Custom",
        name     = "${stream.displayName}",
        url      = "${stream.url}",
        referer  = "${stream.referer}",
        quality  = ${quality}.value,
        isM3u8   = ${isM3u8},
        headers  = mapOf(
            "Referer"    to "${stream.referer}",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
    )
)""".trimIndent()
    }
    
    /**
     * Export multiple streams as batch ExtractorLinks
     */
    fun toBatchExtractorLinks(streams: List<StreamItem>): String {
        return buildString {
            streams.forEachIndexed { index, stream ->
                if (index > 0) appendLine()
                appendLine("// Stream ${index + 1}: ${stream.displayName}")
                append(toExtractorLink(stream))
            }
        }
    }
}
