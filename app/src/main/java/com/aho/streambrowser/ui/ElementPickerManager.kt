package com.aho.streambrowser.ui

import android.webkit.JavascriptInterface
import android.webkit.WebView

/** Lightweight, page-local element picker. Its bridge is installed once per WebView. */
object ElementPickerManager {
    private var isActive = false
    private var onPicked: ((String, String) -> Unit)? = null
    private var variantCallback: ((String, String) -> Unit)? = null
    private var inspectionCallback: ((String) -> Unit)? = null

    fun install(webView: WebView, callback: (html: String, selector: String) -> Unit) {
        onPicked = callback
        webView.addJavascriptInterface(PickerBridge(webView), "HtmlPickerBridge")
    }

    fun toggle(webView: WebView, onActivated: () -> Unit, onDeactivated: () -> Unit) {
        if (isActive) deactivate(webView, onDeactivated) else activate(webView, onActivated, onDeactivated)
    }

    fun activate(webView: WebView, onActivated: () -> Unit, onDeactivated: () -> Unit = {}) =
        activateWithMode(webView, "element", onActivated)

    /** Region mode chooses the smallest useful DOM container spanning a dragged rectangle. */
    fun activateRegion(webView: WebView, onActivated: () -> Unit) =
        activateWithMode(webView, "region", onActivated)

    private fun activateWithMode(webView: WebView, mode: String, onActivated: () -> Unit) {
        isActive = true
        onActivated()
        webView.evaluateJavascript(PICKER_JS) {
            webView.evaluateJavascript("window.__sb_html_picking=true;window.__sb_html_picker_mode='$mode';window.__sb_html_picker_enable&&window.__sb_html_picker_enable();", null)
        }
    }

    fun deactivate(webView: WebView, onDeactivated: () -> Unit = {}) {
        isActive = false
        webView.evaluateJavascript("window.__sb_html_picking=false;window.__sb_html_clear&&window.__sb_html_clear();", null)
        onDeactivated()
    }

    fun isPickerActive() = isActive

    /** Re-serializes a stable ancestor of the last picked node without letting the page receive a tap. */
    fun requestVariant(webView: WebView, variant: String, callback: (String, String) -> Unit) {
        variantCallback = callback
        val safe = when (variant) { "parent", "container", "media" -> variant else -> "element" }
        webView.evaluateJavascript("window.__sb_html_picker_variant&&window.__sb_html_picker_variant('$safe');", null)
    }

    /** Returns a bounded tree/attribute description of the last node; no page mutation required. */
    fun requestInspection(webView: WebView, callback: (String) -> Unit) {
        inspectionCallback = callback
        webView.evaluateJavascript("window.__sb_html_picker_inspect&&window.__sb_html_picker_inspect();", null)
    }

