package com.aho.streambrowser.feature.downloader.hls

import java.net.URI

/**
 * Parses the clear and AES-128 HLS subset supported by [HlsDownloader].
 *
 * What is supported:
 *  - Master playlists (variant selection by bandwidth)
 *  - Media playlists with unencrypted segments
 *  - Media playlists with METHOD=AES-128, including explicit IV attributes and the
 *    implicit "sequence number as IV" rule from RFC 8216 §5.2
 *  - fMP4/CMAF init segments (#EXT-X-MAP)
 *  - Byte-range (single range covering the whole segment on a single URL) segments
 *
 * What is intentionally rejected with [Playlist.unsupportedReason]:
 *  - METHOD=SAMPLE-AES / SAMPLE-AES-CTR (would need per-sample decryption + muxer rebuild)
 *  - Per-segment byte-range lists on a shared URL (only the common single-range case
 *    is handled; most real HLS uses one URL per segment anyway)
 */
object HlsPlaylistResolver {
    data class Variant(val url: String, val bandwidth: Long, val resolution: String? = null)

    /** A single EXT-X-KEY in effect for a run of segments. */
    data class KeyInfo(
        val method: String,
        val uri: String,
        val iv: ByteArray? = null,
        val keyFormat: String? = null
    )

    /** A downloadable segment plus the encryption that applies to it (if any). */
    data class Segment(
        val url: String,
        /** Absolute byte range, e.g. "bytes=1234-5678". Null for a normal full-body GET. */
        val byteRange: String? = null,
        val key: KeyInfo? = null,
        /** Sequence number used as the implicit IV when [key] has no explicit IV. */
        val mediaSequence: Long = 0
    )

    data class Playlist(
        val variants: List<Variant> = emptyList(),
        val initSegment: Segment? = null,
        val segments: List<Segment> = emptyList(),
        val unsupportedReason: String? = null
    ) {
        // Backwards-compatible accessors used by the original callers/tests.
        val initSegmentUrl: String? get() = initSegment?.url
        val segmentUrls: List<String> get() = segments.map { it.url }
    }

    fun parse(content: String, playlistUrl: String): Playlist {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        val mediaSequence = lines.firstNotNullOfOrNull { line ->
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true))
                line.substringAfter(':', "").trim().toLongOrNull()
            else null
        } ?: 0L

        // Reject unsupported encryption methods up front so the downloader never has to.
        val unsupportedKey = lines.firstOrNull { line ->
            line.startsWith("#EXT-X-KEY", ignoreCase = true) &&
                Regex("""(?i)METHOD\s*=\s*("?)(SAMPLE-AES(?:-CTR)?)"?""").containsMatchIn(line)
        }
        if (unsupportedKey != null) {
            val method = Regex("""(?i)METHOD\s*=\s*"?([A-Z0-9-]+)"?""")
                .find(unsupportedKey)?.groupValues?.getOrNull(1) ?: "SAMPLE-AES"
            return Playlist(unsupportedReason = "Mã hóa $method chưa được hỗ trợ (cần giải mã theo sample)")
        }

        val variants = mutableListOf<Variant>()
        val segments = mutableListOf<Segment>()
        var pendingBandwidth = -1L
        var pendingResolution: String? = null

        var currentKey: KeyInfo? = null
        var sequence = mediaSequence
        var pendingMap: Segment? = null
        var pendingByteRange: String? = null

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    pendingBandwidth = Regex("""(?i)(?:AVERAGE-)?BANDWIDTH\s*=\s*(\d+)""")
                        .find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    pendingResolution = Regex("""(?i)RESOLUTION\s*=\s*([0-9]+x[0-9]+)""")
                        .find(line)?.groupValues?.getOrNull(1)
                }

                line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                    currentKey = parseKey(line, playlistUrl)
                }

                line.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                    val uri = extractQuoted(line, "URI")
                    if (uri != null) {
                        val byterange = extractQuoted(line, "BYTERANGE")
                            ?: Regex("""(?i)BYTERANGE\s*=\s*"?([^",\s]+)"?""").find(line)?.groupValues?.getOrNull(1)
                        pendingMap = Segment(
                            url = resolve(playlistUrl, uri),
                            byteRange = byterange?.let(::toBytesRange),
                            key = currentKey
                        )
                    }
                }

                line.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) -> {
                    pendingByteRange = toBytesRange(line.substringAfter(':', "").trim())
                }

                !line.startsWith("#") -> {
                    val absolute = resolve(playlistUrl, line)
                    if (pendingBandwidth >= 0) {
                        variants += Variant(absolute, pendingBandwidth, pendingResolution)
                        pendingBandwidth = -1L
                        pendingResolution = null
                    } else {
                        val segKey = currentKey
                        val iv = segKey?.iv
                        segments += Segment(
                            url = absolute,
                            byteRange = pendingByteRange,
                            key = if (segKey != null) {
                                // Copy per-segment so the implicit-IV (sequence number) is
                                // frozen at the segment that introduced it.
                                segKey.copy(iv = iv ?: sequenceToIv(sequence))
                            } else null,
                            mediaSequence = sequence
                        )
                        sequence++
                        pendingByteRange = null
                    }
                }
            }
        }

        if (variants.isNotEmpty()) {
            return Playlist(variants = variants.sortedByDescending { it.bandwidth })
        }
        return Playlist(initSegment = pendingMap, segments = segments)
    }

    private fun parseKey(line: String, playlistUrl: String): KeyInfo? {
        val method = Regex("""(?i)METHOD\s*=\s*"?([A-Z0-9-]+)"?""")
            .find(line)?.groupValues?.getOrNull(1)?.uppercase()
            ?: return null
        if (method == "NONE") return null

        val uri = extractQuoted(line, "URI") ?: return null
        val ivHex = Regex("""(?i)IV\s*=\s*0x([0-9A-Fa-f]+)""")
            .find(line)?.groupValues?.getOrNull(1)
        val iv = ivHex?.let { hexToBytes(it) }
        val keyFormat = extractQuoted(line, "KEYFORMAT")

        return KeyInfo(
            method = method,
            uri = resolve(playlistUrl, uri),
            iv = iv,
            keyFormat = keyFormat
        )
    }

    /** Converts EXT-X-BYTERANGE value (e.g. "1024@0") into an HTTP Range header value. */
    private fun toBytesRange(value: String): String? {
        if (value.isBlank()) return null
        val parts = value.split('@')
        val length = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val start = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return "bytes=$start-${start + length - 1}"
    }

    // In HLS, the first attribute directly follows the tag's colon (e.g. "#EXT-X-MAP:URI=..."),
    // while subsequent attributes are comma-separated. Anchor on either boundary so we don't
    // accidentally match a substring of another attribute name.
    private fun extractQuoted(line: String, name: String): String? =
        Regex("""(?i)(?:^[^:]*:|,)$name\s*=\s*"([^"]+)"""").find(line)?.groupValues?.getOrNull(1)

    /** Implicit IV = big-endian 128-bit representation of the media sequence number. */
    private fun sequenceToIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var v = sequence
        for (i in 15 downTo 0) {
            iv[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return iv
    }

    private fun hexToBytes(hex: String): ByteArray {
        val s = if (hex.length % 2 == 0) hex else "0$hex"
        return ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }
    }

    private fun resolve(base: String, child: String): String =
        runCatching { URI(base).resolve(child).toString() }.getOrElse { child }
}
