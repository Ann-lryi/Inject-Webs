package com.aho.streambrowser.ui.devtools

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class CssTab(private val host: DevToolsHost) : DevToolsTab {
    private val ACCENT = Color.parseColor("#1DB954")
    private val DANGER = Color.parseColor("#EF4444")
    private val TEXT_PRI = Color.parseColor("#F0F0F0")
    private val TEXT_SEC = Color.parseColor("#888888")
    private val BG_BADGE = Color.parseColor("#1E1E1E")

    override fun buildView(): View {
        val ctx = host.hostContext
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val et = EditText(ctx).apply {
            setBackgroundColor(Color.parseColor("#0F0F0F"))
            setTextColor(TEXT_PRI)
            setHintTextColor(TEXT_SEC)
            textSize = 11f
            setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(16))
            gravity = android.view.Gravity.TOP
            minLines = 6
            inputType = EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
                EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(host.dp(8), host.dp(6), host.dp(8), host.dp(6))
        }
        row.addView(btn("▶ Inject", ACCENT) {
            val css = et.text.toString()
            host.webView.evaluateJavascript(
                "(function(){var s=document.createElement('style');s.textContent=" +
                "JSON.stringify(css)+";document.head.appendChild(s);})()", null)
            host.toast("CSS injected")
        })
        row.addView(btn("✖ Remove", DANGER) {
            host.webView.evaluateJavascript(
                "(function(){Array.from(document.querySelectorAll('style')).forEach(s=>s.remove());})()", null)
        })
        outer.addView(et)
        outer.addView(row)
        return outer
    }

    private fun sectionHeader(text: String) = TextView(host.hostContext).apply {
        this.text = text; textSize = 12f; setTextColor(TEXT_PRI)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(host.dp(2), host.dp(4), host.dp(2), host.dp(6))
    }
    private fun btn(label: String, color: Int, action: () -> Unit) =
        TextView(host.hostContext).apply {
            text = label; textSize = 10.5f; setTextColor(Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(color); cornerRadius = host.dp(5).toFloat()
            }
            setPadding(host.dp(12), host.dp(5), host.dp(12), host.dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = host.dp(4); topMargin = host.dp(4) }
            setOnClickListener { action() }
        }
}
