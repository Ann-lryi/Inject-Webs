package com.aho.streambrowser.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
    private val blocker by lazy { RequestBlocker(this) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupWebView()
        setupAddressBar()
        setupButtons()
        setupDetector()
        // Modern back press handling for API 33+
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.webView.canGoBack()) b.webView.goBack() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
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
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            userAgentString                  = ua
            setSupportZoom(true)
            builtInZoomControls  = true
            displayZoomControls  = false
            loadWithOverviewMode = true
            useWideViewPort      = true
            allowFileAccessFromFileURLs   = false
            allowUniversalAccessFromFileURLs = false
            allowContentAccess            = false
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
                            getColor(R.color.picker_active))
                    Toast.makeText(this,
                        getString(R.string.picker_tap_hint), Toast.LENGTH_LONG).show()
                },
                onDeactivated = { hidePicker() }
            )
        }
    }

    /** Gọi từ DevToolsSheet khi bật picker */
    fun activatePicker() {
        b.btnPickerFloat.isVisible = true
        ElementPickerManager.activate(
            webView     = b.webView,
            onActivated = {
                b.btnPickerFloat.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        getColor(R.color.picker_active))
                Toast.makeText(this,
                    getString(R.string.picker_tap_hint), Toast.LENGTH_LONG).show()
            },
            onDeactivated = { runOnUiThread { hidePicker() } }
        )
    }

    private fun hidePicker() {
        b.btnPickerFloat.isVisible = false
        b.btnPickerFloat.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                getColor(R.color.picker_active))
    }

    private fun setupDetector() {
        detector.onStreamFound  = { runOnUiThread { updateFab() } }
        detector.onRequestAdded = { runOnUiThread { updateFab() } }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun navigateTo(input: String) {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Không có kết nối mạng", Toast.LENGTH_LONG).show()
            return
        }
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
        // Reset picker khi navigate trang mới
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        b.webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        b.webView.restoreState(savedInstanceState)
    }
    override fun onPause()   { super.onPause();   b.webView.onPause()  }
    override fun onResume()  { super.onResume();  b.webView.onResume() }
    override fun onDestroy() {
        b.webView.apply {
            stopLoading()
            // Fix: Remove JS interface before destroy to prevent crash
            // "Java object was released" WebView crash
            removeJavascriptInterface("SBridge")
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }
}
