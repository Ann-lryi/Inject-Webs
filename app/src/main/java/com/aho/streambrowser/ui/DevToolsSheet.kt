package com.aho.streambrowser.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.webkit.WebView
import android.widget.*
import androidx.recyclerview.widget.*
import com.aho.streambrowser.detector.StreamDetector
import com.aho.streambrowser.detector.CryptoKeyCapture
import com.aho.streambrowser.detector.WebSocketMessage
import com.aho.streambrowser.util.CookieExporter
import com.aho.streambrowser.util.JwtDecoder
import com.aho.streambrowser.util.M3u8QualityParser
import com.aho.streambrowser.util.PluginGenerator
import com.aho.streambrowser.util.HarExporter
import com.aho.streambrowser.util.AesKeyFinder
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.aho.streambrowser.model.NetworkRequest
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.util.*
import com.google.android.material.tabs.TabLayout
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*

import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class DevToolsSheet(
    private val detector: StreamDetector,
    private val webView: WebView,
    private val activity: MainActivity,
    private val onPlayStream: (StreamItem) -> Unit
) : Fragment() {

    private lateinit var tabLayout: TabLayout
    private lateinit var contentFrame: FrameLayout
    private var currentTab = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) = buildRoot()
    override fun onDestroyView() { super.onDestroyView(); scope.cancel() }

    /** Called by MainActivity's Hide button to close this panel. */
    fun close() {
        parentFragmentManager.beginTransaction().remove(this).commit()
    }

    // ── Root ──────────────────────────────────────────────────────────────────
    private fun buildRoot(): View {
        val ctx = requireContext()
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        // ── Top bar: Hide button + DevTools Pro v5 + LIVE indicator ───────
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 6.dp, 8.dp, 6.dp)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val btnHide = android.widget.TextView(ctx).apply {
            text = "Hide"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(10.dp, 4.dp, 10.dp, 4.dp)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#10B981"))
                cornerRadius = 16.dp.toFloat()
            }
            setOnClickListener { close() }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        val ivBolt = android.widget.ImageView(ctx).apply {
            setImageResource(com.aho.streambrowser.R.drawable.ic_lightning)
            setColorFilter(Color.parseColor("#10B981"))
            layoutParams = LinearLayout.LayoutParams(18.dp, 18.dp).apply {
                marginStart = 12.dp; marginEnd = 6.dp
            }
        }
        val tvTitle = android.widget.TextView(ctx).apply {
            text = "DevTools Pro"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        val tvV5 = android.widget.TextView(ctx).apply {
            text = "v5"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 9f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(4.dp, 0, 4.dp, 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#1A2A1A"))
                cornerRadius = 4f
            }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = 4.dp; topMargin = 1.dp
            }
        }
        val spacer = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val liveDot = View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
            layoutParams = LinearLayout.LayoutParams(7.dp, 7.dp).apply { marginEnd = 4.dp }
        }
        val tvLive = android.widget.TextView(ctx).apply {
            text = "LIVE"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        topBar.addView(btnHide)
        topBar.addView(ivBolt)
        topBar.addView(tvTitle)
        topBar.addView(tvV5)
        topBar.addView(spacer)
        topBar.addView(liveDot)
        topBar.addView(tvLive)
        outer.addView(topBar)

        outer.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(MATCH, 1)
        })

        // ── Tab row ───────────────────────────────────────────────────────
        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            layoutParams = LinearLayout.LayoutParams(MATCH, 40.dp)
        }
        tabLayout = TabLayout(ctx).apply {
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setTabTextColors(Color.parseColor("#9CA3AF"), Color.parseColor("#10B981"))
            setSelectedTabIndicatorColor(Color.parseColor("#10B981"))
            setSelectedTabIndicatorHeight(3.dp)
            tabMode = TabLayout.MODE_SCROLLABLE
            tabGravity = TabLayout.GRAVITY_START
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
        }
        refreshTabs()

        val btnClearAll = btn(ctx, "Clear", "#2A1A1A", "#EF4444").apply {
            setOnClickListener {
                detector.clear()
                refreshTabs()
                contentFrame.removeAllViews()
                showTab(currentTab)
            }
        }
        tabRow.addView(tabLayout)
        tabRow.addView(btnClearAll)
        outer.addView(tabRow)
        outer.addView(divider(ctx))

        contentFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            setBackgroundColor(Color.parseColor("#121212"))
        }
        outer.addView(contentFrame)
        showTab(0)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { currentTab=t.position; contentFrame.removeAllViews(); showTab(t.position) }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        return outer
    }

    // Tab ids (stable across reorders). Primary design tabs come first.
    private enum class TabId(val title: () -> String) {
        Network ({ "Network" }),
        Streams ({ "Streams" }),
        Console ({ "Console" }),
        Crypto  ({ "Crypto" }),
        WS      ({ "WS" }),
        Deep    ({ "Deep" }),
        HTML    ({ "HTML" }),
        M3U8    ({ "M3U8" }),
        Blocker ({ "Blocker" }),
        UA      ({ "UA" }),
        Saved   ({ "Saved" }),
        Cookie  ({ "Cookie" }),
        Plugin  ({ "Plugin" }),
        Storage ({ "Storage" }),
        CSS     ({ "CSS" }),
        Timeline({ "Timeline" }),
        SW      ({ "SW/IDB" }),
        DOM     ({ "DOM" }),
        Proxy   ({ "Proxy" }),
        History ({ "History" }),
    }
    private val tabOrder: List<TabId> = TabId.values().toList()

    private fun refreshTabs() {
        val titles = tabOrder.map { it.title() }
        if (tabLayout.tabCount == 0) {
            titles.forEach { tabLayout.addTab(tabLayout.newTab().setText(it)) }
        } else {
            titles.forEachIndexed { i, t -> tabLayout.getTabAt(i)?.text = t }
        }
    }

    private fun showTab(pos: Int) {
        val id = tabOrder.getOrNull(pos) ?: return
        when (id) {
            TabId.Network  -> showNetworkTab()
            TabId.Streams  -> showStreamsTab()
            TabId.Console  -> showConsoleTab()
            TabId.Crypto   -> showCryptoTab()
            TabId.WS       -> showWsTab()
            TabId.Deep     -> showDeepTab()
            TabId.HTML     -> showHtmlTab()
            TabId.M3U8     -> showM3u8Tab()
            TabId.Blocker  -> showBlockerTab()
            TabId.UA       -> showUaTab()
            TabId.Saved    -> showSavedTab()
            TabId.Cookie   -> showCookieTab()
            TabId.Plugin   -> showPluginTab()
            TabId.Storage  -> showStorageTab()
            TabId.CSS      -> showCssTab()
            TabId.Timeline -> showTimelineTab()
            TabId.SW       -> showSwIdbTab()
            TabId.DOM      -> showDomTab()
            TabId.Proxy    -> showProxyTab()
            TabId.History  -> showHistoryTab()
        }
    }

    // ── 1. Network ────────────────────────────────────────────────────────────
    private fun showNetworkTab() {
        val ctx = requireContext()
        val container = col(ctx)
        val filterRow = row(ctx, "#1E1E1E").apply { setPadding(12.dp,6.dp,8.dp,6.dp) }
        val searchBox = editText(ctx, "Lọc URL...")
        val btnX = btn(ctx, "✕", "#9CA3AF")
        filterRow.addView(searchBox)
        filterRow.addView(btnX)
        container.addView(filterRow)
        container.addView(divider(ctx))

        val rv = RecyclerView(ctx).apply { layoutManager=LinearLayoutManager(ctx); layoutParams=LinearLayout.LayoutParams(MATCH,0,1f) }
        val adapter = NetworkAdapter(detector.requests) { showRequestDetail(it) }
        rv.adapter = adapter
        container.addView(rv)

        searchBox.addTextChangedListener(tw { adapter.filter(it) })
        btnX.setOnClickListener { searchBox.setText("") }
        contentFrame.addView(container)
    }

    // ── 2. Streams ────────────────────────────────────────────────────────────
    private fun showStreamsTab() {
        val ctx = requireContext()
        val streams = detector.streams
        if (streams.isEmpty()) {
            contentFrame.addView(centerText(ctx, "Chưa có stream.\nHãy nhấn Play trên trang."))
            return
        }
        val rv = RecyclerView(ctx).apply { layoutManager=LinearLayoutManager(ctx); layoutParams=FrameLayout.LayoutParams(MATCH,MATCH) }
        rv.adapter = StreamAdapter(
            onCopy  = { copy(it.url) },
            onPlay  = { onPlayStream(it); close() },
            onShare = { share(it.url) }
        ).also { it.submitList(streams) }
        contentFrame.addView(rv)
    }

    // ── 3. Console ────────────────────────────────────────────────────────────
    private fun showConsoleTab() {
        val ctx = requireContext()
        val container = col(ctx)
        val (scroll, tv) = outputArea(ctx, detector.consoleLog, "#00FF41")
        container.addView(scroll)
        val presets = listOf(
            "video.src"      to "(function(){var r=[].slice.call(document.querySelectorAll('video,source')).map(function(v){return v.src||v.getAttribute('src')});return r.length?r.join('\\n'):'none';})()",
            "jwplayer"       to "try{var p=jwplayer();JSON.stringify({file:p.getPlaylistItem().file})}catch(e){'ERR:'+e.message}",
            "blob URLs"      to "(function(){var r=[].slice.call(document.querySelectorAll('video')).map(function(v){return v.src}).filter(function(s){return s&&s.startsWith('blob')});return r.length?r.join('\\n'):'none';})()",
            "localStorage"   to "(function(){try{var k=Object.keys(localStorage);return k.length?k.map(function(k){return k+'='+localStorage.getItem(k)}).join('\\n'):'empty';}catch(e){return 'blocked';}})()",
            "cookies"        to "(document.cookie||'(none)')",
            "m3u8 in DOM"    to "(function(){var m=document.documentElement.innerHTML.match(/https?:\\/\\/[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*/g);return m?[].concat(m).join('\\n'):'not found';})()",
            "mp4 in DOM"     to "(function(){var m=document.documentElement.innerHTML.match(/https?:\\/\\/[^\\s\"'<>]+\\.mp4[^\\s\"'<>]*/g);return m?[].concat(m).join('\\n'):'not found';})()",
            "all scripts"    to "(function(){return [].slice.call(document.querySelectorAll('script[src]')).map(function(s){return s.src}).join('\\n');})()",
            "window keys"    to "(function(){return Object.keys(window).filter(function(k){return['caches','indexedDB','crypto','performance','webkit'].indexOf(k)===-1}).slice(0,60).join(', ');})()",
        )
        addPresets(ctx, container, presets, tv, scroll, detector.consoleLog)
        container.addView(divider(ctx))
        addInputRow(ctx, container, tv, scroll, detector.consoleLog, false)
        contentFrame.addView(container)
    }

    // ── 4. Deep Inject ────────────────────────────────────────────────────────
    private fun showDeepTab() {
        val ctx = requireContext()
        val container = col(ctx)
        val (scroll, tv) = outputArea(ctx, detector.deepLog, "#4FC3F7")
        container.addView(scroll)
        val presets = listOf(
            "Hook XHR"        to "(function(){var _s=XMLHttpRequest.prototype.send;window.__xhr_log=window.__xhr_log||[];XMLHttpRequest.prototype.send=function(b){this.addEventListener('load',function(){var u=this.responseURL||'';if(u)window.__xhr_log.push({url:u,status:this.status,len:this.responseText.length});try{SBridge.onRequest(u,'xhr_hook','GET');}catch(e){}});return _s.apply(this,arguments);};return 'XHR hooked.';})();",
            "Dump XHR"        to "(function(){if(!window.__xhr_log||!window.__xhr_log.length)return 'No log.';return window.__xhr_log.slice(-8).map(function(x){return x.status+' '+x.url;}).join('\\n');})()",
            "Hook Fetch"      to "(function(){if(window.__fetch_hooked)return 'Already hooked';window.__fetch_hooked=true;var _f=window.fetch;window.__fetch_log=[];window.fetch=function(i,o){var u=typeof i==='string'?i:(i&&i.url)||'';return _f.apply(this,arguments).then(function(r){var url=r.url||u;window.__fetch_log.push({url:url,status:r.status});try{SBridge.onRequest(url,'fetch_hook','GET');}catch(e){}return r;});};return 'Fetch hooked.';})();",
            "Dump Fetch"      to "(function(){if(!window.__fetch_log||!window.__fetch_log.length)return 'No log.';return window.__fetch_log.slice(-8).map(function(x){return x.status+' '+x.url;}).join('\\n');})()",
            "Hook HLS.js"     to "(function(){if(!window.Hls)return 'No Hls.js';if(window.__hls_hooked)return 'Already hooked';window.__hls_hooked=true;var o=Hls.prototype.loadSource;Hls.prototype.loadSource=function(url){try{SBridge.onRequest(url,'hlsjs','GET');}catch(e){}return o.apply(this,arguments);};return 'HLS.js hooked!';})();",
            "Hook MSE"        to "(function(){if(window.__ms_hooked)return 'Already hooked';window.__ms_hooked=true;var o=MediaSource.prototype.addSourceBuffer;MediaSource.prototype.addSourceBuffer=function(mime){try{SBridge.onRequest('mse://'+encodeURIComponent(mime),'mse','GET');}catch(e){}return o.apply(this,arguments);};return 'MSE hooked.';})();",
            "Quét players"    to "(function(){var keys=['player','_player','videojs','jwplayer','hls','Hls','dash','shaka','flvjs','clappr','bitmovin','ZP'];var found=[];keys.forEach(function(k){if(window[k]!=null)found.push(k+':'+typeof window[k]);});return found.length?found.join('\\n'):'none';})()",
            "Tìm keys/tokens" to "(function(){var txt=document.documentElement.innerHTML.slice(0,200000);var out=[];var pats=[['key',/[\"']key[\"']\\s*:\\s*[\"']([^\"']{6,80})[\"']/gi],['token',/[\"']token[\"']\\s*:\\s*[\"']([^\"']{6,200})[\"']/gi],['secret',/[\"']secret[\"']\\s*:\\s*[\"']([^\"']{6,80})[\"']/gi]];pats.forEach(function(p){var m;p[1].lastIndex=0;while((m=p[1].exec(txt))!==null&&out.length<12)out.push(p[0]+': '+m[1]);});return out.length?out.join('\\n'):'none';})()",
            "Base64 decode"   to "(function(){var html=document.documentElement.innerHTML;var ms=html.match(/[\"']([A-Za-z0-9+\\/]{40,}={0,2})[\"']/g)||[];var out=[];ms.slice(0,30).forEach(function(m){try{var d=atob(m.replace(/[\"']/g,''));if(d.indexOf('http')!==-1||d.indexOf('m3u8')!==-1)out.push(d.slice(0,200));}catch(e){}});return out.length?out.join('\\n'):'none';})()",
            "Cookie+Storage"  to "(function(){var r={cookies:document.cookie};try{r.local=[].concat(Object.keys(localStorage)).map(function(k){return k+'='+localStorage.getItem(k)}).join('; ');}catch(e){r.local='blocked';}try{r.session=[].concat(Object.keys(sessionStorage)).map(function(k){return k+'='+sessionStorage.getItem(k)}).join('; ');}catch(e){r.session='blocked';}return JSON.stringify(r,null,2);})()",
            "Tất cả iframes"  to "(function(){var f=[].slice.call(document.querySelectorAll('iframe'));return f.length?f.map(function(el,i){return i+': '+(el.src||el.getAttribute('src')||'no-src');}).join('\\n'):'none';})()",
        )
        addPresets(ctx, container, presets, tv, scroll, detector.deepLog)
        container.addView(divider(ctx))
        addInputRow(ctx, container, tv, scroll, detector.deepLog, true)
        contentFrame.addView(container)
    }

    // ── 5. HTML Viewer ────────────────────────────────────────────────────────
    private fun showHtmlTab() {
        val ctx = requireContext()
        val activity = requireActivity() as? MainActivity
        val container = col(ctx)

        // ── Mode buttons ──────────────────────────────────────────────────────
        val modeRow = row(ctx, "#1E1E1E").apply { setPadding(10.dp, 8.dp, 10.dp, 8.dp) }

        val btnFull = Button(ctx).apply {
            text = "📄 Full HTML"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#10B981"))
            setPadding(14.dp, 6.dp, 14.dp, 6.dp)
        }
        val btnPicker = Button(ctx).apply {
            text = "✏ Element Picker"
            textSize = 11f
            setTextColor(Color.parseColor("#CCCCCC"))
            setBackgroundColor(Color.parseColor("#E24B4A"))
            setPadding(14.dp, 6.dp, 14.dp, 6.dp)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = 8.dp }
        }
        val tvInfo = tv(ctx, "Kết quả hiện qua dialog sau khi chọn element", "#555555", 10f).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = 8.dp }
        }
        modeRow.addView(btnFull)
        modeRow.addView(btnPicker)
        modeRow.addView(tvInfo)
        container.addView(modeRow)
        container.addView(divider(ctx))

        // ── Search ────────────────────────────────────────────────────────────
        val searchRow = row(ctx, "#121212").apply { setPadding(8.dp, 4.dp, 8.dp, 4.dp) }
        val searchBox = EditText(ctx).apply {
            hint = "🔍  Tìm trong HTML..."
            setHintTextColor(Color.parseColor("#444444"))
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; background = null
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val tvMatch = TextView(ctx).apply {
            text = ""; setTextColor(Color.parseColor("#10B981"))
            textSize = 10f; typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8.dp, 0, 0, 0)
        }
        searchRow.addView(searchBox); searchRow.addView(tvMatch)
        container.addView(searchRow)
        container.addView(divider(ctx))

        // ── Output ────────────────────────────────────────────────────────────
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            setBackgroundColor(Color.parseColor("#121212"))
        }
        val outputTv = TextView(ctx).apply {
            text = "📄 Full HTML – lấy toàn bộ HTML trang\n\n✏ Element Picker – đóng panel, tap vào element trên trang → dialog hiện HTML của đúng element đó"
            setTextColor(Color.parseColor("#555555"))
            textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
            setTextIsSelectable(true)
        }
        scroll.addView(outputTv); container.addView(scroll)
        container.addView(divider(ctx))

        // ── Action row ────────────────────────────────────────────────────────
        val actionRow = row(ctx, "#1E1E1E").apply { setPadding(8.dp, 6.dp, 8.dp, 6.dp) }
        val btnSnap = btn(ctx, "📸 Snap", "#1A1A2A", "#90CAF9").apply {
            setOnClickListener {
                snapshotUrls = detector.requests.map { it.url }.toSet()
                Toast.makeText(requireContext(), "Snapshot: ${snapshotUrls.size} requests", Toast.LENGTH_SHORT).show()
            }
        }
        val btnDiff = btn(ctx, "🔍 Diff", "#1A2A1A", "#A5D6A7").apply {
            setOnClickListener { showDiffDialog() }
        }
        // J1: SSL bypass toggle
        var sslOn = false
        val btnSsl = btn(ctx, "🔒 SSL", "#2A1A1A", "#9CA3AF").apply {
            setOnClickListener {
                sslOn = !sslOn
                (activity as? com.aho.streambrowser.ui.MainActivity)?.setSslBypass(sslOn)
                setTextColor(android.graphics.Color.parseColor(if (sslOn) "#E57373" else "#9CA3AF"))
                text = if (sslOn) "⚠ SSL" else "🔒 SSL"
            }
        }
        val btnCopyAll = Button(ctx).apply {
            text = "Copy All"; textSize = 11f
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#0D47A1"))
            setPadding(12.dp, 4.dp, 12.dp, 4.dp); isEnabled = false; alpha = 0.4f
        }
        val btnCopy3k = Button(ctx).apply {
            text = "Copy 3KB"; textSize = 11f
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#4A148C"))
            setPadding(12.dp, 4.dp, 12.dp, 4.dp)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = 8.dp }
            isEnabled = false; alpha = 0.4f
        }
        val tvSize = TextView(ctx).apply {
            text = ""; setTextColor(Color.parseColor("#555555"))
            textSize = 10f; typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = 8.dp }
            gravity = Gravity.END
        }
        val btnHar = btn(ctx, "📥 HAR", "#1A1A2A", "#64B5F6").apply {
            setOnClickListener {
                scope.launch {
                    val har = HarExporter.export(detector.requests)
                    contentFrame.post { showCodeDialog(requireContext(), "HAR Export (${detector.requestCount()} requests)", har) }
                }
            }
        }
        actionRow.addView(btnSnap); actionRow.addView(btnDiff); actionRow.addView(btnSsl); actionRow.addView(btnHar); actionRow.addView(tvSize)
        container.addView(actionRow)

        // ── State & logic ─────────────────────────────────────────────────────
        var currentHtml = ""

        fun displayHtml(html: String) {
            currentHtml = html
            outputTv.setTextColor(Color.parseColor("#00FF41"))
            outputTv.text = html
            val kb = html.length / 1024f
            tvSize.text = "${String.format("%.1f", kb)} KB"
            btnCopyAll.isEnabled = true; btnCopyAll.alpha = 1f
            btnCopy3k.isEnabled  = true; btnCopy3k.alpha  = 1f
            scroll.post { scroll.scrollTo(0, 0) }
        }

        btnFull.setOnClickListener {
            tvSize.text = "Đang tải..."
            outputTv.setTextColor(Color.parseColor("#9CA3AF"))
            outputTv.text = "Đang lấy HTML..."
            webView.evaluateJavascript("document.documentElement.outerHTML") { result ->
                requireActivity().runOnUiThread {
                    val html = result?.takeIf { it != "null" }
                        ?.removeSurrounding("\"")
                        ?.replace("\\n", "\n")?.replace("\\t", "\t")
                        ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
                        ?.replace("\\/", "/") ?: ""
                    if (html.isEmpty()) {
                        outputTv.text = "(Không lấy được HTML)"
                        tvSize.text = "Thất bại"
                    } else displayHtml(html)
                }
            }
        }

        btnPicker.setOnClickListener {
            close()
            activity?.activatePicker()
        }

        btnCopyAll.setOnClickListener {
            if (currentHtml.isEmpty()) return@setOnClickListener
            copy(currentHtml)
            Toast.makeText(ctx, "Đã copy ${currentHtml.length} ký tự", Toast.LENGTH_SHORT).show()
        }
        btnCopy3k.setOnClickListener {
            if (currentHtml.isEmpty()) return@setOnClickListener
            copy(currentHtml.take(3000))
            Toast.makeText(ctx, "Đã copy ${minOf(currentHtml.length, 3000)} ký tự", Toast.LENGTH_SHORT).show()
        }

        // Search + highlight
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString()
                if (q.isEmpty() || currentHtml.isEmpty()) {
                    outputTv.text = currentHtml; tvMatch.text = ""; return
                }
                val count = currentHtml.split(q, ignoreCase = true).size - 1
                tvMatch.text = "$count kết quả"
                val span = android.text.SpannableString(currentHtml)
                var idx = currentHtml.indexOf(q, ignoreCase = true); var n = 0
                while (idx >= 0 && n < 200) {
                    span.setSpan(android.text.style.BackgroundColorSpan(Color.parseColor("#FFD700")),
                        idx, idx + q.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    span.setSpan(android.text.style.ForegroundColorSpan(Color.BLACK),
                        idx, idx + q.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    idx = currentHtml.indexOf(q, idx + 1, ignoreCase = true); n++
                }
                outputTv.text = span
            }
        })

        contentFrame.addView(container)
    }

    // ── 6. M3U8 Parser ───────────────────────────────────────────────────────
    private fun showM3u8Tab() {
        val ctx = requireContext()
        val container = col(ctx)
        container.addView(tv(ctx, "Paste URL m3u8 để xem danh sách chất lượng:", "#9CA3AF", 11f).apply {
            setPadding(12.dp,10.dp,12.dp,4.dp)
        })

        val inputRow = row(ctx, "#1E1E1E").apply { setPadding(8.dp,6.dp,8.dp,6.dp) }
        val urlBox = editText(ctx, "https://example.com/master.m3u8").apply {
            detector.streams.firstOrNull { it.url.contains("m3u8") }?.let { setText(it.url) }
        }
        val btnParse = btn(ctx, "Parse", "#10B981", "#FFFFFF")
        inputRow.addView(urlBox)
        inputRow.addView(btnParse)
        container.addView(inputRow)
        container.addView(divider(ctx))

        val resultContainer = col(ctx)
        val scrollResult = ScrollView(ctx).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f) }
        scrollResult.addView(resultContainer)
        container.addView(scrollResult)

        btnParse.setOnClickListener {
            val url = urlBox.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            resultContainer.removeAllViews()
            resultContainer.addView(tv(ctx, "⏳ Đang parse...", "#9CA3AF", 11f).apply { setPadding(12.dp,12.dp,12.dp,0) })

            scope.launch {
                val referer = detector.streams.firstOrNull { it.url == url }?.referer ?: ""
                val qualities = withContext(Dispatchers.IO) {
                    try { M3u8Parser.parse(url, referer) }
                    catch (e: Exception) { emptyList() }
                }
                resultContainer.removeAllViews()
                if (qualities.isEmpty()) {
                    resultContainer.addView(tv(ctx, "Không parse được – có thể là media playlist hoặc cần auth.", "#E24B4A", 11f).apply { setPadding(12.dp,12.dp,12.dp,0) })
                    return@launch
                }
                resultContainer.addView(tv(ctx, "${qualities.size} quality streams:", "#10B981", 12f).apply { setPadding(12.dp,10.dp,12.dp,4.dp) })
                qualities.forEach { q ->
                    val card = col(ctx, "#242424").apply {
                        setPadding(12.dp,10.dp,12.dp,10.dp)
                        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                        lp.setMargins(8.dp,4.dp,8.dp,0)
                        layoutParams = lp
                    }
                    card.addView(tv(ctx, "📺 ${q.label}", "#FFFFFF", 13f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
                    card.addView(tv(ctx, q.url, "#9CA3AF", 10f).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    })
                    val btnRow = row(ctx)
                    btnRow.addView(btn(ctx, "Copy", "#0D47A1", "#FFFFFF").apply {
                        setOnClickListener { copy(q.url) }
                    })
                    btnRow.addView(btn(ctx, "Phát", "#10B981", "#FFFFFF").apply {
                        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = 8.dp }
                        setOnClickListener {
                            val stream = StreamItem.fromUrl(q.url, referer, "m3u8_parser")
                            if (stream != null) { onPlayStream(stream); close() }
                        }
                    })
                    card.addView(btnRow)
                    resultContainer.addView(card)
                }
            }
        }
        contentFrame.addView(container)
    }

    // ── 7. Blocker ───────────────────────────────────────────────────────────
    private fun showBlockerTab() {
        val ctx = requireContext()
        val blocker = com.aho.streambrowser.util.RequestBlocker(ctx)
        val container = col(ctx)
        container.addView(ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(col(ctx).apply {
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)

                // Stats
                addView(tv(ctx, "🚫 Đã block: ${blocker.blockedCount} requests", "#10B981", 13f).apply {
                    setPadding(0, 0, 0, 8.dp)
                })

                // Builtin toggle
                val builtinRow = row(ctx)
                builtinRow.addView(tv(ctx, "Block ads/trackers mặc định", "#FFFFFF", 12f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                })
                val sw = Switch(ctx).apply {
                    isChecked = blocker.isBuiltinEnabled()
                    setOnCheckedChangeListener { _, v -> blocker.setBuiltinEnabled(v) }
                }
                builtinRow.addView(sw)
                addView(builtinRow)
                addView(divider(ctx).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0,8.dp,0,8.dp) })

                // Custom patterns
                addView(tv(ctx, "Custom patterns:", "#9CA3AF", 11f))
                val patterns = blocker.getCustomPatterns()
                patterns.forEach { pattern ->
                    val row2 = row(ctx)
                    row2.addView(tv(ctx, pattern, "#FFFFFF", 11f).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                    })
                    row2.addView(btn(ctx, "✕", "#E24B4A").apply {
                        setOnClickListener { blocker.removePattern(pattern); contentFrame.removeAllViews(); showBlockerTab() }
                    })
                    addView(row2)
                }
                if (patterns.isEmpty()) addView(tv(ctx, "(chưa có pattern tùy chỉnh)", "#555555", 11f))

                // Add pattern
                addView(divider(ctx).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0,8.dp,0,8.dp) })
                val addRow = row(ctx, "#1E1E1E").apply { setPadding(8.dp,6.dp,8.dp,6.dp) }
                val input = editText(ctx, "vd: ads.example.com")
                val btnAdd = btn(ctx, "Thêm", "#10B981", "#FFFFFF")
                addRow.addView(input)
                addRow.addView(btnAdd)
                addView(addRow)
                btnAdd.setOnClickListener {
                    val p = input.text.toString().trim()
                    if (p.isNotEmpty()) { blocker.addPattern(p); contentFrame.removeAllViews(); showBlockerTab() }
                }
            })
        })
        contentFrame.addView(container)
    }

    // ── 8. User-Agent ────────────────────────────────────────────────────────
    private fun showUaTab() {
        val ctx = requireContext()
        val container = col(ctx)
        val current = UserAgentManager.load(ctx)
        container.addView(ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(col(ctx).apply {
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                addView(tv(ctx, "Current UA:", "#9CA3AF", 10f))
                addView(tv(ctx, current, "#10B981", 10f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    maxLines = 3
                    setPadding(0, 4.dp, 0, 12.dp)
                })
                addView(divider(ctx))

                UserAgentManager.presets.forEach { (name, ua) ->
                    val isActive = ua == current
                    val card = col(ctx, if (isActive) "#1A2A1A" else "#242424").apply {
                        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
                        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                        lp.setMargins(0, 6.dp, 0, 0)
                        layoutParams = lp
                    }
                    card.addView(tv(ctx, (if (isActive) "✓ " else "") + name, if (isActive) "#10B981" else "#FFFFFF", 13f).apply {
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                    card.addView(tv(ctx, ua, "#9CA3AF", 9f).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    card.setOnClickListener {
                        UserAgentManager.save(ctx, ua)
                        webView.settings.userAgentString = ua
                        injectUaOverride(ua)
                        Toast.makeText(ctx, "✓ UA: $name\nTrang sẽ reload để áp dụng", Toast.LENGTH_SHORT).show()
                        webView.reload()
                        contentFrame.removeAllViews(); showUaTab()
                    }
                    addView(card)
                }

                // Custom UA input
                addView(divider(ctx).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 12.dp, 0, 8.dp) })
                addView(tv(ctx, "Custom UA:", "#9CA3AF", 11f))
                val customInput = EditText(ctx).apply {
                    setText(current)
                    setTextColor(Color.parseColor("#FFFFFF"))
                    setHintTextColor(Color.parseColor("#555555"))
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    background = null
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    minLines = 2
                }
                addView(customInput)
                addView(btn(ctx, "Áp dụng UA này", "#10B981", "#FFFFFF").apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 8.dp }
                    setOnClickListener {
                        val ua2 = customInput.text.toString().trim()
                        if (ua2.isNotEmpty()) {
                            UserAgentManager.save(ctx, ua2)
                            webView.settings.userAgentString = ua2
                            injectUaOverride(ua2)
                            webView.reload()
                            Toast.makeText(ctx, "UA đã cập nhật, đang reload...", Toast.LENGTH_SHORT).show()
                            contentFrame.removeAllViews(); showUaTab()
                        }
                    }
                })
            })
        })
        contentFrame.addView(container)
    }

    // ── 9. Saved (Bookmark + History) ────────────────────────────────────────
    private fun showSavedTab() {
        val ctx = requireContext()
        val act = requireActivity() as? MainActivity ?: return
        val container = col(ctx)
        val bookmarks = BookmarkManager.getBookmarks(ctx)
        val history   = BookmarkManager.getHistory(ctx)
        val all       = bookmarks + history

        if (all.isEmpty()) { contentFrame.addView(centerText(ctx, "Chưa có bookmark/history.\nLong-press nút Home để bookmark trang hiện tại.")); return }

        val rv = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx); layoutParams = FrameLayout.LayoutParams(MATCH, MATCH) }
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class VH(
                val root: LinearLayout,
                val tvTitle: TextView,
                val tvUrl: TextView,
                val btnDel: Button
            ) : RecyclerView.ViewHolder(root)

            override fun onCreateViewHolder(parent: ViewGroup, t: Int): VH {
                val title = tv(ctx, "", "#FFFFFF", 12f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
                val url   = tv(ctx, "", "#9CA3AF", 10f).apply { typeface=android.graphics.Typeface.MONOSPACE; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.MIDDLE; layoutParams=LinearLayout.LayoutParams(0,WRAP,1f) }
                val del   = btn(ctx, "✕", "#E24B4A")
                val urlRow = row(ctx)
                urlRow.addView(url)
                urlRow.addView(del)
                val root2 = col(ctx, "#1E1E1E").apply {
                    setPadding(12.dp,10.dp,12.dp,10.dp)
                    addView(title); addView(urlRow)
                    addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#333333")); layoutParams=LinearLayout.LayoutParams(MATCH,1).apply{topMargin=6.dp} })
                }
                return VH(root2, title, url, del)
            }

            override fun getItemCount() = all.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val vh = h as VH
                val e = all[pos]
                vh.tvTitle.text = (if (e.isBookmark) "★ " else "  ") + e.title
                vh.tvUrl.text   = e.url
                vh.root.setOnClickListener { act.navigateTo(e.url); close() }
                vh.btnDel.setOnClickListener {
                    if (e.isBookmark) BookmarkManager.removeBookmark(ctx, e.url)
                    else BookmarkManager.clearHistory(ctx)
                    contentFrame.removeAllViews(); showSavedTab()
                }
            }
        }
        container.addView(rv)
        // Clear history button
        val btnClearHist = btn(ctx, "🗑 Xoá lịch sử duyệt", "#E24B4A", "#FFFFFF").apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(12.dp,8.dp,12.dp,8.dp) }
            setOnClickListener { BookmarkManager.clearHistory(ctx); contentFrame.removeAllViews(); showSavedTab() }
        }
        container.addView(btnClearHist)
        contentFrame.addView(container)
    }

    // ── Request detail (DevTools-style with full sections) ────────────────────
    private fun showRequestDetail(req: NetworkRequest) {
        val ctx = requireContext()
        val scrollView = ScrollView(ctx).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 16.dp)
        }

        // ── Section: General ──
        content.addView(sectionHeader(ctx, "📋 General"))
        content.addView(kvRow(ctx, "Request URL", req.url))
        content.addView(kvRow(ctx, "Request Method", req.method))
        if (req.statusCode > 0) {
            content.addView(kvRow(ctx, "Status Code", "${req.statusCode}"))
        }
        if (req.mimeType.isNotEmpty()) {
            content.addView(kvRow(ctx, "MIME Type", req.mimeType))
        }
        if (req.contentLength >= 0) {
            val sizeStr = if (req.contentLength > 1024) {
                String.format("%.1f KB", req.contentLength / 1024.0)
            } else {
                "${req.contentLength} B"
            }
            content.addView(kvRow(ctx, "Content Length", sizeStr))
        }
        content.addView(sectionDivider(ctx))

        // ── Section: Query Parameters (Payload) ──
        val queryParams = req.parseQueryParams()
        if (queryParams.isNotEmpty()) {
            content.addView(sectionHeader(ctx, "📤 Query Parameters"))
            queryParams.forEach { (k, v) ->
                content.addView(kvRow(ctx, k, v))
            }
            content.addView(sectionDivider(ctx))
        }

        // ── Section: Request Headers ──
        content.addView(sectionHeader(ctx, "📥 Request Headers"))
        if (req.headers.isEmpty()) {
            content.addView(tv(ctx, "  (none)", "#555555", 10f))
        } else {
            req.headers.forEach { (k, v) ->
                content.addView(kvRow(ctx, k, v))
            }
        }
        content.addView(sectionDivider(ctx))

        // ── Section: Response Headers ──
        content.addView(sectionHeader(ctx, "📤 Response Headers"))
        if (req.responseHeaders.isEmpty()) {
            content.addView(tv(ctx, "  ⏳ Đang tải... (mở lại để xem)", "#555555", 10f))
        } else {
            req.responseHeaders.forEach { (k, v) ->
                content.addView(kvRow(ctx, k, v))
            }
        }
        content.addView(sectionDivider(ctx))

        // ── Section: Response Body ──
        content.addView(sectionHeader(ctx, "📄 Response Body"))
        if (req.responseBodyPreview.isNotEmpty()) {
            val bodyTv = TextView(ctx).apply {
                text = req.responseBodyPreview
                setTextColor(Color.parseColor("#00FF41"))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(8.dp, 6.dp, 8.dp, 6.dp)
                setBackgroundColor(Color.parseColor("#121212"))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }
            content.addView(bodyTv)
        } else {
            content.addView(tv(ctx, "  ⏳ Đang tải... (mở lại để xem)", "#555555", 10f))
        }

        scrollView.addView(content)

        // Extra action buttons row
        val extraRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        val btnOkHttp = Button(ctx).apply {
            text = "⚙ OkHttp"; textSize = 11f; setTextColor(Color.parseColor("#4CAF50"))
            background = null; setPadding(10.dp, 6.dp, 10.dp, 6.dp)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            setOnClickListener { copy(buildOkHttpCode(req)) }
        }
        val btnCs3 = Button(ctx).apply {
            text = "☁ CS3"; textSize = 11f; setTextColor(Color.parseColor("#64B5F6"))
            background = null; setPadding(10.dp, 6.dp, 10.dp, 6.dp)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            visibility = if (req.isStream) View.VISIBLE else View.GONE
            setOnClickListener { copy(buildCs3Code(req)) }
        }
        extraRow.addView(btnOkHttp); extraRow.addView(btnCs3)
        content.addView(extraRow)

        val btnReplay = btn(ctx, "↺ Replay", "#2A1A1A", "#FF8A65").apply {
            setOnClickListener { showReplayDialog(req) }
        }
        extraRow.addView(btnReplay)

        AlertDialog.Builder(ctx)
            .setTitle("Request Detail")
            .setView(scrollView)
            .setPositiveButton("Copy URL")      { _,_ -> copy(req.url) }
            .setNeutralButton("Export cURL")    { _,_ -> copy(CurlExporter.toCurl(req)) }
            .setNegativeButton("Đóng", null)
            .show()
    }

    // ── Detail view helpers ──────────────────────────────────────────────────
    private fun sectionHeader(ctx: Context, title: String): TextView {
        return tv(ctx, title, "#10B981", 13f).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 12.dp, 0, 8.dp)
        }
    }

    private fun kvRow(ctx: Context, key: String, value: String): LinearLayout {
        return row(ctx).apply {
            setPadding(0, 2.dp, 0, 2.dp)
            addView(TextView(ctx).apply {
                text = "$key: "
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            })
            addView(TextView(ctx).apply {
                text = value
                setTextColor(Color.parseColor("#FFFFFF"))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
        }
    }

    private fun sectionDivider(ctx: Context): View {
        return View(ctx).apply {
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = 6.dp; bottomMargin = 6.dp }
        }
    }

    // ── Shared helpers ────────────────────────────────────────────────────────
    private fun addPresets(ctx: Context, container: LinearLayout, presets: List<Pair<String,String>>,
                           tv: TextView, scroll: ScrollView, log: StringBuilder) {
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled=false; layoutParams=LinearLayout.LayoutParams(MATCH,WRAP) }
        val row2 = row(ctx).apply { setPadding(8.dp,4.dp,8.dp,4.dp) }
        presets.forEach { (label, code) ->
            row2.addView(Button(ctx).apply {
                text=label; textSize=10f
                setTextColor(Color.parseColor("#4FC3F7"))
                setBackgroundColor(Color.parseColor("#0A1929"))
                setPadding(10.dp,4.dp,10.dp,4.dp)
                layoutParams = LinearLayout.LayoutParams(WRAP,WRAP).apply { marginEnd=6.dp }
                setOnClickListener { runJs(code, tv, scroll, log, label) }
            })
        }
        hs.addView(row2); container.addView(hs)
    }

    private fun addInputRow(ctx: Context, container: LinearLayout, outputTv: TextView,
                            scroll: ScrollView, log: StringBuilder, isDeep: Boolean) {
        val inputRow = row(ctx, "#1E1E1E").apply { setPadding(8.dp,6.dp,8.dp,6.dp) }
        val prefix = tv(ctx, "JS>", "#10B981", 12f).apply {
            typeface=android.graphics.Typeface.MONOSPACE; setPadding(0,0,8.dp,0)
        }
        var histIdx = detector.consoleHistory.size
        val jsInput = editText(ctx, "Nhập JS...").apply { layoutParams=LinearLayout.LayoutParams(0,WRAP,1f); maxLines=4 }
        val btnUp = btn(ctx, "↑", "#9CA3AF").apply {
            setOnClickListener {
                val h = detector.consoleHistory
                if (h.isEmpty()) return@setOnClickListener
                histIdx = (histIdx-1).coerceAtLeast(0)
                jsInput.setText(h[histIdx]); jsInput.setSelection(jsInput.text.length)
            }
        }
        val btnRun = btn(ctx, "▶", "#10B981", "#FFFFFF").apply {
            setOnClickListener {
                val code = jsInput.text.toString().trim(); if (code.isEmpty()) return@setOnClickListener
                if (detector.consoleHistory.lastOrNull()!=code) { detector.consoleHistory.add(code); if (detector.consoleHistory.size>50) detector.consoleHistory.removeAt(0) }
                histIdx = detector.consoleHistory.size
                runJs(code, outputTv, scroll, log, code.take(40))
                jsInput.setText("")
            }
        }
        val btnClear = btn(ctx, "🗑", "#E24B4A").apply {
            setOnClickListener {
                log.clear(); log.append(if (isDeep) "// Deep Inject\n" else "// Console\n")
                outputTv.text = log
            }
        }
        // D1: History button
        val btnHist = btn(ctx, "⏫", "#1A1A2A", "#90CAF9").apply {
            setOnClickListener {
                if (consoleHistory.isEmpty()) return@setOnClickListener
                consoleHistoryIndex = (consoleHistoryIndex + 1).coerceAtMost(consoleHistory.size - 1)
                jsInput.setText(consoleHistory[consoleHistoryIndex])
                jsInput.setSelection(jsInput.text.length)
            }
            setOnLongClickListener {
                if (consoleHistory.isEmpty()) return@setOnLongClickListener true
                val names = consoleHistory.take(20).toTypedArray()
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("History")
                    .setItems(names) { _, i ->
                        jsInput.setText(consoleHistory[i])
                        consoleHistoryIndex = i
                    }.show()
                true
            }
        }
        listOf(prefix, jsInput, btnHist, btnRun, btnClear).forEach { inputRow.addView(it) }
        container.addView(inputRow)
    }

    private fun runJs(code: String, output: TextView, scroll: ScrollView, log: StringBuilder, label: String) {
        log.append("\n▶ $label\n"); output.text = log
        // D1: Save to console history
        consoleHistory.remove(code)
        consoleHistory.add(0, code)
        if (consoleHistory.size > 50) consoleHistory.removeAt(consoleHistory.lastIndex)
        consoleHistoryIndex = -1
        webView.evaluateJavascript(code) { result ->
            requireActivity().runOnUiThread {
                val d = when {
                    result == null || result == "null" -> "(undefined)"
                    result.startsWith("\"") && result.endsWith("\"") ->
                        result.removeSurrounding("\"").replace("\\n","\n").replace("\\t","\t").replace("\\\"","\"").replace("\\\\","\\")
                    else -> result
                }
                log.append("$d\n"); output.text = log
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun outputArea(ctx: Context, log: StringBuilder, color: String): Pair<ScrollView, TextView> {
        val tv2 = TextView(ctx).apply {
            text=log; setTextColor(Color.parseColor(color))
            textSize=11f; typeface=android.graphics.Typeface.MONOSPACE
            setPadding(12.dp,12.dp,12.dp,12.dp); setTextIsSelectable(true)
        }
        val sv = ScrollView(ctx).apply {
            layoutParams=LinearLayout.LayoutParams(MATCH,0,1f)
            setBackgroundColor(Color.parseColor("#121212"))
            addView(tv2)
        }
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        return sv to tv2
    }

    /**
     * Override navigator.userAgent + các thuộc tính liên quan ở tầng JS.
     * Cần thiết vì sites bảo mật cao detect UA qua JS chứ không chỉ HTTP header.
     * Inject trước khi reload để có hiệu lực ngay từ đầu.
     */
    private fun injectUaOverride(ua: String) {
        val isMobile  = ua.contains("Mobile") || ua.contains("Android") || ua.contains("iPhone")
        val isChrome  = ua.contains("Chrome")
        val chromeVer = Regex("Chrome/([\\d.]+)").find(ua)?.groupValues?.get(1) ?: "124.0.0.0"
        val major     = chromeVer.split(".").firstOrNull() ?: "124"
        val platform  = when {
            ua.contains("Windows") -> "Win32"
            ua.contains("Mac")     -> "MacIntel"
            ua.contains("Linux") || ua.contains("Android") -> "Linux ${if (isMobile) "arm" else "x86_64"}"
            else -> "Linux x86_64"
        }
        val vendor = if (isChrome) "Google Inc." else ""

        val js = """
(function() {
    var ua = ${ua.let { "\"${it.replace("\"", "\\\"")}\"" }};
    var platform = "$platform";
    var vendor = "$vendor";

    // Override navigator properties
    var props = {
        userAgent:   { get: function() { return ua; } },
        platform:    { get: function() { return platform; } },
        vendor:      { get: function() { return vendor; } },
        appVersion:  { get: function() { return ua.replace('Mozilla/', ''); } },
        appName:     { get: function() { return 'Netscape'; } },
        webdriver:   { get: function() { return false; } },
        plugins:     { get: function() {
            return Object.create(PluginArray.prototype, {
                length: { get: function() { return 3; } },
                item:   { value: function(i) { return null; } },
                namedItem: { value: function(n) { return null; } }
            });
        }},
        languages:   { get: function() { return ['vi-VN', 'vi', 'en-US', 'en']; } }
    };
    Object.entries(props).forEach(function(e) {
        try { Object.defineProperty(navigator, e[0], e[1]); } catch(ex) {}
    });

    // Remove webdriver traces
    try { delete window.navigator.__proto__.webdriver; } catch(e) {}
    try { delete window.callPhantom; } catch(e) {}
    try { delete window._phantom; } catch(e) {}
    try { delete window.__nightmare; } catch(e) {}
    try { delete window.Buffer; } catch(e) {}
    try { delete window.emit; } catch(e) {}
    try { delete window.spawn; } catch(e) {}

    // Override toString để bypass detection
    window.navigator.userAgent.toString = function() { return ua; };
})();
""".trimIndent()

        // Inject vào trang hiện tại
        webView.evaluateJavascript(js, null)
    }

    // ── View helpers ──────────────────────────────────────────────────────────
    private fun col(ctx: Context, bg: String = ""): LinearLayout = LinearLayout(ctx).apply {
        orientation=LinearLayout.VERTICAL
        layoutParams=if(bg.isEmpty()) FrameLayout.LayoutParams(MATCH,MATCH) else LinearLayout.LayoutParams(MATCH,WRAP)
        if(bg.isNotEmpty()) setBackgroundColor(Color.parseColor(bg))
    }
    private fun row(ctx: Context, bg: String = ""): LinearLayout = LinearLayout(ctx).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
        layoutParams=LinearLayout.LayoutParams(MATCH,WRAP)
        if(bg.isNotEmpty()) setBackgroundColor(Color.parseColor(bg))
    }
    private fun tv(ctx: Context, text: String, color: String, size: Float) = TextView(ctx).apply {
        this.text=text; setTextColor(Color.parseColor(color)); textSize=size
        layoutParams=LinearLayout.LayoutParams(MATCH,WRAP)
    }
    private fun btn(ctx: Context, label: String, bg: String, fg: String = bg): Button = Button(ctx).apply {
        text=label; textSize=11f
        val hasFg = fg != bg
        setTextColor(if (hasFg) Color.parseColor(fg) else Color.parseColor("#9CA3AF"))
        if (hasFg) {
            setBackgroundColor(Color.parseColor(bg))
        } else {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#2D2D2D"))
                cornerRadius = 8.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#333333"))
            }
        }
        setPadding(12.dp, 6.dp, 12.dp, 6.dp)
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        stateListAnimator = null
    }
    private fun editText(ctx: Context, hint: String) = EditText(ctx).apply {
        this.hint=hint; setHintTextColor(Color.parseColor("#444444")); setTextColor(Color.parseColor("#FFFFFF"))
        textSize=12f; typeface=android.graphics.Typeface.MONOSPACE; background=null
        layoutParams=LinearLayout.LayoutParams(MATCH,WRAP)
    }
    private fun divider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#333333")); layoutParams=LinearLayout.LayoutParams(MATCH,1)
    }
    private fun centerText(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.parseColor("#9CA3AF"))
        textSize = 13f; gravity = Gravity.CENTER
        setPadding(24.dp, 56.dp, 24.dp, 0)
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
    }
    private fun tw(fn: (String)->Unit) = object:TextWatcher {
        override fun afterTextChanged(s: Editable?) { fn(s.toString()) }
        override fun beforeTextChanged(s: CharSequence?,a:Int,b:Int,c:Int){}
        override fun onTextChanged(s: CharSequence?,a:Int,b:Int,c:Int){}
    }
    private fun copy(text: String) {
        try {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // Use ClipData with proper MIME type for long text
            val clip = ClipData.newPlainText("StreamBrowser", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Đã copy ${text.length} ký tự", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Copy thất bại: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Chia sẻ"))
    }

    private val Int.dp  get() = (this * resources.displayMetrics.density).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT


    // ── 🔐 Crypto Tab ────────────────────────────────────────────────────────
    private fun showCryptoTab() {
        val ctx = requireContext()
        val container = col(ctx)
        val sv = ScrollView(ctx).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH) }
        val inner = col(ctx).apply { setPadding(12.dp, 8.dp, 12.dp, 16.dp) }
        sv.addView(inner)
        container.addView(sv)

        // ── Section: Captured Keys ─────────────────────────────────────────
        val keys = detector.cryptoKeys
        inner.addView(sectionHeader(ctx, "🔑 Captured Keys (${keys.size})"))
        if (keys.isEmpty()) {
            inner.addView(tv(ctx, "Chưa có key nào. Mở trang có CryptoJS hoặc Web Crypto API.", "#9CA3AF", 12f).apply {
                setPadding(0, 8.dp, 0, 16.dp)
            })
        } else {
            keys.forEach { cap ->
                val card = col(ctx, "#1A2A1A").apply { setPadding(10.dp,8.dp,10.dp,8.dp)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp }
                }
                card.addView(tv(ctx, cap.algorithm, "#4CAF50", 10f))
                card.addView(tv(ctx, "KEY: ${cap.key}", "#FFFFFF", 11f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
                })
                if (cap.iv.isNotBlank()) card.addView(tv(ctx, "IV:  ${cap.iv}", "#90CAF9", 11f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
                })
                card.addView(tv(ctx, cap.pageUrl.take(60), "#666666", 9f))
                val btnCopy = btn(ctx, "Copy Key", "#1A2A1A", "#4CAF50").apply {
                    setOnClickListener { copy(cap.key) }
                }
                card.addView(btnCopy)
                inner.addView(card)
            }
        }

        // ── Section: Encrypted Response Bodies ───────────────────────────
        inner.addView(divider(ctx).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = 12.dp; bottomMargin = 12.dp } })
        val bodies = detector.responseBodies
        // AES Key Finder from loaded JS files
        inner.addView(divider(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = 4.dp; bottomMargin = 8.dp }
        })
        inner.addView(sectionHeader(ctx, "🔍 Find AES Keys in JS Files"))
        val btnFindKeys = btn(ctx, "Scan JS Files for Keys", "#1A2A2A", "#FFD54F").apply {
            setOnClickListener {
                text = "Scanning..."
                val jsUrls = detector.requests.filter {
                    it.url.endsWith(".js") || it.url.contains(".js?")
                }.map { it.url }.take(15)
                scope.launch {
                    val found = AesKeyFinder.scanJsFiles(jsUrls, webView.url ?: "")
                    contentFrame.post {
                        text = "Scan JS Files for Keys"
                        if (found.isEmpty()) {
                            Toast.makeText(requireContext(), "Không tìm thấy key nào trong ${jsUrls.size} JS files", Toast.LENGTH_SHORT).show()
                        } else {
                            showFoundKeysDialog(found)
                        }
                    }
                }
            }
        }
        inner.addView(btnFindKeys)
        inner.addView(tv(ctx, "${detector.requests.count { it.url.endsWith(".js") || it.url.contains(".js?") }} JS files available to scan", "#666", 10f)
            .apply { setPadding(0, 2.dp, 0, 12.dp) })

        inner.addView(sectionHeader(ctx, "🔒 Encrypted Responses (${bodies.size})")  )
        if (bodies.isEmpty()) {
            inner.addView(tv(ctx, "Chưa có. XHR trả về hex IV:CIPHERTEXT sẽ hiện ở đây.", "#9CA3AF", 12f).apply { setPadding(0,8.dp,0,16.dp) })
        } else {
            bodies.take(5).forEach { rb ->
                val card = col(ctx, "#2A1A1A").apply { setPadding(10.dp,8.dp,10.dp,8.dp)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp }
                }
                card.addView(tv(ctx, rb.url.take(70), "#E57373", 10f).apply { typeface = android.graphics.Typeface.MONOSPACE })
                card.addView(tv(ctx, "HTTP ${rb.statusCode} • ${rb.body.length} chars", "#9CA3AF", 9f))
                card.addView(tv(ctx, rb.body.take(80) + "…", "#FFFFFF", 10f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
                })
                val btnCopyBody = btn(ctx, "Copy", "#2A1A1A", "#E57373").apply { setOnClickListener { copy(rb.body) } }
                card.addView(btnCopyBody)
                inner.addView(card)
            }
        }

        // ── Section: Manual AES Decrypt ───────────────────────────────────
        inner.addView(divider(ctx).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = 12.dp; bottomMargin = 12.dp } })
        inner.addView(sectionHeader(ctx, "🔓 Manual AES-CBC Decrypt"))
        inner.addView(tv(ctx, "Format: IV_HEX:CIPHERTEXT_HEX", "#666666", 10f).apply { setPadding(0,0,0,4.dp) })

        val inputCipher = editText(ctx, "IV_HEX:CIPHERTEXT_HEX").apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp }
            minLines = 2
        }
        val inputKey    = editText(ctx, "Key (string or hex)").apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp }
        }
        val tvResult    = tv(ctx, "", "#64B5F6", 11f).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(0, 8.dp, 0, 0)
        }
        val btnDecrypt  = btn(ctx, "🔓 Decrypt", "#1A1A2A", "#64B5F6").apply {
            setOnClickListener {
                val cipher = inputCipher.text.toString().trim()
                val key    = inputKey.text.toString().trim()
                tvResult.text = aesDecrypt(cipher, key)
            }
        }
        val btnFillLast = btn(ctx, "↓ Fill last body", "#1E1E1E").apply {
            setOnClickListener { bodies.firstOrNull()?.let { inputCipher.setText(it.body) } }
        }
        val btnFillKey  = btn(ctx, "↓ Fill last key", "#1E1E1E").apply {
            setOnClickListener { keys.firstOrNull()?.let { inputKey.setText(it.key) } }
        }
        val btnRow = row(ctx).apply { gravity = android.view.Gravity.START }
        listOf(btnDecrypt, btnFillLast, btnFillKey).forEach { btnRow.addView(it) }

        listOf(inputCipher, inputKey, btnRow, tvResult).forEach { inner.addView(it) }

        // ── Section: Hex ↔ Base64 converter ─────────────────────────────
        inner.addView(divider(ctx).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = 12.dp; bottomMargin = 12.dp } })
        inner.addView(sectionHeader(ctx, "🔄 Hex ↔ Base64"))
        val inputConv  = editText(ctx, "Nhập hex hoặc base64...").apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp }
        }
        val tvConvOut  = tv(ctx, "", "#FFD54F", 11f).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(0, 8.dp, 0, 0)
        }
        val btnHexToB64 = btn(ctx, "Hex→B64", "#1A1A2A", "#FFD54F").apply {
            setOnClickListener {
                try {
                    val hex = inputConv.text.toString().trim().replace(" ","")
                    val bytes = ByteArray(hex.length/2) { i -> hex.substring(i*2,i*2+2).toInt(16).toByte() }
                    tvConvOut.text = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                } catch(e: Exception) { tvConvOut.text = "Lỗi: ${e.message}" }
            }
        }
        val btnB64ToHex = btn(ctx, "B64→Hex", "#1A2A1A", "#A5D6A7").apply {
            setOnClickListener {
                try {
                    val bytes = android.util.Base64.decode(inputConv.text.toString().trim(), android.util.Base64.DEFAULT)
                    tvConvOut.text = bytes.joinToString("") { "%02x".format(it) }
                } catch(e: Exception) { tvConvOut.text = "Lỗi: ${e.message}" }
            }
        }
        val convRow = row(ctx).apply { addView(btnHexToB64); addView(btnB64ToHex) }
        listOf(inputConv, convRow, tvConvOut).forEach { inner.addView(it) }

        contentFrame.removeAllViews()
        contentFrame.addView(container)
    }

    // ── AES-CBC decrypt helper ─────────────────────────────────────────────
    private fun aesDecrypt(input: String, keyInput: String): String {
        val parts = input.trim().split(":")
        if (parts.size < 2) return "Format cần: IV_HEX:CIPHERTEXT_HEX"
        val ivHex     = parts[0]
        val cipherHex = parts.drop(1).joinToString("")
        return try {
            val keyBytes = if (keyInput.length in listOf(32,48,64) && keyInput.all{it in '0'..'9'||it in 'a'..'f'||it in 'A'..'F'}) {
                hexToBytes(keyInput)
            } else {
                keyInput.toByteArray(Charsets.UTF_8).let { when { it.size<=16->it.copyOf(16); it.size<=24->it.copyOf(24); else->it.copyOf(32) } }
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes,"AES"), IvParameterSpec(hexToBytes(ivHex)))
            String(cipher.doFinal(hexToBytes(cipherHex)), Charsets.UTF_8)
        } catch(e: Exception) { "Decrypt fail: ${e.message}" }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.replace(":","").replace(" ","")
        return ByteArray(h.length/2) { i -> h.substring(i*2,i*2+2).toInt(16).toByte() }
    }

    // ── OkHttp Kotlin code generator ─────────────────────────────────────
        private fun buildOkHttpCode(req: NetworkRequest): String {
        val dq = '"'
        val lines = mutableListOf(
            "val client = OkHttpClient()",
            "",
            "val request = Request.Builder()"
        )
        lines.add("    .url(" + dq + req.url + dq + ")")
        req.headers.forEach { (k, v) ->
            if (k.lowercase() !in listOf("host", "content-length", "connection"))
                lines.add("    .addHeader(" + dq + k + dq + ", " + dq + v + dq + ")")
        }
        lines.addAll(listOf(
            "    .get()", "    .build()", "",
            "val response = client.newCall(request).execute()"
        ))
        return lines.joinToString("\n")
    }

    // ── CloudStream3 ExtractorLink code ──────────────────────────────────
        private fun buildCs3Code(req: NetworkRequest): String {
        val dq = '"'
        val isM3u8  = req.url.contains(".m3u8", true)
        val referer = req.headers["Referer"] ?: req.headers["referer"] ?: req.pageUrl
        val ltype   = if (isM3u8) "ExtractorLinkType.M3U8" else "ExtractorLinkType.VIDEO"
        return listOf(
            "callback(newExtractorLink(",
            "    source = " + dq + "SOURCE_NAME" + dq + ",",
            "    name   = " + dq + "SOURCE_NAME" + dq + ",",
            "    url    = " + dq + req.url + dq + ",",
            "    type   = $ltype",
            ") {",
            "    quality = Qualities.P1080.value",
            "    headers = mapOf(",
            "        " + dq + "User-Agent" + dq + " to " + dq + "Mozilla/5.0" + dq + ",",
            "        " + dq + "Referer" + dq + " to " + dq + referer + dq,
            "    )",
            "})"
        ).joinToString("\n")
    }


    // ── 🔌 WebSocket Tab ─────────────────────────────────────────────────────
    private fun showWsTab() {
        val ctx = requireContext()
        val inner = col(ctx).apply { setPadding(12.dp, 8.dp, 12.dp, 16.dp) }
        val sv = android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
            addView(inner)
        }
        val msgs = detector.wsMessages
        inner.addView(sectionHeader(ctx, "WebSocket Messages (${msgs.size})"))
        if (msgs.isEmpty()) {
            inner.addView(tv(ctx, "Chưa có WS message. Mở trang dùng WebSocket API.", "#888", 12f)
                .apply { setPadding(0, 8.dp, 0, 0) })
        } else {
            msgs.take(50).forEach { msg ->
                val bg = when(msg.direction) {
                    "open"  -> "#1A2A1A"; "send" -> "#2A1A1A"; "recv" -> "#1A1A2A"; else -> "#1E1E1E"
                }
                val icon = when(msg.direction) { "open"->"🟢"; "send"->"↑"; "recv"->"↓"; else->"✖" }
                val card = col(ctx, bg).apply {
                    setPadding(8.dp, 6.dp, 8.dp, 6.dp)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 4.dp }
                }
                card.addView(tv(ctx, "$icon ${msg.wsUrl.take(60)}", "#FFFFFF", 10f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE })
                if (msg.data.isNotBlank()) {
                    card.addView(tv(ctx, msg.data.take(200), "#B0B0B0", 10f).apply {
                        typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
                    })
                }
                card.addView(btn(ctx, "Copy", bg).apply { setOnClickListener { copy(msg.data) } })
                inner.addView(card)
            }
        }
        contentFrame.removeAllViews()
        val container = col(ctx)
        container.addView(sv)
        contentFrame.addView(container)
    }

    // ── 🍪 Cookie Tab ────────────────────────────────────────────────────────
    private fun showCookieTab() {
        val ctx = requireContext()
        val inner = col(ctx).apply { setPadding(12.dp, 8.dp, 12.dp, 16.dp) }
        val sv = android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
            addView(inner)
        }
        val url = webView.url ?: ""
        val raw = CookieExporter.getRaw(url)

        inner.addView(sectionHeader(ctx, "Cookies — ${url.take(50)}"))
        if (raw.isBlank()) {
            inner.addView(tv(ctx, "Không có cookie cho URL này.", "#888", 12f))
        } else {
            val parsed = CookieExporter.parse(raw)
            parsed.forEach { (k, v) ->
                val rowLayout = row(ctx, "#1E1E1E").apply {
                    setPadding(8.dp, 6.dp, 8.dp, 6.dp)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 3.dp }
                }
                val isJwt = JwtDecoder.isJwt(v)
                val keyTv  = tv(ctx, k, if (isJwt) "#FFD54F" else "#64B5F6", 11f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                val valTv  = tv(ctx, v.take(40) + if (v.length > 40) "…" else "", "#FFFFFF", 10f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 2f)
                    typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
                }
                rowLayout.addView(keyTv); rowLayout.addView(valTv)
                inner.addView(rowLayout)

                // If JWT, add decode button
                if (isJwt) {
                    inner.addView(btn(ctx, "🔍 Decode JWT: $k", "#1A2A2A", "#FFD54F").apply {
                        setOnClickListener {
                            val info = JwtDecoder.decode(v)
                            if (info != null) showJwtDialog(info) else Toast.makeText(ctx,"Parse fail",Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
        }

        inner.addView(divider(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = 12.dp; bottomMargin = 12.dp }
        })
        inner.addView(sectionHeader(ctx, "Export"))
        val exports = listOf(
            "Cookie raw"   to CookieExporter.toDocument(url),
            "curl -b"      to CookieExporter.toCurlFlag(url),
            "Kotlin Map"   to CookieExporter.toKotlinMap(url),
            "Header line"  to CookieExporter.toHeaderLine(url)
        )
        exports.forEach { (label, value) ->
            inner.addView(btn(ctx, "Copy $label", "#1E1E1E").apply {
                setOnClickListener { copy(value); Toast.makeText(ctx,"$label copied",Toast.LENGTH_SHORT).show() }
            })
        }

        contentFrame.removeAllViews()
        contentFrame.addView(sv)
    }

    private fun showJwtDialog(info: JwtDecoder.JwtInfo) {
        val ctx = requireContext()
        val sv  = android.widget.ScrollView(ctx)
        val col = col(ctx).apply { setPadding(16.dp, 8.dp, 16.dp, 16.dp) }
        col.addView(tv(ctx, "Header:", "#64B5F6", 11f))
        col.addView(tv(ctx, info.header, "#FFFFFF", 10f).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
        })
        col.addView(tv(ctx, "Payload:", "#64B5F6", 11f).apply { setPadding(0, 8.dp, 0, 0) })
        col.addView(tv(ctx, info.payload, "#FFFFFF", 10f).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
        })
        val expColor = if (info.isExpired) "#E57373" else "#4CAF50"
        col.addView(tv(ctx, "Exp: ${info.expTime} ${if (info.isExpired) "⚠ EXPIRED" else "✓ valid"}", expColor, 11f)
            .apply { setPadding(0, 8.dp, 0, 0) })
        if (info.subject.isNotBlank()) col.addView(tv(ctx, "sub: ${info.subject}", "#888", 10f))
        if (info.issuer.isNotBlank())  col.addView(tv(ctx, "iss: ${info.issuer}",  "#888", 10f))
        sv.addView(col)
        AlertDialog.Builder(ctx).setTitle("JWT Decoded").setView(sv)
            .setPositiveButton("Copy Payload") { _,_ -> copy(info.payload) }
            .setNegativeButton("Đóng", null).show()
    }

    // ── ☁ Plugin Generator Tab ────────────────────────────────────────────────
    private fun showPluginTab() {
        val ctx = requireContext()
        val inner = col(ctx).apply { setPadding(12.dp, 8.dp, 12.dp, 16.dp) }
        val sv = android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
            addView(inner)
        }
        val url = webView.url ?: ""
        val site = runCatching { java.net.URL(url).let { u -> "${u.protocol}://${u.host}" } }.getOrElse { url }

        inner.addView(sectionHeader(ctx, "☁ CloudStream3 Plugin Generator"))
        inner.addView(tv(ctx, "Site: $site", "#888", 10f).apply { setPadding(0, 0, 0, 8.dp) })

        // Plugin name input
        val inputName = editText(ctx, "Plugin name (e.g. HentaiZ)").apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 8.dp }
            setText(site.substringAfterLast("/").substringAfterLast(".").replaceFirstChar { it.uppercaseChar() })
        }
        inner.addView(inputName)

        // URL Pattern analysis
        val btnAnalyze = btn(ctx, "🔍 Analyze URL Patterns", "#1A1A2A", "#64B5F6").apply {
            setOnClickListener {
                val analysis = PluginGenerator.analyzePatterns(detector.requests)
                showCodeDialog(ctx, "URL Patterns", analysis)
            }
        }
        inner.addView(btnAnalyze)

        // Generate extractor code
        val btnExtractor = btn(ctx, "⚙ Generate Extractor Code", "#1A2A1A", "#4CAF50").apply {
            setOnClickListener {
                val cookies = CookieExporter.toDocument(url)
                val session = PluginGenerator.SessionData(
                    streams  = detector.streams,
                    requests = detector.requests,
                    siteUrl  = site,
                    cookies  = cookies,
                    referer  = url
                )
                showCodeDialog(ctx, "ExtractorLink Code", PluginGenerator.generateExtractorCode(session))
            }
        }
        inner.addView(btnExtractor)

        // Generate full skeleton
        val btnSkeleton = btn(ctx, "📋 Generate Plugin Skeleton", "#2A1A1A", "#FF8A65").apply {
            setOnClickListener {
                val name = inputName.text.toString().ifBlank { "MyPlugin" }
                showCodeDialog(ctx, "Plugin Skeleton", PluginGenerator.generateSkeleton(name, site))
            }
        }
        inner.addView(btnSkeleton)

        inner.addView(divider(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = 12.dp; bottomMargin = 8.dp }
        })
        inner.addView(sectionHeader(ctx, "Session Summary"))
        inner.addView(tv(ctx, buildString {
            appendLine("Streams captured: ${detector.streamCount()}")
            appendLine("Requests captured: ${detector.requestCount()}")
            appendLine("WebSocket messages: ${detector.wsCount()}")
            appendLine("Crypto keys: ${detector.cryptoCount()}")
            val cookies = CookieExporter.toDocument(url)
            appendLine("Cookies: ${if (cookies.isBlank()) "none" else "${cookies.split(";").size} entries"}")
        }, "#FFFFFF", 11f))

        contentFrame.removeAllViews()
        contentFrame.addView(sv)
    }

    private fun showCodeDialog(ctx: android.content.Context, title: String, code: String) {
        val sv  = android.widget.ScrollView(ctx)
        val tvCode = tv(ctx, code, "#FFFFFF", 10f).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
        }
        sv.addView(tvCode)
        AlertDialog.Builder(ctx).setTitle(title).setView(sv)
            .setPositiveButton("Copy") { _,_ -> copy(code) }
            .setNegativeButton("Đóng", null).show()
    }

    // ── A7: Auto-test stream ──────────────────────────────────────────────────
    private suspend fun testStream(url: String, referer: String): Boolean {
        return try {
            val req = okhttp3.Request.Builder().url(url).head()
                .addHeader("Referer", referer)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    // ── A2: Quality dialog ────────────────────────────────────────────────────
    private fun showQualityDialog(qualities: List<M3u8QualityParser.Quality>, referer: String) {
        val ctx = requireContext()
        if (qualities.isEmpty()) {
            AlertDialog.Builder(ctx).setTitle("Quality Info")
                .setMessage("Không parse được quality variants (có thể là single-bitrate stream).")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = qualities.map { "${it.label} — ${it.bandwidth/1000}kbps ${it.codecs.take(20)}" }
        AlertDialog.Builder(ctx).setTitle("Chọn quality")
            .setItems(labels.toTypedArray()) { _, i ->
                copy(qualities[i].url)
                Toast.makeText(ctx, "Đã copy ${qualities[i].label} URL", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null).show()
    }



    // ── 💾 Storage Tab (D4+D5) ───────────────────────────────────────────────
    private fun showStorageTab() {
        val ctx = requireContext()
        val inner = col(ctx).apply { setPadding(12.dp, 8.dp, 12.dp, 16.dp) }
        val sv = android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
        }
        sv.addView(inner)

        inner.addView(sectionHeader(ctx, "💾 localStorage & sessionStorage"))
        val tvResult = tv(ctx, "Đang đọc...", "#FFFFFF", 10f).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
        }
        inner.addView(tvResult)

        webView.evaluateJavascript("""
            (function() {
                try {
                    var ls = {}, ss = {};
                    for (var i = 0; i < localStorage.length; i++) {
                        var k = localStorage.key(i); ls[k] = localStorage.getItem(k);
                    }
                    for (var i = 0; i < sessionStorage.length; i++) {
                        var k = sessionStorage.key(i); ss[k] = sessionStorage.getItem(k);
                    }
                    return JSON.stringify({localStorage: ls, sessionStorage: ss});
                } catch(e) { return JSON.stringify({error: e.toString()}); }
            })()
        """) { raw ->
            val result = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"","\"")?.replace("\\n","\n") ?: "{}"
            activity?.runOnUiThread {
                try {
                    val json = org.json.JSONObject(result)
                    val sb = StringBuilder()
                    sb.appendLine("=== localStorage ===")
                    val ls = json.optJSONObject("localStorage")
                    if (ls == null || ls.length() == 0) sb.appendLine("(empty)")
                    else ls.keys().forEach { k -> sb.appendLine("$k: ${ls.getString(k).take(120)}") }
                    sb.appendLine()
                    sb.appendLine("=== sessionStorage ===")
                    val ss = json.optJSONObject("sessionStorage")
                    if (ss == null || ss.length() == 0) sb.appendLine("(empty)")
                    else ss.keys().forEach { k -> sb.appendLine("$k: ${ss.getString(k).take(120)}") }
                    tvResult.text = sb.toString()

                    // Add copy + clear buttons
                    val btnCopy = btn(ctx, "Copy All", "#1E1E1E").apply {
                        setOnClickListener { copy(sb.toString()) }
                    }
                    val btnClearLs = btn(ctx, "🗑 Clear localStorage", "#2A1A1A", "#E57373").apply {
                        setOnClickListener {
                            webView.evaluateJavascript("localStorage.clear(); void 0", null)
                            Toast.makeText(ctx, "localStorage cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                    val btnClearSs = btn(ctx, "🗑 Clear sessionStorage", "#2A1A1A", "#E57373").apply {
                        setOnClickListener {
                            webView.evaluateJavascript("sessionStorage.clear(); void 0", null)
                            Toast.makeText(ctx, "sessionStorage cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                    listOf(btnCopy, btnClearLs, btnClearSs).forEach { inner.addView(it) }
                } catch (_: Exception) { tvResult.text = result }
            }
        }

        contentFrame.removeAllViews()
        contentFrame.addView(sv)
    }

    // ── 🎨 CSS Injector Tab (D3) ─────────────────────────────────────────────
    private fun showCssTab() {
        val ctx = requireContext()
        val inner = col(ctx).apply { setPadding(12.dp, 8.dp, 12.dp, 16.dp) }
        val sv = android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
            addView(inner)
        }
        inner.addView(sectionHeader(ctx, "🎨 CSS Injector"))

        val inputCss = editText(ctx, "/* Nhập CSS tại đây */\nbody { background: #000 !important; }").apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, (160 * resources.displayMetrics.density).toInt()).apply {
                bottomMargin = 8.dp
            }
            minLines = 6
        }
        inner.addView(inputCss)

        val btnInject = btn(ctx, "▶ Inject CSS", "#1A2A1A", "#4CAF50").apply {
            setOnClickListener {
                val css = inputCss.text.toString()
                    .replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
                val js = """
                    (function() {
                        var id = '__sb_css_inject';
                        var el = document.getElementById(id);
                        if (!el) { el = document.createElement('style'); el.id = id; document.head.appendChild(el); }
                        el.textContent = `$css`;
                        return 'injected';
                    })()
                """.trimIndent()
                webView.evaluateJavascript(js) { res ->
                    activity?.runOnUiThread {
                        Toast.makeText(ctx, "CSS injected ✅", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        val btnRemove = btn(ctx, "✖ Remove CSS", "#2A1A1A", "#E57373").apply {
            setOnClickListener {
                webView.evaluateJavascript("""
                    var el = document.getElementById('__sb_css_inject');
                    if (el) el.remove(); 'removed'
                """.trimIndent(), null)
                Toast.makeText(ctx, "CSS removed", Toast.LENGTH_SHORT).show()
            }
        }
        inner.addView(row(ctx).apply { addView(btnInject); addView(btnRemove) })

        inner.addView(divider(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = 12.dp; bottomMargin = 8.dp }
        })
        inner.addView(sectionHeader(ctx, "Presets"))
        val presets = mapOf(
            "🌑 Dark override" to """* { background: #111 !important; color: #eee !important; } a { color: #64b5f6 !important; }""",
            "🙈 Hide ads"       to """.ad,.ads,.advertisement,[id*=ad],[class*=ad],iframe[src*=ad] { display:none!important; }""",
            "👁 Show hidden"    to """[style*="display:none"],[hidden] { display:block!important; visibility:visible!important; }""",
            "🔍 Highlight video" to """video { outline: 3px solid #1D9E75 !important; }""",
            "📐 Desktop layout"  to """body { min-width: 1280px !important; zoom: 0.7; }"""
        )
        presets.forEach { (label, css) ->
            inner.addView(btn(ctx, label, "#1E1E1E").apply {
                setOnClickListener { inputCss.setText(css) }
            })
        }

        contentFrame.removeAllViews()
        contentFrame.addView(sv)
    }

    // ── B6: Request Replay Dialog ─────────────────────────────────────────────
    private fun showReplayDialog(req: NetworkRequest) {
        val ctx = requireContext()
        val inner = col(ctx).apply { setPadding(16.dp, 8.dp, 16.dp, 8.dp) }
        val sv = android.widget.ScrollView(ctx)
        sv.addView(inner)

        inner.addView(sectionHeader(ctx, "URL"))
        val inputUrl = editText(ctx, req.url).apply {
            setText(req.url)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp }
        }
        inner.addView(inputUrl)

        inner.addView(sectionHeader(ctx, "Headers (một dòng = key: value)"))
        val headersText = req.headers
            .filter { (k, _) -> k.lowercase() !in listOf("host", "connection", "accept-encoding") }
            .entries.joinToString("\n") { "${it.key}: ${it.value}" }
        val inputHeaders = editText(ctx, "Header: Value").apply {
            setText(headersText)
            minLines = 4
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp }
        }
        inner.addView(inputHeaders)

        val tvResponse = tv(ctx, "", "#FFFFFF", 10f).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
            setPadding(0, 8.dp, 0, 0)
        }

        AlertDialog.Builder(ctx)
            .setTitle("↺ Replay Request")
            .setView(sv)
            .setPositiveButton("Send") { _, _ ->
                val url = inputUrl.text.toString().trim()
                val headers = inputHeaders.text.toString().lines()
                    .mapNotNull { line ->
                        val i = line.indexOf(":"); if (i < 0) null
                        else line.substring(0,i).trim() to line.substring(i+1).trim()
                    }.toMap()
                executeReplay(url, headers) { status, body ->
                    inner.addView(sectionHeader(ctx, "Response: HTTP $status"))
                    tvResponse.text = body.take(5000)
                    inner.addView(tvResponse)
                    inner.addView(btn(ctx, "Copy Response", "#1E1E1E").apply {
                        setOnClickListener { copy(body) }
                    })
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun executeReplay(url: String, headers: Map<String,String>, onResult: (Int, String) -> Unit) {
        scope.launch {
            try {
                val rb = okhttp3.Request.Builder().url(url)
                headers.forEach { (k, v) -> rb.addHeader(k, v) }
                rb.get()
                val resp = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build().newCall(rb.build()).execute()
                val status = resp.code
                val body   = resp.body?.string()?.take(50000) ?: ""
                resp.close()
                contentFrame.post { onResult(status, body) }
            } catch (e: Exception) { contentFrame.post { onResult(-1, e.message ?: "Error") } }
        }
    }

    // ── C1: Found keys dialog ─────────────────────────────────────────────────
    private fun showFoundKeysDialog(found: List<AesKeyFinder.FoundKey>) {
        val ctx = requireContext()
        val inner = col(ctx).apply { setPadding(12.dp, 8.dp, 12.dp, 16.dp) }
        val sv = android.widget.ScrollView(ctx)
        sv.addView(inner)
        inner.addView(sectionHeader(ctx, "${found.size} potential keys found"))
        found.forEach { key ->
            val card = col(ctx, "#1A2A1A").apply {
                setPadding(8.dp, 6.dp, 8.dp, 6.dp)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp }
            }
            card.addView(tv(ctx, "[${key.keyType}] ${key.keyValue}", "#4CAF50", 11f).apply {
                typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
            })
            card.addView(tv(ctx, "…${key.context.take(100)}…", "#888", 9f))
            card.addView(tv(ctx, key.jsUrl.substringAfterLast("/").take(50), "#555", 9f))
            card.addView(btn(ctx, "Copy Key", "#1A2A1A", "#4CAF50").apply {
                setOnClickListener { copy(key.keyValue) }
            })
            inner.addView(card)
        }
        AlertDialog.Builder(ctx).setTitle("🔍 AES Keys Found")
            .setView(sv).setNegativeButton("Đóng", null).show()
    }

    private var snapshotUrls: Set<String> = emptySet()
    private val consoleHistory = mutableListOf<String>()
    private var consoleHistoryIndex = -1

    companion object { const val TAG = "DevToolsSheet" }

    // ── NetworkAdapter (nested class) ─────────────────────────────────────────
    class NetworkAdapter(
        private val allItems: List<NetworkRequest>,
        private val onClick: (NetworkRequest) -> Unit
    ) : RecyclerView.Adapter<NetworkAdapter.VH>() {

        private var displayed = allItems.toMutableList()

        fun filter(q: String) {
            displayed = if(q.isBlank()) allItems.toMutableList()
            else allItems.filter{it.url.contains(q,true)}.toMutableList()
            notifyDataSetChanged()
        }

        inner class VH(root: View, val tvTag:TextView, val tvHost:TextView, val tvPath:TextView): RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, t: Int): VH {
            val ctx = parent.context; val d=ctx.resources.displayMetrics.density; fun Int.dp()=(this*d).toInt()
            val tvTag  = TextView(ctx).apply{textSize=9f;typeface=android.graphics.Typeface.DEFAULT_BOLD;setPadding(6.dp(),2.dp(),6.dp(),2.dp());minWidth=52.dp();gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{marginEnd=8.dp()}}
            val tvHost = TextView(ctx).apply{setTextColor(Color.parseColor("#FFFFFF"));textSize=11f;typeface=android.graphics.Typeface.DEFAULT_BOLD;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
            val tvPath = TextView(ctx).apply{setTextColor(Color.parseColor("#9CA3AF"));textSize=10f;typeface=android.graphics.Typeface.MONOSPACE;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.MIDDLE}
            val info   = LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);addView(tvHost);addView(tvPath)}
            val row    = LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(12.dp(),10.dp(),12.dp(),10.dp());setBackgroundColor(Color.parseColor("#1E1E1E"));addView(tvTag);addView(info)}
            val root   = LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);addView(row);addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#252525"));layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,1)})}
            return VH(root,tvTag,tvHost,tvPath)
        }
        override fun getItemCount() = displayed.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val req=displayed[pos]
            h.tvTag.text=req.tag; h.tvTag.setBackgroundColor(Color.parseColor(req.tagColor)); h.tvTag.setTextColor(Color.WHITE)
            h.tvHost.text=req.host; h.tvPath.text=req.path
            h.itemView.setOnClickListener{onClick(req)}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B1: Network Waterfall / Timeline Tab
    // ─────────────────────────────────────────────────────────────────────────
    private fun showTimelineTab() {
        val ctx  = requireContext()
        val reqs = detector.requests.sortedBy { it.timestamp }
        if (reqs.isEmpty()) {
            contentFrame.removeAllViews()
            contentFrame.addView(tv(ctx, "Chưa có requests.", "#888", 13f).apply { setPadding(16.dp,16.dp,16.dp,16.dp) })
            return
        }
        val t0   = reqs.first().timestamp
        val tEnd = reqs.last().timestamp.let { if (it == t0) t0 + 1 else it }
        val span = (tEnd - t0).toFloat().coerceAtLeast(1f)

        val outer = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        // Header row
        val header = row(ctx, "#1E1E1E").apply {
            setPadding(8.dp, 6.dp, 8.dp, 6.dp)
        }
        header.addView(tv(ctx, "Tag", "#FFFFFF", 9f).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(36.dp, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        header.addView(tv(ctx, "Host / Timeline (+ms)", "#FFFFFF", 9f).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(tv(ctx, "Status", "#FFFFFF", 9f).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(36.dp, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        outer.addView(header)

        val scroll = android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
        }
        val inner = col(ctx)
        scroll.addView(inner)

        reqs.take(200).forEach { req ->
            val relStart = ((req.timestamp - t0).toFloat() / span)
            val row2     = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(8.dp, 3.dp, 8.dp, 3.dp)
                setOnClickListener { showRequestDetail(req) }
            }
            // Tag badge
            val tagTv = tv(ctx, req.tag, "#FFFFFF", 8f).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(36.dp, 18.dp)
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(android.graphics.Color.parseColor(req.tagColor))
                setPadding(2.dp, 0, 2.dp, 0)
            }
            // Timeline bar area
            val barFrame = android.widget.FrameLayout(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, 24.dp, 1f)
            }
            val barWrapper = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
            }
            // Spacer before bar
            val spacer = android.view.View(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, MATCH, relStart)
            }
            // The bar itself
            val barMinW = 3.dp
            val barW = ((1f - relStart) * 0.4f).coerceAtLeast(0f)
            val bar = android.view.View(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(barMinW, MATCH)
                setBackgroundColor(android.graphics.Color.parseColor(req.tagColor))
            }
            // Host label
            val hostTv = tv(ctx, req.host.take(20), "#B0B0B0", 8f).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, MATCH, 1f - relStart - barW)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(4.dp, 0, 0, 0)
            }
            barWrapper.addView(spacer); barWrapper.addView(bar); barWrapper.addView(hostTv)
            barFrame.addView(barWrapper)
            // Timestamp label
            val msTv = tv(ctx, "+${req.timestamp - t0}ms", "#666666", 7f).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.FrameLayout.LayoutParams(WRAP, MATCH, android.view.Gravity.END)
            }
            barFrame.addView(msTv)
            // Status
            val statusTv = tv(ctx,
                if (req.statusCode > 0) req.statusCode.toString() else "…",
                if (req.statusCode in 200..299) "#4CAF50" else if (req.statusCode >= 400) "#E57373" else "#9CA3AF",
                8f
            ).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(36.dp, WRAP)
                gravity = android.view.Gravity.CENTER
            }
            row2.addView(tagTv); row2.addView(barFrame); row2.addView(statusTv)
            inner.addView(row2)
            // Divider
            inner.addView(android.view.View(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, 1).apply { marginStart = 44.dp }
                setBackgroundColor(android.graphics.Color.parseColor("#222222"))
            })
        }

        outer.addView(scroll)
        contentFrame.removeAllViews()
        contentFrame.addView(outer)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B2: Request Diff Dialog
    // ─────────────────────────────────────────────────────────────────────────
    private fun showDiffDialog() {
        val ctx  = requireContext()
        if (snapshotUrls.isEmpty()) {
            Toast.makeText(ctx, "Chưa có snapshot. Bấm 📸 Snap trước.", Toast.LENGTH_SHORT).show()
            return
        }
        val newReqs = detector.requests.filter { it.url !in snapshotUrls }
        val inner   = col(ctx).apply { setPadding(12.dp, 8.dp, 12.dp, 16.dp) }
        val sv      = android.widget.ScrollView(ctx)
        sv.addView(inner)
        inner.addView(sectionHeader(ctx, "🔍 New since snapshot: ${newReqs.size} requests"))
        if (newReqs.isEmpty()) {
            inner.addView(tv(ctx, "Không có request mới so với snapshot.", "#888", 12f))
        } else {
            newReqs.forEach { req ->
                val card = col(ctx, "#1E1E1E").apply {
                    setPadding(8.dp, 5.dp, 8.dp, 5.dp)
                    layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 3.dp }
                    setOnClickListener { showRequestDetail(req) }
                }
                val color = if (req.isStream) "#4CAF50" else "#FFFFFF"
                card.addView(tv(ctx, "[${req.tag}] ${req.host}${req.path.take(50)}", color, 10f))
                card.addView(tv(ctx, req.url.take(80), "#666", 9f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                })
                inner.addView(card)
            }
        }
        val btnUpdate = btn(ctx, "Update Snapshot", "#1A1A2A", "#90CAF9").apply {
            setOnClickListener {
                snapshotUrls = detector.requests.map { it.url }.toSet()
                Toast.makeText(ctx, "Snapshot updated: ${snapshotUrls.size}", Toast.LENGTH_SHORT).show()
            }
        }
        inner.addView(btnUpdate)
        android.app.AlertDialog.Builder(ctx).setTitle("Request Diff")
            .setView(sv).setNegativeButton("Đóng", null).show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // D5+D6: Service Worker + IndexedDB Tab
    // ─────────────────────────────────────────────────────────────────────────
    private fun showSwIdbTab() {
        val ctx   = requireContext()
        val inner = col(ctx).apply { setPadding(12.dp, 8.dp, 12.dp, 16.dp) }
        val sv    = android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
            addView(inner)
        }

        // ── Service Worker ──
        inner.addView(sectionHeader(ctx, "⚙ Service Workers"))
        val tvSw = tv(ctx, "Đang kiểm tra...", "#FFFFFF", 10f).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
        }
        inner.addView(tvSw)

        webView.evaluateJavascript("""
            (function() {
                if (!navigator.serviceWorker) return JSON.stringify([]);
                return navigator.serviceWorker.getRegistrations().then(function(regs) {
                    return JSON.stringify(regs.map(function(r) {
                        return {
                            scope: r.scope,
                            state: r.active ? r.active.state : 'none',
                            scriptURL: r.active ? r.active.scriptURL : ''
                        };
                    }));
                }).catch(function(e) { return JSON.stringify({error: e.toString()}); });
            })()
        """.trimIndent()) { raw ->
            activity?.runOnUiThread {
                val clean = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"","\"")?.replace("\\n","") ?: "[]"
                try {
                    val arr = org.json.JSONArray(clean)
                    if (arr.length() == 0) {
                        tvSw.text = "Không có Service Worker nào đăng ký."
                    } else {
                        val sb = StringBuilder()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            sb.appendLine("Scope: ${obj.optString("scope")}")
                            sb.appendLine("State: ${obj.optString("state")}")
                            sb.appendLine("Script: ${obj.optString("scriptURL").take(80)}")
                            sb.appendLine()
                        }
                        tvSw.text = sb.toString()
                    }
                } catch (_: Exception) { tvSw.text = clean }
            }
        }

        val btnUnregisterSw = btn(ctx, "🗑 Unregister All SW", "#2A1A1A", "#E57373").apply {
            setOnClickListener {
                webView.evaluateJavascript("""
                    navigator.serviceWorker.getRegistrations().then(function(regs) {
                        regs.forEach(function(r) { r.unregister(); });
                        return 'unregistered ' + regs.length;
                    })
                """.trimIndent()) { res ->
                    activity?.runOnUiThread {
                        Toast.makeText(ctx, "SW unregistered: $res", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        inner.addView(btnUnregisterSw)

        inner.addView(divider(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = 12.dp; bottomMargin = 12.dp }
        })

        // ── IndexedDB ──
        inner.addView(sectionHeader(ctx, "🗄 IndexedDB"))
        val tvIdb = tv(ctx, "Đang kiểm tra...", "#FFFFFF", 10f).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
        }
        inner.addView(tvIdb)

        webView.evaluateJavascript("""
            (function() {
                if (!window.indexedDB) return Promise.resolve(JSON.stringify({error:'not supported'}));
                return indexedDB.databases().then(function(dbs) {
                    return JSON.stringify(dbs.map(function(d) { return {name: d.name, version: d.version}; }));
                }).catch(function(e) { return JSON.stringify({error: e.toString()}); });
            })()
        """.trimIndent()) { raw ->
            activity?.runOnUiThread {
                val clean = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"","\"") ?: "[]"
                try {
                    val arr = org.json.JSONArray(clean)
                    if (arr.length() == 0) {
                        tvIdb.text = "Không có IndexedDB nào."
                    } else {
                        val sb = StringBuilder()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            sb.appendLine("DB: ${obj.optString("name")}  v${obj.optInt("version")}")
                        }
                        tvIdb.text = sb.toString()
                    }
                } catch (_: Exception) { tvIdb.text = clean }
            }
        }

        contentFrame.removeAllViews()
        contentFrame.addView(sv)
    }

