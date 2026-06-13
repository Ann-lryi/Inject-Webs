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
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.aho.streambrowser.model.NetworkRequest
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.util.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.*

class DevToolsSheet(
    private val detector: StreamDetector,
    private val webView: WebView,
    private val activity: MainActivity,
    private val onPlayStream: (StreamItem) -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var tabLayout: TabLayout
    private lateinit var contentFrame: FrameLayout
    private var currentTab = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) = buildRoot()
    override fun onDestroyView() { super.onDestroyView(); scope.cancel() }

    // ── Fix: Configure bottom sheet to take ~60% of screen, not full screen ──
    override fun onCreateDialog(savedInstanceState: Bundle?) = super.onCreateDialog(savedInstanceState).also { dialog ->
        dialog.setOnShowListener { dlg ->
            val bottomSheet = (dlg as? BottomSheetDialog)?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                // Set to PEER at 60% height - prevents full expansion
                val displayMetrics = resources.displayMetrics
                val sheetHeight = (displayMetrics.heightPixels * 0.60).toInt()
                behavior.peekHeight = sheetHeight
                behavior.expandedOffset = (displayMetrics.heightPixels * 0.40).toInt()
                // Lock to collapsed/half-expanded only - no full expansion
                behavior.isHideable = false
                behavior.skipCollapsed = false
                // Set to half-expanded state
                behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
                // Set max height
                it.layoutParams = it.layoutParams.apply {
                    height = sheetHeight
                }
            }
        }
    }

    // ── Root ──────────────────────────────────────────────────────────────────
    private fun buildRoot(): View {
        val ctx = requireContext()
        val outer = col(ctx, "#1A1A1A")

        // Handle
        outer.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#444444"))
                layoutParams = LinearLayout.LayoutParams(120.dp, 4.dp).apply { topMargin=10.dp; bottomMargin=10.dp }
            })
        })

        // Tab row
        val tabRow = row(ctx, "#1A1A1A").apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 44.dp)
        }
        tabLayout = TabLayout(ctx).apply {
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setTabTextColors(Color.parseColor("#888888"), Color.parseColor("#1D9E75"))
            setSelectedTabIndicatorColor(Color.parseColor("#1D9E75"))
            tabMode = TabLayout.MODE_SCROLLABLE
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
        }
        refreshTabs()

        val btnClearAll = btn(ctx, "🗑", "#E24B4A").apply {
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

    private fun refreshTabs() {
        val titles = listOf(
            "🌐 Net(${detector.requestCount()})",
            "▶ Stream(${detector.streamCount()})",
            "⚡ Console",
            "🔬 Deep",
            "📄 HTML",
            "📋 M3U8",
            "🚫 Blocker",
            "🔀 UA",
            "★ Saved",
            "🔐 Crypto(${detector.cryptoCount()})"
        )
        if (tabLayout.tabCount == 0) titles.forEach { tabLayout.addTab(tabLayout.newTab().setText(it)) }
        else titles.forEachIndexed { i, t -> tabLayout.getTabAt(i)?.text = t }
    }

    private fun showTab(pos: Int) = when(pos) {
        0 -> showNetworkTab()
        1 -> showStreamsTab()
        2 -> showConsoleTab()
        3 -> showDeepTab()
        4 -> showHtmlTab()
        5 -> showM3u8Tab()
        6 -> showBlockerTab()
        7 -> showUaTab()
        8 -> showSavedTab()
        9  -> showCryptoTab()
        10 -> showWsTab()
        11 -> showCookieTab()
        12 -> showPluginTab()
        else -> {}
    }

    // ── 1. Network ────────────────────────────────────────────────────────────
    private fun showNetworkTab() {
        val ctx = requireContext()
        val container = col(ctx)
        val filterRow = row(ctx, "#141414").apply { setPadding(12.dp,6.dp,8.dp,6.dp) }
        val searchBox = editText(ctx, "Lọc URL...")
        val btnX = btn(ctx, "✕", "#888888")
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
            onPlay  = { onPlayStream(it); dismiss() },
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
        val modeRow = row(ctx, "#141414").apply { setPadding(10.dp, 8.dp, 10.dp, 8.dp) }

        val btnFull = Button(ctx).apply {
            text = "📄 Full HTML"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1D9E75"))
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
        val searchRow = row(ctx, "#0D0D0D").apply { setPadding(8.dp, 4.dp, 8.dp, 4.dp) }
        val searchBox = EditText(ctx).apply {
            hint = "🔍  Tìm trong HTML..."
            setHintTextColor(Color.parseColor("#444444"))
            setTextColor(Color.parseColor("#EFEFEF"))
            textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; background = null
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val tvMatch = TextView(ctx).apply {
            text = ""; setTextColor(Color.parseColor("#1D9E75"))
            textSize = 10f; typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8.dp, 0, 0, 0)
        }
        searchRow.addView(searchBox); searchRow.addView(tvMatch)
        container.addView(searchRow)
        container.addView(divider(ctx))

        // ── Output ────────────────────────────────────────────────────────────
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            setBackgroundColor(Color.parseColor("#080808"))
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
        val actionRow = row(ctx, "#141414").apply { setPadding(8.dp, 6.dp, 8.dp, 6.dp) }
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
        actionRow.addView(btnCopyAll); actionRow.addView(btnCopy3k); actionRow.addView(tvSize)
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
            outputTv.setTextColor(Color.parseColor("#888888"))
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
            dismiss()
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
        container.addView(tv(ctx, "Paste URL m3u8 để xem danh sách chất lượng:", "#888888", 11f).apply {
            setPadding(12.dp,10.dp,12.dp,4.dp)
        })

        val inputRow = row(ctx, "#141414").apply { setPadding(8.dp,6.dp,8.dp,6.dp) }
        val urlBox = editText(ctx, "https://example.com/master.m3u8").apply {
            detector.streams.firstOrNull { it.url.contains("m3u8") }?.let { setText(it.url) }
        }
        val btnParse = btn(ctx, "Parse", "#1D9E75", "#FFFFFF")
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
            resultContainer.addView(tv(ctx, "⏳ Đang parse...", "#888888", 11f).apply { setPadding(12.dp,12.dp,12.dp,0) })

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
                resultContainer.addView(tv(ctx, "${qualities.size} quality streams:", "#1D9E75", 12f).apply { setPadding(12.dp,10.dp,12.dp,4.dp) })
                qualities.forEach { q ->
                    val card = col(ctx, "#242424").apply {
                        setPadding(12.dp,10.dp,12.dp,10.dp)
                        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                        lp.setMargins(8.dp,4.dp,8.dp,0)
                        layoutParams = lp
                    }
                    card.addView(tv(ctx, "📺 ${q.label}", "#EFEFEF", 13f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
                    card.addView(tv(ctx, q.url, "#888888", 10f).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    })
                    val btnRow = row(ctx)
                    btnRow.addView(btn(ctx, "Copy", "#0D47A1", "#FFFFFF").apply {
                        setOnClickListener { copy(q.url) }
                    })
                    btnRow.addView(btn(ctx, "Phát", "#1D9E75", "#FFFFFF").apply {
                        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = 8.dp }
                        setOnClickListener {
                            val stream = StreamItem.fromUrl(q.url, referer, "m3u8_parser")
                            if (stream != null) { onPlayStream(stream); dismiss() }
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
                addView(tv(ctx, "🚫 Đã block: ${blocker.blockedCount} requests", "#1D9E75", 13f).apply {
                    setPadding(0, 0, 0, 8.dp)
                })

                // Builtin toggle
                val builtinRow = row(ctx)
                builtinRow.addView(tv(ctx, "Block ads/trackers mặc định", "#EFEFEF", 12f).apply {
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
                addView(tv(ctx, "Custom patterns:", "#888888", 11f))
                val patterns = blocker.getCustomPatterns()
                patterns.forEach { pattern ->
                    val row2 = row(ctx)
                    row2.addView(tv(ctx, pattern, "#EFEFEF", 11f).apply {
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
                val addRow = row(ctx, "#141414").apply { setPadding(8.dp,6.dp,8.dp,6.dp) }
                val input = editText(ctx, "vd: ads.example.com")
                val btnAdd = btn(ctx, "Thêm", "#1D9E75", "#FFFFFF")
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
                addView(tv(ctx, "Current UA:", "#888888", 10f))
                addView(tv(ctx, current, "#1D9E75", 10f).apply {
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
                    card.addView(tv(ctx, (if (isActive) "✓ " else "") + name, if (isActive) "#1D9E75" else "#EFEFEF", 13f).apply {
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                    card.addView(tv(ctx, ua, "#888888", 9f).apply {
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
                addView(tv(ctx, "Custom UA:", "#888888", 11f))
                val customInput = EditText(ctx).apply {
                    setText(current)
                    setTextColor(Color.parseColor("#EFEFEF"))
                    setHintTextColor(Color.parseColor("#555555"))
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    background = null
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    minLines = 2
                }
                addView(customInput)
                addView(btn(ctx, "Áp dụng UA này", "#1D9E75", "#FFFFFF").apply {
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
                val title = tv(ctx, "", "#EFEFEF", 12f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
                val url   = tv(ctx, "", "#888888", 10f).apply { typeface=android.graphics.Typeface.MONOSPACE; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.MIDDLE; layoutParams=LinearLayout.LayoutParams(0,WRAP,1f) }
                val del   = btn(ctx, "✕", "#E24B4A")
                val urlRow = row(ctx)
                urlRow.addView(url)
                urlRow.addView(del)
                val root2 = col(ctx, "#1A1A1A").apply {
                    setPadding(12.dp,10.dp,12.dp,10.dp)
                    addView(title); addView(urlRow)
                    addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#2E2E2E")); layoutParams=LinearLayout.LayoutParams(MATCH,1).apply{topMargin=6.dp} })
                }
                return VH(root2, title, url, del)
            }

            override fun getItemCount() = all.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val vh = h as VH
                val e = all[pos]
                vh.tvTitle.text = (if (e.isBookmark) "★ " else "  ") + e.title
                vh.tvUrl.text   = e.url
                vh.root.setOnClickListener { act.navigateTo(e.url); dismiss() }
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
            setBackgroundColor(Color.parseColor("#0D0D0D"))
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
                setBackgroundColor(Color.parseColor("#0A0A0A"))
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
            setBackgroundColor(Color.parseColor("#141414"))
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
        return TextView(ctx).apply {
            text = title
            setTextColor(Color.parseColor("#1D9E75"))
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 8.dp, 0, 4.dp)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
    }

    private fun kvRow(ctx: Context, key: String, value: String): LinearLayout {
        return row(ctx).apply {
            setPadding(0, 2.dp, 0, 2.dp)
            addView(TextView(ctx).apply {
                text = "$key: "
                setTextColor(Color.parseColor("#888888"))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            })
            addView(TextView(ctx).apply {
                text = value
                setTextColor(Color.parseColor("#EFEFEF"))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
        }
    }

    private fun sectionDivider(ctx: Context): View {
        return View(ctx).apply {
            setBackgroundColor(Color.parseColor("#2E2E2E"))
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
        val inputRow = row(ctx, "#141414").apply { setPadding(8.dp,6.dp,8.dp,6.dp) }
        val prefix = tv(ctx, "JS>", "#1D9E75", 12f).apply {
            typeface=android.graphics.Typeface.MONOSPACE; setPadding(0,0,8.dp,0)
        }
        var histIdx = detector.consoleHistory.size
        val jsInput = editText(ctx, "Nhập JS...").apply { layoutParams=LinearLayout.LayoutParams(0,WRAP,1f); maxLines=4 }
        val btnUp = btn(ctx, "↑", "#888888").apply {
            setOnClickListener {
                val h = detector.consoleHistory
                if (h.isEmpty()) return@setOnClickListener
                histIdx = (histIdx-1).coerceAtLeast(0)
                jsInput.setText(h[histIdx]); jsInput.setSelection(jsInput.text.length)
            }
        }
        val btnRun = btn(ctx, "▶", "#1D9E75", "#FFFFFF").apply {
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
        listOf(prefix, jsInput, btnUp, btnRun, btnClear).forEach { inputRow.addView(it) }
        container.addView(inputRow)
    }

    private fun runJs(code: String, output: TextView, scroll: ScrollView, log: StringBuilder, label: String) {
        log.append("\n▶ $label\n"); output.text = log
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
            setBackgroundColor(Color.parseColor("#0D0D0D"))
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
        setTextColor(if(fg==bg) Color.parseColor("#888888") else Color.parseColor(fg))
        if(fg!=bg) setBackgroundColor(Color.parseColor(bg)) else background=null
        setPadding(10.dp,4.dp,10.dp,4.dp)
        layoutParams=LinearLayout.LayoutParams(WRAP,WRAP)
    }
    private fun editText(ctx: Context, hint: String) = EditText(ctx).apply {
        this.hint=hint; setHintTextColor(Color.parseColor("#444444")); setTextColor(Color.parseColor("#EFEFEF"))
        textSize=12f; typeface=android.graphics.Typeface.MONOSPACE; background=null
        layoutParams=LinearLayout.LayoutParams(MATCH,WRAP)
    }
    private fun divider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#2E2E2E")); layoutParams=LinearLayout.LayoutParams(MATCH,1)
    }
    private fun centerText(ctx: Context, text: String) = TextView(ctx).apply {
        this.text=text; setTextColor(Color.parseColor("#888888")); textSize=13f; gravity=Gravity.CENTER
        setPadding(24.dp,48.dp,24.dp,0)
        layoutParams=FrameLayout.LayoutParams(MATCH,MATCH)
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
            inner.addView(tv(ctx, "Chưa có key nào. Mở trang có CryptoJS hoặc Web Crypto API.", "#888888", 12f).apply {
                setPadding(0, 8.dp, 0, 16.dp)
            })
        } else {
            keys.forEach { cap ->
                val card = col(ctx, "#1A2A1A").apply { setPadding(10.dp,8.dp,10.dp,8.dp)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp }
                }
                card.addView(tv(ctx, cap.algorithm, "#4CAF50", 10f))
                card.addView(tv(ctx, "KEY: ${cap.key}", "#EFEFEF", 11f).apply {
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
        inner.addView(sectionHeader(ctx, "🔒 Encrypted Responses (${bodies.size})"))
        if (bodies.isEmpty()) {
            inner.addView(tv(ctx, "Chưa có. XHR trả về hex IV:CIPHERTEXT sẽ hiện ở đây.", "#888888", 12f).apply { setPadding(0,8.dp,0,16.dp) })
        } else {
            bodies.take(5).forEach { rb ->
                val card = col(ctx, "#2A1A1A").apply { setPadding(10.dp,8.dp,10.dp,8.dp)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp }
                }
                card.addView(tv(ctx, rb.url.take(70), "#E57373", 10f).apply { typeface = android.graphics.Typeface.MONOSPACE })
                card.addView(tv(ctx, "HTTP ${rb.statusCode} • ${rb.body.length} chars", "#888888", 9f))
                card.addView(tv(ctx, rb.body.take(80) + "…", "#EFEFEF", 10f).apply {
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
        val btnFillLast = btn(ctx, "↓ Fill last body", "#1A1A1A").apply {
            setOnClickListener { bodies.firstOrNull()?.let { inputCipher.setText(it.body) } }
        }
        val btnFillKey  = btn(ctx, "↓ Fill last key", "#1A1A1A").apply {
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
                    "open"  -> "#1A2A1A"; "send" -> "#2A1A1A"; "recv" -> "#1A1A2A"; else -> "#1A1A1A"
                }
                val icon = when(msg.direction) { "open"->"🟢"; "send"->"↑"; "recv"->"↓"; else->"✖" }
                val card = col(ctx, bg).apply {
                    setPadding(8.dp, 6.dp, 8.dp, 6.dp)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 4.dp }
                }
                card.addView(tv(ctx, "$icon ${msg.wsUrl.take(60)}", "#EFEFEF", 10f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE })
                if (msg.data.isNotBlank()) {
                    card.addView(tv(ctx, msg.data.take(200), "#AAAAAA", 10f).apply {
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
                val rowLayout = row(ctx, "#141414").apply {
                    setPadding(8.dp, 6.dp, 8.dp, 6.dp)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 3.dp }
                }
                val isJwt = JwtDecoder.isJwt(v)
                val keyTv  = tv(ctx, k, if (isJwt) "#FFD54F" else "#64B5F6", 11f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                val valTv  = tv(ctx, v.take(40) + if (v.length > 40) "…" else "", "#EFEFEF", 10f).apply {
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
            inner.addView(btn(ctx, "Copy $label", "#1A1A1A").apply {
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
        col.addView(tv(ctx, info.header, "#EFEFEF", 10f).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
        })
        col.addView(tv(ctx, "Payload:", "#64B5F6", 11f).apply { setPadding(0, 8.dp, 0, 0) })
        col.addView(tv(ctx, info.payload, "#EFEFEF", 10f).apply {
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
        val site = url.let { runCatching { java.net.URL(it).let { u -> "${u.protocol}://${u.host}" } }.getOrElse { it } }

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
        }, "#EFEFEF", 11f))

        contentFrame.removeAllViews()
        contentFrame.addView(sv)
    }

    private fun showCodeDialog(ctx: android.content.Context, title: String, code: String) {
        val sv  = android.widget.ScrollView(ctx)
        val tvCode = tv(ctx, code, "#EFEFEF", 10f).apply {
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

    companion object { const val TAG = "DevToolsSheet" }
}

// ── NetworkAdapter ────────────────────────────────────────────────────────────
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
        val tvHost = TextView(ctx).apply{setTextColor(Color.parseColor("#EFEFEF"));textSize=11f;typeface=android.graphics.Typeface.DEFAULT_BOLD;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        val tvPath = TextView(ctx).apply{setTextColor(Color.parseColor("#888888"));textSize=10f;typeface=android.graphics.Typeface.MONOSPACE;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.MIDDLE}
        val info   = LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);addView(tvHost);addView(tvPath)}
        val row    = LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(12.dp(),10.dp(),12.dp(),10.dp());setBackgroundColor(Color.parseColor("#1A1A1A"));addView(tvTag);addView(info)}
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
