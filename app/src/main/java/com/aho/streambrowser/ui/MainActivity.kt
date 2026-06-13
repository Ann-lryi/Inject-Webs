package com.aho.streambrowser.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.widget.Toast
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
import com.aho.streambrowser.util.BookmarkManager
import com.aho.streambrowser.util.Constants
import com.aho.streambrowser.util.RequestBlocker
import com.aho.streambrowser.util.UserAgentManager

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var webViewClient: BrowserWebViewClient? = null

    val detector by lazy { StreamDetector(this) }
    private val blocker by lazy { RequestBlocker(this) }

    private var isDesktopMode = false
    private var mobileUA = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Window insets: status bar → toolbar, nav bar → FABs
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, wi ->
            val bars = wi.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
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

        b.webView.loadUrl(Constants.DEFAULT_HOME_URL)
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
            setSupportZoom(true)
            builtInZoomControls              = true
            displayZoomControls              = false
            loadWithOverviewMode             = true
            useWideViewPort                  = true
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
            onTitleReceived   = { _ -> }
        )

        // F3: SwipeRefresh → reload page
        b.swipeRefresh.setColorSchemeColors(0xFF1D9E75.toInt())
        b.swipeRefresh.setOnRefreshListener {
            b.webView.reload()
            b.swipeRefresh.postDelayed({ b.swipeRefresh.isRefreshing = false }, 500)
        }

        // E1: SPA navigation → re-inject JS
        detector.onSpaNavigation = { url ->
            runOnUiThread {
                if (!b.etUrl.isFocused) b.etUrl.setText(url)
                b.webView.evaluateJavascript("window.__sb_injected_v4 = false; void 0;", null)
                webViewClient?.forceReInject(b.webView)
            }
        }
    }

    private fun setupAddressBar() {
        b.etUrl.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                     event?.keyCode == KeyEvent.KEYCODE_ENTER
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
        b.btnBack.setOnLongClickListener {
            b.webView.clearHistory(); updateNavButtons()
            Toast.makeText(this, "Đã xoá lịch sử điều hướng", Toast.LENGTH_SHORT).show(); true
        }
        b.btnRefresh.setOnClickListener {
            if (b.progressBar.isVisible) b.webView.stopLoading() else b.webView.reload()
        }

        // F6: Desktop mode toggle
        b.btnDesktop.setOnClickListener { toggleDesktopMode() }
        b.btnDesktop.setOnLongClickListener {
            Toast.makeText(this, if (isDesktopMode) "Desktop ON" else "Mobile ON", Toast.LENGTH_SHORT).show()
            true
        }

        b.btnBookmark.setOnClickListener {
            val url = b.webView.url ?: return@setOnClickListener
            val isBookmarked = BookmarkManager.isBookmarked(this, url)
            if (isBookmarked) {
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
        b.btnDevTools.setOnClickListener { openDevTools() }
        b.btnPickerFloat.setOnClickListener { togglePicker() }
    }

    private fun setupDetector() {
        detector.onStreamFound  = { runOnUiThread { updateFab() } }
        detector.onRequestAdded = { runOnUiThread { updateFab() } }
    }

    // ── F6: Desktop mode ──────────────────────────────────────────────────────
    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        val ua = if (isDesktopMode)
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        else mobileUA
        b.webView.settings.userAgentString = ua
        b.btnDesktop.alpha = if (isDesktopMode) 1f else 0.5f
        b.webView.reload()
        Toast.makeText(this, if (isDesktopMode) "🖥 Desktop mode" else "📱 Mobile mode", Toast.LENGTH_SHORT).show()
    }

    // ── F7: PiP — enter when leaving app with active streams ─────────────────
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (detector.streamCount() > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
            }
        }
    }

    // ── URL normalization (A3/improved) ───────────────────────────────────────
    fun navigateTo(input: String) {
        val t = input.trim()
        if (t.isBlank()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("about:") || t.startsWith("data:")     -> t
            t.startsWith("ws://")  || t.startsWith("wss://")    -> t
            !t.contains(" ") && t.contains(".") &&
                t.substringAfterLast(".").length in 2..6 &&
                !t.contains("?") -> "https://$t"
            else -> "https://www.google.com/search?q=${Uri.encode(t)}"
        }
        b.webView.loadUrl(url)
        b.etUrl.clearFocus()
        hideKeyboard()
    }

    // ── Page lifecycle ────────────────────────────────────────────────────────

    private fun pageStarted(url: String) {
        if (!b.etUrl.isFocused) b.etUrl.setText(url)
        b.progressBar.isVisible = true
        b.btnRefresh.setImageResource(R.drawable.ic_close)
        b.swipeRefresh.isRefreshing = false
        updateNavButtons()
        b.ivSecure.alpha = if (url.startsWith("https")) 1f else 0.4f
        b.btnBookmark.setImageResource(
            if (BookmarkManager.isBookmarked(this, url)) R.drawable.ic_bookmark_filled
            else R.drawable.ic_bookmark
        )
    }

    private fun pageFinished(url: String) {
        if (!b.etUrl.isFocused) b.etUrl.setText(url)
        b.progressBar.isVisible = false
        b.btnRefresh.setImageResource(R.drawable.ic_refresh)
        b.swipeRefresh.isRefreshing = false
        updateNavButtons()
        BookmarkManager.addHistory(this, url, b.webView.title ?: url)
    }

    private fun updateProgress(p: Int) {
        b.progressBar.progress  = p
        b.progressBar.isVisible = p < 100
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
        when {
            s > 0  -> { b.btnDevTools.text = "● $s Stream"; b.btnDevTools.extend() }
            r > 0  -> { b.btnDevTools.text = "DevTools ($r)"; b.btnDevTools.shrink() }
            else   -> { b.btnDevTools.text = "DevTools"; b.btnDevTools.shrink() }
        }
    }

    private fun togglePicker() {
        b.btnPickerFloat.isVisible = !b.btnPickerFloat.isVisible
    }

    fun activatePicker() { b.btnPickerFloat.isVisible = true }

    private fun openDevTools() {
        DevToolsSheet(detector, b.webView, this) { playStream(it) }
            .show(supportFragmentManager, DevToolsSheet.TAG)
    }

    private fun playStream(item: StreamItem) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM, item)
        })
    }

    private fun showBookmarkHistory() {
        val entries = BookmarkManager.getBookmarks(this) + BookmarkManager.getHistory(this)
        if (entries.isEmpty()) { Toast.makeText(this,"Chưa có dữ liệu",Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle("Bookmark & History")
            .setItems(entries.map { (if (it.isBookmark) "★ " else "   ") + it.title.take(55) }
                .toTypedArray()) { _, i -> navigateTo(entries[i].url) }
            .setNeutralButton("Xoá history") { _,_ ->
                BookmarkManager.clearHistory(this)
                Toast.makeText(this,"Đã xoá",Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null).show()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.etUrl.windowToken, 0)
    }

    private fun copyToClipboard(text: String, msg: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("text", text))
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onPause()   { super.onPause();   b.webView.onPause()  }
    override fun onResume()  { super.onResume();  b.webView.onResume() }
    override fun onDestroy() {
        webViewClient?.cleanup(); webViewClient = null
        b.webView.apply { stopLoading(); clearHistory(); destroy() }
        super.onDestroy()
    }
}
