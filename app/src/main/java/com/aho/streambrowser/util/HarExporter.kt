package com.aho.streambrowser.util

import com.aho.streambrowser.model.NetworkRequest
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** I2: Export network session as .har (HTTP Archive) */
object HarExporter {

    fun export(requests: List<NetworkRequest>, creator: String = "StreamBrowser v4"): String {
        val entries = JSONArray()
        requests.forEach { req ->
            val entry = JSONObject().apply {
                put("startedDateTime", iso8601(System.currentTimeMillis()))
                put("time", 0.0)
                put("request", buildRequest(req))
                put("response", buildResponse(req))
                put("cache", JSONObject())
                put("timings", JSONObject().apply {
                    put("send", 0); put("wait", 0); put("receive", 0)
                })
            }
            entries.put(entry)
        }
        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("version", "1.2")
                put("creator", JSONObject().apply {
                    put("name", creator); put("version", "4.0")
                })
                put("entries", entries)
            })
        }.toString(2)
    }

    private fun buildRequest(req: NetworkRequest) = JSONObject().apply {
        put("method",      req.method)
        put("url",         req.url)
        put("httpVersion", "HTTP/1.1")
        put("headers",     headersArray(req.headers))
        put("queryString", queryParams(req.url))
        put("cookies",     JSONArray())
        put("headersSize", -1)
        put("bodySize",    -1)
    }

    private fun buildResponse(req: NetworkRequest) = JSONObject().apply {
        val status = req.statusCode
        put("status",      status)
        put("statusText",  if (status in 200..299) "OK" else "")
        put("httpVersion", "HTTP/1.1")
        put("headers",     headersArray(req.responseHeaders))
        put("cookies",     JSONArray())
        put("content", JSONObject().apply {
            put("size",     -1L)
            put("mimeType", req.mimeType.ifBlank { "text/plain" })
            if (req.responseBodyPreview.isNotBlank()) put("text", req.responseBodyPreview)
        })
        put("redirectURL",   "")
        put("headersSize",   -1)
        put("bodySize",      -1L)
    }

    private fun headersArray(headers: Map<String, String>) = JSONArray().apply {
        headers.forEach { (k, v) -> put(JSONObject().apply { put("name", k); put("value", v) }) }
    }

    private fun queryParams(url: String) = JSONArray().apply {
        runCatching {
            val q = url.substringAfter("?", "")
            q.split("&").filter { it.contains("=") }.forEach { param ->
                put(JSONObject().apply {
                    put("name",  param.substringBefore("="))
                    put("value", param.substringAfter("="))
                })
            }
        }
    }

    private fun iso8601(ts: Long) =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .also { it.timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ts))
}
