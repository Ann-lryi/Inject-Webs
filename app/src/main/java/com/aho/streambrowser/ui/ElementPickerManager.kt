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
            webView.evaluateJavascript("window.__sb_html_picking=true;window.__sb_html_picker_enable&&window.__sb_html_picker_enable();", null)
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
          var overlay=document.createElement('div'), marker=document.createElement('div'), last=null;
          overlay.id='__sb_html_picker_overlay'; marker.id='__sb_html_picker_marker';
          overlay.style.cssText='position:fixed;inset:0;z-index:2147483647;background:transparent;display:none;touch-action:none;cursor:crosshair;';
          marker.style.cssText='position:fixed;z-index:2147483647;pointer-events:none;display:none;border:3px solid #ff3d71;background:rgba(255,61,113,.10);box-sizing:border-box;';
          (document.documentElement || document.body).appendChild(overlay);
          (document.documentElement || document.body).appendChild(marker);
          function below(x,y) {
            // The overlay receives the touch before a video/native control can consume it.
            overlay.style.pointerEvents='none';
            var el=document.elementFromPoint(x,y);
            overlay.style.pointerEvents='auto';
            return el;
          }
          function show(el) {
            if(!el) { marker.style.display='none'; last=null; return; }
            last=el; var r=el.getBoundingClientRect();
            marker.style.display='block'; marker.style.left=r.left+'px'; marker.style.top=r.top+'px';
            marker.style.width=r.width+'px'; marker.style.height=r.height+'px';
          }
          function path(el) {
            var p=[];
            while(el && el.nodeType===1 && p.length<8) {
              var s=el.tagName.toLowerCase();
              if(el.id){ p.unshift(s+'#'+el.id); break; }
              var cls=(typeof el.className==='string' ? el.className.trim().split(/\s+/).filter(Boolean).slice(0,2) : []);
              if(cls.length) s+='.'+cls.join('.'); p.unshift(s); el=el.parentElement;
            } return p.join(' > ');
          }
          function serialize(el) {
            var clone=el.cloneNode(true);
            function fields(a,b) {
              var aa=[a].concat([].slice.call(a.querySelectorAll ? a.querySelectorAll('input,textarea,select,option') : []));
              var bb=[b].concat([].slice.call(b.querySelectorAll ? b.querySelectorAll('input,textarea,select,option') : []));
              for(var i=0;i<aa.length&&i<bb.length;i++) { var x=aa[i],y=bb[i];
                if(x.tagName==='TEXTAREA') y.textContent=x.value;
                else if(x.tagName==='OPTION') { if(x.selected)y.setAttribute('selected','');else y.removeAttribute('selected'); }
                else if(x.tagName==='INPUT') { if(x.type==='checkbox'||x.type==='radio'){if(x.checked)y.setAttribute('checked','');else y.removeAttribute('checked');}else y.setAttribute('value',x.value); }
              }
            } fields(el,clone);
            var media=[el].concat([].slice.call(el.querySelectorAll ? el.querySelectorAll('video,audio') : []));
            var cloned=[clone].concat([].slice.call(clone.querySelectorAll ? clone.querySelectorAll('video,audio') : []));
            for(var j=0;j<media.length&&j<cloned.length;j++) if(/^(VIDEO|AUDIO)$/.test(media[j].tagName)) {
              var m=media[j], d=cloned[j];
              d.setAttribute('data-sb-current-src',m.currentSrc||m.src||'');
              d.setAttribute('data-sb-current-time',String(m.currentTime||0));
              d.setAttribute('data-sb-paused',String(m.paused)); d.setAttribute('data-sb-muted',String(m.muted));
            }
            return clone.outerHTML || '';
          }
          function choose(e) {
            if(!window.__sb_html_picking) return;
            // Stop at pointer/touch DOWN, before video default actions or site gesture handlers run.
            if(e.cancelable) e.preventDefault(); e.stopImmediatePropagation(); e.stopPropagation();
            var pt=e.touches && e.touches[0] ? e.touches[0] : e;
            var el=below(pt.clientX,pt.clientY); if(!el) return;
            window.__sb_html_picking=false; overlay.style.display='none'; marker.style.display='none';
            try { HtmlPickerBridge.onPicked(serialize(el), path(el)); } catch(err) {}
          }
          overlay.addEventListener('pointermove',function(e){ if(window.__sb_html_picking) { e.preventDefault(); show(below(e.clientX,e.clientY)); } },{passive:false});
          overlay.addEventListener('touchmove',function(e){ if(window.__sb_html_picking) { e.preventDefault(); var t=e.touches[0]; if(t)show(below(t.clientX,t.clientY)); } },{passive:false});
          // Some Android WebView/video implementations dispatch only one of these event families.
          overlay.addEventListener('pointerdown',choose,{capture:true,passive:false});
          overlay.addEventListener('touchstart',choose,{capture:true,passive:false});
          overlay.addEventListener('mousedown',choose,{capture:true,passive:false});
          window.__sb_html_clear=function(){ marker.style.display='none'; overlay.style.display='none'; last=null; };
          window.__sb_html_picker_enable=function(){ overlay.style.display='block'; overlay.style.pointerEvents='auto'; };
        })();
    """.trimIndent()
}
