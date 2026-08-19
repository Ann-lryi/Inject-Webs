package com.aho.streambrowser.feature.downloader.hls

import android.content.Context
import android.os.Environment
import android.util.Log
import com.aho.streambrowser.model.StreamItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class DownloadStatus {
    IDLE, FETCHING_MANIFEST, DOWNLOADING_SEGMENTS, DECRYPTING, REMUXING, MERGING, SUCCESS, ERROR
}

data class DownloadState(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Int = 0,
    val totalSegments: Int = 0,
    val downloadedSegments: Int = 0,
    val message: String = "",
    val outputPath: String = ""
)

object HlsDownloader {

    private const val TAG = "HlsDownloader"

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState

    private var downloadJob: Job? = null

    // OkHttp Client tối ưu cho việc tải file liên tục
    private val client by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Bắt đầu tiến trình tải M3U8.
     * @param streamItem Thông tin luồng (Chứa URL m3u8)
     * @param fileName Tên file đầu ra (VD: "Phim_Hay.mp4")
     * @param remuxToMp4 Nếu true, MPEG-TS sẽ được remux sang MP4 bằng MediaMuxer (không cần FFmpeg).
     *                   fMP4/CMAF playlists luôn ghi đuôi .mp4 và nối trực tiếp.
     */
    fun startDownload(
        context: Context,
        streamItem: StreamItem,
        fileName: String,
        remuxToMp4: Boolean = true
    ) {
        if (downloadJob?.isActive == true) {
            _downloadState.value = DownloadState(DownloadStatus.ERROR, message = "Một tiến trình tải đang diễn ra!")
            return
        }

        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            var tempDir: File? = null
            var partialOutput: File? = null
            try {
                _downloadState.value = DownloadState(DownloadStatus.FETCHING_MANIFEST, message = "Đang phân tích M3U8 Manifest...")

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

                // Include init segment (fMP4/CMAF) ahead of media segments.
                val segments = buildList {
                    playlist.initSegment?.let(::add)
                    addAll(playlist.segments)
                }
                if (segments.isEmpty()) throw Exception("Không tìm thấy phân mảnh trong m3u8")

                val needsDecrypt = segments.any { decryptor.hasKey(it) }
                val isFmp4 = playlist.initSegment != null ||
                    segments.any { it.url.substringBefore('?').endsWith(".m4s", true) }
                val isMpegTs = !isFmp4

                // 3. Chuẩn bị thư mục tải (Dùng Context.getExternalFilesDir để tránh lỗi Scoped Storage)
                val moviesDir = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)) { "Không có thư mục Movies" }
                val downloadDir = File(moviesDir, "HLS_${UUID.randomUUID()}")
                tempDir = downloadDir
                if (!downloadDir.mkdirs()) throw Exception("Không tạo được thư mục tạm")

                val baseName = fileName.replace(Regex("[^a-zA-Z0-9.-]"), "_").trim('.').ifBlank { "stream" }
                // fMP4/CMAF concatenates to a valid MP4; MPEG-TS concatenation produces .ts which we
                // then remux to .mp4 when remuxToMp4 is true and the tracks are AVC/AAC.
                val finalMp4 = File(moviesDir, uniqueName(moviesDir, baseName, "mp4"))
                val finalTs = File(moviesDir, uniqueName(moviesDir, baseName, "ts"))

                // Decide output target. We always write the raw concatenation to a working file,
                // then either rename/remux it into the final file.
                val concatenated = File(downloadDir, "concat.${if (isFmp4) "mp4" else "ts"}")
                partialOutput = if (isFmp4 || (isMpegTs && !remuxToMp4)) {
                    if (isFmp4) finalMp4 else finalTs
                } else concatenated

                _downloadState.value = DownloadState(
                    status = DownloadStatus.DOWNLOADING_SEGMENTS,
                    totalSegments = segments.size,
                    message = "Bắt đầu tải ${segments.size} phân mảnh..." +
                        if (needsDecrypt) " (AES-128)" else ""
                )

                // 4. TẢI ĐA LUỒNG — 4 kết nối song song, có retry, giải mã AES-128 ngay sau khi tải.
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
                        _downloadState.value = _downloadState.value.copy(
                            progress = percent,
                            downloadedSegments = downloadedCount,
                            message = "Đang tải: $percent% ($downloadedCount/${segments.size})"
                        )
                    }
                }

                // 5. NỐI FILE theo đúng thứ tự.
                val mergeTarget = partialOutput!!
                _downloadState.value = DownloadState(
                    status = DownloadStatus.MERGING,
                    progress = 100,
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

                // 6. (Tùy chọn) Remux MPEG-TS -> MP4 bằng MediaExtractor/MediaMuxer của Android.
                val finalFile: File = when {
                    isFmp4 -> finalMp4.also {
                        // concat.mp4 IS the final output when partialOutput was set to finalMp4.
                        if (mergeTarget != it) mergeTarget.renameTo(it)
                    }
                    !remuxToMp4 -> finalTs.also {
                        if (mergeTarget != it) mergeTarget.renameTo(it)
                    }
                    else -> {
                        _downloadState.value = DownloadState(
                            status = DownloadStatus.REMUXING,
                            progress = 100,
                            message = "Đang remux MPEG-TS sang MP4..."
                        )
                        val r = HlsRemuxer.remuxTsToMp4(context, mergeTarget, finalMp4)
                        if (r.success) finalMp4 else {
                            // Fallback: keep the concatenated .ts so the user doesn't lose the download.
                            mergeTarget.renameTo(finalTs)
                            _downloadState.value = _downloadState.value.copy(
                                message = "Remux không hỗ trợ codec, đã lưu .ts"
                            )
                            finalTs
                        }
                    }
                }

                // Dọn dẹp toàn bộ thư mục tạm.
                downloadDir.deleteRecursively()
                tempDir = null
                partialOutput = null

                _downloadState.value = DownloadState(
                    status = DownloadStatus.SUCCESS,
                    progress = 100,
                    message = "Tải thành công!",
                    outputPath = finalFile.absolutePath
                )

            } catch (e: CancellationException) {
                tempDir?.deleteRecursively(); partialOutput?.delete()
                _downloadState.value = DownloadState(DownloadStatus.ERROR, message = "Đã hủy tải xuống.")
            } catch (e: Exception) {
                tempDir?.deleteRecursively(); partialOutput?.delete()
                Log.e(TAG, "Download failed", e)
                _downloadState.value = DownloadState(DownloadStatus.ERROR, message = "Lỗi: ${e.message}")
            }
        }
    }

    /** Loads master-playlist variants for UI choice. No segment is downloaded here. */
    fun inspectVariants(streamItem: StreamItem, callback: (List<HlsPlaylistResolver.Variant>) -> Unit) {
        val cookie = android.webkit.CookieManager.getInstance().getCookie(streamItem.url).orEmpty()
        val headers = buildRequestHeaders(streamItem, cookie)
        CoroutineScope(Dispatchers.IO).launch {
            val variants = runCatching {
                HlsPlaylistResolver.parse(fetchPlaylist(streamItem.url, headers), streamItem.url).variants
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { callback(variants) }
        }
    }

    fun stopDownload() {
        downloadJob?.cancel()
        _downloadState.value = DownloadState(DownloadStatus.IDLE, message = "Đã dừng tải.")
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
                    val finalBytes = if (decryptor.hasKey(segment)) {
                        decryptor.decrypt(segment, bytes)
                    } else bytes
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

    private const val MAX_PLAYLIST_DEPTH = 3
    private const val MAX_SEGMENT_ATTEMPTS = 3
}
