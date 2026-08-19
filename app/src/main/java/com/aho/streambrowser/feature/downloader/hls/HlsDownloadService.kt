package com.aho.streambrowser.feature.downloader.hls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aho.streambrowser.R
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that actually runs an HLS download.
 *
 * Why a service: the previous implementation launched the coroutine on a process-global
 * scope from an adapter click. Swiping the app away or Android killing the process (especially
 * under memory pressure) silently terminated the download with no progress visible to the user.
 * A foreground service keeps the download alive and exposes a real progress notification.
 *
 * The public surface ([HlsDownloader.startDownload]/[stopDownload]/[downloadState]) is unchanged;
 * it just forwards to this service.
 */
class HlsDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var currentTitle: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val streamItem = intent.parcelable<StreamItem>(EXTRA_STREAM) ?: return START_NOT_STICKY
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "stream"
                val remux = intent.getBooleanExtra(EXTRA_REMUX, true)
                startForeground(NOTIF_ID, buildNotification(DownloadStatus.IDLE, 0, "Đang chuẩn bị…"))
                startDownload(streamItem, fileName, remux)
            }
            ACTION_STOP -> {
                job?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(streamItem: StreamItem, fileName: String, remuxToMp4: Boolean) {
        if (job?.isActive == true) return
        currentTitle = fileName
        job = scope.launch {
            HlsEngine.runDownload(
                context = applicationContext,
                streamItem = streamItem,
                fileName = fileName,
                remuxToMp4 = remuxToMp4
            )
        }
        // Bridge engine state -> notification. The public StateFlow is owned by HlsEngine
        // and exposed via HlsDownloader.downloadState for the in-app card.
        scope.launch {
            HlsEngine.state.collect { state ->
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(state.status, state.progress, state.message))
                if (state.status == DownloadStatus.SUCCESS || state.status == DownloadStatus.ERROR) {
                    // Stop the foreground shortly so the completion/error notification stays
                    // visible (autocancel on tap) without holding a foreground slot forever.
                    kotlinx.coroutines.delay(1200)
                    if (state.status == DownloadStatus.SUCCESS) {
                        nm.notify(NOTIF_ID, buildNotification(state.status, 100,
                            "Hoàn tất: ${state.outputPath.substringAfterLast('/')}"))
                    }
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(status: DownloadStatus, progress: Int, text: String): Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, HlsDownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val indeterminate = status == DownloadStatus.FETCHING_MANIFEST ||
            status == DownloadStatus.MERGING || status == DownloadStatus.REMUXING ||
            status == DownloadStatus.DECRYPTING
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (currentTitle.isBlank()) "Tải HLS" else currentTitle.take(40))
            .setSmallIcon(R.drawable.ic_devtools)
            .setContentIntent(openApp)
            .setOngoing(status != DownloadStatus.SUCCESS && status != DownloadStatus.ERROR)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .setContentText(text)

        if (status != DownloadStatus.SUCCESS && status != DownloadStatus.ERROR) {
            builder.addAction(0, "Dừng", stop)
        }
        if (status == DownloadStatus.SUCCESS) {
            builder.setAutoCancel(true).setProgress(0, 0, false)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Tải HLS", NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Tiến trình tải luồng HLS" }
                )
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // Parcelable helper that works across API levels without deprecation warnings.
    private inline fun <reified T : android.os.Parcelable> Intent.parcelable(name: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(name, T::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(name) as? T

    companion object {
        const val ACTION_START = "com.aho.streambrowser.action.START_DOWNLOAD"
        const val ACTION_STOP = "com.aho.streambrowser.action.STOP_DOWNLOAD"
        private const val EXTRA_STREAM = "extra_stream"
        private const val EXTRA_FILE_NAME = "extra_file_name"
        private const val EXTRA_REMUX = "extra_remux"
        private const val CHANNEL_ID = "hls_downloads"
        private const val NOTIF_ID = 4711

        fun start(context: Context, stream: StreamItem, fileName: String, remux: Boolean) {
            val intent = Intent(context, HlsDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STREAM, stream)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_REMUX, remux)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HlsDownloadService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
