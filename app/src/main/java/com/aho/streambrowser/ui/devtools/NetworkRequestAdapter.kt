package com.aho.streambrowser.ui.devtools

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aho.streambrowser.model.NetworkRequest

class NetworkRequestAdapter(
    private val onClick: (NetworkRequest) -> Unit,
    private val onLongClick: (NetworkRequest) -> Unit
) : RecyclerView.Adapter<NetworkRequestAdapter.VH>() {

    private val items = ArrayList<NetworkRequest>()
    var t0: Long = 0L; private set
    var tRange: Long = 1L; private set

    fun submit(list: List<NetworkRequest>) {
        items.clear(); items.addAll(list)
        t0 = list.minOfOrNull { it.timestamp } ?: 0L
        tRange = ((list.maxOfOrNull { it.timestamp } ?: t0) - t0).coerceAtLeast(1L)
        notifyDataSetChanged()
    }
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = { dp: Int -> (dp * ctx.resources.displayMetrics.density).toInt() }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1A1A1A.toInt())
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 1 }
        }
        val strip = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(d(3), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(d(6), d(6), d(8), d(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val icon = TextView(ctx).apply { textSize = 11f; lp(d, 16, Gravity.CENTER) }
        val method = TextView(ctx).apply { textSize = 9.5f; setTextColor(0xFF888888.toInt()); maxLines = 1; lp(d, 40) }
        val hostCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(4), 0, d(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val host = TextView(ctx).apply { textSize = 10.5f; setTextColor(0xFFF0F0F0.toInt()); maxLines = 1 }
        val path = TextView(ctx).apply { textSize = 9f; setTextColor(0xFF888888.toInt()); maxLines = 1 }
        hostCol.addView(host); hostCol.addView(path)
        val status = TextView(ctx).apply { textSize = 9.5f; maxLines = 1; lp(d, 32, Gravity.CENTER) }
        val type = TextView(ctx).apply { textSize = 8.5f; setTextColor(Color.BLACK); maxLines = 1
            setPadding(d(3), d(1), d(3), d(1)); lp(d, 38, Gravity.CENTER) }
        val size = TextView(ctx).apply { textSize = 9f; setTextColor(0xFF888888.toInt()); maxLines = 1; lp(d, 42, Gravity.END) }
        val waterfall = FrameLayout(ctx).apply { layoutParams = LinearLayout.LayoutParams(d(60), d(12)) }
        content.addView(icon); content.addView(method); content.addView(hostCol)
        content.addView(status); content.addView(type); content.addView(size); content.addView(waterfall)
        row.addView(strip); row.addView(content)
        return VH(row, strip, icon, method, host, path, status, type, size, waterfall, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], t0, tRange)

    private fun TextView.lp(d: (Int)->Int, w: Int, g: Int) {
        layoutParams = LinearLayout.LayoutParams(
            if (w == 0) 0 else d(w),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            if (w == 0) 1f else 0f).apply { gravity = g }
    }

    class VH(
        row: View, private val strip: View, private val icon: TextView,
        private val method: TextView, private val host: TextView, private val path: TextView,
        private val status: TextView, private val type: TextView, private val size: TextView,
        private val waterfall: FrameLayout,
        private val onClick: (NetworkRequest) -> Unit,
        private val onLongClick: (NetworkRequest) -> Unit
    ) : RecyclerView.ViewHolder(row) {
        fun bind(req: NetworkRequest, t0: Long, tRange: Long) {
            val typeCol = typeColor(req.tag)
            strip.setBackgroundColor(typeCol)
            val (ic, icColor) = rowIcon(req)
            icon.text = ic; icon.setTextColor(icColor)
            method.text = req.method
            host.text = req.host.take(24)
            path.text = req.path.take(30)
            status.text = if (req.statusCode > 0) req.statusCode.toString() else "…"
            status.setTextColor(when {
                req.statusCode in 200..299 -> 0xFF1DB954.toInt()
                req.statusCode in 300..399 -> 0xFFFFD600.toInt()
                req.statusCode >= 400 -> 0xFFEF4444.toInt()
                else -> 0xFF687892.toInt()
            })
            type.text = req.tag; type.setBackgroundColor(typeCol)
            size.text = when {
                req.contentLength > 0 -> formatBytes(req.contentLength)
                req.responseBodyPreview.isEmpty() -> "—"
                req.responseBodyPreview.length < 1024 -> "${req.responseBodyPreview.length}B"
                else -> "${req.responseBodyPreview.length / 1024}K"
            }
            waterfall.removeAllViews()
            val totalPx = (60 * itemView.resources.displayMetrics.density).toInt()
            val rel = ((req.timestamp - t0).toFloat() / tRange).coerceIn(0f, 0.9f)
            val spacerW = (totalPx * rel).toInt()
            val barW = (totalPx * (0.08f + rel * 0.02f).coerceAtMost(0.15f)).toInt()
            waterfall.addView(View(itemView.context).apply {
                layoutParams = FrameLayout.LayoutParams(spacerW, FrameLayout.LayoutParams.MATCH_PARENT)
            })
            waterfall.addView(View(itemView.context).apply {
                setBackgroundColor(typeCol)
                layoutParams = FrameLayout.LayoutParams(barW, FrameLayout.LayoutParams.MATCH_PARENT)
            })
            itemView.setOnClickListener { onClick(req) }
            itemView.setOnLongClickListener { onLongClick(req); true }
        }
        companion object {
            fun typeColor(tag: String): Int = when (tag) {
                "HLS","DASH","MP4","FLV","STREAM" -> 0xFF1B5E20.toInt()
                "API","JSON" -> 0xFF0D47A1.toInt()
                "JS"  -> 0xFFE65100.toInt()
                else  -> 0xFF37474F.toInt()
            }
            fun rowIcon(req: NetworkRequest): Pair<String, Int> = when {
                req.statusCode in 300..399 -> "↻" to 0xFFFFD600.toInt()
                req.isStream -> "▶" to typeColor(req.tag)
                else -> "●" to typeColor(req.tag)
            }
            fun formatBytes(b: Long): String = when {
                b >= 1_000_000 -> String.format("%.1f MB", b / 1_000_000.0)
                b >= 1_000 -> String.format("%.1f KB", b / 1_000.0)
                else -> "$b B"
            }
        }
    }
}
