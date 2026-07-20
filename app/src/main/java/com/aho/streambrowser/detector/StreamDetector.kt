package com.aho.streambrowser.detector

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.WebResourceRequest
import com.aho.streambrowser.model.NetworkRequest
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.model.StreamQuality
import com.aho.streambrowser.model.StreamType

// ── Data classes ─────────────────────────────────────────────────────────────

data class CryptoKeyCapture(
    val algorithm: String,
    val key:       String,
    val iv:        String  = "",
    val pageUrl:   String  = "",
    val timestamp: Long    = System.currentTimeMillis()
)

data class WebSocketMessage(
    val direction: String,   // "open" | "send" | "recv"
    val wsUrl:     String,
    val data:      String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ResponseBodyCapture(
    val url:         String,
    val statusCode:  Int,
    val contentType: String,
    val body:        String,
    val timestamp:   Long = System.currentTimeMillis()
)

/**
 * Activity / event log entry shown in the "Console" tab feed.
 * level: "success" (✓) | "warn" (⚠) | "info" (›)
 * Only emitted from events the app genuinely detects — see addLog() call sites.
 */
data class ActivityLogEntry(
    val level:     String,
    val message:   String,
    val source:    String = "",
    val timestamp: Long = System.currentTimeMillis()
)

// ── Protected domains ─────────────────────────────────────────────────────────

val PROTECTED_DOMAINS = listOf(
    "streamc.xyz", "streamc.com", "vidsrc", "vidsrc.in", "vidsrc.io",
    "streamwish", "streamwish.com", "wishfast", "dood", "dood.to",
    "embedsr", "srsone", "123movies", "putlocker", "fmovies",
    "gogohd", "gogohd.net", "superembed", "moviehab"
)

// ── HOOK JS ──────────────────────────────────────────────────────────────────
// Moved out of this file into assets/hook.js — was an 861-line string literal with no syntax
// highlighting, linting, or independent testability. Content is unchanged (verified with
// `node --check` both before and after the move); only where it lives changed.
fun loadHookJs(context: Context): String =
    context.assets.open("hook.js").bufferedReader().use { it.readText() }


// ── StreamDetector ────────────────────────────────────────────────────────────

class StreamDetector(private val context: Context? = null) {
    private val _streams       = mutableListOf<StreamItem>()
    private val _requests      = mutableListOf<NetworkRequest>()
    private val _cryptoKeys    = mutableListOf<CryptoKeyCapture>()
    private val _wsMessages     = mutableListOf<WebSocketMessage>()
    private val _responseBodies = mutableListOf<ResponseBodyCapture>()
    private val _activityLog    = mutableListOf<ActivityLogEntry>()
    private val _wasmSeen       = mutableSetOf<String>()
    private val _swSeen         = mutableSetOf<String>()
    private val _pendingPayloads = mutableMapOf<String, Pair<Map<String, String>, String>>()

    val streams:        List<StreamItem>          get() = synchronized(this) { _streams.toList()        }
    val requests:       List<NetworkRequest>      get() = synchronized(this) { _requests.toList()       }
    val cryptoKeys:     List<CryptoKeyCapture>    get() = synchronized(this) { _cryptoKeys.toList()     }
    val wsMessages:     List<WebSocketMessage>      get() = synchronized(this) { _wsMessages.toList()      }
    val responseBodies: List<ResponseBodyCapture> get() = synchronized(this) { _responseBodies.toList() }
    val activityLog:    List<ActivityLogEntry>    get() = synchronized(this) { _activityLog.toList()    }

    var onStreamFound:  ((StreamItem)     -> Unit)? = null
    var onRequestAdded: ((NetworkRequest) -> Unit)? = null

    val consoleLog     = StringBuilder("// Console\n")
    val deepLog        = StringBuilder("// Deep Inject\n")
    val consoleHistory = mutableListOf<String>()

    fun interceptRequest(request: WebResourceRequest, pageUrl: String) {
        val url    = request.url.toString()
        val method = request.method ?: "GET"
        val hdrs   = request.requestHeaders ?: emptyMap()
        if (isNoise(url)) return
        val streamType = StreamItem.detectType(url)
        val isSub = url.lowercase().endsWith(".vtt") || url.lowercase().endsWith(".srt") || url.lowercase().endsWith(".ass")
        
        val req = NetworkRequest(url=url, method=method, headers=hdrs, pageUrl=pageUrl,
                                 isStream=streamType!=null, streamType=streamType)
                                 
        // B5: Tự động trích xuất Token/JWT từ mọi Request đi qua
        com.aho.streambrowser.feature.devtools.token.TokenAutoExtractor.inspectRequest(req)
        
        addRequest(req)
        if (streamType != null)
            addStream(StreamItem(url=url, type=streamType, source="network", referer=pageUrl))
        if (isSub) {
            // A4: Quét Phụ Đề
            addLog("INFO", "Phát hiện Phụ đề: ${url.substringAfterLast("/")}", "A4_SUBTITLE")
        }
    }

    fun reportFromJs(url: String, source: String, method: String, referer: String) {
        val streamType = StreamItem.detectType(url)
        val req = NetworkRequest(url=url, method=method, headers=emptyMap(), pageUrl=referer,
                                 isStream=streamType!=null, streamType=streamType)
        addRequest(req)
        if (streamType != null)
            addStream(StreamItem(url=url, type=streamType, source=source, referer=referer))
    }

    /** Activity-log feed shown in the Console tab. Only call this from real, verified events. */
    fun addLog(level: String, message: String, source: String = "") {
        synchronized(this) {
            _activityLog.add(0, ActivityLogEntry(level, message, source))
            if (_activityLog.size > 200) _activityLog.removeAt(_activityLog.lastIndex)
        }
    }

    fun onWasmDetected(url: String) {
        val isNew = synchronized(this) { _wasmSeen.add(url) }
        if (isNew) {
            val name = url.substringAfterLast('/').substringBefore('?').take(40)
            addLog("warn", "WebAssembly module loaded ($name) — một phần logic có thể không thấy được qua JS hook", "wasm")
        }
    }

    fun onServiceWorkerDetected(scriptUrl: String) {
        val isNew = synchronized(this) { _swSeen.add(scriptUrl) }
        if (isNew) {
            val name = scriptUrl.substringAfterLast('/').substringBefore('?').take(40)
            addLog("warn", "Service Worker phát hiện ($name) — request bên trong SW nằm ngoài tầm với của mọi hook JS ở trên", "sw")
        }
    }

    /** B4: Add WebSocket message */
    fun addWebSocketMessage(msg: WebSocketMessage) {
        synchronized(this) {
            _wsMessages.add(0, msg)
            if (_wsMessages.size > 200) _wsMessages.removeAt(_wsMessages.lastIndex)
        }
        if (msg.direction == "open") addLog("info", "WebSocket connected: ${msg.wsUrl}", "ws")
    }

    /** A5: Get stream capture timestamp */
    fun getStreamAge(url: String): Long {
        val stream = synchronized(this) { _streams.find { it.url == url } }
        return stream?.let { System.currentTimeMillis() - it.foundAt } ?: -1L
    }

    /** E1+E2: SPA navigation — trigger JS re-inject signal */
    var onSpaNavigation: ((String) -> Unit)? = null
    fun onSpaNavigate(url: String) {
        onSpaNavigation?.invoke(url)
    }

    /** Scan arbitrary text for stream URLs (used for WS messages, response bodies) */
    fun scanTextForStreams(text: String, source: String, pageUrl: String) {
        val normalizedText = text
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
        val patterns = listOf(
            Regex("""((?:https?:)?//[^\s"'<>]+\.(?:m3u8?|m3u9|mpd|mp4|m4v|webm|mkv|flv)[^\s"'<>]*)""", RegexOption.IGNORE_CASE),
            Regex("""["']([^"'<>\s]+\.(?:m3u8?|m3u9|mpd|mp4|m4v|webm|mkv|flv)(?:[^"'<>\s]*))["']""", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { pat ->
            pat.findAll(normalizedText).forEach { m ->
                val raw = m.groupValues[1]
                val absolute = when {
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    pageUrl.startsWith("http") -> runCatching { java.net.URL(java.net.URL(pageUrl), raw).toString() }.getOrElse { raw }
                    else -> raw
                }
                reportFromJs(absolute, source, "GET", pageUrl)
            }
        }
    }


    fun scanManifestForStreams(manifestUrl: String, manifestBody: String, source: String, pageUrl: String) {
        if (manifestBody.isBlank()) return
        val base = runCatching { java.net.URL(manifestUrl) }.getOrNull() ?: return
        val streamType = StreamItem.detectType(manifestUrl)
        if (streamType != null) {
            addStream(StreamItem(url = manifestUrl, type = streamType, source = source, referer = pageUrl))
        }

        var pendingVariantAttrs: Map<String, String>? = null
        manifestBody.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(600)
            .forEach { line ->
                when {
                    line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                        pendingVariantAttrs = parseManifestAttributes(line.substringAfter(':', ""))
                    }
                    line.startsWith("#EXT-X-MEDIA", ignoreCase = true) || line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                        extractManifestAttribute(line, "URI")?.let { uri ->
                            val resolved = resolveUrl(base, uri)
                            reportFromJs(resolved, "${source}_manifest_attr", "GET", pageUrl)
                        }
                    }
                    !line.startsWith("#") -> {
                        val resolved = resolveUrl(base, line)
                        val childType = StreamItem.detectType(resolved)
                        val variantAttrs = pendingVariantAttrs
                        if (variantAttrs != null && childType != null) {
                            addEnrichedStream(resolved, childType, "${source}_variant", pageUrl, variantAttrs)
                        } else if (variantAttrs != null || childType != null) {
                            reportFromJs(resolved, "${source}_manifest", "GET", pageUrl)
                        }
                        pendingVariantAttrs = null
                    }
                }
            }
        if (manifestBody.contains("<MPD", ignoreCase = true)) {
            scanDashManifest(base, manifestBody, source, pageUrl)
        }
    }


    private fun addEnrichedStream(
        url: String,
        type: StreamType,
        source: String,
        pageUrl: String,
        attrs: Map<String, String>
    ) {
        val quality = qualityFromResolution(attrs["RESOLUTION"] ?: "")
        val codec = attrs["CODECS"]?.takeIf { it.isNotBlank() }
        val bitrate = attrs["AVERAGE-BANDWIDTH"]?.toIntOrNull() ?: attrs["BANDWIDTH"]?.toIntOrNull()
        addRequest(NetworkRequest(url = url, method = "GET", headers = emptyMap(), pageUrl = pageUrl, isStream = true, streamType = type))
        addStream(StreamItem(url = url, type = type, source = source, referer = pageUrl, quality = quality, codec = codec, bitrate = bitrate))
    }

    private fun qualityFromResolution(resolution: String): StreamQuality {
        val height = resolution.substringAfter('x', "").toIntOrNull() ?: return StreamQuality.UNKNOWN
        return when {
            height >= 2160 -> StreamQuality.P4K
            height >= 1440 -> StreamQuality.P1440
            height >= 1080 -> StreamQuality.P1080
            height >= 720 -> StreamQuality.P720
            height >= 480 -> StreamQuality.P480
            height >= 360 -> StreamQuality.P360
            height >= 240 -> StreamQuality.P240
            else -> StreamQuality.UNKNOWN
        }
    }

    private fun scanDashManifest(base: java.net.URL, body: String, source: String, pageUrl: String) {
        Regex("""<BaseURL>([^<]+)</BaseURL>""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .take(80)
            .forEach { value ->
                val resolved = resolveUrl(base, value)
                if (StreamItem.detectType(resolved) != null || resolved.startsWith("http")) {
                    reportFromJs(resolved, "${source}_dash_baseurl", "GET", pageUrl)
                }
            }
        scanTextForStreams(body, "${source}_dash", pageUrl)
    }

    private fun parseManifestAttributes(attrs: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        Regex("""([A-Z0-9-]+)=(?:"([^"]*)"|([^,]*))""", RegexOption.IGNORE_CASE)
            .findAll(attrs)
            .forEach { match -> map[match.groupValues[1].uppercase()] = match.groupValues[2].ifEmpty { match.groupValues[3] } }
        return map
    }

    private fun extractManifestAttribute(line: String, name: String): String? {
        val regex = Regex("""(?:^|,)${Regex.escape(name)}=(?:"([^"]+)"|([^,]+))""", RegexOption.IGNORE_CASE)
        val match = regex.find(line) ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
    }

    private fun resolveUrl(base: java.net.URL, value: String): String =
        runCatching { java.net.URL(base, value.trim()).toString() }.getOrElse { value.trim() }

    fun addCryptoKey(capture: CryptoKeyCapture) {
        synchronized(this) {
            if (_cryptoKeys.none { it.key == capture.key && it.algorithm == capture.algorithm }) {
                _cryptoKeys.add(0, capture)
                if (_cryptoKeys.size > 50) _cryptoKeys.removeAt(_cryptoKeys.lastIndex)
                addLog("warn", "CryptoKey captured: ${capture.algorithm}", "crypto")
            }
        }
    }

    fun addResponseBody(url: String, statusCode: Int, contentType: String, body: String) {
        synchronized(this) {
            val idx = _responseBodies.indexOfFirst { it.url == url }
            if (idx >= 0) {
                _responseBodies[idx] = ResponseBodyCapture(url, statusCode, contentType, body)
            } else {
                _responseBodies.add(0, ResponseBodyCapture(url, statusCode, contentType, body))
                if (_responseBodies.size > 20) _responseBodies.removeAt(_responseBodies.lastIndex)
            }
        }
    }

    @Synchronized fun updateRequest(url: String, updated: NetworkRequest) {
        val idx = _requests.indexOfFirst { it.url == url }
        if (idx >= 0) _requests[idx] = updated
    }

    /** Fix: merge in the request body/headers the JS hook captured at XHR.send()/fetch()
     *  call-time — native WebResourceRequest can never expose a POST body, and the JS hook
     *  previously captured this data (xhr.__sb_headers, send(body)/init.body) then discarded it. */
    fun updateRequestPayload(url: String, headersJson: String, body: String) {
        val parsedHeaders = parseHeadersJson(headersJson)
        val old = synchronized(this) { _requests.find { it.url == url } }
        if (old == null) {
            synchronized(this) { _pendingPayloads[url] = parsedHeaders to body.take(4000) }
            return
        }
        updateRequest(url, old.withPayload(parsedHeaders, body))
    }


    private fun parseHeadersJson(headersJson: String): Map<String, String> {
        if (headersJson.isBlank()) return emptyMap()
        return try {
            val obj = org.json.JSONObject(headersJson)
            val map = LinkedHashMap<String, String>()
            obj.keys().forEach { k -> map[k] = obj.optString(k, "") }
            map
        } catch (_: Exception) { emptyMap() }
    }

    private fun NetworkRequest.withPayload(headersFromJs: Map<String, String>, body: String): NetworkRequest = copy(
        requestBody = if (body.isNotBlank()) body.take(4000) else requestBody,
        headers = if (headers.isEmpty() && headersFromJs.isNotEmpty()) headersFromJs else headers
    )

    @Synchronized private fun addRequest(req: NetworkRequest) {
        // G2: Normalize URL for dedup — ignore timestamp/token query params
        val normalizedUrl = normalizeUrlForDedup(req.url)
        val duplicateIndex = _requests.indexOfFirst { normalizeUrlForDedup(it.url) == normalizedUrl }
        if (duplicateIndex >= 0) {
            _requests[duplicateIndex] = _requests[duplicateIndex].mergeWithRicher(req)
            return
        }
        val payload = _pendingPayloads.remove(req.url)
        val enrichedReq = payload?.let { req.withPayload(it.first, it.second) } ?: req
        _requests.add(0, enrichedReq)
        if (_requests.size > 500) _requests.removeAt(_requests.lastIndex)
        onRequestAdded?.invoke(enrichedReq)
    }

    private fun NetworkRequest.mergeWithRicher(candidate: NetworkRequest): NetworkRequest = copy(
        headers = if (headers.isEmpty() && candidate.headers.isNotEmpty()) candidate.headers else headers,
        isStream = isStream || candidate.isStream,
        streamType = streamType ?: candidate.streamType,
        requestBody = requestBody.ifBlank { candidate.requestBody },
        referer = referer.ifBlank { candidate.referer }
    )

    private fun normalizeUrlForDedup(url: String): String {
        return try {
            val noQuery = url.substringBefore("?")
            val query   = url.substringAfter("?", "")
            // Keep only non-volatile params (drop t=, ts=, token=, sign=, _=, etc.)
            val cleanQ = query.split("&").filter { param ->
                val k = param.substringBefore("=").lowercase()
                k !in listOf("t","ts","token","sign","_","cb","cache","v","ver","bust","nonce","rand","timestamp","expires","expire")
            }.joinToString("&")
            if (cleanQ.isEmpty()) noQuery else "$noQuery?$cleanQ"
        } catch (_: Exception) { url }
    }

    @Synchronized private fun addStream(item: StreamItem) {
        val existingIndex = _streams.indexOfFirst { it.url == item.url }
        if (existingIndex >= 0) {
            val existing = _streams[existingIndex]
            val upgraded = existing.mergeWithRicher(item)
            if (upgraded != existing) {
                _streams[existingIndex] = upgraded
                addLog("info", "Stream enriched: ${upgraded.displayName} (via ${item.source})", "stream")
                onStreamFound?.invoke(upgraded)
            }
            return
        }
        _streams.add(0, item)
        val fileName = item.url.substringBefore("?").substringAfterLast("/").take(40)
        addLog("success", "Stream found: $fileName (via ${item.source})", "stream")
        onStreamFound?.invoke(item)
        vibrate()
    }

    private fun StreamItem.mergeWithRicher(candidate: StreamItem): StreamItem = copy(
        source = mergeSource(source, candidate.source),
        referer = referer.ifBlank { candidate.referer },
        quality = if (quality == StreamQuality.UNKNOWN) candidate.quality else quality,
        codec = codec ?: candidate.codec,
        bitrate = bitrate ?: candidate.bitrate
    )

    private fun mergeSource(old: String, new: String): String = when {
        old.isBlank() -> new
        new.isBlank() || old == new -> old
        old.contains(new) -> old
        else -> "$old+$new"
    }

    fun clear() = synchronized(this) {
        _streams.clear(); _requests.clear(); _pendingPayloads.clear()
        _cryptoKeys.clear(); _responseBodies.clear()
        _wsMessages.clear(); _activityLog.clear()
    }
    fun streamCount()  = synchronized(this) { _streams.size  }
    fun requestCount() = synchronized(this) { _requests.size }
    fun wsCount()      = synchronized(this) { _wsMessages.size }
    fun cryptoCount()  = synchronized(this) { _cryptoKeys.size }

    private fun vibrate() {
        context ?: return
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vib.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vib.vibrate(80)
        } catch (_: Exception) {}
    }

    private fun isNoise(url: String): Boolean {
        val l = url.lowercase()
        return l.contains(".css")||l.contains(".woff")||l.contains(".ttf")||l.contains(".eot")||
               l.contains(".ico")||l.contains(".svg")||l.contains(".png")||l.contains(".jpg")||
               l.contains(".jpeg")||l.contains(".gif")||l.contains(".webp")||l.contains(".avif")||
               l.contains("google-analytics")||l.contains("googletagmanager")||
               l.contains("facebook.com/tr")||l.contains("doubleclick")||l.contains("hotjar")||
               l.contains("clarity.ms")||l.contains("beacon")||l.contains("telemetry")
    }
}
