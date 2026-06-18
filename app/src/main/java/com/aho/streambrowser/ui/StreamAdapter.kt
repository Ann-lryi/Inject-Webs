package com.aho.streambrowser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aho.streambrowser.R
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
            Toast.makeText(ctx, "Copied: ${text.take(50)}...", Toast.LENGTH_SHORT).show()
        }

        fun bind(item: StreamItem) {
            // ── Format chip + quality dot color ──────────────────────────
            val (chipBg, chipFg, dotColor) = when (item.type) {
                StreamType.HLS       -> Triple(R.drawable.bg_chip_hls,   R.color.chip_hls,   R.color.chip_hls)
                StreamType.MP4       -> Triple(R.drawable.bg_chip_mp4,   R.color.chip_mp4,   R.color.chip_mp4)
                StreamType.DASH      -> Triple(R.drawable.bg_chip_dash,  R.color.chip_dash,  R.color.chip_dash)
                StreamType.FLV       -> Triple(R.drawable.bg_chip_flv,   R.color.chip_flv,   R.color.chip_flv)
                StreamType.WEBM      -> Triple(R.drawable.bg_chip_webm,  R.color.chip_webm,  R.color.chip_webm)
                StreamType.WEBSOCKET -> Triple(R.drawable.bg_chip_ws,    R.color.chip_ws,    R.color.chip_ws)
                StreamType.RTMP      -> Triple(R.drawable.bg_chip_rtmp,  R.color.chip_rtmp,  R.color.chip_rtmp)
                StreamType.OTHER     -> Triple(R.drawable.bg_chip_other, R.color.chip_other, R.color.chip_other)
            }
            val ctx = b.root.context
            b.tvType.text = item.label
            b.tvType.setBackgroundResource(chipBg)
            b.tvType.setTextColor(ContextCompat.getColor(ctx, chipFg))
            b.qualityDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, dotColor)
            )

            // ── Quality + codec ───────────────────────────────────────────
            val resolution = extractResolution(item.url) ?: "Auto"
            b.tvQuality.text = resolution
            b.tvCodec.text = buildString {
                append(item.type.name)
                append(" \u00b7 ")
                append(guessCodec(item.type))
                if (item.source.isNotBlank()) {
                    append(" \u00b7 via ")
                    append(item.source)
                }
            }
            b.tvSource.text = ""

            // ── URL ───────────────────────────────────────────────────────
            b.tvUrl.text = item.url
            b.tvUrl.setTextIsSelectable(true)
            b.root.setOnLongClickListener {
                copyToClipboard(item.url); true
            }

            // ── Actions ───────────────────────────────────────────────────
            b.btnCopy.setOnClickListener  { onCopy(item)  }
            b.btnPlay.setOnClickListener  { onPlay(item)  }
            b.btnShare.setOnClickListener { onShare(item) }
            // Plugin button currently re-uses Copy (placeholder — wired by caller if needed)
            b.btnPlugin.setOnClickListener {
                val plugin = """// ==StreamBrowser Plugin==
// @stream  ${item.url}
// @type    ${item.type}
// @quality $resolution
// ========================="""
                copyToClipboard(plugin)
            }
        }

        private fun extractResolution(url: String): String? {
            // Look for patterns like 1080p, 720p, 480p, 360p, 4k, etc.
            val m = Regex("(?i)(4k|2160p|1440p|1080p|720p|480p|360p|240p|144p)").find(url)
            return m?.value?.uppercase()
        }

        private fun guessCodec(type: StreamType): String = when (type) {
            StreamType.HLS       -> "H.264 / AAC"
            StreamType.MP4       -> "H.264 / AAC"
            StreamType.DASH      -> "H.265 / AAC"
            StreamType.FLV       -> "H.264 / MP3"
            StreamType.WEBM      -> "VP8 / Vorbis"
            StreamType.WEBSOCKET -> "Opus / VP8"
            StreamType.RTMP      -> "H.264 / AAC"
            StreamType.OTHER     -> "Unknown"
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