// ─────────────────────────────────────────────────────────────────────────
    // D2: DOM Inspector Tab
    // ─────────────────────────────────────────────────────────────────────────
    private var selectedElementIndex = 0

    private fun showDomTab() {
        val ctx   = requireContext()
        val outer = col(ctx)
        val sv    = android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
        }
        val inner = col(ctx).apply { setPadding(12.dp, 8.dp, 12.dp, 16.dp) }
        sv.addView(inner)

        // ── Selector input ──
        inner.addView(sectionHeader(ctx, "🌳 DOM Inspector"))
        val etSel = editText(ctx, "CSS selector  (vd: video, .player, #app, [data-src])").apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp }
        }
        inner.addView(etSel)

        // Quick selector presets
        val presetRow = row(ctx)
        listOf("video","audio","iframe","[src]","[data-src]","img").forEach { sel ->
            presetRow.addView(btn(ctx, sel, "#1E1E1E", "#90CAF9").apply {
                textSize = 9f; setPadding(6.dp, 4.dp, 6.dp, 4.dp)
                setOnClickListener { etSel.setText(sel); inspectSelector(sel, inner, sv) }
            })
        }
        inner.addView(presetRow)

        // ── Inspect button ──
        val btnInspect = btn(ctx, "🔍 Inspect", "#1A2A1A", "#4CAF50").apply {
            setOnClickListener { inspectSelector(etSel.text.toString().trim(), inner, sv) }
        }
        // ── DOM Tree button ──
        val btnTree = btn(ctx, "🌲 Full DOM", "#1A1A2A", "#64B5F6").apply {
            setOnClickListener { showDomTree() }
        }
        inner.addView(row(ctx).apply { addView(btnInspect); addView(btnTree) })

        // Results area
        val tvResults = tv(ctx, "", "#FFFFFF", 10f).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
        }
        inner.addView(tvResults)

        outer.addView(sv)
        contentFrame.removeAllViews()
        contentFrame.addView(outer)
    }

    private fun inspectSelector(sel: String, container: android.widget.LinearLayout, sv: android.widget.ScrollView) {
        if (sel.isBlank()) return
        val escapedSel = sel.replace("\"", "\\\"").replace("'", "\\'")
        val js = """
            (function() {
                try {
                    var els = Array.from(document.querySelectorAll("$escapedSel")).slice(0, 30);
                    return JSON.stringify({
                        count: document.querySelectorAll("$escapedSel").length,
                        elements: els.map(function(el, idx) {
                            var attrs = {};
                            Array.from(el.attributes).forEach(function(a) { attrs[a.name] = a.value.substring(0, 200); });
                            var rect = el.getBoundingClientRect();
                            return {
                                index: idx,
                                tag: el.tagName.toLowerCase(),
                                id: el.id,
                                classes: el.className.toString().trim().split(' ').filter(function(c){ return c; }),
                                attrs: attrs,
                                text: el.innerText ? el.innerText.trim().substring(0, 100) : '',
                                src: el.src || el.getAttribute('src') || el.getAttribute('data-src') || '',
                                href: el.href || '',
                                visible: el.offsetParent !== null,
                                rect: {w: Math.round(rect.width), h: Math.round(rect.height)}
                            };
                        })
                    });
                } catch(e) { return JSON.stringify({error: e.toString()}); }
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { raw ->
            activity?.runOnUiThread {
                try {
                    val clean = raw?.trim()?.removeSurrounding("\"")
                        ?.replace("\\\"","\"")?.replace("\\n","")?.replace("\\\\","\\") ?: ""
                    val json  = org.json.JSONObject(clean)
                    if (json.has("error")) { showInDom(container, "Error: ${json.getString("error")}"); return@runOnUiThread }
                    val count = json.getInt("count")
                    val els   = json.getJSONArray("elements")
                    // Remove old results (keep header + presets + buttons)
                    while (container.childCount > 4) container.removeViewAt(container.childCount - 1)
                    container.addView(sectionHeader(requireContext(), "Found $count elements (showing ${els.length()})"))
                    for (i in 0 until els.length()) {
                        val el   = els.getJSONObject(i)
                        val tag  = el.optString("tag")
                        val id   = el.optString("id").let { if (it.isNotBlank()) "#$it" else "" }
                        val cls  = el.optJSONArray("classes")?.let { arr ->
                            (0 until arr.length()).map { j -> ".${arr.getString(j)}" }.take(3).joinToString("")
                        } ?: ""
                        val src  = el.optString("src")
                        val text = el.optString("text")
                        val vis  = el.optBoolean("visible")
                        val rect = el.optJSONObject("rect")
                        container.addView(buildElementCard(requireContext(), el, i, "$tag$id$cls", src, text, vis, rect))
                    }
                    sv.post { sv.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
                } catch (e: Exception) { showInDom(container, "Parse error: ${e.message}") }
            }
        }
    }

    private fun buildElementCard(ctx: Context, el: org.json.JSONObject, idx: Int,
                                  label: String, src: String, text: String,
                                  visible: Boolean, rect: org.json.JSONObject?): android.view.View {
        val card = col(ctx, "#1E1E1E").apply {
            setPadding(8.dp, 6.dp, 8.dp, 6.dp)
            layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 4.dp }
        }
        val visIcon = if (visible) "👁" else "🙈"
        val size    = rect?.let { "${it.optInt("w")}×${it.optInt("h")}" } ?: ""
        card.addView(tv(ctx, "[$idx] $visIcon $label  $size", "#4CAF50", 11f).apply {
            typeface = android.graphics.Typeface.MONOSPACE
        })
        if (src.isNotBlank()) card.addView(tv(ctx, "src: ${src.take(80)}", "#64B5F6", 10f).apply {
            setTextIsSelectable(true)
        })
        if (text.isNotBlank()) card.addView(tv(ctx, "text: $text", "#B0B0B0", 10f))

        // Attributes
        val attrs = el.optJSONObject("attrs")
        if (attrs != null && attrs.length() > 0) {
            val attrStr = attrs.keys().asSequence().take(6).joinToString("  ") { k ->
                "$k=\"${attrs.optString(k).take(30)}\""
            }
            card.addView(tv(ctx, attrStr, "#9CA3AF", 9f).apply { typeface = android.graphics.Typeface.MONOSPACE })
        }

        // Actions
        val actionRow = row(ctx)
        val btnHighlight = btn(ctx, "✨ Highlight", "#1A2A1A", "#4CAF50").apply {
            setOnClickListener {
                val js = """
                    (function(){
                        var el = document.querySelectorAll("${el.optString("tag")}")[${idx}];
                        if (!el) return;
                        el.style.outline='4px solid #1D9E75';
                        el.scrollIntoView({behavior:'smooth', block:'center'});
                        setTimeout(function(){ el.style.outline=''; }, 3000);
                    })()
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
        }
        if (src.isNotBlank()) {
            val btnCopySrc = btn(ctx, "Copy src", "#1E1E1E").apply {
                setOnClickListener { copy(src) }
            }
            actionRow.addView(btnCopySrc)
        }
        actionRow.addView(btnHighlight)
        val btnEdit = btn(ctx, "✏ Edit attr", "#1A1A2A", "#90CAF9").apply {
            setOnClickListener { showEditAttrDialog(el, idx) }
        }
        actionRow.addView(btnEdit)
        card.addView(actionRow)
        return card
    }

    private fun showEditAttrDialog(el: org.json.JSONObject, idx: Int) {
        val ctx  = requireContext()
        val tag  = el.optString("tag")
        val etAttr  = editText(ctx, "Attribute name (vd: src, style, class)")
        val etValue = editText(ctx, "New value")
        val col2 = col(ctx).apply {
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            addView(tv(ctx, "Element: <$tag> [$idx]", "#FFFFFF", 11f))
            addView(etAttr); addView(etValue)
        }
        AlertDialog.Builder(ctx).setTitle("✏ Edit Attribute").setView(col2)
            .setPositiveButton("Apply") { _, _ ->
                val attr = etAttr.text.toString().trim().replace("\"","\\\"")
                val value = etValue.text.toString().trim().replace("\"","\\\"").replace("'","\\'")
                val js = """(function(){
                    var el = document.querySelectorAll("$tag")[$idx];
                    if (el) { el.setAttribute("$attr", "$value"); return "ok"; } return "not found";
                })()""".trimIndent()
                webView.evaluateJavascript(js) { res ->
                    activity?.runOnUiThread { Toast.makeText(ctx, "Result: $res", Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton("Đóng", null).show()
    }

    private fun showDomTree() {
        val js = """
            (function() {
                function tree(el, depth) {
                    if (depth > 4 || !el) return '';
                    var tag = el.tagName ? el.tagName.toLowerCase() : '';
                    if (!tag) return '';
                    var id  = el.id ? '#'+el.id : '';
                    var cls = el.className ? '.'+el.className.toString().split(' ')[0] : '';
                    var pad = '  '.repeat(depth);
                    var ch  = Array.from(el.children).slice(0,6).map(function(c){ return tree(c, depth+1); }).join('');
                    return pad + '<' + tag + id + cls + '>\n' + ch;
                }
                return tree(document.body, 0).substring(0, 8000);
            })()
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            val clean = raw?.trim()?.removeSurrounding("\"")?.replace("\\n","\n")?.replace("\\\"","\"") ?: ""
            activity?.runOnUiThread { showCodeDialog(requireContext(), "DOM Tree", clean) }
        }
    }

    private fun showInDom(container: android.widget.LinearLayout, msg: String) {
        container.addView(tv(requireContext(), msg, "#E57373", 11f))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // J2: HTTP Proxy Tab
    // ─────────────────────────────────────────────────────────────────────────
    private fun showProxyTab() {
        val ctx   = requireContext()
        val inner = col(ctx).apply { setPadding(12.dp, 8.dp, 12.dp, 16.dp) }
        val sv    = android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
            addView(inner)
        }

        inner.addView(sectionHeader(ctx, "🌐 HTTP Proxy (J2)"))
        inner.addView(tv(ctx, "Ảnh hưởng đến OkHttp (response fetch). WebView cần cấu hình thêm.", "#888", 10f).apply {
            setPadding(0, 0, 0, 8.dp)
        })

        val etHost = editText(ctx, "Proxy host (vd: 192.168.1.100)").apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp }
        }
        val etPort = editText(ctx, "Port (vd: 8888)").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 8.dp }
        }
        inner.addView(etHost)
        inner.addView(etPort)

        // Current proxy status
        val currentHost = System.getProperty("http.proxyHost") ?: ""
        val currentPort = System.getProperty("http.proxyPort") ?: ""
        val tvStatus = tv(ctx,
            if (currentHost.isNotBlank()) "Current: $currentHost:$currentPort" else "Proxy: OFF",
            if (currentHost.isNotBlank()) "#4CAF50" else "#9CA3AF", 11f
        ).apply { setPadding(0, 0, 0, 8.dp) }
        inner.addView(tvStatus)

        val btnSet = btn(ctx, "✅ Set Proxy", "#1A2A1A", "#4CAF50").apply {
            setOnClickListener {
                val host = etHost.text.toString().trim()
                val port = etPort.text.toString().trim().toIntOrNull() ?: 0
                (activity as? com.aho.streambrowser.ui.MainActivity)?.setHttpProxy(host, port)
                tvStatus.text = if (host.isNotBlank()) "Current: $host:$port" else "Proxy: OFF"
                tvStatus.setTextColor(android.graphics.Color.parseColor(if (host.isNotBlank()) "#4CAF50" else "#9CA3AF"))
            }
        }
        val btnClear = btn(ctx, "🗑 Clear Proxy", "#2A1A1A", "#E57373").apply {
            setOnClickListener {
                etHost.setText(""); etPort.setText("")
                (activity as? com.aho.streambrowser.ui.MainActivity)?.setHttpProxy("", 0)
                tvStatus.text = "Proxy: OFF"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
            }
        }
        inner.addView(row(ctx).apply { addView(btnSet); addView(btnClear) })

        inner.addView(divider(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = 12.dp; bottomMargin = 12.dp }
        })
        inner.addView(sectionHeader(ctx, "Charles / Burp Quick Setup"))
        inner.addView(tv(ctx, """1. Mở Charles/Burp trên máy tính
2. Lấy IP máy tính (cùng WiFi với điện thoại)
3. Charles port mặc định: 8888 | Burp: 8080
4. Nhập host+port ở trên → Set Proxy
5. Cài Charles/Burp SSL cert vào điện thoại
6. Bật SSL bypass (tab Network → 🔒 SSL)""", "#FFFFFF", 11f).apply {
            typeface = android.graphics.Typeface.MONOSPACE
        })

        inner.addView(divider(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = 12.dp; bottomMargin = 12.dp }
        })
        inner.addView(sectionHeader(ctx, "WebView Proxy (via reflection)"))
        inner.addView(tv(ctx, "Áp dụng proxy trực tiếp cho WebView (Android 5+).", "#888", 10f).apply { setPadding(0,0,0,6.dp) })
        val btnWebViewProxy = btn(ctx, "🔧 Apply to WebView", "#1A1A2A", "#64B5F6").apply {
            setOnClickListener {
                val host = etHost.text.toString().trim()
                val port = etPort.text.toString().trim().toIntOrNull() ?: 0
                if (host.isBlank()) { Toast.makeText(ctx, "Nhập host trước", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                applyWebViewProxy(host, port)
            }
        }
        inner.addView(btnWebViewProxy)

        contentFrame.removeAllViews()
        contentFrame.addView(sv)
    }

    private fun applyWebViewProxy(host: String, port: Int) {
        val ctx = requireContext()
        try {
            val contentResolver = ctx.contentResolver
            android.provider.Settings.Global.putString(contentResolver, android.provider.Settings.Global.HTTP_PROXY, "$host:$port")
            Toast.makeText(ctx, "WebView proxy applied: $host:$port\n(may need restart)", Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            // Fallback: reflection approach
            try {
                val clazz  = Class.forName("android.webkit.WebViewDatabase")
                val method = clazz.getDeclaredMethod("getInstance", Context::class.java)
                method.isAccessible = true
                Toast.makeText(ctx, "⚠ Cần WRITE_SETTINGS permission để set WebView proxy", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(ctx, "Set HTTPS_PROXY env và restart app", Toast.LENGTH_LONG).show()
            }
        }
    }

// ─────────────────────────────────────────────────────────────────────────
    // H2: History Tab — powered by Room via ViewModel
    // ─────────────────────────────────────────────────────────────────────────
    private fun showHistoryTab() {
        val ctx = requireContext()
        val inner = col(ctx).apply { setPadding(12.dp, 8.dp, 12.dp, 16.dp) }
        val sv = android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
            addView(inner)
        }
        val vm = (activity as? com.aho.streambrowser.ui.MainActivity)?.let {
            androidx.lifecycle.ViewModelProvider(it).get(com.aho.streambrowser.viewmodel.BrowserViewModel::class.java)
        }
        inner.addView(sectionHeader(ctx, "📚 Browsing History (Room DB)"))
        val tvHist = tv(ctx, "Đang tải...", "#FFFFFF", 11f).apply { setTextIsSelectable(true) }
        inner.addView(tvHist)
        scope.launch {
            val items = vm?.history?.value ?: emptyList()
            contentFrame.post {
                if (items.isEmpty()) { tvHist.text = "Chưa có history." }
                else {
                    inner.removeView(tvHist)
                    items.take(50).forEach { entry ->
                        val row2 = row(ctx, "#1E1E1E").apply {
                            setPadding(8.dp, 6.dp, 8.dp, 6.dp)
                            layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 2.dp }
                            setOnClickListener { (activity as? com.aho.streambrowser.ui.MainActivity)?.navigateTo(entry.url) }
                        }
                        val col2 = col(ctx)
                        col2.addView(tv(ctx, entry.title.take(50), "#FFFFFF", 11f))
                        col2.addView(tv(ctx, entry.url.take(60), "#666", 9f).apply { typeface = android.graphics.Typeface.MONOSPACE })
                        row2.addView(col2.apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, WRAP, 1f) })
                        row2.addView(btn(ctx, "Copy", "#1E1E1E").apply {
                            textSize = 9f; setOnClickListener { copy(entry.url) }
                        })
                        inner.addView(row2)
                    }
                }
            }
        }
        inner.addView(divider(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = 12.dp; bottomMargin = 8.dp }
        })
        inner.addView(sectionHeader(ctx, "🎬 Stream History (Room DB)"))
        scope.launch {
            val streams = vm?.getRecentStreams() ?: emptyList()
            contentFrame.post {
                if (streams.isEmpty()) inner.addView(tv(ctx, "Chưa có stream history.", "#888", 11f))
                else streams.take(30).forEach { s ->
                    val card = col(ctx, "#1E1E1E").apply {
                        setPadding(8.dp, 5.dp, 8.dp, 5.dp)
                        layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 3.dp }
                    }
                    card.addView(tv(ctx, "[${s.streamType}] ${s.url.take(70)}", "#4CAF50", 10f).apply {
                        typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
                    })
                    card.addView(tv(ctx, "ref: ${s.referer.take(50)}", "#666", 9f))
                    card.addView(btn(ctx, "Copy URL", "#1A2A1A", "#4CAF50").apply {
                        setOnClickListener { copy(s.url) }
                    })
                    inner.addView(card)
                }
            }
        }
        inner.addView(btn(ctx, "🗑 Clear All History", "#2A1A1A", "#E57373").apply {
            setOnClickListener {
                vm?.clearHistory()
                Toast.makeText(ctx, "History cleared", Toast.LENGTH_SHORT).show()
            }
        })
        contentFrame.removeAllViews()
        contentFrame.addView(sv)
    }

}
