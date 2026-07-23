package com.aho.streambrowser.ui

import android.webkit.JavascriptInterface
import android.webkit.WebView

/** Lightweight, page-local element picker. Its bridge is installed once per WebView. */
object ElementPickerManager {
    private var isActive = false
    private var onPicked: ((String, String) -> Unit)? = null

    fun install(webView: WebView, callback: (html: String, selector: String) -> Unit) {
        onPicked = callback
        webView.addJavascriptInterface(PickerBridge(webView), "HtmlPickerBridge")
    }

    fun toggle(webView: WebView, onActivated: () -> Unit, onDeactivated: () -> Unit) {
        if (isActive) deactivate(webView, onDeactivated) else activate(webView, onActivated, onDeactivated)
    }

    fun activate(webView: WebView, onActivated: () -> Unit, onDeactivated: () -> Unit = {}) {
        isActive = true
        onActivated()
        webView.evaluateJavascript(PICKER_JS) {
            webView.evaluateJavascript("window.__sb_html_picking=true;", null)
        }
    }

    fun deactivate(webView: WebView, onDeactivated: () -> Unit = {}) {
        isActive = false
        webView.evaluateJavascript("window.__sb_html_picking=false;window.__sb_html_clear&&window.__sb_html_clear();", null)
        onDeactivated()
    }

    fun isPickerActive() = isActive

    private class PickerBridge(private val webView: WebView) {
        @JavascriptInterface fun onPicked(html: String, selector: String) {
            // The bridge is visible to page JavaScript: reject abusive payloads before posting to UI.
            if (!isActive) return
            isActive = false
            if (html.length > 4 * 1024 * 1024) {
                webView.post { android.widget.Toast.makeText(webView.context, "Vùng chọn quá lớn (tối đa 4 MB)", android.widget.Toast.LENGTH_LONG).show() }
                return
            }
            webView.post { onPicked?.invoke(html, selector.take(1000)) }
        }
    }

    private val PICKER_JS = """
        (function () {
          if (window.__sb_html_picker_installed) return;
          window.__sb_html_picker_installed=true;
          var last=null, oldOutline='', oldOffset='';
          window.__sb_html_clear=function(){
            if(last){ last.style.outline=oldOutline; last.style.outlineOffset=oldOffset; last=null; }
          };
          function path(el) {
            var p=[];
            while(el && el.nodeType===1 && p.length<8) {
              var s=el.tagName.toLowerCase();
              if(el.id){ p.unshift(s+'#'+el.id); break; }
              var cls=(typeof el.className==='string' ? el.className.trim().split(/\s+/).filter(Boolean).slice(0,2) : []);
              if(cls.length) s+='.'+cls.join('.');
              p.unshift(s); el=el.parentElement;
            } return p.join(' > ');
          }
          document.addEventListener('pointerover',function(e){
            if(!window.__sb_html_picking) return;
            window.__sb_html_clear(); last=e.target; oldOutline=last.style.outline; oldOffset=last.style.outlineOffset;
            last.style.outline='3px solid #ff3d71'; last.style.outlineOffset='2px';
          },true);
          document.addEventListener('click',function(e){
            if(!window.__sb_html_picking) return;
            e.preventDefault(); e.stopImmediatePropagation();
            var el=e.target, selector=path(el); window.__sb_html_picking=false; window.__sb_html_clear();
            try {
              var clone=el.cloneNode(true);
              if(el.tagName==='TEXTAREA') clone.textContent=el.value;
              else if(el.tagName==='INPUT') { if(el.type==='checkbox'||el.type==='radio'){if(el.checked)clone.setAttribute('checked','');else clone.removeAttribute('checked');}else clone.setAttribute('value',el.value); }
              else if(el.tagName==='OPTION') { if(el.selected)clone.setAttribute('selected','');else clone.removeAttribute('selected'); }
              HtmlPickerBridge.onPicked(clone.outerHTML || '', selector);
            } catch(err) {}
          },true);
        })();
    """.trimIndent()
}
