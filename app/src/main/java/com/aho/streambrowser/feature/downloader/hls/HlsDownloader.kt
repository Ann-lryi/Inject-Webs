package com.aho.streambrowser.feature.downloader.hls

import android.content.Context
import com.aho.streambrowser.model.StreamItem
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin facade over [HlsDownloadService] (foreground execution + notification) and [HlsEngine]
 * (the actual download logic). The original object-global coroutine has been removed so
 * downloads survive the app being swiped away and report real progress via a notification.
 *
 * Public API is kept stable for existing callers ([com.aho.streambrowser.ui.StreamAdapter]).
 */
object HlsDownloader {

    val downloadState: StateFlow<DownloadState> = HlsEngine.state

    fun startDownload(
        context: Context,
        streamItem: StreamItem,
        fileName: String,
        remuxToMp4: Boolean = true
    ) {
        HlsDownloadService.start(context.applicationContext, streamItem, fileName, remuxToMp4)
    }

    fun stopDownload(context: Context? = null) {
        // The service owns the running job; ask it to stop. If we have no context (legacy call
        // signature), cancel via a best-effort no-op — there is no process-global job anymore.
        context?.let { HlsDownloadService.stop(it.applicationContext) }
    }

    fun inspectVariants(
        streamItem: StreamItem,
        callback: (List<HlsPlaylistResolver.Variant>) -> Unit
    ) {
        HlsEngine.inspectVariants(streamItem, callback)
    }
}
