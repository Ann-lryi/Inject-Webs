package com.aho.streambrowser.util

import com.aho.streambrowser.model.NetworkRequest
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.model.StreamType

object CurlExporter {
    fun toCurl(req: NetworkRequest): String {
        val sb = StringBuilder("curl -X ${req.method} \\\n")
        sb.append("  '${req.url}'")
        req.headers.forEach { (k, v) ->
            sb.append(" \\\n  -H '${k}: ${v.replace("'", "\\'")}'")
        }
        return sb.toString()
    }
}

object CloudStreamExporter {
    fun toExtractorLink(stream: StreamItem): String {
        val isM3u8 = stream.type == StreamType.HLS
        val quality = when {
            stream.url.contains("1080") -> "Qualities.P1080"
            stream.url.contains("720")  -> "Qualities.P720"
            stream.url.contains("480")  -> "Qualities.P480"
            else -> "Qualities.Unknown"
        }
        return """
callback(
    ExtractorLink(
        source   = "Custom",
        name     = "${stream.label}",
        url      = "${stream.url}",
        referer  = "${stream.referer}",
        quality  = ${quality}.value,
        isM3u8   = ${isM3u8},
        headers  = mapOf(
            "Referer"    to "${stream.referer}",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
    )
)""".trimIndent()
    }
}
