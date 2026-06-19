package com.aho.streambrowser.util

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class M3u8Quality(
    val bandwidth: Int,
    val resolution: String,
    val url: String,
    val label: String
)

object M3u8Parser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch và parse master playlist.
     * Trả về list quality streams, hoặc list chứa 1 entry nếu là media playlist.
     */
    fun parse(url: String, referer: String = ""): List<M3u8Quality> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .apply { if (referer.isNotBlank()) header("Referer", referer) }
            .build()

        val body = client.newCall(req).execute().use { it.body?.string() ?: "" }

        // Master playlist có #EXT-X-STREAM-INF
        return if (body.contains("#EXT-X-STREAM-INF")) {
            parseMaster(body, url)
        } else {
            // Media playlist – chỉ có 1 stream
            listOf(M3u8Quality(0, "Default", url, "Stream"))
        }
    }

    private fun parseMaster(content: String, baseUrl: String): List<M3u8Quality> {
        val results = mutableListOf<M3u8Quality>()
        val lines = content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = parseAttrs(line.removePrefix("#EXT-X-STREAM-INF:"))
                val bandwidth  = attrs["BANDWIDTH"]?.toIntOrNull() ?: 0
                val resolution = attrs["RESOLUTION"] ?: ""
                val nextLine   = lines.getOrNull(i + 1)?.trim() ?: ""
                if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                    val streamUrl = if (nextLine.startsWith("http")) nextLine
                                    else resolveUrl(baseUrl, nextLine)
                    val kbps  = bandwidth / 1000
                    val label = if (resolution.isNotEmpty()) "$resolution (${kbps}k)"
                                else "${kbps}k"
                    results.add(M3u8Quality(bandwidth, resolution, streamUrl, label))
                    i++
                }
            }
            i++
        }
        return results.sortedByDescending { it.bandwidth }
    }

    private fun parseAttrs(line: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = Regex("""(\w+(?:-\w+)*)=(?:"([^"]*)"|([\w@\-./]+))""")
        regex.findAll(line).forEach { m ->
            map[m.groupValues[1]] = m.groupValues[2].ifBlank { m.groupValues[3] }
        }
        return map
    }

    private fun resolveUrl(base: String, relative: String): String {
        return try {
            val baseUri = java.net.URI(base)
            baseUri.resolve(relative).toString()
        } catch (e: Exception) {
            val basePath = base.substringBeforeLast("/")
            "$basePath/$relative"
        }
    }
}
