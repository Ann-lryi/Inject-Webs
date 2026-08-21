package com.aho.streambrowser.ui.devtools

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Storage inspector tab — shows localStorage + sessionStorage.
 * Extracted from DevToolsOverlay.buildStorageView(); behavior unchanged.
 */
class StorageTab(private val host: DevToolsHost) : DevToolsTab {

    private val ACCENT = Color.parseColor("#1DB954")
    private val DANGER = Color.parseColor("#EF4444")
    private val TEXT_SEC = Color.parseColor("#888888")
    private val BG_BADGE = Color.parseColor("#1E1E1E")

    override fun buildView(): View {
        val ctx = host.hostContext
        val sv = ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(16))
        }
        inner.addView(header("localStorage + sessionStorage"))

        val tvRes = mono("Đang đọc...", TEXT_SEC, 9.5f).apply { setTextIsSelectable(true) }
        inner.addView(tvRes)
        var full = ""

        host.webView.evaluateJavascript(
            """(function(){try{var ls={},ss={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);ls[k]=localStorage.getItem(k);}for(var i=0;i<sessionStorage.length;i++){var k=sessionStorage.key(i);ss[k]=sessionStorage.getItem(k);}return JSON.stringify({l:ls,s:ss});}catch(e){return '{"error":"'+e+'"}';}})()"""
        ) { raw ->
            val clean = raw?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
            full = clean
            tvRes.post {
                try {
                    val j = org.json.JSONObject(clean)
                    val sb = StringBuilder()
                    sb.appendLine("=== localStorage ===")
                    j.optJSONObject("l")?.keys()?.forEach { k0 ->
                        val k = k0 as String
                        sb.appendLine("$k: ${j.optJSONObject("l")?.optString(k, "")?.take(100)}")
                    }
                    sb.appendLine("\n=== sessionStorage ===")
                    j.optJSONObject("s")?.keys()?.forEach { k0 ->
                        val k = k0 as String
                        sb.appendLine("$k: ${j.optJSONObject("s")?.optString(k, "")?.take(100)}")
                    }
                    tvRes.text = sb
                } catch (_: Exception) {
                    tvRes.text = clean
                }
            }
        }

        inner.addView(btn("📋 Copy full JSON", ACCENT) {
            if (full.isNotBlank()) host.copyText(full, "Storage JSON copied (${full.length} chars)")
            else host.toast("Chưa đọc xong, thử lại sau")
        })
        inner.addView(btn("🗑 Clear localStorage", DANGER) {
            host.webView.evaluateJavascript("localStorage.clear();void 0", null)
            host.toast("localStorage cleared")
        })

        sv.addView(inner)
        return sv
    }

    private fun header(text: String) = TextView(host.hostContext).apply {
        this.text = text; textSize = 12f
        setTextColor(Color.parseColor("#F0F0F0")); typeface = Typeface.DEFAULT_BOLD
        setPadding(host.dp(2), host.dp(4), host.dp(2), host.dp(6))
    }

    private fun mono(text: String, color: Int, size: Float) = TextView(host.hostContext).apply {
        this.text = text; this.textSize = size; setTextColor(color); typeface = Typeface.MONOSPACE
    }

    private fun btn(label: String, color: Int, action: () -> Unit) =
        TextView(host.hostContext).apply {
            text = label; textSize = 10.5f
            setTextColor(if (isDark(color)) Color.WHITE else Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = host.dp(5).toFloat()
            }
            setPadding(host.dp(12), host.dp(5), host.dp(12), host.dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = host.dp(4); topMargin = host.dp(4) }
            setOnClickListener { action() }
        }

    private fun isDark(c: Int) =
        1 - (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255 >= 0.5
}
