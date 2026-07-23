package com.aho.streambrowser.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

/**
 * Exports the live DOM, not the server response.  The page serializes itself in chunks through
 * a bridge so large documents do not hit evaluateJavascript's callback-size limit.
 */
class HtmlExportManager(
    private val activity: AppCompatActivity,
    private val webView: WebView
) {
    private val maxChars = 12 * 1024 * 1024
    private var session: String? = null
    private var expectedChunks = 0
    private var receivedChunks = 0
    private var buffer = StringBuilder()
    private var pendingHtml: String? = null
    private var pendingName = "page.html"

    private val createFile = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        val html = pendingHtml
        pendingHtml = null
        if (uri == null || html == null) return@registerForActivityResult
        runCatching {
            activity.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8).use { out ->
                requireNotNull(out) { "Không mở được file đích" }
                out.write(html)
            }
        }.onSuccess {
            Toast.makeText(activity, "Đã xuất HTML (${html.length / 1024} KB)", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(activity, "Không thể lưu HTML: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun installBridge() {
        webView.addJavascriptInterface(ExportBridge(), "HtmlExportBridge")
    }

    fun exportLivePage() {
        if (webView.url.isNullOrBlank() || webView.url!!.startsWith("about:")) {
            Toast.makeText(activity, "Trang này chưa có HTML để xuất", Toast.LENGTH_SHORT).show()
            return
        }
        val token = beginCapture("page-${System.currentTimeMillis()}.html")
        webView.evaluateJavascript(FULL_PAGE_SCRIPT.replace("__SB_EXPORT_TOKEN__", token), null)
        Toast.makeText(activity, "Đang tạo snapshot HTML từ DOM hiện tại…", Toast.LENGTH_SHORT).show()
    }

    /** Called by the picker. The string is the selected element's current runtime outerHTML. */
    fun showSelectedHtml(html: String, selector: String) {
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(TextView(activity).apply {
            text = "Vùng đã chọn: $selector\n${html.length} ký tự · ${"%.1f".format(html.length / 1024f)} KB"
            textSize = 12f
            setPadding(dp(16), dp(12), dp(16), dp(8))
        })
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360))
        }
        scroll.addView(TextView(activity).apply {
            text = html; textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true); setPadding(dp(16), dp(8), dp(16), dp(16))
        })
        layout.addView(scroll)
        AlertDialog.Builder(activity)
            .setTitle("HTML vùng đã chọn")
            .setView(layout)
            .setPositiveButton("Xuất .html") { _, _ ->
                // A standalone fragment keeps its current URL as base so relative resources resolve.
                val doc = "<!doctype html><html><head><meta charset=\"utf-8\"><base href=\"${escapeAttribute(webView.url ?: "")}\"></head><body>$html</body></html>"
                save(doc, "selection-${System.currentTimeMillis()}.html")
            }
            .setNeutralButton("Copy") { _, _ -> copy(html) }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun beginCapture(name: String): String {
        session = UUID.randomUUID().toString()
        expectedChunks = 0; receivedChunks = 0; buffer = StringBuilder(); pendingName = name
        return requireNotNull(session)
    }

    private fun save(html: String, name: String) {
        pendingHtml = html
        pendingName = name
        createFile.launch(name)
    }

    private fun copy(text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("html", text))
        Toast.makeText(activity, "Đã copy HTML", Toast.LENGTH_SHORT).show()
    }

    inner class ExportBridge {
        @JavascriptInterface fun onChunk(token: String, index: Int, total: Int, chunk: String) {
            activity.runOnUiThread {
                if (token != session || total <= 0 || total > 10000 || index != receivedChunks) return@runOnUiThread
                if (expectedChunks == 0) expectedChunks = total
                if (expectedChunks != total || buffer.length + chunk.length > maxChars) {
                    session = null
                    Toast.makeText(activity, "HTML quá lớn (giới hạn 12 MB)", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                buffer.append(chunk); receivedChunks++
            }
        }
        @JavascriptInterface fun onComplete(token: String) {
            activity.runOnUiThread {
                if (token != session || expectedChunks == 0 || receivedChunks != expectedChunks) return@runOnUiThread
                session = null
                save(buffer.toString(), pendingName)
            }
        }
        @JavascriptInterface fun onError(token: String, message: String) {
            activity.runOnUiThread {
                if (token == session) {
                    session = null
                    Toast.makeText(activity, "Không thể snapshot HTML: ${message.take(120)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun escapeAttribute(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    companion object {
        // Includes current form state, open shadow roots (declarative shadow DOM), and canvas pixels
        // where the browser permits reading them. Cross-origin frame DOM and tainted canvases remain
        // intentionally inaccessible under browser security rules.
        private val FULL_PAGE_SCRIPT = """
            (function () {
              try {
                var token = '__SB_EXPORT_TOKEN__';
                if (!token || !window.HtmlExportBridge) return;
                var source = document.documentElement;
                var clone = source.cloneNode(true);
                function all(root, q) { try { return root.querySelectorAll(q); } catch(e) { return []; } }
                var srcFields = all(source, 'input,textarea,select,option');
                var dstFields = all(clone, 'input,textarea,select,option');
                for (var i=0; i<srcFields.length && i<dstFields.length; i++) {
                  var a=srcFields[i], b=dstFields[i], tag=a.tagName;
                  if (tag === 'TEXTAREA') b.textContent=a.value;
                  else if (tag === 'OPTION') { if(a.selected) b.setAttribute('selected',''); else b.removeAttribute('selected'); }
                  else if (tag === 'SELECT') b.value=a.value;
                  else { if (a.type === 'checkbox' || a.type === 'radio') { if(a.checked)b.setAttribute('checked','');else b.removeAttribute('checked'); } else b.setAttribute('value',a.value); }
                }
                var srcCanvases=all(source,'canvas'), dstCanvases=all(clone,'canvas');
                for (var c=0;c<srcCanvases.length && c<dstCanvases.length;c++) {
                  try { var image=document.createElement('img'); image.src=srcCanvases[c].toDataURL(); image.setAttribute('data-sb-original','canvas'); dstCanvases[c].replaceWith(image); } catch(e) {}
                }
                var srcAll=all(source,'*'), dstAll=all(clone,'*');
                for (var n=0;n<srcAll.length && n<dstAll.length;n++) {
                  var shadow=srcAll[n].shadowRoot;
                  if (shadow && shadow.mode === 'open') { var t=document.createElement('template'); t.setAttribute('shadowrootmode','open'); t.innerHTML=shadow.innerHTML; dstAll[n].appendChild(t); }
                }
                var html='<!DOCTYPE ' + (document.doctype ? document.doctype.name : 'html') + '>\n' + clone.outerHTML;
                var size=30000, total=Math.ceil(html.length/size);
                for(var p=0;p<total;p++) HtmlExportBridge.onChunk(token,p,total,html.slice(p*size,(p+1)*size));
                HtmlExportBridge.onComplete(token);
              } catch(e) { try { HtmlExportBridge.onError('__SB_EXPORT_TOKEN__', String(e)); } catch(ignore) {} }
            })();
        """.trimIndent()
    }
}
