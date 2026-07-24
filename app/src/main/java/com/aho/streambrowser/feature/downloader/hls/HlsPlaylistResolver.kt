package com.aho.streambrowser.feature.downloader.hls

import java.net.URI

/**
 * Parses only the clear, byte-addressable HLS subset supported by [HlsDownloader].
 * Keeping this pure makes playlist decisions testable without Android or network access.
 */
object HlsPlaylistResolver {
    data class Variant(val url: String, val bandwidth: Long, val resolution: String? = null)

    data class Playlist(
        val variants: List<Variant> = emptyList(),
        val initSegmentUrl: String? = null,
        val segmentUrls: List<String> = emptyList(),
        val unsupportedReason: String? = null
    )

    fun parse(content: String, playlistUrl: String): Playlist {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.any { it.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) }) {
            return Playlist(unsupportedReason = "HLS byte-range chưa được hỗ trợ")
        }
        val encrypted = lines.any { line ->
            line.startsWith("#EXT-X-KEY", ignoreCase = true) &&
                !Regex("""(?i)METHOD\s*=\s*NONE""").containsMatchIn(line)
        }
        if (encrypted) return Playlist(unsupportedReason = "Luồng HLS đã mã hóa/DRM")

        val variants = mutableListOf<Variant>()
        var pendingBandwidth = -1L
        var pendingResolution: String? = null
        val segments = mutableListOf<String>()
        var initSegment: String? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    pendingBandwidth = Regex("""(?i)(?:AVERAGE-)?BANDWIDTH\s*=\s*(\d+)""")
                        .find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    pendingResolution = Regex("""(?i)RESOLUTION\s*=\s*([0-9]+x[0-9]+)""")
                        .find(line)?.groupValues?.getOrNull(1)
                }
                line.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                    extractUri(line)?.let { initSegment = resolve(playlistUrl, it) }
                }
                !line.startsWith("#") -> {
                    val absolute = resolve(playlistUrl, line)
                    if (pendingBandwidth >= 0) {
                        variants += Variant(absolute, pendingBandwidth, pendingResolution)
                        pendingBandwidth = -1L
                        pendingResolution = null
                    } else {
                        segments += absolute
                    }
                }
            }
        }
        if (variants.isNotEmpty()) return Playlist(variants = variants.sortedByDescending { it.bandwidth })
        return Playlist(initSegmentUrl = initSegment, segmentUrls = segments)
    }

    private fun extractUri(line: String): String? =
        Regex("""(?i)URI\s*=\s*"([^"]+)"""").find(line)?.groupValues?.getOrNull(1)

    private fun resolve(base: String, child: String): String =
        runCatching { URI(base).resolve(child).toString() }.getOrElse { child }
}
