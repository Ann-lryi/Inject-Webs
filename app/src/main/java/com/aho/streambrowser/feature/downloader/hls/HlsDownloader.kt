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
import java.net.URI

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
            try {
                _downloadState.value = DownloadState(DownloadStatus.FETCHING_MANIFEST, message = "Đang phân tích M3U8 Manifest...")
                
                // 1. Tải và phân tích file m3u8
                val request = Request.Builder().url(streamItem.url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw Exception("Không thể tải m3u8: ${response.code}")
                
                val m3u8Content = response.body?.string() ?: throw Exception("Nội dung m3u8 trống")
                
                // BẮT LỖI BẢO MẬT (DRM/ENCRYPTION)
                if (m3u8Content.contains("#EXT-X-KEY")) {
                    throw Exception("Lỗi: Luồng HLS này đã bị Mã hóa (Encrypted). HlsDownloader hiện tại chỉ hỗ trợ luồng Clear-Text.")
                }

                // 2. Trích xuất danh sách các file phân mảnh (.ts)
                val lines = m3u8Content.split("\n")
                val segmentUrls = mutableListOf<String>()
                
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        // Xử lý URL tương đối (Relative Path) thành URL tuyệt đối (Absolute Path)
                        val absoluteUrl = URI(streamItem.url).resolve(trimmed).toString()
                        segmentUrls.add(absoluteUrl)
                    }
                }

                if (segmentUrls.isEmpty()) throw Exception("Không tìm thấy phân mảnh .ts nào trong m3u8")

                // 3. Chuẩn bị thư mục tải (Dùng Context.getExternalFilesDir để tránh lỗi Scoped Storage Android 11+)
                val downloadDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "HLS_Temp")
                if (!downloadDir.exists()) downloadDir.mkdirs()
                
                val safeFileName = fileName.replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".mp4"
                val finalOutputFile = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), safeFileName)

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
                                downloadSegment(url, tempFile)
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
                
                FileOutputStream(finalOutputFile, true).use { outputStream ->
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

                // Dọn dẹp thư mục Temp
                downloadDir.delete()

                _downloadState.value = DownloadState(
                    status = DownloadStatus.SUCCESS,
                    progress = 100,
                    message = "Tải thành công!",
                    outputPath = finalOutputFile.absolutePath
                )

            } catch (e: CancellationException) {
                _downloadState.value = DownloadState(DownloadStatus.ERROR, message = "Đã hủy tải xuống.")
            } catch (e: Exception) {
                e.printStackTrace()
                _downloadState.value = DownloadState(DownloadStatus.ERROR, message = "Lỗi: ${e.message}")
            }
        }
    }

    fun stopDownload() {
        downloadJob?.cancel()
        _downloadState.value = DownloadState(DownloadStatus.IDLE, message = "Đã dừng tải.")
    }

    private fun downloadSegment(url: String, outputFile: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Lỗi tải Fragment: ${response.code}")
            
            val inputStream = response.body?.byteStream() ?: throw Exception("Body rỗng")
            FileOutputStream(outputFile).use { output ->
                inputStream.copyTo(output)
            }
        }
    }
}