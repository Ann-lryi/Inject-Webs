package com.aho.streambrowser.feature.downloader.hls

import android.content.Context
import android.os.Environment
import android.util.Log
import com.aho.streambrowser.model.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Stateless(ish) download engine. The old [HlsDownloader] mixed an object-global job, a
 * StateFlow, and all the I/O logic. That logic now lives here so it can be driven by
 * [HlsDownloadService] (foreground, survives app swipe) while [HlsDownloader] stays as a
 * thin compatibility facade over the same [state].
 */
object HlsEngine {

    private const val TAG = "HlsEngine"
    private const val MAX_PLAYLIST_DEPTH = 3
    private const val MAX_SEGMENT_ATTEMPTS = 3

    private val client by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state

    fun inspectVariants(
        streamItem: StreamItem,
        callback: (List<HlsPlaylistResolver.Variant>) -> Unit
    ) {
        val cookie = android.webkit.CookieManager.getInstance().getCookie(streamItem.url).orEmpty()
        val headers = buildRequestHeaders(streamItem, cookie)
        CoroutineScope(Dispatchers.IO).launch {
            val variants = runCatching {
                HlsPlaylistResolver.parse(fetchPlaylist(streamItem.url, headers), streamItem.url).variants
            }.getOrDefault(emptyList())
            // Caller (StreamAdapter) shows an AlertDialog from the callback, which must run on
            // the main thread.
            withContext(Dispatchers.Main) { callback(variants) }
        }
    }

    /** Runs one download. Updates [state] throughout; throws (caught by caller) on failure. */
    suspend fun runDownload(
        context: Context,
        streamItem: StreamItem,
        fileName: String,
        remuxToMp4: Boolean = true
    ) {
        var tempDir: File? = null
        var partialOutput: File? = null
        try {
            _state.value = DownloadState(DownloadStatus.FETCHING_MANIFEST, message = "Đang phân tích M3U8 Manifest...")

            val cookie = android.webkit.CookieManager.getInstance().getCookie(streamItem.url).orEmpty()
            val requestHeaders = buildRequestHeaders(streamItem, cookie)
            val decryptor = HlsAesDecryptor(client, requestHeaders)

            var playlistUrl = streamItem.url
            var playlist = HlsPlaylistResolver.Playlist()
            for (depth in 0 until MAX_PLAYLIST_DEPTH) {
                val content = fetchPlaylist(playlistUrl, requestHeaders)
                playlist = HlsPlaylistResolver.parse(content, playlistUrl)
                playlist.unsupportedReason?.let { reason -> throw Exception(reason) }
                val variant = playlist.variants.maxByOrNull { it.bandwidth } ?: break
                if (depth == MAX_PLAYLIST_DEPTH - 1) throw Exception("Master playlist lồng quá sâu")
                playlistUrl = variant.url
            }

            val segments = buildList {
                playlist.initSegment?.let(::add)
                addAll(playlist.segments)
            }
            if (segments.isEmpty()) throw Exception("Không tìm thấy phân mảnh trong m3u8")

            val needsDecrypt = segments.any { it.key != null }
            val isFmp4 = playlist.initSegment != null ||
                segments.any { it.url.substringBefore('?').endsWith(".m4s", true) }
            val isMpegTs = !isFmp4

            val moviesDir = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)) {
                "Không có thư mục Movies"
            }
            val downloadDir = File(moviesDir, "HLS_${UUID.randomUUID()}")
            tempDir = downloadDir
            if (!downloadDir.mkdirs()) throw Exception("Không tạo được thư mục tạm")

            val baseName = fileName.replace(Regex("[^a-zA-Z0-9.-]"), "_").trim('.').ifBlank { "stream" }
            val finalMp4 = File(moviesDir, uniqueName(moviesDir, baseName, "mp4"))
            val finalTs = File(moviesDir, uniqueName(moviesDir, baseName, "ts"))

            val concatenated = File(downloadDir, "concat.${if (isFmp4) "mp4" else "ts"}")
            partialOutput = when {
                isFmp4 -> finalMp4
                !remuxToMp4 -> finalTs
                else -> concatenated
            }

            _state.value = DownloadState(
                status = DownloadStatus.DOWNLOADING_SEGMENTS,
                totalSegments = segments.size,
                message = "Bắt đầu tải ${segments.size} phân mảnh..." + if (needsDecrypt) " (AES-128)" else ""
            )

            var downloadedCount = 0
            val tempFiles = arrayOfNulls<File>(segments.size)

