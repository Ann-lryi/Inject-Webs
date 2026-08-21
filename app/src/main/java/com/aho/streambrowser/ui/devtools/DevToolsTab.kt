package com.aho.streambrowser.ui.devtools

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * A single DevTools tab. Extracted from the 1800-line DevToolsOverlay so each tab can be
 * built and tested in isolation. Implementations depend only on [DevToolsHost], never on the
 * overlay internals.
 */
interface DevToolsTab {
    fun buildView(): View
}

/**
 * Minimal bridge a tab needs back to the overlay/activity. Named [hostContext] (not `context`)
 * on purpose: the overlay is a View and already has getContext(), so using a different name
 * avoids a JVM-signature clash when the overlay implements this interface.
 */
interface DevToolsHost {
    val hostContext: Context
    val webView: WebView
    fun toast(msg: String)
    fun dp(v: Int): Int
    fun copyText(text: String, msg: String)
    fun hostRefresh()
}
