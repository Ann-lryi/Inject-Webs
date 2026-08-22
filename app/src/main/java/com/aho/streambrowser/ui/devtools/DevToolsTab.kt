package com.aho.streambrowser.ui.devtools

import android.content.Context
import android.view.View
import android.webkit.WebView

interface DevToolsTab { fun buildView(): View }

interface DevToolsHost {
    val hostContext: Context
    val webView: WebView
    fun toast(msg: String)
    fun dp(v: Int): Int
    fun copyText(text: String, msg: String)
    fun hostRefresh()
}
