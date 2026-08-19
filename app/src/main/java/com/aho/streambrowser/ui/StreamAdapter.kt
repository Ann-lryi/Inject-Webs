package com.aho.streambrowser.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aho.streambrowser.databinding.ItemStreamBinding
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.model.StreamType

class StreamAdapter(
    private val onCopy:  (StreamItem) -> Unit,
    private val onPlay:  (StreamItem) -> Unit,
    private val onShare: (StreamItem) -> Unit
) : ListAdapter<StreamItem, StreamAdapter.VH>(DIFF) {

    inner class VH(private val b: ItemStreamBinding) : RecyclerView.ViewHolder(b.root) {
        private fun copyToClipboard(text: String) {
            val ctx = b.root.context
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("stream_url", text))
            Toast.makeText(ctx, "Đã copy: ${text.take(50)}...", Toast.LENGTH_SHORT).show()
        }

        fun bind(item: StreamItem) {
            b.tvType.text   = item.displayName.ifBlank { item.label }
            b.tvSource.text = buildString {
                append("via ${item.source}")
                item.bitrate?.takeIf { it > 0 }?.let { append(" • ${it / 1000}kbps") }
            }
            b.tvUrl.text    = item.url
            // Enable text selection on URL
            b.tvUrl.setTextIsSelectable(true)
            // Allow long-press copy anywhere in the item
            b.root.setOnLongClickListener {
                copyToClipboard(item.url)
                true
            }
            val (bg, fg) = BADGE_COLORS[item.type] ?: BADGE_COLORS[StreamType.OTHER]!!
            b.tvType.setBackgroundColor(bg)
            b.tvType.setTextColor(fg)
            b.btnCopy.setOnClickListener  { onCopy(item)  }
            b.btnPlay.setOnClickListener  { onPlay(item)  }
            b.btnShare.setOnClickListener { onShare(item) }

            // Download: HLS via the foreground HLS downloader; MP4/WebM/FLV via the direct
            // progressive downloader. Other protocols aren't downloadable yet.
            val canDownloadHls = item.type == StreamType.HLS
            val canDownloadDirect = com.aho.streambrowser.feature.downloader.ProgressiveDownloader.isSupported(item)
            val canDownload = canDownloadHls || canDownloadDirect
            b.btnDownload.isEnabled = canDownload
            b.btnDownload.alpha = if (canDownload) 1f else 0.4f
            b.btnDownload.text = when {
                canDownloadHls -> "Tải HLS"
                canDownloadDirect -> "Tải file"
                else -> "Không tải được"
            }
            b.btnDownload.setOnClickListener {
                when {
                    canDownloadHls -> chooseHlsQuality(item)
                    canDownloadDirect -> {
                        val ctx = b.root.context
                        android.widget.Toast.makeText(ctx, "Bắt đầu tải file…", android.widget.Toast.LENGTH_SHORT).show()
                        com.aho.streambrowser.feature.downloader.ProgressiveDownloader.download(
                            ctx, item, "Downloaded_${System.currentTimeMillis()}"
                        ) { ok, path, err ->
                            (ctx as? android.app.Activity)?.runOnUiThread {
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (ok) "Đã tải: ${path?.substringAfterLast('/')}" else "Lỗi tải: $err",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
        }

        private fun chooseHlsQuality(item: StreamItem) {
            val context = b.root.context
            val start: (StreamItem) -> Unit = { selected ->
                Toast.makeText(context, "Bắt đầu tải HLS…", Toast.LENGTH_SHORT).show()
                ensureNotificationPermissionThen(context) {
                    com.aho.streambrowser.feature.downloader.hls.HlsDownloader.startDownload(
                        context, selected, "Downloaded_Video_${System.currentTimeMillis()}"
                    )
                }
            }
            Toast.makeText(context, "Đang đọc quality từ master playlist…", Toast.LENGTH_SHORT).show()
            com.aho.streambrowser.feature.downloader.hls.HlsDownloader.inspectVariants(item) { variants ->
                if (variants.isEmpty()) { start(item); return@inspectVariants }
                val labels = mutableListOf("Tự động — cao nhất")
                labels += variants.map { variant ->
                    val resolution = variant.resolution ?: "không rõ độ phân giải"
                    "$resolution · ${variant.bandwidth / 1000} kbps"
                }
                AlertDialog.Builder(context).setTitle("Chọn chất lượng HLS")
                    .setItems(labels.toTypedArray()) { _, index ->
                        start(if (index == 0) item else item.copy(url = variants[index - 1].url))
                    }
                    .setNegativeButton("Huỷ", null).show()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemStreamBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        /** On Android 13+, posting the download notification requires runtime permission. */
        private const val REQ_NOTIF = 4101

        /** Asks for POST_NOTIFICATIONS when needed; always runs [then] (download still works
         *  without the notification, it just won't show progress). */
        fun ensureNotificationPermissionThen(context: Context, then: () -> Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                val activity = context as? Activity
                if (activity != null) {
                    // We don't gate the download on the result: fire the request and proceed.
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                }
            }
            then()
        }

        // Pre-parsed badge color pairs (background, foreground) per stream type so we don't
        // re-parse hex strings on every bind/rebind of a RecyclerView item.
        private val BADGE_COLORS: Map<StreamType, Pair<Int, Int>> = mapOf(
            StreamType.HLS       to (Color.parseColor("#1B5E20") to Color.parseColor("#C8E6C9")),
            StreamType.MP4       to (Color.parseColor("#0D47A1") to Color.parseColor("#BBDEFB")),
            StreamType.DASH      to (Color.parseColor("#4A148C") to Color.parseColor("#E1BEE7")),
            StreamType.FLV       to (Color.parseColor("#BF360C") to Color.parseColor("#FFCCBC")),
            StreamType.WEBM      to (Color.parseColor("#006064") to Color.parseColor("#B2EBF2")),
            StreamType.M3U9      to (Color.parseColor("#7B5800") to Color.parseColor("#FFECB3")),
            StreamType.WEBSOCKET to (Color.parseColor("#E65100") to Color.parseColor("#FFE0B2")),
            StreamType.RTMP      to (Color.parseColor("#880E4F") to Color.parseColor("#F8BBD0")),
            StreamType.OTHER     to (Color.parseColor("#37474F") to Color.parseColor("#ECEFF1"))
        )

        private val DIFF = object : DiffUtil.ItemCallback<StreamItem>() {
            override fun areItemsTheSame(a: StreamItem, b: StreamItem) = a.url == b.url
            override fun areContentsTheSame(a: StreamItem, b: StreamItem) = a == b
        }
    }
}
