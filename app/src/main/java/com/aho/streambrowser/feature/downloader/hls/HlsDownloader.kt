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
    IDLE, FETCHING_MANIFEST, DOWNLOADING_SEGMENTS, MERGING, SUCCESS, ERROR
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
     */
    fun startDownload(context: Context, streamItem: StreamItem, fileName: String) {
        if (downloadJob?.isActive == true) {
            _downloadState.value = DownloadState(DownloadStatus.ERROR, message = "Một tiến trình tải đang diễn ra!")
            return
        }

        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            var tempDir: File? = null
            var partialOutput: File? = null
            try {
                _downloadState.value = DownloadState(DownloadStatus.FETCHING_MANIFEST, message = "Đang phân tích M3U8 Manifest...")
                
                // Resolve master playlists (best available bandwidth) and parse the clear HLS subset.
                val cookie = android.webkit.CookieManager.getInstance().getCookie(streamItem.url).orEmpty()
                val requestHeaders = buildRequestHeaders(streamItem, cookie)
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
                val segmentUrls = buildList {
                    playlist.initSegmentUrl?.let(::add)
                    addAll(playlist.segmentUrls)
                }
                if (segmentUrls.isEmpty()) throw Exception("Không tìm thấy phân mảnh trong m3u8")

                // 3. Chuẩn bị thư mục tải (Dùng Context.getExternalFilesDir để tránh lỗi Scoped Storage Android 11+)
                val moviesDir = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)) { "Không có thư mục Movies" }
                // Unique temp directory prevents stale segments from a cancelled download being merged later.
                val downloadDir = File(moviesDir, "HLS_${UUID.randomUUID()}")
                tempDir = downloadDir
                if (!downloadDir.mkdirs()) throw Exception("Không tạo được thư mục tạm")

                val baseName = fileName.replace(Regex("[^a-zA-Z0-9.-]"), "_").trim('.').ifBlank { "stream" }
                // Concatenated MPEG-TS is not an MP4 container. Name the output honestly;
                // CMAF/fMP4 playlists with an init segment remain .mp4-compatible.
                val extension = if (playlist.initSegmentUrl != null || segmentUrls.any { it.substringBefore('?').endsWith(".m4s", true) }) "mp4" else "ts"
                val finalOutputFile = uniqueOutputFile(moviesDir, baseName, extension)
                partialOutput = finalOutputFile

                _downloadState.value = DownloadState(
                    status = DownloadStatus.DOWNLOADING_SEGMENTS,
                    totalSegments = segmentUrls.size,
                    message = "Bắt đầu tải ${segmentUrls.size} phân mảnh..."
                )

                // 4. TẢI ĐA LUỒNG (Multi-Threaded Download)
                // Giới hạn chạy song song 4 file cùng lúc để tránh bị Server chặn IP (Rate Limit)
                var downloadedCount = 0
                val tempFiles = arrayOfNulls<File>(segmentUrls.size)
                
                // Dùng Semaphore/Channel để điều phối luồng hoặc chia khối
                coroutineScope {
                    val parallelism = 4
                    val chunkedList = segmentUrls.withIndex().chunked(parallelism)
                    
                    for (chunk in chunkedList) {
                        ensureActive() // Kiểm tra nếu user ấn Hủy
                        
                        val deferreds = chunk.map { (index, url) ->
                            async {
                                val tempFile = File(downloadDir, "segment_$index.ts")
                                downloadSegment(url, tempFile, requestHeaders)
                                tempFiles[index] = tempFile
                            }
                        }
                        deferreds.awaitAll()
                        
                        downloadedCount += chunk.size
                        val progressPercent = ((downloadedCount.toFloat() / segmentUrls.size) * 100).toInt()
                        _downloadState.value = _downloadState.value.copy(
                            progress = progressPercent,
                            downloadedSegments = downloadedCount,
                            message = "Đang tải: $progressPercent% ($downloadedCount/${segmentUrls.size})"
                        )
                    }
                }

                // 5. NỐI FILE (Muxing TS files)
                _downloadState.value = DownloadState(DownloadStatus.MERGING, message = "Đang ghép các phân mảnh thành MP4...")
                
                FileOutputStream(finalOutputFile, false).use { outputStream ->
                    for (i in tempFiles.indices) {
                        val tempFile = tempFiles[i]
                        if (tempFile != null && tempFile.exists()) {
                            tempFile.inputStream().use { input ->
                                input.copyTo(outputStream)
                            }
                            // Xóa file rác ngay lập tức để tiết kiệm bộ nhớ
                            tempFile.delete()
                        }
                    }
                }

                // Dọn dẹp toàn bộ thư mục Temp, including partially-created segment files.
                downloadDir.deleteRecursively()
                tempDir = null
                partialOutput = null

                _downloadState.value = DownloadState(
                    status = DownloadStatus.SUCCESS,
                    progress = 100,
                    message = "Tải thành công!",
                    outputPath = finalOutputFile.absolutePath
                )

            } catch (e: CancellationException) {
                tempDir?.deleteRecursively(); partialOutput?.delete()
                _downloadState.value = DownloadState(DownloadStatus.ERROR, message = "Đã hủy tải xuống.")
            } catch (e: Exception) {
                tempDir?.deleteRecursively(); partialOutput?.delete()
                e.printStackTrace()
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

    private suspend fun downloadSegment(url: String, outputFile: File, headers: okhttp3.Headers) {
        var lastError: Exception? = null
        repeat(MAX_SEGMENT_ATTEMPTS) { attempt ->
            try {
                client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                    val input = response.body?.byteStream() ?: throw Exception("Body rỗng")
                    FileOutputStream(outputFile, false).use { input.copyTo(it) }
                }
                return
            } catch (e: Exception) {
                lastError = e; outputFile.delete()
                if (attempt + 1 < MAX_SEGMENT_ATTEMPTS) delay(400L * (attempt + 1))
            }
        }
        throw Exception("Lỗi tải fragment sau $MAX_SEGMENT_ATTEMPTS lần: ${lastError?.message}")
    }

    private fun buildRequestHeaders(stream: StreamItem, cookie: String): okhttp3.Headers = okhttp3.Headers.Builder().apply {
        add("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        if (stream.referer.isNotBlank()) add("Referer", stream.referer)
        if (cookie.isNotBlank()) add("Cookie", cookie)
        runCatching {
            val u = java.net.URL(stream.referer)
            add("Origin", "${u.protocol}://${u.host}")
        }
    }.build()

    private fun uniqueOutputFile(directory: File, baseName: String, extension: String): File {
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else " ($index)"
            val file = File(directory, "$baseName$suffix.$extension")
            if (!file.exists()) return file
            index++
        }
    }

    private const val MAX_PLAYLIST_DEPTH = 3
    private const val MAX_SEGMENT_ATTEMPTS = 3

}