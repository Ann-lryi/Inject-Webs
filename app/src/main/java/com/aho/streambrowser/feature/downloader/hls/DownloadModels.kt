package com.aho.streambrowser.feature.downloader.hls

/**
 * Lifecycle states for an HLS download, used by [HlsEngine]/[HlsDownloadService] to drive both
 * the foreground notification and the in-app progress card.
 */
enum class DownloadStatus {
    IDLE,
    FETCHING_MANIFEST,
    DOWNLOADING_SEGMENTS,
    DECRYPTING,
    REMUXING,
    MERGING,
    SUCCESS,
    ERROR
}

/**
 * Snapshot of an in-progress or completed download.
 */
data class DownloadState(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Int = 0,
    val totalSegments: Int = 0,
    val downloadedSegments: Int = 0,
    val message: String = "",
    val outputPath: String = ""
)
