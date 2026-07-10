package com.aho.streambrowser.ui

import com.aho.streambrowser.R
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebSettings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.aho.streambrowser.databinding.ActivityMainBinding
import com.aho.streambrowser.detector.BrowserChromeClient
import com.aho.streambrowser.detector.BrowserWebViewClient
import com.aho.streambrowser.detector.StreamDetector
import com.aho.streambrowser.detector.StreamJsBridge
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.model.TabManager
import com.aho.streambrowser.util.Constants
import com.aho.streambrowser.util.RequestBlocker
import com.aho.streambrowser.util.UserAgentManager
import com.aho.streambrowser.viewmodel.BrowserViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var webViewClient: BrowserWebViewClient? = null

    // H2: ViewModel injected by Hilt
    private val vm: BrowserViewModel by viewModels()

    val detector      by lazy { StreamDetector(this) }
    private val blocker by lazy { RequestBlocker(this) }
    val tabManager    = TabManager()

    private var isDesktopMode = false
    private var isIncognito   = false
    private var mobileUA      = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, wi ->
            val bars = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.toolbar.updatePadding(top = bars.top)
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            listOf(b.btnDevTools, b.btnPickerFloat).forEach { fab ->
                (fab.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.let {
                    it.bottomMargin = dp16 + bars.bottom; fab.requestLayout()
                }
            }
            WindowInsetsCompat.CONSUMED
        }

        setupWebView()
        setupAddressBar()
        setupButtons()
        setupBackHandler()
        setupDetector()
        observeViewModel()    // H2: observe StateFlow
        renderTabStrip()

        b.webView.loadUrl(Constants.DEFAULT_HOME_URL)
    }

    // H2: Observe StateFlow from ViewModel
    private fun observeViewModel() {
        lifecycleScope.launch {
            vm.bookmarks.collect { bookmarks ->
                val url = b.webView.url ?: return@collect
                val isBookmarked = bookmarks.any { it.url == url }
                b.btnBookmark.setImageResource(
                    if (isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark
                )
            }
        }
        // Auto-save streams to history via ViewModel
        detector.onStreamFound = { stream ->
            vm.saveStream(stream)
            runOnUiThread { updateFab() }
        }
        detector.onRequestAdded = { runOnUiThread { updateFab() } }

    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    b.webView.canGoBack() -> b.webView.goBack()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        mobileUA = UserAgentManager.load(this)
        b.webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            databaseEnabled                  = true
            allowFileAccess                  = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            userAgentString                  = mobileUA
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            loadWithOverviewMode = true; useWideViewPort = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(b.webView, true)
        }
        b.webView.addJavascriptInterface(
            StreamJsBridge(detector) { b.webView.url ?: "" }, "SBridge"
        )
        webViewClient = BrowserWebViewClient(
            detector       = detector,
            blocker        = blocker,
            onPageStarted  = { url, _ -> runOnUiThread { pageStarted(url) } },
            onPageFinished = { url    -> runOnUiThread { pageFinished(url) } }
        )
        b.webView.webViewClient = webViewClient!!
        b.webView.webChromeClient = BrowserChromeClient(
            detector          = detector,
            onProgressChanged = { p -> runOnUiThread { updateProgress(p) } },
            onTitleReceived   = { t -> runOnUiThread {
                tabManager.updateCurrent(b.webView.url ?: "", t)
                renderTabStrip()
            }}
        )
        b.swipeRefresh.setColorSchemeColors(0xFF1D9E75.toInt())
        b.swipeRefresh.setOnRefreshListener {
            b.webView.reload()
            b.swipeRefresh.postDelayed({ b.swipeRefresh.isRefreshing = false }, 500)
        }
        b.webView.setDownloadListener(DownloadListener { url, ua, cd, mime, _ ->
            downloadFile(url, ua, cd, mime)
        })
        detector.onSpaNavigation = { url ->
            runOnUiThread {
                if (!b.etUrl.isFocused) b.etUrl.setText(url)
                b.webView.evaluateJavascript("window.__sb_injected_v4 = false; void 0;", null)
                webViewClient?.forceReInject(b.webView)
                tabManager.updateCurrent(url, tabManager.current.title)
                renderTabStrip()
            }
        }
    }

    private fun setupAddressBar() {
        b.etUrl.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (go) { navigateTo(b.etUrl.text.toString()); true } else false
        }
        b.etUrl.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) v.post { b.etUrl.selectAll() } else hideKeyboard()
        }
        b.etUrl.setOnLongClickListener {
            copyToClipboard(b.webView.url ?: return@setOnLongClickListener false, "Đã copy URL"); true
        }
    }

    private fun setupButtons() {
        b.btnBack.setOnClickListener    { if (b.webView.canGoBack())    b.webView.goBack()    }
        b.btnForward.setOnClickListener { if (b.webView.canGoForward()) b.webView.goForward() }
        b.btnBack.setOnLongClickListener {
            b.webView.clearHistory(); updateNavButtons()
            Toast.makeText(this, "Đã xoá history điều hướng", Toast.LENGTH_SHORT).show(); true
        }
        b.btnRefresh.setOnClickListener {
            if (b.progressBar.isVisible) b.webView.stopLoading() else b.webView.reload()
        }
        b.btnDesktop.setOnClickListener    { toggleDesktopMode() }
        b.btnDesktop.setOnLongClickListener { toggleIncognito(); true }

        // H2: Bookmark uses ViewModel
        b.btnBookmark.setOnClickListener {
            val url = b.webView.url ?: return@setOnClickListener
            lifecycleScope.launch {
                if (vm.isBookmarked(url)) {
                    vm.removeBookmark(url)
                    Toast.makeText(this@MainActivity, "Đã xoá bookmark", Toast.LENGTH_SHORT).show()
                } else {
                    vm.addBookmark(url, b.webView.title ?: url)
                    Toast.makeText(this@MainActivity, "Đã lưu bookmark", Toast.LENGTH_SHORT).show()
                }
            }
        }
        b.btnBookmark.setOnLongClickListener { showBookmarkHistory(); true }
        b.btnDevTools.setOnClickListener     { openDevTools() }
        b.btnDevTools.setOnLongClickListener { showQuickActions(); true }
        b.btnPickerFloat.setOnClickListener  { b.btnPickerFloat.isVisible = !b.btnPickerFloat.isVisible }
    }

    private fun setupDetector() {
        // onStreamFound and onRequestAdded set in observeViewModel()
    }

    // ── F2: Multi-tab ──────────────────────────────────────────────────────────
    private fun renderTabStrip() {
        val strip = b.tabStrip; strip.removeAllViews()
        val dp = resources.displayMetrics.density
        tabManager.tabs.forEachIndexed { idx, tab ->
            val isCurrent = idx == tabManager.currentIdx
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = android.view.Gravity.CENTER_VERTICAL
                setPadding((8*dp).toInt(), 0, (4*dp).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = (2*dp).toInt() }
                setBackgroundColor(if (isCurrent) Color.parseColor("#1D9E75") else Color.parseColor("#1E1E1E"))
                setOnClickListener { switchTab(idx) }
            }
            val label = TextView(this).apply {
                text     = tab.title.take(14).ifBlank { tab.url.take(16).removePrefix("https://").removePrefix("http://") }
                textSize = 10f
                setTextColor(if (isCurrent) Color.WHITE else Color.parseColor("#AAAAAA"))
                maxLines = 1
            }
            val close = android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_close); background = null
                layoutParams = LinearLayout.LayoutParams((20*dp).toInt(), (20*dp).toInt())
                setColorFilter(if (isCurrent) Color.WHITE else Color.parseColor("#888888"))
                setOnClickListener { closeTab(idx) }
            }
            chip.addView(label); chip.addView(close); strip.addView(chip)
        }
        val addBtn = TextView(this).apply {
            text = " + "; textSize = 14f
            setTextColor(Color.parseColor("#1D9E75")); gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((32*dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { openNewTab() }
            setOnLongClickListener { showTabManager(); true }
        }
        strip.addView(addBtn)
        b.tabScrollView.isVisible = tabManager.count > 1
    }

    fun openNewTab(url: String = "about:blank") {
        detector.clear(); tabManager.newTab(url)
        b.webView.loadUrl(url); updateFab(); renderTabStrip()
    }

    private fun switchTab(idx: Int) {
        tabManager.updateCurrent(b.webView.url ?: "", b.webView.title ?: "")
        val tab = tabManager.switchTo(idx)
        detector.clear(); b.webView.loadUrl(tab.url.ifBlank { "about:blank" })
        if (!b.etUrl.isFocused) b.etUrl.setText(tab.url)
        updateFab(); renderTabStrip()
    }

    private fun closeTab(idx: Int) {
        tabManager.close(idx); val cur = tabManager.current
        detector.clear(); b.webView.loadUrl(cur.url.ifBlank { "about:blank" })
        if (!b.etUrl.isFocused) b.etUrl.setText(cur.url)
        updateFab(); renderTabStrip()
    }

    private fun showTabManager() {
        val tabs = tabManager.tabs
        AlertDialog.Builder(this).setTitle("Tabs (${tabs.size})")
            .setItems(tabs.mapIndexed { i, t ->
                "${if (i == tabManager.currentIdx) "▶ " else "   "}${t.title.take(40).ifBlank { t.url.take(40) }}"
            }.toTypedArray()) { _, i -> switchTab(i) }
            .setPositiveButton("+ New Tab") { _, _ -> openNewTab() }
            .setNegativeButton("Đóng", null).show()
    }

    // ── Navigation ────────────────────────────────────────────────────────────
    fun navigateTo(input: String) {
        val t = input.trim(); if (t.isBlank()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("about:") || t.startsWith("data:")     -> t
            !t.contains(" ") && t.contains(".") && t.substringAfterLast(".").length in 2..6 -> "https://$t"
            else -> "https://www.google.com/search?q=${Uri.encode(t)}"
        }
        b.webView.loadUrl(url); b.etUrl.clearFocus(); hideKeyboard()
    }

    private fun pageStarted(url: String) {
        if (!b.etUrl.isFocused) b.etUrl.setText(url)
        b.progressBar.isVisible = true
        b.btnRefresh.setImageResource(R.drawable.ic_close)
        b.swipeRefresh.isRefreshing = false
        b.ivSecure.alpha = if (url.startsWith("https")) 1f else 0.4f
        updateNavButtons()
        tabManager.updateCurrent(url, "Loading…"); renderTabStrip()
    }

    private fun pageFinished(url: String) {
        if (!b.etUrl.isFocused) b.etUrl.setText(url)
        b.progressBar.isVisible = false
        b.btnRefresh.setImageResource(R.drawable.ic_refresh)
        b.swipeRefresh.isRefreshing = false
        updateNavButtons()
        // H2: history via ViewModel
        vm.updatePage(url, b.webView.title ?: url)
        tabManager.updateCurrent(url, b.webView.title ?: url); renderTabStrip()
    }

    private fun updateProgress(p: Int) {
        b.progressBar.progress = p; b.progressBar.isVisible = p < 100
        if (p == 100) b.swipeRefresh.isRefreshing = false
    }

    private fun updateNavButtons() {
        b.btnBack.isEnabled    = b.webView.canGoBack();    b.btnBack.alpha    = if (b.webView.canGoBack()) 1f else 0.3f
        b.btnForward.isEnabled = b.webView.canGoForward(); b.btnForward.alpha = if (b.webView.canGoForward()) 1f else 0.3f
    }

    private fun updateFab() {
        val s = detector.streamCount()
        val r = detector.requestCount()

        // Phục hồi logic nguyên thủy cho nút DevTools để không gây lỗi trùng lặp
        when { 
            s > 0 -> { b.btnDevTools.text = "● $s Stream"; b.btnDevTools.extend() }
            r > 0 -> { b.btnDevTools.text = "DevTools ($r)"; b.btnDevTools.shrink() }
            else -> { b.btnDevTools.text = "DevTools"; b.btnDevTools.shrink() } 
        }
    }

    // ── Extras ────────────────────────────────────────────────────────────────
    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        b.webView.settings.userAgentString = if (isDesktopMode)
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"
        else mobileUA
        b.btnDesktop.alpha = if (isDesktopMode) 1f else 0.5f
        b.webView.reload()
        Toast.makeText(this, if (isDesktopMode) "🖥 Desktop" else "📱 Mobile", Toast.LENGTH_SHORT).show()
    }

    private fun toggleIncognito() {
        isIncognito = !isIncognito
        if (isIncognito) {
            CookieManager.getInstance().removeAllCookies(null)
            b.webView.clearCache(true); b.webView.clearHistory()
            b.webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            b.toolbar.setBackgroundColor(Color.parseColor("#1A1A2A"))
        } else {
            b.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            b.toolbar.setBackgroundColor(Color.parseColor("#141414"))
        }
        Toast.makeText(this, if (isIncognito) "🕵 Incognito ON" else "🔓 OFF", Toast.LENGTH_SHORT).show()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (detector.streamCount() > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build()) }
    }

    private fun showQuickActions() {
        val streams = detector.streams
        val items = mutableListOf<String>(); val actions = mutableListOf<() -> Unit>()
        streams.firstOrNull()?.url?.let { u -> items.add("📋 Copy last stream"); actions.add { copyToClipboard(u,"Copied") } }
        if (streams.isNotEmpty()) { items.add("⚙ Export cURLs"); actions.add {
            copyToClipboard(streams.take(5).joinToString("\n") { "curl \"${it.url}\" -H \"Referer: ${it.referer}\"" },"cURLs copied")
        }}
        items.add("+ New Tab"); actions.add { openNewTab() }
        items.add("📑 All Tabs"); actions.add { showTabManager() }
        items.add("🗑 Clear Session"); actions.add { detector.clear(); updateFab() }
        items.add(if (isIncognito) "🔓 Exit Incognito" else "🕵 Incognito"); actions.add { toggleIncognito() }
        items.add(if (isDesktopMode) "📱 Mobile" else "🖥 Desktop"); actions.add { toggleDesktopMode() }
        AlertDialog.Builder(this).setTitle("Quick Actions")
            .setItems(items.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("Đóng", null).show()
    }

    fun setSslBypass(enabled: Boolean) {
        webViewClient?.sslBypassEnabled = enabled
        Toast.makeText(this, if (enabled) "⚠ SSL bypass ON" else "SSL OFF", Toast.LENGTH_SHORT).show()
    }

    fun setHttpProxy(host: String, port: Int) {
        if (host.isBlank() || port <= 0) {
            listOf("http.proxyHost","http.proxyPort","https.proxyHost","https.proxyPort")
                .forEach { System.clearProperty(it) }
        } else {
            System.setProperty("http.proxyHost", host);  System.setProperty("http.proxyPort", port.toString())
            System.setProperty("https.proxyHost", host); System.setProperty("https.proxyPort", port.toString())
        }
        Toast.makeText(this, if (host.isBlank()) "Proxy cleared" else "Proxy: $host:$port", Toast.LENGTH_SHORT).show()
    }

    private var devToolsOverlay: DevToolsOverlay? = null

    // FIX (crash on DevTools tap): wrapped in try/catch as a safety net. Without a
    // logcat/stack trace from the device we could not confirm a single root-cause
    // exception line (see chat answer for the concrete layout bug we DID find and
    // fixed in DevToolsOverlay.kt). This ensures that *whatever* throws here from
    // now on surfaces as a Toast instead of taking down the whole app.
    private fun openDevTools() {
        try {
            if (devToolsOverlay == null) {
                devToolsOverlay = DevToolsOverlay(this, detector, b.webView, this) { playStream(it) }
                val root = window.decorView as android.widget.FrameLayout
                root.addView(devToolsOverlay, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                ))
                devToolsOverlay?.visibility = android.view.View.GONE
            }
            devToolsOverlay?.show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "openDevTools crashed", e)
            Toast.makeText(this, "DevTools lỗi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun playStream(item: StreamItem) {
        startActivity(Intent(this, PlayerActivity::class.java).apply { putExtra(PlayerActivity.EXTRA_STREAM, item) })
    }

    private fun downloadFile(url: String, ua: String, cd: String, mime: String) {
        try {
            val fn = android.webkit.URLUtil.guessFileName(url, cd, mime)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(
                DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mime); addRequestHeader("User-Agent", ua)
                    addRequestHeader("Referer", b.webView.url ?: "")
                    setTitle(fn); setDescription("Tải $fn")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fn)
                })
            Toast.makeText(this, "⬇ $fn", Toast.LENGTH_LONG).show()
        } catch (_: Exception) { copyToClipboard(url, "⬇ URL copied") }
    }

    private fun showBookmarkHistory() {
        lifecycleScope.launch {
            val entries = vm.bookmarks.value + vm.history.value
            if (entries.isEmpty()) { Toast.makeText(this@MainActivity,"Chưa có dữ liệu",Toast.LENGTH_SHORT).show(); return@launch }
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity).setTitle("Bookmark & History")
                    .setItems(entries.map { (if (it.isBookmark)"★ " else "   ")+it.title.take(55) }.toTypedArray()) { _,i -> navigateTo(entries[i].url) }
                    .setNeutralButton("Xoá History") { _,_ -> vm.clearHistory() }
                    .setNegativeButton("Đóng", null).show()
            }
        }
    }

    fun activatePicker() { b.btnPickerFloat.isVisible = true }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(b.etUrl.windowToken, 0)
    }

    fun copyToClipboard(text: String, msg: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("text", text))
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onPause()   { super.onPause();   b.webView.onPause() }
    override fun onResume()  { super.onResume();  b.webView.onResume() }
    override fun onDestroy() { webViewClient?.cleanup(); b.webView.apply { stopLoading(); clearHistory(); destroy() }; super.onDestroy() }
}
