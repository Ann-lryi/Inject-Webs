package com.aho.streambrowser.ui.devtools

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

class CssTab(private val host: DevToolsHost) : DevToolsTab {
    private val accent = Color.parseColor("#1DB954")
    private val danger = Color.parseColor("#EF4444")
    private val textPri = Color.parseColor("#F0F0F0")
    private val textSec = Color.parseColor("#888888")
    private val bgBadge = Color.parseColor("#1E1E1E")

    override fun buildView(): View {
        val ctx = host.hostContext
        val sv = ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(16))
        }
        inner.addView(TextView(ctx).apply {
            text = "🎨 CSS Injector"; textSize = 12f; setTextColor(textPri)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(host.dp(2), host.dp(4), host.dp(2), host.dp(6))
        })
        val et = EditText(ctx).apply {
            hint = "/* CSS here */\nbody { background: #000 !important; }"
            minLines = 5
            inputType = EditorInfo.TYPE_CLASS_TEXT or
                EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
                EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
        }
        inner.addView(et)
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(actionBtn("▶ Inject", accent) {
            val jsCss = JSONObject.quote(et.text.toString())
            val js = "(function(){var el=document.getElementById('__sb_css');" +
                "if(!el){el=document.createElement('style');el.id='__sb_css';document.head.appendChild(el);}" +
                "el.textContent=" + jsCss + ";return 'ok';})()"
            host.webView.evaluateJavascript(js, null)
            host.toast("CSS injected")
        })
        btnRow.addView(actionBtn("✖ Remove", danger) {
            host.webView.evaluateJavascript(
                "(function(){var e=document.getElementById('__sb_css');if(e)e.remove();return 'ok';})()", null)
        })
        inner.addView(btnRow)
        listOf(
            "🌑 Dark" to "* { background: #111 !important; color: #eee !important; }",
            "🙈 Hide ads" to ".ad,.ads,[id*=ad],[class*=ad] { display:none !important; }",
            "🔤 Larger text" to "body { font-size: 18px !important; line-height: 1.5 !important; }",
            "🌙 Sepia" to "body { background: #f4ecd8 !important; color: #5b4636 !important; }"
        ).forEach { (label, css) ->
            inner.addView(actionBtn(label, bgBadge) { et.setText(css) })
        }
        sv.addView(inner)
        return sv
    }

    private fun actionBtn(label: String, bgColor: Int, onClick: () -> Unit) =
        TextView(host.hostContext).apply {
            text = label; textSize = 10.5f
            setTextColor(if (bgColor == bgBadge) textSec else Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(bgColor); cornerRadius = host.dp(5).toFloat()
            }
            setPadding(host.dp(12), host.dp(5), host.dp(12), host.dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = host.dp(4); topMargin = host.dp(4) }
            setOnClickListener { onClick() }
        }
}
