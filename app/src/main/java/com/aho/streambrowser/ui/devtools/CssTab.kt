package com.aho.streambrowser.ui.devtools

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * CSS injector tab (extracted verbatim from DevToolsOverlay.buildCssView).
 * Lets the user type/quick-inject CSS into the current page.
 */
class CssTab(private val host: DevToolsHost) : DevToolsTab {

    private val ACCENT = Color.parseColor("#1DB954")
    private val DANGER = Color.parseColor("#EF4444")
    private val BG_BADGE = Color.parseColor("#1E1E1E")

    override fun buildView(): View {
        val ctx = host.hostContext
        val sv = ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(16))
        }
        inner.addView(sectionHeader("🎨 CSS Injector"))

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
        btnRow.addView(actionBtn("▶ Inject", ACCENT) {
            val css = et.text.toString().replace("`", "\\`")
            host.webView.evaluateJavascript(
                """(function(){var el=document.getElementById('__sb_css');if(!el){el=document.createElement('style');el.id='__sb_css';document.head.appendChild(el);}el.textContent=`$css`;return 'ok';})()""",
                null
            )
            host.toast("CSS injected")
        })
        btnRow.addView(actionBtn("✖ Remove", DANGER) {
            host.webView.evaluateJavascript(
                "var e=document.getElementById('__sb_css');if(e)e.remove();'ok'",
                null
            )
        })
        inner.addView(btnRow)

        listOf(
            "🌑 Dark" to "* { background: #111 !important; color: #eee !important; }",
            "🙈 Hide ads" to ".ad,.ads,[id*=ad],[class*=ad] { display:none!important; }",
            "👁 Show hidden" to "[style*='display:none'],[hidden] { display:block!important; }",
            "📐 Desktop layout" to "body { min-width:1280px!important; zoom:0.7; }",
            "🔍 Highlight video" to "video { outline:3px solid #1DB954!important; }"
        ).forEach { (label, css) ->
            inner.addView(actionBtn(label, BG_BADGE) { et.setText(css) })
        }

        sv.addView(inner)
        return sv
    }

    private fun sectionHeader(text: String) = TextView(host.hostContext).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#F0F0F0"))
        typeface = Typeface.DEFAULT_BOLD
        setPadding(host.dp(2), host.dp(4), host.dp(2), host.dp(6))
    }

    private fun actionBtn(label: String, color: Int, action: () -> Unit) =
        TextView(host.hostContext).apply {
            text = label
            textSize = 10.5f
            setTextColor(if (isColorDark(color)) Color.WHITE else Color.BLACK)
            background = roundedRect(color, 5f)
            setPadding(host.dp(12), host.dp(5), host.dp(12), host.dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = host.dp(4); topMargin = host.dp(4) }
            setOnClickListener { action() }
        }

    private fun roundedRect(color: Int, cornerDp: Float): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = host.dp(cornerDp.toInt()).toFloat()
        }

    private fun isColorDark(c: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255
        return darkness >= 0.5
    }
}
