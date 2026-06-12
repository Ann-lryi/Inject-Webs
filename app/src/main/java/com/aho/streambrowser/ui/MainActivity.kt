package com.aho.streambrowser.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.aho.streambrowser.R
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── FIX #1: Edge-to-edge (Android 15+ enforcement) ──────────────────
        WindowCompat.setDecorFitsSystemWindows(window, false)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Apply window insets: toolbar pads top for status bar, FABs pad bottom for nav bar
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
            )
            // Toolbar: add top padding = status bar height
            b.toolbar.updatePadding(top = bars.top)
            // DevTools FAB: offset from nav bar
            (b.btnDevTools.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.let {
                it.bottomMargin = (16 * resources.displayMetrics.density).toInt() + bars.bottom
                b.btnDevTools.requestLayout()
            }
            (b.btnPickerFloat.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.let {
                it.bottomMargin = (16 * resources.displayMetrics.density).toInt() + bars.bottom
                b.btnPickerFloat.requestLayout()
            }
            WindowInsetsCompat.CONSUMED
        }

        setupWebView()
        setupAddressBar()
        setupButtons()
        setupBackHandler()   // FIX #2: replace onBackPressed()
        setupDetector()

        b.webView.loadUrl(Constants.DEFAULT_HOME_URL)
    }

    // ── FIX #2: Back button — OnBackPressedDispatcher (Android 13+) ─────────
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    b.webView.canGoBack() -> b.webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val ua = UserAgentManager.load(this)
        b.webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            databaseEnabled                  = true
            allowFileAccess                  = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            userAgentString                  = ua
            setSupportZoom(true)
            builtInZoomControls              = true
            displayZoomControls              = false
            loadWithOverviewMode             = true
            useWideViewPort                  = true
            cacheMode                        = WebSettings.LOAD_DEFAULT
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
            onTitleReceived   = { t -> runOnUiThread { updateTitle(t) } }
        )
    }

    // ── FIX #3: URL bar improvements ─────────────────────────────────────────
    private fun setupAddressBar() {
        // Enter to navigate
        b.etUrl.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                     event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (go) { navigateTo(b.etUrl.text.toString()); true } else false
        }

        // Select all on focus — easy URL replacement
        b.etUrl.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.post { b.etUrl.selectAll() }
            } else {
                // Restore page title when losing focus (if title is set)
                val title = b.webView.title
                if (!title.isNullOrBlank() && title != b.webView.url) {
                    // keep URL in field for now
                }
                hideKeyboard()
            }
        }

        // Long press URL bar: copy current URL
        b.etUrl.setOnLongClickListener {
            val url = b.webView.url ?: return@setOnLongClickListener false
            copyToClipboard(url, "URL đã copy")
            true
        }
    }

    private fun setupButtons() {
        b.btnBack.setOnClickListener    { if (b.webView.canGoBack())    b.webView.goBack()    }
        b.btnForward.setOnClickListener { if (b.webView.canGoForward()) b.webView.goForward() }

        // Long-press back → go to beginning of history
        b.btnBack.setOnLongClickListener {
            b.webView.clearHistory()
            Toast.makeText(this, "Đã xoá lịch sử điều hướng", Toast.LENGTH_SHORT).show()
            true
        }

        b.btnRefresh.setOnClickListener {
            if (b.webView.progress < 100) b.webView.stopLoading()
            else b.webView.reload()
        }

        b.btnHome.setOnClickListener { b.webView.loadUrl(Constants.DEFAULT_HOME_URL) }
        b.btnDevTools.setOnClickListener { openDevTools() }

        // Bookmark
        b.btnBookmark.setOnClickListener {
            val url   = b.webView.url   ?: return@setOnClickListener
            val title = b.webView.title ?: url
            val isBookmarked = BookmarkManager.isBookmarked(this, url)
            if (isBookmarked) {
                BookmarkManager.removeBookmark(this, url)
                b.btnBookmark.setImageResource(R.drawable.ic_bookmark)
                Toast.makeText(this, "Đã xoá bookmark", Toast.LENGTH_SHORT).show()
            } else {
                BookmarkManager.addBookmark(this, url, title)
                b.btnBookmark.setImageResource(R.drawable.ic_bookmark_filled)
                Toast.makeText(this, "Đã lưu bookmark", Toast.LENGTH_SHORT).show()
            }
        }
        b.btnBookmark.setOnLongClickListener { showBookmarkHistory(); true }

        // Element picker FAB
        b.btnPickerFloat.setOnClickListener {
            ElementPickerManager.toggle(
                webView     = b.webView,
                onActivated = {
                    b.btnPickerFloat.setImageResource(R.drawable.ic_picker)
                    b.btnPickerFloat.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#E24B4A"))
                    Toast.makeText(this, "✏ Tap vào element bất kỳ", Toast.LENGTH_LONG).show()
                },
                onDeactivated = { hidePicker() }
            )
        }
    }

    fun activatePicker() {
        b.btnPickerFloat.isVisible = true
        ElementPickerManager.activate(
            webView       = b.webView,
            onActivated   = {
                b.btnPickerFloat.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#E24B4A"))
                Toast.makeText(this, "✏ Tap vào element bất kỳ", Toast.LENGTH_LONG).show()
            },
            onDeactivated = { runOnUiThread { hidePicker() } }
        )
    }

    private fun hidePicker() {
        b.btnPickerFloat.isVisible = false
    }

    private fun setupDetector() {
        detector.onStreamFound  = { runOnUiThread { updateFab() } }
        detector.onRequestAdded = { runOnUiThread { updateFab() } }
    }

    // ── FIX #4: Better URL normalization ─────────────────────────────────────
    fun navigateTo(input: String) {
        val trimmed = input.trim()
        val url = when {
            trimmed.isBlank() -> return
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("about:") || trimmed.startsWith("data:")      -> trimmed
            // Detect domain-like strings (contains dot, no spaces)
            !trimmed.contains(" ") && trimmed.contains(".") &&
            trimmed.substringAfterLast(".").length in 2..6 -> "https://$trimmed"
            // Everything else = search
            else -> "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        }
        b.webView.loadUrl(url)
        b.etUrl.clearFocus()
        hideKeyboard()
    }

    // ── Page lifecycle ────────────────────────────────────────────────────────

    private fun pageStarted(url: String) {
        if (!b.etUrl.isFocused) b.etUrl.setText(url)
        b.progressBar.isVisible  = true
        b.btnRefresh.setImageResource(R.drawable.ic_close)  // show X while loading
        b.btnBack.isEnabled    = b.webView.canGoBack()
        b.btnBack.alpha        = if (b.webView.canGoBack()) 1f else 0.3f
        b.btnForward.isEnabled = b.webView.canGoForward()
        b.btnForward.alpha     = if (b.webView.canGoForward()) 1f else 0.3f
        b.ivSecure.alpha       = if (url.startsWith("https")) 1f else 0.4f
        val bookmarked = BookmarkManager.isBookmarked(this, url)
        b.btnBookmark.setImageResource(
            if (bookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark)
        b.btnDevTools.text = "DevTools"
        b.btnDevTools.shrink()
        if (ElementPickerManager.isPickerActive()) hidePicker()
    }

    private fun pageFinished(url: String) {
        if (!b.etUrl.isFocused) b.etUrl.setText(url)
        b.progressBar.isVisible  = false
        b.btnRefresh.setImageResource(R.drawable.ic_refresh)  // restore refresh icon
        b.btnBack.isEnabled    = b.webView.canGoBack()
        b.btnBack.alpha        = if (b.webView.canGoBack()) 1f else 0.3f
        b.btnForward.isEnabled = b.webView.canGoForward()
        b.btnForward.alpha     = if (b.webView.canGoForward()) 1f else 0.3f
        BookmarkManager.addHistory(this, url, b.webView.title ?: url)
    }

    // Show page title in URL bar briefly
    private fun updateTitle(title: String) {
        if (!b.etUrl.isFocused && title.isNotBlank()) {
            // Don't replace URL with title — just update if user not typing
        }
    }

    private fun updateProgress(p: Int) {
        b.progressBar.progress  = p
        b.progressBar.isVisible = p < 100
    }

    private fun updateFab() {
        val s = detector.streamCount()
        val r = detector.requestCount()
        if (s > 0) {
            b.btnDevTools.text = "● $s Stream"
            b.btnDevTools.extend()
        } else if (r > 0) {
            b.btnDevTools.text = "DevTools ($r)"
            b.btnDevTools.shrink()
        } else {
            b.btnDevTools.text = "DevTools"
            b.btnDevTools.shrink()
        }
    }

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
        val bookmarks = BookmarkManager.getBookmarks(this)
        val history   = BookmarkManager.getHistory(this)
        val entries   = bookmarks + history
        if (entries.isEmpty()) {
            Toast.makeText(this, "Chưa có bookmark/history", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Bookmark & History")
            .setItems(entries.map {
                (if (it.isBookmark) "★ " else "    ") + it.title.take(60)
            }.toTypedArray()) { _, i -> navigateTo(entries[i].url) }
            .setNeutralButton("Xoá history") { _, _ ->
                BookmarkManager.clearHistory(this)
                Toast.makeText(this, "Đã xoá history", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.etUrl.windowToken, 0)
    }

    private fun copyToClipboard(text: String, msg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onPause()   { super.onPause();   b.webView.onPause()  }
    override fun onResume()  { super.onResume();  b.webView.onResume() }
    override fun onDestroy() {
        webViewClient?.cleanup()
        webViewClient = null
        b.webView.apply {
            stopLoading(); clearHistory()
            clearCache(true); loadUrl("about:blank")
            removeAllViews(); destroy()
        }
        super.onDestroy()
    }
}