            coroutineScope {
                val parallelism = 4
                segments.withIndex().chunked(parallelism).forEach { chunk ->
                    ensureActive()
                    chunk.map { (index, segment) ->
                        async {
                            val ext = when {
                                segment.byteRange != null -> ".bin"
                                segment.url.substringBefore('?').endsWith(".m4s", true) -> ".m4s"
                                else -> ".ts"
                            }
                            val rawFile = File(downloadDir, "segment_$index$ext")
                            downloadSegment(segment, rawFile, requestHeaders, decryptor)
                            tempFiles[index] = rawFile
                        }
                    }.awaitAll()

                    downloadedCount += chunk.size
                    val percent = ((downloadedCount.toFloat() / segments.size) * 100).toInt()
                    _state.value = _state.value.copy(
                        status = if (needsDecrypt) DownloadStatus.DECRYPTING else DownloadStatus.DOWNLOADING_SEGMENTS,
                        progress = percent,
                        downloadedSegments = downloadedCount,
                        message = "Đang tải: $percent% ($downloadedCount/${segments.size})"
                    )
                }
            }

            val mergeTarget = partialOutput!!
            _state.value = DownloadState(
                status = DownloadStatus.MERGING, progress = 100,
                message = "Đang ghép ${segments.size} phân mảnh..."
            )
            FileOutputStream(mergeTarget, false).use { output ->
                for (i in tempFiles.indices) {
                    val f = tempFiles[i]
                    if (f != null && f.exists()) {
                        f.inputStream().use { it.copyTo(output) }
                        f.delete()
                    }
                }
            }

            val finalFile: File = when {
                isFmp4 -> finalMp4
                !remuxToMp4 -> finalTs
                else -> {
                    _state.value = DownloadState(
                        status = DownloadStatus.REMUXING, progress = 100,
                        message = "Đang remux MPEG-TS sang MP4..."
                    )
                    val r = HlsRemuxer.remuxTsToMp4(context, mergeTarget, finalMp4)
                    if (r.success) finalMp4 else {
                        mergeTarget.renameTo(finalTs)
                        _state.value = _state.value.copy(message = "Remux không hỗ trợ codec, đã lưu .ts")
                        finalTs
                    }
                }
            }

            downloadDir.deleteRecursively()
            tempDir = null
            partialOutput = null

            _state.value = DownloadState(
                status = DownloadStatus.SUCCESS, progress = 100,
                message = "Tải thành công!", outputPath = finalFile.absolutePath
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            tempDir?.deleteRecursively(); partialOutput?.delete()
            _state.value = DownloadState(DownloadStatus.ERROR, message = "Đã hủy tải xuống.")
        } catch (e: Exception) {
            tempDir?.deleteRecursively(); partialOutput?.delete()
            Log.e(TAG, "Download failed", e)
            _state.value = DownloadState(DownloadStatus.ERROR, message = "Lỗi: ${e.message}")
        }
    }

    private fun fetchPlaylist(url: String, headers: okhttp3.Headers): String =
        client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Không thể tải m3u8: ${response.code}")
            response.body?.string() ?: throw Exception("Nội dung m3u8 trống")
        }

    private suspend fun downloadSegment(
        segment: HlsPlaylistResolver.Segment,
        outputFile: File,
        headers: okhttp3.Headers,
        decryptor: HlsAesDecryptor
    ) {
        var lastError: Exception? = null
        repeat(MAX_SEGMENT_ATTEMPTS) { attempt ->
            try {
                val reqBuilder = Request.Builder().url(segment.url).headers(headers)
                if (!segment.byteRange.isNullOrBlank()) reqBuilder.header("Range", segment.byteRange)
                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                    val bytes = response.body?.bytes() ?: throw Exception("Body rỗng")
                    val finalBytes = if (segment.key != null) decryptor.decrypt(segment, bytes) else bytes
                    FileOutputStream(outputFile, false).use { it.write(finalBytes) }
                }
                return
            } catch (e: Exception) {
                lastError = e
                outputFile.delete()
                if (attempt + 1 < MAX_SEGMENT_ATTEMPTS) delay(400L * (attempt + 1))
            }
        }
        throw Exception("Lỗi tải fragment sau $MAX_SEGMENT_ATTEMPTS lần: ${lastError?.message}")
    }

    private fun buildRequestHeaders(stream: StreamItem, cookie: String): okhttp3.Headers =
        okhttp3.Headers.Builder().apply {
            add("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            if (stream.referer.isNotBlank()) add("Referer", stream.referer)
            if (cookie.isNotBlank()) add("Cookie", cookie)
            runCatching {
                val u = java.net.URL(stream.referer)
                add("Origin", "${u.protocol}://${u.host}")
            }
        }.build()

    private fun uniqueName(directory: File, baseName: String, extension: String): String {
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else " ($index)"
            val name = "$baseName$suffix.$extension"
            if (!File(directory, name).exists()) return name
            index++
        }
    }
}
