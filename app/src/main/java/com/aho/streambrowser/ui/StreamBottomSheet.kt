package com.aho.streambrowser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.aho.streambrowser.databinding.BottomSheetStreamsBinding
import com.aho.streambrowser.model.StreamItem
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class StreamBottomSheet(
    private val streams: List<StreamItem>,
    private val onPlayInApp: (StreamItem) -> Unit
) : BottomSheetDialogFragment() {

    private var _b: BottomSheetStreamsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _b = BottomSheetStreamsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.tvCount.text = "${streams.size} Streams Detected"

        val adapter = StreamAdapter(
            onCopy  = ::copyUrl,
            onPlay  = { onPlayInApp(it); dismiss() },
            onShare = ::shareUrl
        )
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter
        adapter.submitList(streams)
    }

    private fun copyUrl(item: StreamItem) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("stream_url", item.url)
        cm.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Đã copy URL (${item.url.length} ký tự)", Toast.LENGTH_SHORT).show()
    }

    private fun shareUrl(item: StreamItem) {
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, item.url)
            }, "Chia sẻ link stream"
        ))
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    companion object { const val TAG = "StreamBottomSheet" }
}
