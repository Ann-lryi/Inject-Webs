package com.aho.streambrowser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
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
            val (bg, fg) = when (item.type) {
                StreamType.HLS       -> Color.parseColor("#1B5E20") to Color.parseColor("#C8E6C9")
                StreamType.MP4       -> Color.parseColor("#0D47A1") to Color.parseColor("#BBDEFB")
                StreamType.DASH     -> Color.parseColor("#4A148C") to Color.parseColor("#E1BEE7")
                StreamType.FLV      -> Color.parseColor("#BF360C") to Color.parseColor("#FFCCBC")
                StreamType.WEBM     -> Color.parseColor("#006064") to Color.parseColor("#B2EBF2")
                StreamType.M3U9     -> Color.parseColor("#7B5800") to Color.parseColor("#FFECB3")
                StreamType.WEBSOCKET -> Color.parseColor("#E65100") to Color.parseColor("#FFE0B2")
                StreamType.RTMP     -> Color.parseColor("#880E4F") to Color.parseColor("#F8BBD0")
                StreamType.OTHER    -> Color.parseColor("#37474F") to Color.parseColor("#ECEFF1")
            }
            b.tvType.setBackgroundColor(bg)
            b.tvType.setTextColor(fg)
            b.btnCopy.setOnClickListener  { onCopy(item)  }
            b.btnPlay.setOnClickListener  { onPlay(item)  }
            b.btnShare.setOnClickListener { onShare(item) }
            
            // Tích hợp Nút Tải
            b.btnDownload.setOnClickListener {
                if (item.type == com.aho.streambrowser.model.StreamType.HLS) {
                    android.widget.Toast.makeText(b.root.context, "Bắt đầu tải M3U8 đa luồng...", android.widget.Toast.LENGTH_SHORT).show()
                    com.aho.streambrowser.feature.downloader.hls.HlsDownloader.startDownload(
                        b.root.context, 
                        item, 
                        "Downloaded_Video_${System.currentTimeMillis()}"
                    )
                } else {
                    android.widget.Toast.makeText(b.root.context, "Tính năng tải hiện chỉ hỗ trợ định dạng HLS (.m3u8)", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemStreamBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<StreamItem>() {
            override fun areItemsTheSame(a: StreamItem, b: StreamItem) = a.url == b.url
            override fun areContentsTheSame(a: StreamItem, b: StreamItem) = a == b
        }
    }
}
