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
import com.aho.streambrowser.detector.BoundedStringBuilder
import com.aho.streambrowser.detector.StreamDetector
import com.aho.streambrowser.model.NetworkRequest
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.util.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.*

class DevToolsSheet(
    private val detector: StreamDetector,
    private val blocker: RequestBlocker,
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
                AlertDialog.Builder(ctx)
                    .setTitle("Xoá tất cả?")
                    .setMessage("Xoá tất cả dữ liệu streams và requests?")
                    .setPositiveButton("Có") { _, _ ->
                        detector.clear()
                        refreshTabs()
                        contentFrame.removeAllViews()
                        showTab(currentTab)
                    }
                    .setNegativeButton("Không", null)
                    .show()
            }
        }
        tabRow.addView(tabLayout)
        tabRow.addView(btnClearAll)
        outer.addView(tabRow)
        outer.addView(divider(ctx))

        contentFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 580.dp)
        }
        outer.addView(contentFrame)
        showTab(0)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { currentTab=t.position; contentFrame.removeAllViews(); showTab(t.position) }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) { contentFrame.removeAllViews(); showTab(t.position) }
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
            "★ Saved"
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
        else -> {}
    }

    // ── 1. Network (Enhanced with response info) ──────────────────────────────
    private fun showNetworkTab() {
        val ctx = requireContext()
        val container = col(ctx)

        // Filter row
        val filterRow = row(ctx, "#141414").apply { setPadding(12.dp,6.dp,8.dp,6.dp) }
        val searchBox = editText(ctx, "Lọc URL...")
        val btnX = btn(ctx, "✕", "#888888")
        // Filter type buttons
        val btnAll = btn(ctx, "All", "#37474F", "#FFFFFF").apply { setPadding(8.dp,2.dp,8.dp,2.dp) }
        val btnStream = btn(ctx, "Stream", "#1B5E20", "#FFFFFF").apply { setPadding(8.dp,2.dp,8.dp,2.dp) }
        val btnApi = btn(ctx, "API", "#0D47A1", "#FFFFFF").apply { setPadding(8.dp,2.dp,8.dp,2.dp) }
        val btnJs = btn(ctx, "JS", "#E65100", "#FFFFFF").apply { setPadding(8.dp,2.dp,8.dp,2.dp) }
        filterRow.addView(searchBox)
        filterRow.addView(btnX)
        filterRow.addView(btnAll)
        filterRow.addView(btnStream)
        filterRow.addView(btnApi)
        filterRow.addView(btnJs)
        container.addView(filterRow)
        container.addView(divider(ctx))

        val rv = RecyclerView(ctx).apply { layoutManager=LinearLayoutManager(ctx); layoutParams=LinearLayout.LayoutParams(MATCH,0,1f) }
        val adapter = NetworkAdapter(detector.requests) { showRequestDetail(it) }
        rv.adapter = adapter
        container.addView(rv)

        searchBox.addTextChangedListener(tw { adapter.filter(it) })
        btnX.setOnClickListener { searchBox.setText("") }
        btnAll.setOnClickListener { adapter.filterByType(null) }
        btnStream.setOnClickListener { adapter.filterByType("stream") }
        btnApi.setOnClickListener { adapter.filterByType("api") }
        btnJs.setOnClickListener { adapter.filterByType("js") }
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
            "Dump XHR"        to "(function(){if(!window.__xhr_log||!window.__xhr_log.length)return 'No log.';return window.__xhr_log.slice(-20).map(function(x){return x.status+' '+x.url;}).join('\\n');})()",
            "Hook Fetch"      to "(function(){if(window.__fetch_hooked)return 'Already hooked';window.__fetch_hooked=true;var _f=window.fetch;window.__fetch_log=[];window.fetch=function(i,o){var u=typeof i==='string'?i:(i&&i.url)||'';return _f.apply(this,arguments).then(function(r){var url=r.url||u;window.__fetch_log.push({url:url,status:r.status});try{SBridge.onRequest(url,'fetch_hook','GET');}catch(e){}return r;});};return 'Fetch hooked.';})();",
            "Dump Fetch"      to "(function(){if(!window.__fetch_log||!window.__fetch_log.length)return 'No log.';return window.__fetch_log.slice(-20).map(function(x){return x.status+' '+x.url;}).join('\\n');})()",
            "Hook HLS.js"     to "(function(){if(!window.Hls)return 'No Hls.js';if(window.__hls_hooked)return 'Already hooked';window.__hls_hooked=true;var o=Hls.prototype.loadSource;Hls.prototype.loadSource=function(url){try{SBridge.onRequest(url,'hlsjs','GET');}catch(e){}return o.apply(this,arguments);};return 'HLS.js hooked!';})();",
            "Hook MSE"        to "(function(){if(window.__ms_hooked)return 'Already hooked';window.__ms_hooked=true;var o=MediaSource.prototype.addSourceBuffer;MediaSource.prototype.addSourceBuffer=function(mime){try{SBridge.onRequest('mse://'+encodeURIComponent(mime),'mse','GET');}catch(e){}return o.apply(this,arguments);};return 'MSE hooked.';})();",
            "Hook videojs"    to "(function(){if(!window.videojs)return 'No videojs';if(window.__vjs_hooked)return 'Already hooked';window.__vjs_hooked=true;var _orig=window.videojs;window.videojs=function(el,opts,ready){try{if(opts&&opts.sources)opts.sources.forEach(function(s){if(s&&s.src)try{SBridge.onRequest(s.src,'videojs_hook','GET');}catch(e){}});if(opts&&opts.src)try{SBridge.onRequest(opts.src,'videojs_hook','GET');}catch(e){}}catch(e){}return _orig.apply(this,arguments);};Object.assign(window.videojs,_orig);return 'video.js hooked!';})();",
            "Hook play()"     to "(function(){if(window.__play_hooked)return 'Already hooked';window.__play_hooked=true;var _p=HTMLVideoElement.prototype.play;HTMLVideoElement.prototype.play=function(){try{var s=this.src||this.currentSrc||(this.querySelector&&this.querySelector('source')?this.querySelector('source').src:'');if(s)try{SBridge.onRequest(s,'play_hook','GET');}catch(e){}}catch(e){}return _p.apply(this,arguments);};return 'play() hooked!';})();",
            "All video src"   to "(function(){var v=[].slice.call(document.querySelectorAll('video'));var out=[];v.forEach(function(el,i){out.push('Video'+i+': src='+el.src+' currentSrc='+el.currentSrc);if(el.textTracks)out.push('  tracks='+el.textTracks.length);});return out.length?out.join('\\n'):'no video elements';})()",
            "Quét players"    to "(function(){var keys=['player','_player','videojs','jwplayer','hls','Hls','dash','shaka','flvjs','clappr','bitmovin','ZP','Plyr','DPlayer','xgplayer','tcplayer','Aliplayer','Artplayer'];var found=[];keys.forEach(function(k){if(window[k]!=null)found.push(k+':'+typeof window[k]);});return found.length?found.join('\\n'):'none';})()",
            "Tìm keys/tokens" to "(function(){var txt=document.documentElement.innerHTML.slice(0,200000);var out=[];var pats=[['key',/[\"']key[\"']\\s*:\\s*[\"']([^\"']{6,80})[\"']/gi],['token',/[\"']token[\"']\\s*:\\s*[\"']([^\"']{6,200})[\"']/gi],['secret',/[\"']secret[\"']\\s*:\\s*[\"']([^\"']{6,80})[\"']/gi],['apiKey',/[\"']apiKey[\"']\\s*:\\s*[\"']([^\"']{6,80})[\"']/gi],['api_key',/[\"']api_key[\"']\\s*:\\s*[\"']([^\"']{6,80})[\"']/gi]];pats.forEach(function(p){var m;p[1].lastIndex=0;while((m=p[1].exec(txt))!==null&&out.length<12)out.push(p[0]+': '+m[1]);});return out.length?out.join('\\n'):'none';})()",
            "Base64 decode"   to "(function(){var html=document.documentElement.innerHTML;var ms=html.match(/[\"']([A-Za-z0-9+\\/]{40,}={0,2})[\"']/g)||[];var out=[];ms.slice(0,50).forEach(function(m){try{var d=atob(m.replace(/[\"']/g,''));if(d.indexOf('http')!==-1||d.indexOf('m3u8')!==-1||d.indexOf('.mp4')!==-1)out.push(d.slice(0,300));}catch(e){}});return out.length?out.join('\\n'):'none';})()",
            "Cookie+Storage"  to "(function(){var r={cookies:document.cookie};try{r.local=[].concat(Object.keys(localStorage)).map(function(k){return k+'='+localStorage.getItem(k)}).join('; ');}catch(e){r.local='blocked';}try{r.session=[].concat(Object.keys(sessionStorage)).map(function(k){return k+'='+sessionStorage.getItem(k)}).join('; ');}catch(e){r.session='blocked';}return JSON.stringify(r,null,2);})()",
            "Tất cả iframes"  to "(function(){var f=[].slice.call(document.querySelectorAll('iframe'));return f.length?f.map(function(el,i){return i+': '+(el.src||el.getAttribute('src')||'no-src');}).join('\\n'):'none';})()",
            "Deep scan"       to "(function(){var html=document.documentElement.innerHTML;var out=[];var pats=[/https?:\\/\\/[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*/gi,/https?:\\/\\/[^\\s\"'<>]+\\.mp4[^\\s\"'<>]*/gi,/https?:\\/\\/[^\\s\"'<>]+\\.mpd[^\\s\"'<>]*/gi,/https?:\\/\\/[^\\s\"'<>]+\\.flv[^\\s\"'<>]*/gi,/https?:\\/\\/[^\\s\"'<>]*(?:cdn|stream|media|video|vod)[^\\s\"'<>]*/gi];pats.forEach(function(re){re.lastIndex=0;var m;while((m=re.exec(html))!==null&&out.length<30){if(out.indexOf(m[0])===-1)out.push(m[0]);}});return out.length?out.join('\\n'):'nothing found';})()",
        )
        addPresets(ctx, container, presets, tv, scroll, detector.deepLog)
        container.addView(divider(ctx))
        addInputRow(ctx, container, tv, scroll, detector.deepLog, true)
        contentFrame.addView(container)
    }

    // ── 5. HTML Viewer ────────────────────────────────────────────────────────
    private fun showHtmlTab() {
        val ctx = requireContext()
        val activityRef = requireActivity() as? MainActivity
        val container = col(ctx)

        // Mode buttons
        val modeRow = row(ctx, "#141414").apply { setPadding(10.dp, 8.dp, 10.dp, 8.dp) }
        val btnFull = Button(ctx).apply {
            text = "📄 Full HTML"; textSize = 11f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1D9E75")); setPadding(14.dp, 6.dp, 14.dp, 6.dp)
        }
        val btnPicker = Button(ctx).apply {
            text = "✏ Element Picker"; textSize = 11f; setTextColor(Color.parseColor("#CCCCCC"))
            setBackgroundColor(Color.parseColor("#E24B4A")); setPadding(14.dp, 6.dp, 14.dp, 6.dp)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = 8.dp }
        }
        val tvInfo = tv(ctx, "Kết quả hiện qua dialog sau khi chọn element", "#555555", 10f).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = 8.dp }
        }
        modeRow.addView(btnFull); modeRow.addView(btnPicker); modeRow.addView(tvInfo)
        container.addView(modeRow)
        container.addView(divider(ctx))

        // Search
        val searchRow = row(ctx, "#0D0D0D").apply { setPadding(8.dp, 4.dp, 8.dp, 4.dp) }
        val searchBox = EditText(ctx).apply {
            hint = "Tìm trong HTML..."; setHintTextColor(Color.parseColor("#444444"))
            setTextColor(Color.parseColor("#EFEFEF")); textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE; background = null
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val tvMatch = TextView(ctx).apply {
            text = ""; setTextColor(Color.parseColor("#1D9E75")); textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE; setPadding(8.dp, 0, 0, 0)
        }
        searchRow.addView(searchBox); searchRow.addView(tvMatch)
        container.addView(searchRow); container.addView(divider(ctx))

        // Output
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            setBackgroundColor(Color.parseColor("#080808"))
        }
        val outputTv = TextView(ctx).apply {
            text = "📄 Full HTML – lấy toàn bộ HTML trang\n\n✏ Element Picker – đóng panel, tap vào element trên trang → dialog hiện HTML"
            setTextColor(Color.parseColor("#555555")); textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE; setPadding(14.dp, 14.dp, 14.dp, 14.dp)
            setTextIsSelectable(true)
        }
        scroll.addView(outputTv); container.addView(scroll); container.addView(divider(ctx))

        // Action row
        val actionRow = row(ctx, "#141414").apply { setPadding(8.dp, 6.dp, 8.dp, 6.dp) }
        val btnCopyAll = Button(ctx).apply {
            text = "Copy All"; textSize = 11f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0D47A1")); setPadding(12.dp, 4.dp, 12.dp, 4.dp)
            isEnabled = false; alpha = 0.4f
        }
        val btnCopy3k = Button(ctx).apply {
            text = "Copy 3KB"; textSize = 11f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4A148C")); setPadding(12.dp, 4.dp, 12.dp, 4.dp)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = 8.dp }
            isEnabled = false; alpha = 0.4f
        }
        val tvSize = TextView(ctx).apply {
            text = ""; setTextColor(Color.parseColor("#555555")); textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = 8.dp }
            gravity = Gravity.END
        }
        actionRow.addView(btnCopyAll); actionRow.addView(btnCopy3k); actionRow.addView(tvSize)
        container.addView(actionRow)

        // State & logic
        var currentHtml = ""

        fun displayHtml(html: String) {
            currentHtml = html
            outputTv.setTextColor(Color.parseColor("#00FF41"))
            outputTv.text = html
            val kb = html.length / 1024f
            tvSize.text = "${String.format("%.1f", kb)} KB"
            btnCopyAll.isEnabled = true; btnCopyAll.alpha = 1f
            btnCopy3k.isEnabled = true; btnCopy3k.alpha = 1f
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
                    if (html.isEmpty()) { outputTv.text = "(Không lấy được HTML)"; tvSize.text = "Thất bại" }
                    else displayHtml(html)
                }
            }
        }

        btnPicker.setOnClickListener { dismiss(); activityRef?.activatePicker() }

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
                if (q.isEmpty() || currentHtml.isEmpty()) { outputTv.text = currentHtml; tvMatch.text = ""; return }
                val regex = Regex(Regex.escape(q), RegexOption.IGNORE_CASE)
                val count = regex.findAll(currentHtml).count()
                tvMatch.text = "$count kết quả"
                val span = android.text.SpannableString(currentHtml)
                var n = 0
                regex.findAll(currentHtml).forEach { match ->
                    if (n >= 200) return@forEach
                    span.setSpan(android.text.style.BackgroundColorSpan(Color.parseColor("#FFD700")),
                        match.range.first, match.range.last + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    span.setSpan(android.text.style.ForegroundColorSpan(Color.BLACK),
                        match.range.first, match.range.last + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    n++
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
        container.addView(tv(ctx, "Paste URL m3u8 để xem danh sách chất lượng:", "#888888", 11f).apply { setPadding(12.dp,10.dp,12.dp,4.dp) })

        val inputRow = row(ctx, "#141414").apply { setPadding(8.dp,6.dp,8.dp,6.dp) }
        val urlBox = editText(ctx, "https://example.com/master.m3u8").apply {
            detector.streams.firstOrNull { it.url.contains("m3u8") }?.let { setText(it.url) }
        }
        val btnParse = btn(ctx, "Parse", "#1D9E75", "#FFFFFF")
        inputRow.addView(urlBox); inputRow.addView(btnParse)
        container.addView(inputRow); container.addView(divider(ctx))

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
                    try { M3u8Parser.parse(url, referer) } catch (e: Exception) { emptyList() }
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
                        lp.setMargins(8.dp,4.dp,8.dp,0); layoutParams = lp
                    }
                    card.addView(tv(ctx, "📺 ${q.label}", "#EFEFEF", 13f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
                    card.addView(tv(ctx, q.url, "#888888", 10f).apply {
                        typeface = android.graphics.Typeface.MONOSPACE; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    })
                    val btnRow = row(ctx)
                    btnRow.addView(btn(ctx, "Copy", "#0D47A1", "#FFFFFF").apply { setOnClickListener { copy(q.url) } })
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
        val container = col(ctx)
        container.addView(ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(col(ctx).apply {
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                addView(tv(ctx, "🚫 Đã block: ${blocker.blockedCount} requests", "#1D9E75", 13f).apply { setPadding(0, 0, 0, 8.dp) })
                val builtinRow = row(ctx)
                builtinRow.addView(tv(ctx, "Block ads/trackers mặc định", "#EFEFEF", 12f).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
                val sw = Switch(ctx).apply { isChecked = blocker.isBuiltinEnabled(); setOnCheckedChangeListener { _, v -> blocker.setBuiltinEnabled(v) } }
                builtinRow.addView(sw)
                addView(builtinRow)
                addView(divider(ctx).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0,8.dp,0,8.dp) })
                addView(tv(ctx, "Custom patterns:", "#888888", 11f))
                val patterns = blocker.getCustomPatterns()
                patterns.forEach { pattern ->
                    val row2 = row(ctx)
                    row2.addView(tv(ctx, pattern, "#EFEFEF", 11f).apply { typeface = android.graphics.Typeface.MONOSPACE; layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
                    row2.addView(btn(ctx, "✕", "#E24B4A").apply { setOnClickListener { blocker.removePattern(pattern); contentFrame.removeAllViews(); showBlockerTab() } })
                    addView(row2)
                }
                if (patterns.isEmpty()) addView(tv(ctx, "(chưa có pattern tùy chỉnh)", "#555555", 11f))
                addView(divider(ctx).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0,8.dp,0,8.dp) })
                val addRow = row(ctx, "#141414").apply { setPadding(8.dp,6.dp,8.dp,6.dp) }
                val input = editText(ctx, "vd: ads.example.com")
                val btnAdd = btn(ctx, "Thêm", "#1D9E75", "#FFFFFF")
                addRow.addView(input); addRow.addView(btnAdd)
                addView(addRow)
                btnAdd.setOnClickListener {
                    val p = input.text.toString().trim()
                    if (p.isNotEmpty()) { blocker.addPattern(p); contentFrame.removeAllViews(); showBlockerTab() }
                }
            })
        })
        contentFrame.addView(container)
    }

    // ── 8. User-Agent (Enhanced) ──────────────────────────────────────────────
    private fun showUaTab() {
        val ctx = requireContext()
        val container = col(ctx)
        val current = UserAgentManager.load(ctx)
        container.addView(ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(col(ctx).apply {
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                // Current UA display
                addView(tv(ctx, "Current UA:", "#888888", 10f))
                addView(tv(ctx, current, "#1D9E75", 10f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE; maxLines = 3; setPadding(0, 4.dp, 0, 8.dp)
                })
                // Client Hints info
                val secChUa = UserAgentManager.buildSecChUa(current)
                val secChUaPlatform = UserAgentManager.buildSecChUaPlatform(current)
                val platform = UserAgentManager.getPlatform(current)
                val isMobile = UserAgentManager.isMobileUA(current)
                addView(tv(ctx, "Sec-CH-UA: $secChUa", "#4FC3F7", 9f).apply { typeface = android.graphics.Typeface.MONOSPACE; setPadding(0, 0, 0, 2.dp) })
                addView(tv(ctx, "Platform: $platform | Mobile: $isMobile", "#4FC3F7", 9f).apply { typeface = android.graphics.Typeface.MONOSPACE; setPadding(0, 0, 0, 8.dp) })
                addView(divider(ctx))

                // UA preset cards
                UserAgentManager.presets.forEach { (name, ua) ->
                    val isActive = ua == current
                    val card = col(ctx, if (isActive) "#1A2A1A" else "#242424").apply {
                        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
                        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                        lp.setMargins(0, 6.dp, 0, 0); layoutParams = lp
                    }
                    card.addView(tv(ctx, (if (isActive) "✓ " else "") + name, if (isActive) "#1D9E75" else "#EFEFEF", 13f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
                    card.addView(tv(ctx, ua.take(80) + if (ua.length > 80) "..." else "", "#888888", 9f).apply {
                        typeface = android.graphics.Typeface.MONOSPACE; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    card.setOnClickListener {
                        applyUa(ctx, ua, name)
                    }
                    addView(card)
                }

                // Custom UA input
                addView(divider(ctx).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 12.dp, 0, 8.dp) })
                addView(tv(ctx, "Custom UA:", "#888888", 11f))
                val customInput = EditText(ctx).apply {
                    setText(current); setTextColor(Color.parseColor("#EFEFEF"))
                    setHintTextColor(Color.parseColor("#555555")); textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE; background = null
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP); minLines = 2
                }
                addView(customInput)
                addView(btn(ctx, "Áp dụng UA này", "#1D9E75", "#FFFFFF").apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 8.dp }
                    setOnClickListener {
                        val ua2 = customInput.text.toString().trim()
                        if (ua2.isNotEmpty()) applyUa(ctx, ua2, "Custom")
                    }
                })
            })
        })
        contentFrame.addView(container)
    }

    private fun applyUa(ctx: Context, ua: String, name: String) {
        UserAgentManager.save(ctx, ua)
        webView.settings.userAgentString = ua
        injectUaOverride(ua)
        Toast.makeText(ctx, "UA: $name\nTrang sẽ reload", Toast.LENGTH_SHORT).show()
        webView.reload()
        contentFrame.removeAllViews(); showUaTab()
    }

    // ── 9. Saved (Bookmark + History) ────────────────────────────────────────
    private fun showSavedTab() {
        val ctx = requireContext()
        val activityRef = requireActivity() as? MainActivity ?: return
        val container = col(ctx)
        val bookmarks = BookmarkManager.getBookmarks(ctx)
        val history   = BookmarkManager.getHistory(ctx)
        val all       = bookmarks + history

        if (all.isEmpty()) { contentFrame.addView(centerText(ctx, "Chưa có bookmark/history.\nLong-press nút Bookmark để xem danh sách.")); return }

        val rv = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx); layoutParams = FrameLayout.LayoutParams(MATCH, MATCH) }
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class VH(val root: LinearLayout, val tvTitle: TextView, val tvUrl: TextView, val btnDel: Button) : RecyclerView.ViewHolder(root)

            override fun onCreateViewHolder(parent: ViewGroup, t: Int): VH {
                val title = tv(ctx, "", "#EFEFEF", 12f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
                val url   = tv(ctx, "", "#888888", 10f).apply { typeface=android.graphics.Typeface.MONOSPACE; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.MIDDLE; layoutParams=LinearLayout.LayoutParams(0,WRAP,1f) }
                val del   = btn(ctx, "✕", "#E24B4A")
                val urlRow = row(ctx); urlRow.addView(url); urlRow.addView(del)
                val root2 = col(ctx, "#1A1A1A").apply {
                    setPadding(12.dp,10.dp,12.dp,10.dp); addView(title); addView(urlRow)
                    addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#2E2E2E")); layoutParams=LinearLayout.LayoutParams(MATCH,1).apply{topMargin=6.dp} })
                }
                return VH(root2, title, url, del)
            }

            override fun getItemCount() = all.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val vh = h as VH; val e = all[pos]
                vh.tvTitle.text = (if (e.isBookmark) "★ " else "  ") + e.title
                vh.tvUrl.text   = e.url
                vh.root.setOnClickListener { activityRef.navigateTo(e.url); dismiss() }
                vh.btnDel.setOnClickListener {
                    if (e.isBookmark) BookmarkManager.removeBookmark(ctx, e.url)
                    else BookmarkManager.removeHistory(ctx, e.url)
                    contentFrame.removeAllViews(); showSavedTab()
                }
            }
        }
        container.addView(rv)
        val btnClearHist = btn(ctx, "🗑 Xoá lịch sử duyệt", "#E24B4A", "#FFFFFF").apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(12.dp,8.dp,12.dp,8.dp) }
            setOnClickListener { BookmarkManager.clearHistory(ctx); contentFrame.removeAllViews(); showSavedTab() }
        }
        container.addView(btnClearHist)
        contentFrame.addView(container)
    }

    // ── Request Detail (Enhanced - like DevTools desktop) ──────────────────────
    private fun showRequestDetail(req: NetworkRequest) {
        val ctx = requireContext()
        val sv = ScrollView(ctx)

        // Tab-like layout: General | Headers | Response | Timing
        val container = col(ctx, "#0D0D0D")

        // ── General Info ──
        container.addView(sectionHeader(ctx, "GENERAL"))
        container.addView(infoRow(ctx, "URL", req.url))
        container.addView(infoRow(ctx, "Method", req.method))
        container.addView(infoRow(ctx, "Status", req.statusText.ifEmpty { "N/A" }))
        container.addView(infoRow(ctx, "Source", req.source))
        container.addView(infoRow(ctx, "Type", req.tag))
        if (req.contentType.isNotEmpty())
            container.addView(infoRow(ctx, "Content-Type", req.contentType))
        if (req.duration > 0)
            container.addView(infoRow(ctx, "Duration", "${req.duration}ms"))

        // ── Request Headers ──
        container.addView(sectionHeader(ctx, "REQUEST HEADERS"))
        if (req.headers.isEmpty()) {
            container.addView(tv(ctx, "  (không có)", "#555555", 10f).apply { setPadding(0,4.dp,0,4.dp) })
        } else {
            req.headers.forEach { (k, v) -> container.addView(infoRow(ctx, k, v)) }
        }

        // ── Response Headers ──
        if (req.responseHeaders.isNotEmpty()) {
            container.addView(sectionHeader(ctx, "RESPONSE HEADERS"))
            req.responseHeaders.forEach { (k, v) -> container.addView(infoRow(ctx, k, v)) }
        }

        // ── Response Body Preview ──
        if (req.bodyPreview.isNotEmpty()) {
            container.addView(sectionHeader(ctx, "RESPONSE BODY (preview)"))
            val bodyTv = TextView(ctx).apply {
                text = req.bodyPreview.take(2000)
                setTextColor(Color.parseColor("#00FF41")); textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                setTextIsSelectable(true)
                maxLines = 20
            }
            container.addView(bodyTv)
        }

        sv.addView(container)
        AlertDialog.Builder(ctx)
            .setTitle("Request Detail")
            .setView(sv)
            .setPositiveButton("Copy URL")      { _,_ -> copy(req.url) }
            .setNeutralButton("Export cURL")    { _,_ -> copy(CurlExporter.toCurl(req)) }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun sectionHeader(ctx: Context, title: String) = TextView(ctx).apply {
        text = title; setTextColor(Color.parseColor("#1D9E75")); textSize = 11f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(12.dp, 10.dp, 12.dp, 4.dp)
        setBackgroundColor(Color.parseColor("#141414"))
    }

    private fun infoRow(ctx: Context, key: String, value: String) = row(ctx).apply {
        setPadding(12.dp, 2.dp, 12.dp, 2.dp)
        addView(TextView(ctx).apply {
            text = "$key: "; setTextColor(Color.parseColor("#4FC3F7")); textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })
        addView(TextView(ctx).apply {
            text = value; setTextColor(Color.parseColor("#EFEFEF")); textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setTextIsSelectable(true)
        })
    }

    // ── Shared helpers ────────────────────────────────────────────────────────
    private fun addPresets(ctx: Context, container: LinearLayout, presets: List<Pair<String,String>>,
                           tv: TextView, scroll: ScrollView, log: BoundedStringBuilder) {
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled=false; layoutParams=LinearLayout.LayoutParams(MATCH,WRAP) }
        val row2 = row(ctx).apply { setPadding(8.dp,4.dp,8.dp,4.dp) }
        presets.forEach { (label, code) ->
            row2.addView(Button(ctx).apply {
                text=label; textSize=10f; setTextColor(Color.parseColor("#4FC3F7"))
                setBackgroundColor(Color.parseColor("#0A1929")); setPadding(10.dp,4.dp,10.dp,4.dp)
                layoutParams = LinearLayout.LayoutParams(WRAP,WRAP).apply { marginEnd=6.dp }
                setOnClickListener { runJs(code, tv, scroll, log, label) }
            })
        }
        hs.addView(row2); container.addView(hs)
    }

    private fun addInputRow(ctx: Context, container: LinearLayout, outputTv: TextView,
                            scroll: ScrollView, log: BoundedStringBuilder, isDeep: Boolean) {
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
        val btnDown = btn(ctx, "↓", "#888888").apply {
            setOnClickListener {
                val h = detector.consoleHistory
                if (h.isEmpty()) return@setOnClickListener
                histIdx = (histIdx+1).coerceAtMost(h.size - 1)
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
                outputTv.text = log.toString()
            }
        }
        listOf(prefix, jsInput, btnUp, btnDown, btnRun, btnClear).forEach { inputRow.addView(it) }
        container.addView(inputRow)
    }

    private fun runJs(code: String, output: TextView, scroll: ScrollView, log: BoundedStringBuilder, label: String) {
        log.append("\n▶ $label\n"); output.text = log.toString()
        webView.evaluateJavascript(code) { result ->
            requireActivity().runOnUiThread {
                val d = when {
                    result == null || result == "null" -> "(undefined)"
                    result.startsWith("\"") && result.endsWith("\"") ->
                        result.removeSurrounding("\"").replace("\\n","\n").replace("\\t","\t").replace("\\\"","\"").replace("\\\\","\\")
                    else -> result
                }
                log.append("$d\n"); output.text = log.toString()
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun outputArea(ctx: Context, log: BoundedStringBuilder, color: String): Pair<ScrollView, TextView> {
        val tv2 = TextView(ctx).apply {
            text=log.toString(); setTextColor(Color.parseColor(color))
            textSize=11f; typeface=android.graphics.Typeface.MONOSPACE
            setPadding(12.dp,12.dp,12.dp,12.dp); setTextIsSelectable(true)
        }
        val sv = ScrollView(ctx).apply {
            layoutParams=LinearLayout.LayoutParams(MATCH,0,1f)
            setBackgroundColor(Color.parseColor("#0D0D0D")); addView(tv2)
        }
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        return sv to tv2
    }

    /**
     * Deep UA override: navigator properties, Sec-CH-UA Client Hints,
     * WebGL renderer, screen info, connection info.
     * This bypasses sophisticated bot detection used by major sites.
     */
    private fun injectUaOverride(ua: String) {
        val isMobile  = UserAgentManager.isMobileUA(ua)
        val isChrome  = UserAgentManager.isChromeUA(ua)
        val major     = UserAgentManager.extractMajorVersion(ua)
        val platform  = UserAgentManager.getPlatform(ua)
        val vendor    = UserAgentManager.getVendor(ua)
        val secChUa   = UserAgentManager.buildSecChUa(ua)
        val secChUaPlatform = UserAgentManager.buildSecChUaPlatform(ua)
        val mobileStr = if (isMobile) "?1" else "?0"

        // WebGL renderer based on UA type
        val glRenderer = when {
            ua.contains("Windows") -> "ANGLE (Intel, Intel(R) UHD Graphics 630 Direct3D11 vs_5_0 ps_5_0, D3D11)"
            ua.contains("Mac")     -> "Apple GPU"
            ua.contains("Pixel 8") -> "Adreno (TM) 740"
            ua.contains("Android") -> "Adreno (TM) 640"
            else -> "ANGLE (Intel, Intel(R) UHD Graphics 630)"
        }
        val glVendor = when {
            ua.contains("Mac") -> "Apple Inc."
            else -> "Google Inc. (Intel)"
        }

        // Screen resolution
        val (screenW, screenH, colorDepth) = when {
            ua.contains("Pixel 8") -> Triple(1080, 2400, 24)
            ua.contains("iPhone")  -> Triple(1170, 2532, 24)
            ua.contains("SMART-TV") -> Triple(1920, 1080, 24)
            isMobile -> Triple(1080, 2400, 24)
            else -> Triple(1920, 1080, 24)
        }

        val js = """
(function() {
    var ua = ${ua.let { "\"${it.replace("\"", "\\\"")}\"" }};
    var platform = "$platform";
    var vendor = "$vendor";
    var major = "$major";
    var secChUa = $secChUa;
    var secChUaPlatform = $secChUaPlatform;
    var isMobile = $isMobile;
    var mobileStr = "$mobileStr";
    var glRenderer = "$glRenderer";
    var glVendor = "$glVendor";

    // ── 1. Override navigator properties ─────────────────────────────────────
    var navProps = {
        userAgent:      { get: function() { return ua; } },
        platform:       { get: function() { return platform; } },
        vendor:         { get: function() { return vendor; } },
        appVersion:     { get: function() { return ua.replace('Mozilla/', ''); } },
        appName:        { get: function() { return 'Netscape'; } },
        productSub:     { get: function() { return '20030107'; } },
        webdriver:      { get: function() { return false; } },
        deviceMemory:   { get: function() { return isMobile ? 8 : 8; } },
        hardwareConcurrency: { get: function() { return isMobile ? 8 : 8; } },
        maxTouchPoints: { get: function() { return isMobile ? 5 : 0; } },
        connection:     { get: function() { return { effectiveType: '4g', rtt: 50, downlink: 10, saveData: false }; } },
        plugins:        { get: function() {
            return Object.create(PluginArray.prototype, {
                length: { get: function() { return 5; } },
                item:   { value: function(i) { return null; } },
                namedItem: { value: function(n) { return null; } },
                refresh: { value: function() {} }
            });
        }},
        mimeTypes: { get: function() {
            return Object.create(MimeTypeArray.prototype, {
                length: { get: function() { return 2; } },
                item:   { value: function(i) { return null; } },
                namedItem: { value: function(n) { return null; } }
            });
        }},
        languages:   { get: function() { return ['vi-VN', 'vi', 'en-US', 'en']; } },
        language:    { get: function() { return 'vi-VN'; } }
    };
    Object.entries(navProps).forEach(function(e) {
        try { Object.defineProperty(navigator, e[0], Object.assign({ configurable: true }, e[1])); } catch(ex) {}
    });

    // ── 2. Override toString to bypass detection ─────────────────────────────
    try { navigator.userAgent.toString = function() { return ua; }; } catch(e) {}
    try { navigator.appVersion.toString = function() { return ua.replace('Mozilla/', ''); }; } catch(e) {}

    // ── 3. Override Sec-CH-UA Client Hints (crucial for Netflix, Disney+, etc.) ─
    try {
        Object.defineProperty(navigator, 'userAgentData', {
            get: function() {
                return {
                    brands: [
                        { brand: "Chromium", version: major },
                        { brand: "Google Chrome", version: major },
                        { brand: "Not-A.Brand", version: "99" }
                    ],
                    mobile: isMobile,
                    platform: secChUaPlatform.replace(/"/g, ''),
                    getHighEntropyValues: function(hints) {
                        return Promise.resolve({
                            brands: this.brands,
                            mobile: this.mobile,
                            platform: this.platform.replace(/"/g, ''),
                            platformVersion: "14.0.0",
                            architecture: isMobile ? "arm" : "x86",
                            bitness: "64",
                            model: isMobile ? "Pixel 8" : "",
                            uaFullVersion: major + ".0.0.0",
                            wow64: false
                        });
                    },
                    toJSON: function() {
                        return { brands: this.brands, mobile: this.mobile, platform: this.platform.replace(/"/g, '') };
                    }
                };
            },
            configurable: true
        });
    } catch(e) {}

    // ── 4. Override WebGL renderer (fingerprint bypass) ──────────────────────
    try {
        var getParamOrig = WebGLRenderingContext.prototype.getParameter;
        WebGLRenderingContext.prototype.getParameter = function(param) {
            if (param === 37445) return glVendor;   // UNMASKED_VENDOR_WEBGL
            if (param === 37446) return glRenderer;  // UNMASKED_RENDERER_WEBGL
            return getParamOrig.call(this, param);
        };
        var getParamOrig2 = WebGL2RenderingContext.prototype.getParameter;
        WebGL2RenderingContext.prototype.getParameter = function(param) {
            if (param === 37445) return glVendor;
            if (param === 37446) return glRenderer;
            return getParamOrig2.call(this, param);
        };
    } catch(e) {}

    // ── 5. Override screen properties (match UA screen resolution) ───────────
    try {
        var sw=$screenW, sh=$screenH, cd=$colorDepth;
        Object.defineProperties(screen, {
            width:           { get: function(){ return sw; }, configurable: true },
            height:          { get: function(){ return sh; }, configurable: true },
            availWidth:      { get: function(){ return sw; }, configurable: true },
            availHeight:     { get: function(){ return sh - (isMobile ? 0 : 40); }, configurable: true },
            colorDepth:      { get: function(){ return cd; }, configurable: true },
            pixelDepth:      { get: function(){ return cd; }, configurable: true },
            orientation:     { get: function(){ return { type: isMobile ? 'portrait-primary' : 'landscape-primary', angle: 0 }; }, configurable: true }
        });
    } catch(e) {}

    // ── 6. Remove automation/bot traces ──────────────────────────────────────
    ['callPhantom','_phantom','__nightmare','Buffer','domAutomation',
     'domAutomationController','spawn','emit','awesomium','cdc_adoQpoasnfa76pfcZLmcfl_Array',
     'cdc_adoQpoasnfa76pfcZLmcfl_Promise','cdc_adoQpoasnfa76pfcZLmcfl_Symbol'].forEach(function(k){
        try { delete window[k]; } catch(e) {}
    });
    try { delete window.navigator.__proto__.webdriver; } catch(e) {}
    try { delete window.document.__proto__.webdriver; } catch(e) {}

    // ── 7. Fix chrome runtime ────────────────────────────────────────────────
    if (!window.chrome) {
        window.chrome = {};
    }
    if (!window.chrome.runtime) {
        window.chrome.runtime = {
            connect: function() {},
            sendMessage: function() {},
            onMessage: { addListener: function() {} },
            id: undefined
        };
    }
    if (!window.chrome.app) {
        window.chrome.app = { isInstalled: false, InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' }, RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' } };
    }
    if (!window.chrome.csi) { window.chrome.csi = function() {}; }
    if (!window.chrome.loadTimes) { window.chrome.loadTimes = function() { return { firstPaintTime: 0, startLoadTime: 0, commitLoadTime: 0, finishDocumentLoadTime: 0, finishLoadTime: 0, requestTime: 0 }; }; }

    // ── 8. Override permissions API ──────────────────────────────────────────
    try {
        var origQuery = Permissions.prototype.query;
        Permissions.prototype.query = function(desc) {
            if (desc && desc.name === 'notifications') {
                return Promise.resolve({ state: Notification.permission });
            }
            return origQuery.call(this, desc);
        };
    } catch(e) {}
})();
""".trimIndent()

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
        setPadding(10.dp,4.dp,10.dp,4.dp); layoutParams=LinearLayout.LayoutParams(WRAP,WRAP)
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
        setPadding(24.dp,48.dp,24.dp,0); layoutParams=FrameLayout.LayoutParams(MATCH,MATCH)
    }
    private fun tw(fn: (String)->Unit) = object:TextWatcher {
        override fun afterTextChanged(s: Editable?) { fn(s.toString()) }
        override fun beforeTextChanged(s: CharSequence?,a:Int,b:Int,c:Int){}
        override fun onTextChanged(s: CharSequence?,a:Int,b:Int,c:Int){}
    }
    private fun copy(text: String) {
        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("copied",text))
        Toast.makeText(requireContext(),"Đã copy",Toast.LENGTH_SHORT).show()
    }
    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Chia sẻ"))
    }

    private val Int.dp  get() = (this * resources.displayMetrics.density).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT

    companion object { const val TAG = "DevToolsSheet" }
}

