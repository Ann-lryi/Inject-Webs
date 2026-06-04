package com.aho.streambrowser.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.*

/**
 * Quản lý Element Picker hoàn toàn độc lập với DevTools sheet.
 * Picker FAB nổi trên màn hình, không bị ảnh hưởng khi đóng sheet.
 */
object ElementPickerManager {

    private var isActive = false

    // JS cài picker vào trang – chỉ cài 1 lần, toggle qua __sb_picking
    private val PICKER_JS = """
(function() {
    if (window.__sb_picker_installed) return 'already';
    window.__sb_picker_installed = true;
    var _lastEl = null;

    function clearHighlight() {
        if (_lastEl) {
            _lastEl.style.outline = _lastEl.__sb_orig_outline || '';
            _lastEl.style.outlineOffset = '';
            _lastEl = null;
        }
    }

    document.addEventListener('mouseover', function(e) {
        if (!window.__sb_picking) return;
        clearHighlight();
        _lastEl = e.target;
        _lastEl.__sb_orig_outline = _lastEl.style.outline || '';
        _lastEl.style.outline = '2px solid #FF4444';
        _lastEl.style.outlineOffset = '1px';
        e.stopPropagation();
    }, true);

    document.addEventListener('click', function(e) {
        if (!window.__sb_picking) return;
        e.preventDefault();
        e.stopPropagation();
        var el = e.target;
        clearHighlight();
        window.__sb_picking = false;

        var html = el.outerHTML || '';
        var tag  = el.tagName  || 'UNKNOWN';
        var id   = el.id ? '#' + el.id : '';
        var cls  = el.className && typeof el.className === 'string'
                   ? '.' + el.className.trim().split(/\s+/).join('.')
                   : '';
        SBridge_picker.onPicked(html, tag + id + cls);
    }, true);

    return 'installed';
})();
""".trimIndent()

    /** Gọi từ MainActivity khi nhấn FAB picker */
    fun toggle(
        webView: WebView,
        onActivated: () -> Unit,
        onDeactivated: () -> Unit
    ) {
        if (isActive) {
            deactivate(webView, onDeactivated)
        } else {
            activate(webView, onActivated, onDeactivated)
        }
    }

    fun activate(
        webView: WebView,
        onActivated: () -> Unit,
        onDeactivated: () -> Unit
    ) {
        isActive = true
        onActivated()

        // Đăng ký bridge (safe nếu đã đăng ký)
        try {
            webView.removeJavascriptInterface("SBridge_picker")
            webView.addJavascriptInterface(
                PickerBridge(webView.context, onDeactivated),
                "SBridge_picker"
            )
        } catch (_: Exception) {}

        // Cài JS rồi bật picker
        webView.evaluateJavascript(PICKER_JS) { _ ->
            webView.evaluateJavascript("window.__sb_picking = true;", null)
        }
    }

    fun deactivate(webView: WebView, onDeactivated: () -> Unit) {
        isActive = false
        webView.evaluateJavascript("window.__sb_picking = false;", null)
        onDeactivated()
    }

    fun isPickerActive() = isActive

    // ── JS Bridge nhận kết quả từ trang ──────────────────────────────────────
    class PickerBridge(
        private val ctx: Context,
        private val onDone: () -> Unit
    ) {
        @JavascriptInterface
        fun onPicked(rawHtml: String, selector: String) {
            val html = rawHtml
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")

            isActive = false
            (ctx as? android.app.Activity)?.runOnUiThread {
                onDone()
                showResultDialog(ctx, html, selector)
            }
        }
    }

    // ── Dialog hiện HTML element đã chọn ─────────────────────────────────────
    private fun showResultDialog(ctx: Context, html: String, selector: String) {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Header info
        val tvSelector = TextView(ctx).apply {
            text = "Element: $selector"
            setTextColor(Color.parseColor("#1D9E75"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(16.dp(ctx), 12.dp(ctx), 16.dp(ctx), 4.dp(ctx))
        }
        val tvSize = TextView(ctx).apply {
            text = "${html.length} ký tự · ${String.format("%.1f", html.length / 1024f)} KB"
            setTextColor(Color.parseColor("#888888"))
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(16.dp(ctx), 0, 16.dp(ctx), 8.dp(ctx))
        }
        layout.addView(tvSelector)
        layout.addView(tvSize)

        // Divider
        layout.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#2E2E2E"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })

        // HTML content
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 400.dp(ctx)
            )
        }
        val tvHtml = TextView(ctx).apply {
            text = html
            setTextColor(Color.parseColor("#00FF41"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(16.dp(ctx), 12.dp(ctx), 16.dp(ctx), 12.dp(ctx))
            setTextIsSelectable(true)
        }
        scroll.addView(tvHtml)
        layout.addView(scroll)

        AlertDialog.Builder(ctx)
            .setTitle("HTML Element")
            .setView(layout)
            .setPositiveButton("Copy All") { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("html", html))
                Toast.makeText(ctx, "Đã copy ${html.length} ký tự", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Copy 3KB đầu") { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("html", html.take(3000)))
                Toast.makeText(ctx, "Đã copy ${minOf(html.length, 3000)} ký tự", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun Int.dp(ctx: Context) =
        (this * ctx.resources.displayMetrics.density).toInt()
}
