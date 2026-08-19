package com.aho.streambrowser.feature.downloader

import android.content.Context
import android.os.Environment
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.model.StreamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads a single progressive (non-adaptive) file — MP4/WebM/FLV and subtitles (.vtt/.srt).
 *
 * Previously only HLS could be saved from the stream list. This covers the "direct file" case by
 * streaming bytes to the Movies/Subtitles directory using the same Referer/Cookie/Origin headers
 * the page used, so gated CDNs that check those headers still work.
 */
object ProgressiveDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun isSupported(item: StreamItem): Boolean = when (item.type) {
        StreamType.MP4, StreamType.WEBM, StreamType.FLV -> true
        else -> false
    }

    fun isSubtitle(url: String): Boolean {
        val l = url.lowercase().substringBefore('?')
        return l.endsWith(".vtt") || l.endsWith(".srt") || l.endsWith(".ass")
    }

    fun download(
        context: Context,
        item: StreamItem,
        fileName: String,
        onResult: (success: Boolean, path: String?, error: String?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cookie = android.webkit.CookieManager.getInstance().getCookie(item.url).orEmpty()
                val headers = okhttp3.Headers.Builder().apply {
                    add("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    if (item.referer.isNotBlank()) add("Referer", item.referer)
                    if (cookie.isNotBlank()) add("Cookie", cookie)
                    runCatching {
                        val u = java.net.URL(item.referer)
                        add("Origin", "${u.protocol}://${u.host}")
                    }
                }.build()

                val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
                val safe = fileName.replace(Regex("[^a-zA-Z0-9.-]"), "_").ifBlank { "download" }
                val out = File(dir, uniqueName(dir, safe, extensionFor(item)))

                client.newCall(Request.Builder().url(item.url).headers(headers).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                    resp.body!!.byteStream().use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                }
                onResult(true, out.absolutePath, null)
            } catch (e: Exception) {
                onResult(false, null, e.message)
            }
        }
    }

    private fun extensionFor(item: StreamItem): String = when (item.type) {
        StreamType.WEBM -> "webm"
        StreamType.FLV -> "flv"
        else -> "mp4"
    }

    private fun uniqueName(dir: File, base: String, ext: String): String {
        var i = 0
        while (true) {
            val name = if (i == 0) "$base.$ext" else "$base ($i).$ext"
            if (!File(dir, name).exists()) return name
            i++
        }
    }
}
