package com.aho.streambrowser.feature.downloader.hls

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Remuxes a concatenated MPEG-TS file into an MP4 container using Android's built-in
 * MediaExtractor/MediaMuxer. No native FFmpeg dependency, so APK size is unchanged.
 *
 * Supported: H.264/AVC video + AAC audio (the codecs used by the vast majority of
 * clear/legacy HLS). Unsupported tracks (HEVC, AC-3, MP3 in TS, etc.) cause the remux
 * to abort cleanly so the caller can fall back to keeping the .ts file.
 */
object HlsRemuxer {

    private const val TAG = "HlsRemuxer"
    private const val MAX_BUFFER_SIZE = 2 * 1024 * 1024 // 2 MB

    data class Result(val success: Boolean, val outputFile: File, val reason: String = "")

    fun remuxTsToMp4(context: Context, tsFile: File, targetMp4: File): Result {
        if (!tsFile.exists() || tsFile.length() == 0L) {
            return Result(false, targetMp4, "File .ts trống")
        }

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(tsFile.absolutePath)

            val trackCount = extractor.trackCount
            if (trackCount == 0) return Result(false, targetMp4, "Không tìm thấy track nào trong .ts")

            data class Selected(val index: Int, val format: MediaFormat, val muxerIndex: Int)
            val selected = mutableListOf<Selected>()

            muxer = MediaMuxer(targetMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!isSupported(mime)) {
                    Log.w(TAG, "Bỏ qua track không hỗ trợ remux: $mime")
                    continue
                }
                extractor.selectTrack(i)
                val outIndex = muxer.addTrack(format)
                selected += Selected(i, format, outIndex)
            }

            if (selected.isEmpty()) {
                muxer.release()
                return Result(false, targetMp4, "Không có track tương thích MP4 (thường do HEVC/AC-3)")
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(MAX_BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val currentTrack = extractor.sampleTrackIndex
                val outTrack = selected.firstOrNull { it.index == currentTrack }
                if (outTrack != null) {
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.flags = extractor.sampleFlags
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    muxer.writeSampleData(outTrack.muxerIndex, buffer, bufferInfo)
                }
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            extractor.release()

            if (targetMp4.exists() && targetMp4.length() > 0L) {
                tsFile.delete()
                Result(true, targetMp4, "Đã remux sang MP4")
            } else {
                Result(false, targetMp4, "MP4 tạo ra rỗng")
            }
        } catch (e: Exception) {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            if (targetMp4.exists()) targetMp4.delete()
            Log.w(TAG, "Remux thất bại: ${e.message}")
            Result(false, targetMp4, e.message ?: "Lỗi remux không xác định")
        }
    }

    private fun isSupported(mime: String): Boolean = when {
        mime.equals("video/avc", ignoreCase = true) -> true
        mime.equals("video/mp4v-es", ignoreCase = true) -> true
        mime.equals("audio/mp4a-latm", ignoreCase = true) -> true
        else -> false
    }
}
