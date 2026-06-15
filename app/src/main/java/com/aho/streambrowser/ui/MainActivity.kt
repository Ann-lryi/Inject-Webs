package com.aho.streambrowser.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Rational
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebSettings
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.aho.streambrowser.databinding.ActivityMainBinding
import com.aho.streambrowser.detector.BrowserChromeClient
import com.aho.streambrowser.detector.BrowserWebViewClient
import com.aho.streambrowser.detector.StreamDetector
import com.aho.streambrowser.detector.StreamJsBridge
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.model.TabManager
import com.aho.streambrowser.model.TabModel
import com.aho.streambrowser.util.BookmarkManager
import com.aho.streambrowser.util.Constants
import com.aho.streambrowser.util.RequestBlocker
import com.aho.streambrowser.util.UserAgentManager

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var webViewClient: BrowserWebViewClient? = null

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
        renderTabStrip()

        b.webView.loadUrl(Constants.DEFAULT_HOME_URL)
    }

    // ── Back handler ──────────────────────────────────────────────────────────
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
            copyToClipboard(b.webView.url ?: return@setOnLongClickListener false, "Đã copy URL")
            true
        }
    }

    private fun setupButtons() {
        b.btnBack.setOnClickListener    { if (b.webView.canGoBack())    b.webView.goBack()    }
        b.btnForward.setOnClickListener { if (b.webView.canGoForward()) b.webView.goForward() }
        b.btnBack.setOnLongClickListener { b.webView.clearHistory(); updateNavButtons(); true }
        b.btnRefresh.setOnClickListener {
            if (b.progressBar.isVisible) b.webView.stopLoading() else b.webView.reload()
        }
        b.btnDesktop.setOnClickListener    { toggleDesktopMode() }
        b.btnDesktop.setOnLongClickListener { toggleIncognito(); true }
        b.btnBookmark.setOnClickListener {
            val url = b.webView.url ?: return@setOnClickListener
            if (BookmarkManager.isBookmarked(this, url)) {
                BookmarkManager.removeBookmark(this, url)
                b.btnBookmark.setImageResource(R.drawable.ic_bookmark)
                Toast.makeText(this, "Đã xoá bookmark", Toast.LENGTH_SHORT).show()
            } else {
                BookmarkManager.addBookmark(this, url, b.webView.title ?: url)
                b.btnBookmark.setImageResource(R.drawable.ic_bookmark_filled)
                Toast.makeText(this, "Đã lưu bookmark", Toast.LENGTH_SHORT).show()
            }
        }
        b.btnBookmark.setOnLongClickListener { showBookmarkHistory(); true }
        b.btnDevTools.setOnClickListener     { openDevTools() }
        b.btnDevTools.setOnLongClickListener { showQuickActions(); true }
        b.btnPickerFloat.setOnClickListener  { b.btnPickerFloat.isVisible = !b.btnPickerFloat.isVisible }
    }

    private fun setupDetector() {
        detector.onStreamFound  = { runOnUiThread { updateFab() } }
        detector.onRequestAdded = { runOnUiThread { updateFab() } }
    }

    // ── F2: Multi-tab ─────────────────────────────────────────────────────────
    private fun renderTabStrip() {
        val strip = b.tabStrip
        strip.removeAllViews()
        val dp = resources.displayMetrics.density

        tabManager.tabs.forEachIndexed { idx, tab ->
            val isCurrent = idx == tabManager.currentIdx
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = android.view.Gravity.CENTER_VERTICAL
                setPadding((8 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply { marginEnd = (2 * dp).toInt() }
                setBackgroundColor(if (isCurrent) Color.parseColor("#1D9E75") else Color.parseColor("#1E1E1E"))
                setOnClickListener { switchTab(idx) }
            }
            val label = TextView(this).apply {
                text     = tab.title.take(16).ifBlank { tab.url.take(20).removePrefix("https://").removePrefix("http://") }
                textSize = 10f
                setTextColor(if (isCurrent) Color.WHITE else Color.parseColor("#AAAAAA"))
                maxLines = 1
            }
            val close = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                background    = null
                layoutParams  = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt())
                setColorFilter(if (isCurrent) Color.WHITE else Color.parseColor("#888888"))
                setOnClickListener { closeTab(idx) }
            }
            chip.addView(label); chip.addView(close)
            strip.addView(chip)
        }
        // "+" button
        val addBtn = TextView(this).apply {
            text      = " + "
            textSize  = 14f
            setTextColor(Color.parseColor("#1D9E75"))
            gravity   = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                (32 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { openNewTab() }
            setOnLongClickListener { showTabManager(); true }
        }
        strip.addView(addBtn)

        // Show/hide tab strip (show if > 1 tab)
        b.tabScrollView.isVisible = tabManager.count > 1
    }

    fun openNewTab(url: String = "about:blank") {
        detector.clear()
        tabManager.newTab(url)
        b.webView.loadUrl(url)
        updateFab()
        renderTabStrip()
    }

    private fun switchTab(idx: Int) {
        tabManager.updateCurrent(b.webView.url ?: "", b.webView.title ?: "")
        val tab = tabManager.switchTo(idx)
        detector.clear()
        b.webView.loadUrl(tab.url.ifBlank { "about:blank" })
        if (!b.etUrl.isFocused) b.etUrl.setText(tab.url)
        updateFab(); renderTabStrip()
    }

    private fun closeTab(idx: Int) {
        tabManager.close(idx)
        val current = tabManager.current
        detector.clear()
        b.webView.loadUrl(current.url.ifBlank { "about:blank" })
        if (!b.etUrl.isFocused) b.etUrl.setText(current.url)
        updateFab(); renderTabStrip()
    }

    private fun showTabManager() {
        val tabs  = tabManager.tabs
        val names = tabs.mapIndexed { i, t ->
            "${if (i == tabManager.currentIdx) "▶ " else "   "}${t.title.take(40).ifBlank { t.url.take(40) }}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tabs (${tabs.size})")
            .setItems(names) { _, i -> switchTab(i) }
            .setPositiveButton("+ New Tab") { _, _ -> openNewTab() }
            .setNegativeButton("Đóng", null)
            .show()
    }

    // ── Navigation ────────────────────────────────────────────────────────────
    fun navigateTo(input: String) {
        val t = input.trim(); if (t.isBlank()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("about:") || t.startsWith("data:")     -> t
            !t.contains(" ") && t.contains(".") &&
            t.substringAfterLast(".").length in 2..6            -> "https://$t"
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
        b.btnBookmark.setImageResource(
            if (BookmarkManager.isBookmarked(this, url)) R.drawable.ic_bookmark_filled
            else R.drawable.ic_bookmark
        )
        updateNavButtons()
        tabManager.updateCurrent(url, "Loading…")
        renderTabStrip()
    }

    private fun pageFinished(url: String) {
        if (!b.etUrl.isFocused) b.etUrl.setText(url)
        b.progressBar.isVisible = false
        b.btnRefresh.setImageResource(R.drawable.ic_refresh)
        b.swipeRefresh.isRefreshing = false
        updateNavButtons()
        BookmarkManager.addHistory(this, url, b.webView.title ?: url)
        tabManager.updateCurrent(url, b.webView.title ?: url)
        renderTabStrip()
    }

    private fun updateProgress(p: Int) {
        b.progressBar.progress = p; b.progressBar.isVisible = p < 100
        if (p == 100) b.swipeRefresh.isRefreshing = false
    }

    private fun updateNavButtons() {
        b.btnBack.isEnabled    = b.webView.canGoBack()
        b.btnBack.alpha        = if (b.webView.canGoBack()) 1f else 0.3f
        b.btnForward.isEnabled = b.webView.canGoForward()
        b.btnForward.alpha     = if (b.webView.canGoForward()) 1f else 0.3f
    }

    private fun updateFab() {
        val s = detector.streamCount(); val r = detector.requestCount()
        when { s>0 -> { b.btnDevTools.text="● $s Stream"; b.btnDevTools.extend() }
               r>0 -> { b.btnDevTools.text="DevTools ($r)"; b.btnDevTools.shrink() }
               else -> { b.btnDevTools.text="DevTools"; b.btnDevTools.shrink() } }
    }

    // ── F6+J3+F10 ─────────────────────────────────────────────────────────────
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
        val items   = mutableListOf<String>(); val actions = mutableListOf<() -> Unit>()
        streams.firstOrNull()?.url?.let { u -> items.add("📋 Copy last stream"); actions.add { copyToClipboard(u,"Copied") } }
        if (streams.isNotEmpty()) { items.add("⚙ Export cURLs"); actions.add {
            copyToClipboard(streams.take(5).joinToString("\n") { "curl \"${it.url}\" -H \"Referer: ${it.referer}\"" }, "cURLs copied")
        }}
        items.add("+ New Tab"); actions.add { openNewTab() }
        items.add("📑 All Tabs"); actions.add { showTabManager() }
        items.add("🗑 Clear Session"); actions.add { detector.clear(); updateFab(); Toast.makeText(this,"Cleared",Toast.LENGTH_SHORT).show() }
        items.add(if (isIncognito) "🔓 Exit Incognito" else "🕵 Incognito"); actions.add { toggleIncognito() }
        items.add(if (isDesktopMode) "📱 Mobile" else "🖥 Desktop"); actions.add { toggleDesktopMode() }
        AlertDialog.Builder(this).setTitle("Quick Actions")
            .setItems(items.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("Đóng", null).show()
    }

    /** J1: SSL bypass — called from DevToolsSheet */
    fun setSslBypass(enabled: Boolean) {
        webViewClient?.sslBypassEnabled = enabled
        Toast.makeText(this, if (enabled) "⚠ SSL bypass ON" else "SSL bypass OFF", Toast.LENGTH_SHORT).show()
    }

    /** J2: HTTP Proxy — sets system properties for OkHttp calls */
    fun setHttpProxy(host: String, port: Int) {
        if (host.isBlank() || port <= 0) {
            System.clearProperty("http.proxyHost"); System.clearProperty("http.proxyPort")
            System.clearProperty("https.proxyHost"); System.clearProperty("https.proxyPort")
            Toast.makeText(this, "Proxy cleared", Toast.LENGTH_SHORT).show()
        } else {
            System.setProperty("http.proxyHost", host);  System.setProperty("http.proxyPort", port.toString())
            System.setProperty("https.proxyHost", host); System.setProperty("https.proxyPort", port.toString())
            Toast.makeText(this, "Proxy set: $host:$port", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDevTools() {
        DevToolsSheet(detector, b.webView, this) { playStream(it) }
            .show(supportFragmentManager, DevToolsSheet.TAG)
    }

    private fun playStream(item: StreamItem) {
        startActivity(Intent(this, PlayerActivity::class.java).apply { putExtra(PlayerActivity.EXTRA_STREAM, item) })
    }

    private fun downloadFile(url: String, ua: String, cd: String, mime: String) {
        try {
            val fn = android.webkit.URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime); addRequestHeader("User-Agent", ua)
                addRequestHeader("Referer", b.webView.url ?: "")
                setTitle(fn); setDescription("Đang tải $fn")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fn)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, "⬇ $fn", Toast.LENGTH_LONG).show()
        } catch (_: Exception) { copyToClipboard(url, "⬇ URL copied (download failed)") }
    }

    private fun showBookmarkHistory() {
        val entries = BookmarkManager.getBookmarks(this) + BookmarkManager.getHistory(this)
        if (entries.isEmpty()) { Toast.makeText(this,"Chưa có dữ liệu",Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Bookmark & History")
            .setItems(entries.map { (if (it.isBookmark) "★ " else "   ") + it.title.take(55) }.toTypedArray()) { _, i -> navigateTo(entries[i].url) }
            .setNeutralButton("Xoá History") { _,_ -> BookmarkManager.clearHistory(this); Toast.makeText(this,"Đã xoá",Toast.LENGTH_SHORT).show() }
            .setNegativeButton("Đóng", null).show()
    }

    fun activatePicker() { b.btnPickerFloat.isVisible = true }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(b.etUrl.windowToken, 0)
    }

    private fun copyToClipboard(text: String, msg: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("text", text))
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onPause()   { super.onPause();   b.webView.onPause() }
    override fun onResume()  { super.onResume();  b.webView.onResume() }
    override fun onDestroy() {
        webViewClient?.cleanup(); webViewClient = null
        b.webView.apply { stopLoading(); clearHistory(); destroy() }
        super.onDestroy()
    }
}
