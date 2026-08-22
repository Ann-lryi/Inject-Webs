package com.aho.streambrowser.ui.devtools

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class StorageTab(private val host: DevToolsHost) : DevToolsTab {
    private val accent = Color.parseColor("#1DB954")
    private val danger = Color.parseColor("#EF4444")
    private val textPri = Color.parseColor("#F0F0F0")
    private val textSec = Color.parseColor("#888888")
    private val bgBadge = Color.parseColor("#1E1E1E")

    override fun buildView(): View {
        val ctx = host.hostContext
        val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(ctx)
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(16))
        }
        inner.addView(TextView(ctx).apply {
            text = "localStorage + sessionStorage"
            textSize = 12f; setTextColor(textPri); typeface = Typeface.DEFAULT_BOLD
            setPadding(host.dp(2), host.dp(4), host.dp(2), host.dp(6))
        })
        val tvRes = TextView(ctx).apply {
            text = "Đang đọc..."; textSize = 9.5f; setTextColor(textSec)
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
        }
        inner.addView(tvRes)
        var full = ""
        val js = "(function(){try{var ls={},ss={};for(var i=0;i<localStorage.length;i++)" +
            "{var k=localStorage.key(i);ls[k]=localStorage.getItem(k);}for(var i=0;i<sessionStorage.length;i++)" +
            "{var k=sessionStorage.key(i);ss[k]=sessionStorage.getItem(k);}return JSON.stringify({l:ls,s:ss});" +
            "}catch(e){return '{\"error\":\"'+e+'\"}';}})()"
        host.webView.evaluateJavascript(js) { raw ->
            val clean = raw?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
            full = clean
            tvRes.post {
                try {
                    val j = org.json.JSONObject(clean)
                    val sb = StringBuilder()
                    sb.appendLine("=== localStorage ===")
                    j.optJSONObject("l")?.keys()?.forEach { k ->
                        sb.appendLine("$k: ${j.optJSONObject("l")?.optString(k, "")?.take(100)}")
                    }
                    sb.appendLine("\n=== sessionStorage ===")
                    j.optJSONObject("s")?.keys()?.forEach { k ->
                        sb.appendLine("$k: ${j.optJSONObject("s")?.optString(k, "")?.take(100)}")
                    }
                    tvRes.text = sb.toString()
                } catch (_: Exception) { tvRes.text = clean }
            }
        }
        inner.addView(btn("📋 Copy full JSON", accent) {
            if (full.isNotBlank()) host.copyText(full, "Storage JSON copied (${full.length} chars)")
            else host.toast("Chưa đọc xong, thử lại sau")
        })
        inner.addView(btn("🗑 Clear localStorage", danger) {
            host.webView.evaluateJavascript(
                "(function(){localStorage.clear();return 'ok';})()", null)
            host.toast("localStorage cleared")
        })
        sv.addView(inner); outer.addView(sv)
        return outer
    }

    private fun btn(label: String, color: Int, action: () -> Unit) =
        TextView(host.hostContext).apply {
            text = label; textSize = 10.5f
            setTextColor(if (color == bgBadge) textSec else Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; setColor(color)
                cornerRadius = host.dp(5).toFloat()
            }
            setPadding(host.dp(12), host.dp(5), host.dp(12), host.dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = host.dp(4); topMargin = host.dp(4) }
            setOnClickListener { action() }
        }
}