// ── NetworkAdapter (Enhanced with type filtering) ────────────────────────────
class NetworkAdapter(
    private val allItems: List<NetworkRequest>,
    private val onClick: (NetworkRequest) -> Unit
) : RecyclerView.Adapter<NetworkAdapter.VH>() {

    private var displayed = allItems.toMutableList()
    private var currentFilter: String? = null
    private var searchText: String = ""

    fun filter(q: String) {
        searchText = q
        applyFilters()
    }

    fun filterByType(type: String?) {
        currentFilter = type
        applyFilters()
    }

    private fun applyFilters() {
        displayed = allItems.filter { item ->
            val matchesSearch = searchText.isBlank() || item.url.contains(searchText, true)
            val matchesType = when {
                currentFilter == null -> true
                currentFilter == "stream" -> item.isStream
                currentFilter == "api" -> item.tag == "API" || item.tag == "JSON"
                currentFilter == "js" -> item.tag == "JS"
                else -> true
            }
            matchesSearch && matchesType
        }.toMutableList()
        notifyDataSetChanged()
    }

    inner class VH(root: View, val tvTag:TextView, val tvStatus:TextView, val tvHost:TextView, val tvPath:TextView, val tvTime:TextView): RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, t: Int): VH {
        val ctx = parent.context; val d=ctx.resources.displayMetrics.density; fun Int.dp()=(this*d).toInt()
        val tvTag    = TextView(ctx).apply{textSize=9f;typeface=android.graphics.Typeface.DEFAULT_BOLD;setPadding(6.dp(),2.dp(),6.dp(),2.dp());minWidth=44.dp();gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{marginEnd=6.dp()}}
        val tvStatus = TextView(ctx).apply{textSize=9f;typeface=android.graphics.Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER;minWidth=32.dp();setPadding(4.dp(),2.dp(),4.dp(),2.dp());layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{marginEnd=6.dp()}}
        val tvHost   = TextView(ctx).apply{setTextColor(Color.parseColor("#EFEFEF"));textSize=11f;typeface=android.graphics.Typeface.DEFAULT_BOLD;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        val tvPath   = TextView(ctx).apply{setTextColor(Color.parseColor("#888888"));textSize=10f;typeface=android.graphics.Typeface.MONOSPACE;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.MIDDLE}
        val tvTime   = TextView(ctx).apply{setTextColor(Color.parseColor("#555555"));textSize=9f;typeface=android.graphics.Typeface.MONOSPACE;layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT)}
        val info     = LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);addView(tvHost);addView(tvPath)}
        val row      = LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(12.dp(),8.dp(),12.dp(),8.dp());setBackgroundColor(Color.parseColor("#1A1A1A"));addView(tvTag);addView(tvStatus);addView(info);addView(tvTime)}
        val root     = LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);addView(row);addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#252525"));layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,1)})}
        return VH(root,tvTag,tvStatus,tvHost,tvPath,tvTime)
    }

    override fun getItemCount() = displayed.size
    override fun onBindViewHolder(h: VH, pos: Int) {
        val req=displayed[pos]
        h.tvTag.text=req.tag; h.tvTag.setBackgroundColor(Color.parseColor(req.tagColor)); h.tvTag.setTextColor(Color.WHITE)
        // Status badge
        if (req.statusCode > 0) {
            h.tvStatus.text = "${req.statusCode}"
            h.tvStatus.setTextColor(Color.parseColor(
                when {
                    req.statusCode in 200..299 -> "#4CAF50"
                    req.statusCode in 300..399 -> "#FF9800"
                    req.statusCode in 400..499 -> "#F44336"
                    req.statusCode >= 500 -> "#E24B4A"
                    else -> "#888888"
                }
            ))
        } else {
            h.tvStatus.text = "—"
            h.tvStatus.setTextColor(Color.parseColor("#555555"))
        }
        h.tvHost.text=req.host; h.tvPath.text=req.path
        h.tvTime.text = if (req.duration > 0) "${req.duration}ms" else ""
        h.itemView.setOnClickListener{onClick(req)}
    }
}