    private class PickerBridge(private val webView: WebView) {
        @JavascriptInterface fun onPicked(html: String, selector: String) {
            if (!isActive) return
            isActive = false
            deliver(html, selector, onPicked)
        }
        @JavascriptInterface fun onVariant(html: String, selector: String) {
            val callback = variantCallback; variantCallback = null
            deliver(html, selector, callback)
        }
        @JavascriptInterface fun onInspection(json: String) {
            val callback = inspectionCallback; inspectionCallback = null
            if (json.length > 300 * 1024) return
            webView.post { callback?.invoke(json) }
        }
        private fun deliver(html: String, selector: String, callback: ((String, String) -> Unit)?) {
            // The bridge is visible to page JavaScript: reject abusive payloads before posting to UI.
            if (html.length > 4 * 1024 * 1024) {
                webView.post { android.widget.Toast.makeText(webView.context, "Vùng chọn quá lớn (tối đa 4 MB)", android.widget.Toast.LENGTH_LONG).show() }
                return
            }
            webView.post { callback?.invoke(html, selector.take(1000)) }
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
          window.__sb_html_path=path;
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
          window.__sb_html_serialize=serialize;
          window.__sb_html_picker_variant=function(kind){
            var el=window.__sb_html_last_target; if(!el) return;
            if(kind==='parent') el=el.parentElement || el;
            else if(kind==='media') el=el.closest('video,audio,iframe') || el.querySelector('video,audio,iframe') || el;
            else if(kind==='container') el=el.closest('article,main,section,[role=main],[class*=player],[class*=content],[class*=container]') || el.parentElement || el;
            try { HtmlPickerBridge.onVariant(serialize(el),path(el)); } catch(e) {}
          };
          window.__sb_html_picker_inspect=function(){
            var root=window.__sb_html_last_target; if(!root)return;
            var count=0;
            function desc(el,depth) {
              if(!el || count++>120) return null;
              var r=el.getBoundingClientRect(), attrs={}, names=el.getAttributeNames ? el.getAttributeNames() : [];
              for(var i=0;i<names.length&&i<25;i++) attrs[names[i]]=String(el.getAttribute(names[i])).slice(0,180);
              var item={tag:el.tagName.toLowerCase(),selector:path(el),attrs:attrs,rect:{x:Math.round(r.x),y:Math.round(r.y),width:Math.round(r.width),height:Math.round(r.height)},text:(el.childElementCount?'' :(el.textContent||'').trim().slice(0,140)),children:[]};
              if(depth<4) for(var j=0;j<el.children.length&&j<20;j++){var child=desc(el.children[j],depth+1);if(child)item.children.push(child);}
              return item;
            }
            try { HtmlPickerBridge.onInspection(JSON.stringify(desc(root,0))); } catch(e) {}
          };
          function choose(e) {
            if(!window.__sb_html_picking) return;
            // Stop at pointer/touch DOWN, before video default actions or site gesture handlers run.
            if(e.cancelable) e.preventDefault(); e.stopImmediatePropagation(); e.stopPropagation();
            var pt=e.touches && e.touches[0] ? e.touches[0] : e;
            var el=below(pt.clientX,pt.clientY); if(!el) return;
            window.__sb_html_last_target=el;
            window.__sb_html_picking=false; overlay.style.display='none'; marker.style.display='none';
            try { HtmlPickerBridge.onPicked(serialize(el), path(el)); } catch(err) {}
          }
          var regionStart=null;
          function point(e, changed) { var list=(changed?e.changedTouches:e.touches); return list&&list[0] ? list[0] : e; }
          function regionBegin(e) {
            if(!window.__sb_html_picking || window.__sb_html_picker_mode!=='region') return false;
            if(e.cancelable)e.preventDefault(); e.stopImmediatePropagation();
            var p=point(e); regionStart={x:p.clientX,y:p.clientY}; marker.style.display='block'; return true;
          }
          function regionMove(e) {
            if(!regionStart) return; if(e.cancelable)e.preventDefault();
            var p=point(e), x=Math.min(regionStart.x,p.clientX), y=Math.min(regionStart.y,p.clientY);
            marker.style.display='block'; marker.style.left=x+'px'; marker.style.top=y+'px';
            marker.style.width=Math.abs(p.clientX-regionStart.x)+'px'; marker.style.height=Math.abs(p.clientY-regionStart.y)+'px';
          }
          function commonAncestor(a,b) { if(!a||!b)return a||b; var seen=[]; for(var x=a;x;x=x.parentElement)seen.push(x); for(var y=b;y;y=y.parentElement)if(seen.indexOf(y)>=0)return y; return a; }
          function regionEnd(e) {
            if(!regionStart) return; if(e.cancelable)e.preventDefault(); e.stopImmediatePropagation();
            var p=point(e,true), x1=Math.min(regionStart.x,p.clientX), y1=Math.min(regionStart.y,p.clientY), x2=Math.max(regionStart.x,p.clientX), y2=Math.max(regionStart.y,p.clientY);
            var a=below(x1,y1), b=below(x2,y2), el=commonAncestor(a,b) || a; regionStart=null;
            if(!el)return; window.__sb_html_last_target=el; window.__sb_html_picking=false; overlay.style.display='none'; marker.style.display='none';
            try { HtmlPickerBridge.onPicked(serialize(el),'[Vùng '+Math.round(x2-x1)+'×'+Math.round(y2-y1)+'] '+path(el)); } catch(err) {}
          }
          function down(e){ if(!regionBegin(e)) choose(e); }
          function move(e){ if(regionStart)regionMove(e); else if(window.__sb_html_picking){ e.preventDefault(); var p=point(e); show(below(p.clientX,p.clientY)); } }
          // Some Android WebView/video implementations dispatch only one of these event families.
          overlay.addEventListener('pointerdown',down,{capture:true,passive:false});
          overlay.addEventListener('touchstart',down,{capture:true,passive:false});
          overlay.addEventListener('mousedown',down,{capture:true,passive:false});
          overlay.addEventListener('pointermove',move,{passive:false}); overlay.addEventListener('touchmove',move,{passive:false});
          overlay.addEventListener('pointerup',regionEnd,{capture:true,passive:false}); overlay.addEventListener('touchend',regionEnd,{capture:true,passive:false}); overlay.addEventListener('mouseup',regionEnd,{capture:true,passive:false});
          window.__sb_html_clear=function(){ marker.style.display='none'; overlay.style.display='none'; last=null; regionStart=null; };
          window.__sb_html_picker_enable=function(){ overlay.style.display='block'; overlay.style.pointerEvents='auto'; };
        })();
    """.trimIndent()
}
