package com.aho.streambrowser.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aho.streambrowser.detector.StreamDetector
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports the live DOM, not the server response.  The page serializes itself in chunks through
 * a bridge so large documents do not hit evaluateJavascript's callback-size limit.
 */
class HtmlExportManager(
    private val activity: AppCompatActivity,
    private val webView: WebView,
    private val detector: StreamDetector
) {
    private val maxChars = 12 * 1024 * 1024
    private var session: String? = null
    private var expectedChunks = 0
    private var receivedChunks = 0
    private var buffer = StringBuilder()
    private var pendingHtml: String? = null
    private var pendingZip: ByteArray? = null
    private var pendingName = "page.html"
    // Applied to the cloned export only; never changes the page currently shown in WebView.
    private var redactOutput = false

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

    private val createZip = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val zip = pendingZip
        pendingZip = null
        if (uri == null || zip == null) return@registerForActivityResult
        runCatching { activity.contentResolver.openOutputStream(uri)?.use { it.write(zip) } }
            .onSuccess { Toast.makeText(activity, "Đã xuất gói snapshot", Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(activity, "Không thể lưu gói: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    fun installBridge() {
        webView.addJavascriptInterface(ExportBridge(), "HtmlExportBridge")
    }

    /** Lets the user choose between immediate DOM capture and a lazy-load aware full snapshot. */
    fun exportLivePage() {
        if (webView.url.isNullOrBlank() || webView.url!!.startsWith("about:")) {
            Toast.makeText(activity, "Trang này chưa có HTML để xuất", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Xuất HTML runtime")
            .setItems(arrayOf(
                "Snapshot nhanh — giữ nguyên dữ liệu",
                "Snapshot đầy đủ — lazy-load, giữ nguyên dữ liệu",
                "Snapshot riêng tư — lazy-load, che token/form/password"
            )) { _, selected ->
                startCapture(loadLazyContent = selected != 0, redact = selected == 2)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun startCapture(loadLazyContent: Boolean, redact: Boolean) {
        redactOutput = redact
        val token = beginCapture("page-${System.currentTimeMillis()}.html")
        val prepare = if (loadLazyContent) LAZY_LOAD_SCRIPT else ""
        val redaction = if (redact) REDACTION_SCRIPT else ""
        webView.evaluateJavascript(
            FULL_PAGE_SCRIPT.replace("__SB_EXPORT_TOKEN__", token)
                .replace("__SB_PREPARE__", prepare).replace("__SB_REDACT__", redaction), null
        )
        val message = when { redact -> "Đang tạo snapshot riêng tư…"; loadLazyContent -> "Đang cuộn tải nội dung động…"; else -> "Đang tạo snapshot HTML…" }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
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
            .setNeutralButton("Chọn cấp khác") { _, _ -> showVariantChooser(html, selector) }
            .setNegativeButton("Đóng", null)
            .show()
    }


    private fun showVariantChooser(currentHtml: String, currentSelector: String) {
        val options = arrayOf("Element đang chọn", "Parent trực tiếp", "Container nội dung/player", "Video / audio / iframe gần nhất", "Mở DOM Inspector")
        AlertDialog.Builder(activity).setTitle("Chọn phạm vi HTML").setItems(options) { _, which ->
            if (which == 0) showSelectedHtml(currentHtml, currentSelector)
            else if (which == 4) ElementPickerManager.requestInspection(webView) { json -> showDomInspector(json) }
            else {
                val kind = arrayOf("element", "parent", "container", "media")[which]
                ElementPickerManager.requestVariant(webView, kind) { html, selector -> showSelectedHtml(html, selector) }
            }
        }.show()
    }
    /** A bounded, read-only DOM tree for the last picked node; it never mutates the page. */
    private fun showDomInspector(json: String) {
        val root = runCatching { JSONObject(json) }.getOrElse {
            Toast.makeText(activity, "Không đọc được cây DOM", Toast.LENGTH_SHORT).show(); return
        }
        val scroll = ScrollView(activity)
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        fun render(node: JSONObject, depth: Int) {
            val attrs = node.optJSONObject("attrs")
            val attrText = attrs?.keys()?.asSequence()?.joinToString(" ") { key -> "$key=\"${attrs.optString(key)}\"" } ?: ""
            val rect = node.optJSONObject("rect")
            content.addView(TextView(activity).apply {
                text = "${"  ".repeat(depth)}<${node.optString("tag")}>  ${node.optString("selector")}\n${"  ".repeat(depth)}$attrText\n${"  ".repeat(depth)}${rect?.optInt("width")}×${rect?.optInt("height")}  ${node.optString("text")}"
                typeface = android.graphics.Typeface.MONOSPACE; textSize = 10f; setPadding(dp(4), dp(5), dp(4), dp(5))
            })
            val children = node.optJSONArray("children") ?: JSONArray()
            for (i in 0 until children.length()) children.optJSONObject(i)?.let { render(it, depth + 1) }
        }
        render(root, 0); scroll.addView(content)
        AlertDialog.Builder(activity).setTitle("DOM Inspector · ${root.optString("tag")}").setView(scroll)
            .setPositiveButton("Copy JSON") { _, _ -> copy(json) }.setNegativeButton("Đóng", null).show()
    }

    private fun beginCapture(name: String): String {
        session = UUID.randomUUID().toString()
        expectedChunks = 0; receivedChunks = 0; buffer = StringBuilder(); pendingName = name
        return requireNotNull(session)
    }

    private fun offerSave(html: String, name: String) {
        AlertDialog.Builder(activity)
            .setTitle("Snapshot đã sẵn sàng")
            .setMessage("${html.length / 1024} KB · Chọn định dạng xuất")
            .setPositiveButton("HTML") { _, _ -> save(html, name) }
            .setNeutralButton("Gói ZIP") { _, _ -> createSnapshotBundle(html, name, redactOutput) }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun save(html: String, name: String) {
        pendingHtml = html; pendingName = name; createFile.launch(name)
    }

    /** ZIP contains reproducible evidence: DOM, request/stream metadata and a viewport screenshot. */
    private fun createSnapshotBundle(html: String, htmlName: String, redacted: Boolean) {
        // A screenshot can itself contain personal data; omit it from private bundles.
        val screenshot = if (redacted) null else ByteArrayOutputStream().also { out ->
            val width = webView.width.coerceAtLeast(1); val height = webView.height.coerceAtLeast(1)
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            webView.draw(android.graphics.Canvas(bitmap)); bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
        }.toByteArray()
        val metadata = JSONObject().apply {
            put("url", if (redacted) redactText(webView.url ?: "") else (webView.url ?: "")); put("title", webView.title ?: "")
            put("capturedAt", System.currentTimeMillis()); put("userAgent", webView.settings.userAgentString)
            put("redacted", redacted)
            put("viewport", JSONObject().put("width", webView.width).put("height", webView.height))
            put("scroll", JSONObject().put("x", webView.scrollX).put("y", webView.scrollY))
        }.toString(2)
        val streams = JSONArray().apply { detector.streams.forEach { put(JSONObject().apply {
            put("url", if (redacted) redactText(it.url) else it.url); put("type", it.type.name); put("source", it.source); put("referer", if (redacted) redactText(it.referer) else it.referer)
        }) } }.toString(2)
        Thread {
            val bytes = ByteArrayOutputStream().use { out ->
                ZipOutputStream(out).use { zip ->
                    fun add(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                    add("snapshot.html", html); add("metadata.json", metadata); add("streams.json", streams)
                    if (screenshot != null) { zip.putNextEntry(ZipEntry("viewport.png")); zip.write(screenshot); zip.closeEntry() }
                }; out.toByteArray()
            }
            activity.runOnUiThread { pendingZip = bytes; createZip.launch(htmlName.removeSuffix(".html") + ".zip") }
        }.start()
    }

    private fun redactText(text: String): String = text
        .replace(Regex("(?i)(Bearer\s+)[A-Za-z0-9._~-]+"), "${'$'}1[REDACTED]")
        .replace(Regex("\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+"), "[REDACTED_JWT]")
        .replace(Regex("(?i)([?&](?:access_token|token|auth|authorization|cookie|session|password|secret|api[_-]?key)=)[^&#\s]+"), "${'$'}1[REDACTED]")

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
                offerSave(buffer.toString(), pendingName)
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
        // Runs only against `clone` in FULL_PAGE_SCRIPT, never against the live document.
        private val REDACTION_SCRIPT = """
            (function redactClone(root) {
              var sensitive=/(pass(word)?|token|secret|api[_-]?key|authorization|cookie|session)/i;
              root.querySelectorAll('input').forEach(function(input) {
                if(input.type==='password' || sensitive.test(input.name||'') || sensitive.test(input.id||'')) input.setAttribute('value','[REDACTED]');
              });
              root.querySelectorAll('*').forEach(function(el) {
                Array.prototype.slice.call(el.attributes||[]).forEach(function(a) {
                  if(sensitive.test(a.name)) el.setAttribute(a.name,'[REDACTED]');
                  else if(/^(href|src|action|data-sb-current-src)$/i.test(a.name)) {
                    el.setAttribute(a.name, String(a.value).replace(/([?&](?:access_token|token|auth|authorization|cookie|session|password|secret|api[_-]?key)=)[^&#\s]+/ig,'$1[REDACTED]'));
                  }
                });
              });
              var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT), node;
              while((node=walker.nextNode())) node.nodeValue=node.nodeValue
                .replace(/Bearer\s+[A-Za-z0-9._~-]+/ig,'Bearer [REDACTED]')
                .replace(/\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+/g,'[REDACTED_JWT]');
            })(clone);
        """.trimIndent()

        private val LAZY_LOAD_SCRIPT = """
            var root=document.scrollingElement || document.documentElement;
            var startX=window.scrollX, startY=window.scrollY, lastHeight=0, stable=0;
            for(var step=0; step<180 && stable<3; step++) {
              window.scrollTo(0, Math.min(root.scrollHeight, window.scrollY + Math.max(500, window.innerHeight * .85)));
              await new Promise(function(resolve){ setTimeout(resolve, 180); });
              var atBottom=window.scrollY + window.innerHeight >= root.scrollHeight - 4;
              if(atBottom && root.scrollHeight === lastHeight) stable++; else stable=0;
              lastHeight=root.scrollHeight;
            }
            window.scrollTo(startX,startY);
            await new Promise(function(resolve){ setTimeout(resolve, 250); });
        """.trimIndent()

        private val FULL_PAGE_SCRIPT = """
            (async function () {
              try {
                __SB_PREPARE__
                var token = '__SB_EXPORT_TOKEN__';
                if (!token || !window.HtmlExportBridge) return;
                var source = document.documentElement;
                var clone = source.cloneNode(true);
                __SB_REDACT__
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
                // Media playback state lives in browser objects rather than markup. Preserve useful
                // live metadata even when the source is a MediaSource/blob URL.
                var srcMedia=all(source,'video,audio'), dstMedia=all(clone,'video,audio');
                for (var m=0;m<srcMedia.length && m<dstMedia.length;m++) {
                  var media=srcMedia[m], copy=dstMedia[m];
                  copy.setAttribute('data-sb-current-src', media.currentSrc || media.src || '');
                  copy.setAttribute('data-sb-current-time', String(media.currentTime || 0));
                  copy.setAttribute('data-sb-duration', String(media.duration || ''));
                  copy.setAttribute('data-sb-paused', String(media.paused));
                  copy.setAttribute('data-sb-muted', String(media.muted));
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
