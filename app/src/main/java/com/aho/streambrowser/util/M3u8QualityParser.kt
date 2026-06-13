package com.aho.streambrowser.util

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A2: Parse m3u8 master playlist — extract quality variants */
object M3u8QualityParser {

    data class Quality(
        val resolution: String,
        val bandwidth:  Int,
        val fps:        String,
        val codecs:     String,
        val url:        String
    ) {
        val label: String get() = when {
            resolution.contains("1920") || resolution.contains("1080") -> "1080p"
            resolution.contains("1280") || resolution.contains("720")  -> "720p"
            resolution.contains("854")  || resolution.contains("480")  -> "480p"
            resolution.contains("640")  || resolution.contains("360")  -> "360p"
            resolution == "unknown" -> "${bandwidth / 1000}kbps"
            else -> resolution
        }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** Fetch and parse master.m3u8, return sorted qualities (highest first) */
    suspend fun fetchQualities(masterUrl: String, referer: String = ""): Result<List<Quality>> {
        return try {
            val req = Request.Builder().url(masterUrl)
                .apply { if (referer.isNotBlank()) addHeader("Referer", referer) }
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() ?: "" }
            Result.success(parse(body, masterUrl))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parse(content: String, baseUrl: String): List<Quality> {
        if (!content.contains("#EXTM3U")) return emptyList()
        // Single-bitrate playlist — no variants
        if (!content.contains("#EXT-X-STREAM-INF")) {
            return listOf(Quality("direct", 0, "", "", baseUrl))
        }
        val lines   = content.lines()
        val results = mutableListOf<Quality>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs      = parseAttrs(line.removePrefix("#EXT-X-STREAM-INF:"))
                val bandwidth  = attrs["BANDWIDTH"]?.toIntOrNull() ?: 0
                val resolution = attrs["RESOLUTION"] ?: "unknown"
                val fps        = attrs["FRAME-RATE"] ?: ""
                val codecs     = attrs["CODECS"] ?: ""
                val urlLine    = lines.getOrNull(i + 1)?.trim() ?: ""
                if (urlLine.isNotBlank() && !urlLine.startsWith("#")) {
                    val url = if (urlLine.startsWith("http")) urlLine else resolveUrl(baseUrl, urlLine)
                    results.add(Quality(resolution, bandwidth, fps, codecs, url))
                    i += 2; continue
                }
            }
            i++
        }
        return results.sortedByDescending { it.bandwidth }
    }

    private fun parseAttrs(s: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        Regex("""([\w-]+)=("([^"]*)"|([\S,]*))""").findAll(s).forEach { m ->
            map[m.groupValues[1]] = m.groupValues[3].ifEmpty { m.groupValues[4] }
        }
        return map
    }

    fun resolveUrl(base: String, relative: String): String = try {
        java.net.URL(java.net.URL(base), relative).toString()
    } catch (_: Exception) { relative }
}
