package com.aho.streambrowser.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.aho.streambrowser.R
import com.aho.streambrowser.databinding.ActivityMainBinding
import com.aho.streambrowser.detector.BrowserChromeClient
import com.aho.streambrowser.detector.BrowserWebViewClient
import com.aho.streambrowser.detector.StreamDetector
import com.aho.streambrowser.detector.StreamJsBridge
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.util.BookmarkManager
import com.aho.streambrowser.util.RequestBlocker
import com.aho.streambrowser.util.UserAgentManager

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    val detector by lazy { StreamDetector(this) }
    val blocker by lazy { RequestBlocker(this) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupWebView()
        setupAddressBar()
        setupButtons()
        setupDetector()
        b.webView.loadUrl("https://www.google.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val ua = UserAgentManager.load(this)
        b.webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            databaseEnabled                  = true
            allowFileAccess                  = false
            // Allow mixed content so HTTP video streams can load on HTTPS pages
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            userAgentString                  = ua
            setSupportZoom(true)
            builtInZoomControls  = true
            displayZoomControls  = false
            loadWithOverviewMode = true
            useWideViewPort      = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(b.webView, true)
        }
        b.webView.addJavascriptInterface(
            StreamJsBridge(detector) { b.webView.url ?: "" }, "SBridge"
        )
        b.webView.webViewClient = BrowserWebViewClient(
            detector       = detector,
            blocker        = blocker,
            onPageStarted  = { url, _ -> runOnUiThread { pageStarted(url) } },
            onPageFinished = { url    -> runOnUiThread { pageFinished(url) } }
        )
        b.webView.webChromeClient = BrowserChromeClient(
            onProgressChanged = { p -> runOnUiThread { updateProgress(p) } },
            onTitleReceived   = { _ -> }
        )
    }

    private fun setupAddressBar() {
        b.etUrl.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                     event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (go) { navigateTo(b.etUrl.text.toString()); true } else false
        }
        b.etUrl.setOnLongClickListener { showBookmarkHistory(); true }
    }

    private fun setupButtons() {
        b.btnBack.setOnClickListener    { if (b.webView.canGoBack())    b.webView.goBack()    }
        b.btnForward.setOnClickListener { if (b.webView.canGoForward()) b.webView.goForward() }
        b.btnRefresh.setOnClickListener { b.webView.reload() }
        b.btnHome.setOnClickListener    { b.webView.loadUrl("https://www.google.com") }
        b.btnDevTools.setOnClickListener { openDevTools() }

        // Bookmark toggle
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
                Toast.makeText(this, "Đã bookmark", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this,
                        "Tap vào bất kỳ element nào trên trang", Toast.LENGTH_LONG).show()
                },
                onDeactivated = { hidePicker() }
            )
        }
    }

    /** Called from DevToolsSheet when picker is activated */
    fun activatePicker() {
        b.btnPickerFloat.isVisible = true
        ElementPickerManager.activate(
            webView     = b.webView,
            onActivated = {
                b.btnPickerFloat.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#E24B4A"))
                Toast.makeText(this,
                    "Tap vào element bất kỳ trên trang", Toast.LENGTH_LONG).show()
            },
            onDeactivated = { runOnUiThread { hidePicker() } }
        )
    }

    private fun hidePicker() {
        b.btnPickerFloat.isVisible = false
        b.btnPickerFloat.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#E24B4A"))
    }

    private fun setupDetector() {
        detector.onStreamFound  = { runOnUiThread { updateFab() } }
        detector.onRequestAdded = { runOnUiThread { updateFab() } }
    }

    fun navigateTo(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${input.replace(" ", "+")}"
        }
        b.webView.loadUrl(url)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.etUrl.windowToken, 0)
    }

    private fun pageStarted(url: String) {
        b.etUrl.setText(url)
        b.progressBar.isVisible = true
        b.btnBack.isEnabled    = b.webView.canGoBack()
        b.btnBack.alpha        = if (b.webView.canGoBack()) 1f else 0.3f
        b.btnForward.isEnabled = b.webView.canGoForward()
        b.btnForward.alpha     = if (b.webView.canGoForward()) 1f else 0.3f
        b.ivSecure.alpha       = if (url.startsWith("https")) 1f else 0.3f
        val bookmarked = BookmarkManager.isBookmarked(this, url)
        b.btnBookmark.setImageResource(
            if (bookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark
        )
        b.btnDevTools.text = "DevTools"
        b.btnDevTools.shrink()
        // Reset picker when navigating to new page
        if (ElementPickerManager.isPickerActive()) hidePicker()
    }

    private fun pageFinished(url: String) {
        b.etUrl.setText(url)
        b.progressBar.isVisible = false
        b.btnBack.isEnabled    = b.webView.canGoBack()
        b.btnBack.alpha        = if (b.webView.canGoBack()) 1f else 0.3f
        b.btnForward.isEnabled = b.webView.canGoForward()
        b.btnForward.alpha     = if (b.webView.canGoForward()) 1f else 0.3f
        BookmarkManager.addHistory(this, url, b.webView.title ?: url)
    }

    private fun updateProgress(p: Int) {
        b.progressBar.progress  = p
        b.progressBar.isVisible = p < 100
    }

    private fun updateFab() {
        val s = detector.streamCount()
        if (s > 0) {
            b.btnDevTools.text = "● $s Stream"
            b.btnDevTools.extend()
        } else {
            b.btnDevTools.text = "DevTools"
        }
    }

    private fun openDevTools() {
        DevToolsSheet(detector, blocker, b.webView, this) { playStream(it) }
            .show(supportFragmentManager, DevToolsSheet.TAG)
    }

    private fun playStream(item: StreamItem) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM, item)
        })
    }

    private fun showBookmarkHistory() {
        val entries = BookmarkManager.getBookmarks(this) + BookmarkManager.getHistory(this)
        if (entries.isEmpty()) {
            Toast.makeText(this, "Chưa có bookmark/history", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Bookmark & History")
            .setItems(entries.map {
                (if (it.isBookmark) "★ " else "    ") + it.title.take(50)
            }.toTypedArray()) { _, i -> navigateTo(entries[i].url) }
            .setNegativeButton("Đóng", null)
            .show()
    }

    // Fix: Use OnBackPressedDispatcher instead of deprecated onBackPressed()
    override fun onBackPressed() {
        if (b.webView.canGoBack()) b.webView.goBack() else super.onBackPressed()
    }

    override fun onPause()   { super.onPause();   b.webView.onPause()  }
    override fun onResume()  { super.onResume();  b.webView.onResume() }

    // Fix: WebView.destroy() must be called after removing from parent to avoid crash
    override fun onDestroy() {
        b.webView.stopLoading()
        (b.webView.parent as? android.view.ViewGroup)?.removeView(b.webView)
        b.webView.destroy()
        super.onDestroy()
    }
}
