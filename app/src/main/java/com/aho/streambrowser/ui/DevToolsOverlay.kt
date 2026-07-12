package com.aho.streambrowser.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.*
import android.webkit.WebView
import android.widget.*
import com.aho.streambrowser.detector.ActivityLogEntry
import com.aho.streambrowser.detector.StreamDetector
import com.aho.streambrowser.model.NetworkRequest
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.model.StreamType
import com.aho.streambrowser.util.*
import kotlinx.coroutines.*

/** DevTools Pro overlay panel — slides from right, matches design reference v5 */
@SuppressLint("ViewConstructor")
class DevToolsOverlay(
    context:    Context,
    private val detector:     StreamDetector,
    private val webView:      WebView,
    private val activity:     MainActivity,
    private val onPlayStream: (StreamItem) -> Unit
) : FrameLayout(context) {

    // ── Design tokens ─────────────────────────────────────────────────────────
    private val BG_PANEL   = Color.parseColor("#0D0D0D")
    private val BG_HEADER  = Color.parseColor("#111111")
    private val BG_CARD    = Color.parseColor("#161616")
    private val BG_CARD2   = Color.parseColor("#1A1A1A")
    private val BG_BADGE   = Color.parseColor("#1E1E1E")
    private val ACCENT     = Color.parseColor("#1DB954")
    private val DANGER     = Color.parseColor("#EF4444")
    private val TEXT_PRI   = Color.parseColor("#F0F0F0")
    private val TEXT_SEC   = Color.parseColor("#888888")
    private val TEXT_DIM   = Color.parseColor("#444444")

    // Named type scale — audit found 10 different ad-hoc textSize values in use (7.5f..14f)
    // with no clear meaning attached to any of them. Not retrofitting every existing call site,
    // but new code should reach for these instead of picking another arbitrary number.
    private val SZ_MICRO    = 8f    // timestamps, tertiary metadata
    private val SZ_LABEL    = 9f    // secondary/supporting text — the most common size in the app
    private val SZ_BODY     = 10.5f // primary readable content (URLs, values)
    private val SZ_EMPHASIS = 12f   // section headers
    private val SZ_TITLE    = 14f   // major headers
    private val DIVIDER    = Color.parseColor("#222222")

    // Type badge colors
    private val C_HLS  = Color.parseColor("#1DB954")
    private val C_DASH = Color.parseColor("#8B5CF6")
    private val C_MP4  = Color.parseColor("#3B82F6")
    private val C_M3U9 = Color.parseColor("#F59E0B")
    private val C_XHR  = Color.parseColor("#EC4899")
    private val C_JS   = Color.parseColor("#EAB308")
    private val C_CSS  = Color.parseColor("#06B6D4")
    private val C_IMG  = Color.parseColor("#6366F1")
    private val C_DOC  = Color.parseColor("#64748B")

    // Crypto-card backgrounds (CryptoJS = amber-tinted, SubtleCrypto = blue-tinted)
    private val C_WARN_BG = Color.parseColor("#2A1F0D")
    private val C_BLUE_BG = Color.parseColor("#0F1A2A")

    // Network tab "Timing" legend — decorative only: NetworkRequest has a single
    // timestamp, not per-phase DNS/Connect/TTFB/Download data, so the waterfall bar
    // stays a single colored segment. Flagging this so nobody assumes real phase data.
    private val C_TIMING_DNS      = Color.parseColor("#3B82F6")
    private val C_TIMING_CONNECT  = Color.parseColor("#F59E0B")
    private val C_TIMING_TTFB     = Color.parseColor("#1DB954")
    private val C_TIMING_DOWNLOAD = Color.parseColor("#8B5CF6")

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val dp get() = context.resources.displayMetrics.density

    // FIX (real crash root cause): TABS used to be declared further down the file,
    // AFTER the `init` block below. Kotlin runs property initializers in textual
    // order — init{} called setupOverlay() -> buildTabStrip() -> TABS.forEachIndexed{}
    // while TABS itself hadn't been assigned yet, so it was still null at that point
    // => "Attempt to invoke interface method ... iterator() on a null object
    // reference" on EVERY DevTools open. Moving it above `init` fixes this for good.
    private val TABS = listOf("Network","Streams","Console","Crypto","WS","Headers","Storage","CSS","Timeline","Proxy","Cookies","Plugin")

    private lateinit var panelView: LinearLayout
    private lateinit var tabStrip: LinearLayout
    private lateinit var contentArea: FrameLayout
    private lateinit var tvLive: TextView
    private lateinit var tvCounts: TextView
    private var currentTab = 0
    private var networkFilter = "All"
    private var streamGroupByType = false
    private var snapshotUrls: Set<String> = emptySet()
    private val consoleHistory = mutableListOf<String>()
    private var consoleLevelFilter = "All"
    // Bottom-sheet drag state
    private var dragStartRawY   = 0f
    private var dragStartTransY = 0f
    private val defaultTopFraction = 0.36f   // panel top at 36% → 64% visible

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setupOverlay()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────
    private fun setupOverlay() {
        // Backdrop — full screen dim layer, tap anywhere above panel to dismiss
        val backdrop = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#88000000"))
            setOnClickListener { hide() }
        }
        addView(backdrop)

        // Bottom-sheet panel — full width, anchored at bottom
        // translationY=0 means visible at defaultTopFraction from top.
        // translationY=panelVisibleH means fully off-screen below.
        panelView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // FIX (touch leak): panel had no isClickable, so a tap on any dead zone
            // inside it (padding, unlabeled text, gaps between rows) fell through to
            // the full-screen `backdrop` sibling below and silently triggered hide().
            // The NEXT tap then landed straight on the WebView. Making the panel
            // itself clickable absorbs those dead-zone taps instead of leaking them.
            // Does not affect any child listeners — Android always tries children first.
            isClickable = true
            isFocusable = true
            setBackgroundColor(BG_PANEL)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                topMargin = (screenHeight * defaultTopFraction).toInt()
            }
            // Start off screen below (will animate up on show())
            translationY = (screenHeight * (1f - defaultTopFraction))
        }

        // Drag handle — tap/drag to resize or close
        panelView.addView(buildDragHandle())

        // Header
        panelView.addView(buildHeader())

        // Tab strip
        tabStrip = buildTabStrip()
        panelView.addView(tabStrip)

        // Divider
        panelView.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(DIVIDER)
        })

        // Content
        contentArea = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(BG_PANEL)
        }
        panelView.addView(contentArea)
        addView(panelView)

        showTab(0)
    }

    // ── Drag handle ───────────────────────────────────────────────────────────
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun buildDragHandle(): View {
        val handle = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(BG_HEADER)
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30))
        }
        // The visible pill
        handle.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(4)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
            }
            background = roundRect(Color.parseColor("#555555"), 2f)
        })
        handle.setOnTouchListener { _, event ->
            val panelH = screenHeight * (1f - defaultTopFraction)
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragStartRawY   = event.rawY
                    dragStartTransY = panelView.translationY
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawY - dragStartRawY
                    // Allow expanding up (negative translationY) to ~95% screen,
                    // and shrinking down to just before dismiss threshold.
                    panelView.translationY = (dragStartTransY + delta)
                        .coerceIn(-panelH * 0.55f, panelH * 0.85f)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    when {
                        panelView.translationY > panelH * 0.40f -> {
                            // Dragged too far down → dismiss
                            hide()
                        }
                        panelView.translationY < -panelH * 0.30f -> {
                            // Dragged far enough up → snap to an expanded height (was a dead gesture before)
                            ObjectAnimator.ofFloat(panelView, "translationY", panelView.translationY, -panelH * 0.42f)
                                .apply {
                                    duration = 200
                                    interpolator = android.view.animation.DecelerateInterpolator()
                                    start()
                                }
                        }
                        else -> {
                            // Snap back to resting position (translationY = 0)
                            ObjectAnimator.ofFloat(panelView, "translationY", panelView.translationY, 0f)
                                .apply {
                                    duration = 200
                                    interpolator = android.view.animation.DecelerateInterpolator()
                                    start()
                                }
                        }
                    }
                }
            }
            true
        }
        return handle
    }

    private val screenWidth  get() = context.resources.displayMetrics.widthPixels
    private val screenHeight get() = context.resources.displayMetrics.heightPixels

    // ── Header ────────────────────────────────────────────────────────────────
    private fun buildHeader(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG_HEADER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        // Logo
        val logo = TextView(context).apply {
            text = "DevTools Pro"
            textSize = 14f; setTextColor(TEXT_PRI)
            typeface = Typeface.DEFAULT_BOLD
        }
        // Version badge
        val verBadge = TextView(context).apply {
            text = "v5"; textSize = 9f; setTextColor(TEXT_SEC)
            setPadding(dp(5), dp(1), dp(5), dp(1))
            background = roundRect(BG_BADGE, 4f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
        }
        // Live badge
        tvLive = TextView(context).apply {
            text = "● LIVE"
            textSize = 9f; setTextColor(ACCENT)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = roundRect(Color.parseColor("#0D2E1A"), 4f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
        }
        // Spacer
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        // Counts
        tvCounts = TextView(context).apply {
            text = "Str: ${detector.streamCount()} | Req: ${detector.requestCount()}"
            textSize = 9f; setTextColor(TEXT_SEC)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        }
        // Hide button
        val btnHide = TextView(context).apply {
            text = "✕ Hide"
            textSize = 11f; setTextColor(TEXT_SEC)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundRect(BG_BADGE, 4f)
            setOnClickListener { hide() }
        }
        // Clear button
        val btnClear = TextView(context).apply {
            text = "🗑"
            textSize = 13f; setTextColor(TEXT_SEC)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { detector.clear(); refresh() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) }
        }
        listOf(logo, verBadge, tvLive, spacer, tvCounts, btnClear, btnHide).forEach { row.addView(it) }
        return row
    }

    // ── Tab strip ─────────────────────────────────────────────────────────────
    private fun buildTabStrip(): LinearLayout {
        val scroll = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG_HEADER)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        TABS.forEachIndexed { idx, name ->
            strip.addView(buildTab(idx, name))
        }
        scroll.addView(strip)
        // Wrap in LinearLayout to return correct type
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(scroll)
        }
    }

    private fun buildTab(idx: Int, name: String): View {
        val count = when (name) {
            "Network" -> detector.requestCount()
            "Streams" -> detector.streamCount()
            "Crypto"  -> detector.cryptoCount()
            "WS"      -> detector.wsCount()
            "Cookies" -> CookieExporter.parse(CookieExporter.getRaw(webView.url ?: "")).size
            else -> -1
        }
        val label = if (count > 0) "$name ($count)" else name
        return TextView(context).apply {
            text = label; textSize = 11f
            setTextColor(if (idx == currentTab) ACCENT else TEXT_SEC)
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = if (idx == currentTab) bottomBorder(ACCENT) else null
            setOnClickListener { currentTab = idx; showTab(idx) }
        }
    }

    private fun refreshTabStrip() {
        // Rebuild with new counts
        val strip = tabStrip.getChildAt(0)
        if (strip is HorizontalScrollView) {
            val inner = strip.getChildAt(0) as? LinearLayout ?: return
            inner.removeAllViews()
            TABS.forEachIndexed { idx, name -> inner.addView(buildTab(idx, name)) }
        }
    }

    fun refresh() {
        tvCounts.text = "Str: ${detector.streamCount()} | Req: ${detector.requestCount()}"
        refreshTabStrip()
        showTab(currentTab)
    }

    // ── Show tab ──────────────────────────────────────────────────────────────
    private fun showTab(idx: Int) {
        currentTab = idx
        refreshTabStrip()
        contentArea.animate().cancel()
        contentArea.alpha = 0f
        contentArea.removeAllViews()
        when (idx) {
            0  -> contentArea.addView(buildNetworkTab())
            1  -> contentArea.addView(buildStreamsTab())
            2  -> contentArea.addView(buildConsoleTab())
            3  -> contentArea.addView(buildCryptoTab())
            4  -> contentArea.addView(buildWsTab())
            5  -> contentArea.addView(buildHeadersTab())
            6  -> contentArea.addView(buildStorageTab())
            7  -> contentArea.addView(buildCssTab())
            8  -> contentArea.addView(buildTimelineTab())
            9  -> contentArea.addView(buildProxyTab())
            10 -> contentArea.addView(buildCookieTab())
            11 -> contentArea.addView(buildPluginTab())
        }
        contentArea.animate().alpha(1f).setDuration(120).start()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB 0: Network — filter chips, timing legend, method/url/status/type/size/waterfall
    // ═══════════════════════════════════════════════════════════════════════════
    private fun buildNetworkTab(): View {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        // Filter strip
        val filters = listOf("All","Streams","Xhr","Js","Css","Redirects")
        val filterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG_HEADER)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        filters.forEach { f ->
            val btn = TextView(context).apply {
                text = f; textSize = 10f
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = roundRect(if (f == networkFilter) ACCENT else BG_BADGE, 10f)
                setTextColor(if (f == networkFilter) Color.BLACK else TEXT_SEC)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) }
                setOnClickListener { networkFilter = f; showTab(0) }
            }
            filterRow.addView(btn)
        }
        // HAR + Snap + Diff buttons
        val actRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END; setPadding(0, 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        listOf("📸" to "Snap", "🔍" to "Diff", "📥" to "HAR").forEach { (icon, label) ->
            actRow.addView(TextView(context).apply {
                text = "$icon$label"; textSize = 9f; setTextColor(TEXT_SEC)
                setPadding(dp(6), dp(3), dp(6), dp(3))
                background = roundRect(BG_BADGE, 4f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) }
                setOnClickListener { when(label) {
                    "Snap" -> { snapshotUrls = detector.requests.map{it.url}.toSet()
                        toast("Snapshot: ${snapshotUrls.size}") }
                    "Diff" -> showDiff()
                    "HAR"  -> { scope.launch { val h = HarExporter.export(detector.requests)
                        post { activity.copyToClipboard(h,"HAR copied (${h.length} chars)") }}}
                }}
            })
        }
        filterRow.addView(actRow)
        outer.addView(filterRow)

        // Timing legend — decorative reference only, see comment on the C_TIMING_* tokens
        val legend = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F0F"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        legend.addView(TextView(context).apply {
            text = "Timing:"; textSize = 8.5f; setTextColor(TEXT_DIM)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        })
        listOf("DNS" to C_TIMING_DNS, "Connect" to C_TIMING_CONNECT, "TTFB" to C_TIMING_TTFB, "Download" to C_TIMING_DOWNLOAD)
            .forEach { (label, color) -> legend.addView(legendDot(label, color)) }
        outer.addView(legend)

        // Column headers
        outer.addView(buildNetworkHeader())

        // Request list
        val reqs = detector.requests.filter { req ->
            when (networkFilter) {
                "Streams"   -> req.isStream
                "Xhr"       -> req.tag == "XHR"
                "Js"        -> req.tag == "JS"
                "Css"       -> req.tag == "CSS"
                "Redirects" -> req.statusCode in 300..399
                else        -> true
            }
        }
        val sv = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val t0 = reqs.minOfOrNull { it.timestamp } ?: System.currentTimeMillis()
        val tRange = ((reqs.maxOfOrNull { it.timestamp } ?: t0) - t0).coerceAtLeast(1L)

        reqs.take(300).forEach { req ->
            inner.addView(buildNetworkRow(req, t0, tRange))
        }
        if (reqs.isEmpty()) inner.addView(emptyState("Chưa có requests${if (networkFilter != "All") " ($networkFilter)" else ""}."))
        sv.addView(inner); outer.addView(sv)
        return outer
    }

    private fun legendDot(label: String, color: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
        addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(3) }
            background = roundRect(color, 3f)
        })
        addView(TextView(context).apply { text = label; textSize = 8.5f; setTextColor(TEXT_SEC) })
    }

    private fun buildNetworkHeader(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Color.parseColor("#0F0F0F"))
        setPadding(dp(8), dp(4), dp(8), dp(4))
        listOf(
            "" to 16f, "Method" to 38f, "URL" to 0f, "Status" to 32f, "Type" to 38f, "Size" to 38f, "Waterfall" to 56f
        ).forEach { (label, w) ->
            addView(TextView(context).apply {
                text = label; textSize = 8.5f; setTextColor(TEXT_DIM)
                layoutParams = if (w == 0f)
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                else
                    LinearLayout.LayoutParams(dp(w.toInt()), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
    }

    /** Small category glyph + color shown before METHOD — closest native equivalent
     *  to the icon set in the reference design (no icon font is bundled in this project). */
    private fun rowIcon(req: NetworkRequest): Pair<String, Int> = when {
        req.statusCode in 300..399 -> "↻" to C_M3U9
        req.isStream                -> "▶" to typeColor(req.tag)
        else                        -> "●" to typeColor(req.tag)
    }

    private fun buildNetworkRow(req: NetworkRequest, t0: Long, tRange: Long): View {
        val typeCol = typeColor(req.tag)
        val (icon, iconColor) = rowIcon(req)
        val row = LinearLayout(context).apply {
            orientation  = LinearLayout.HORIZONTAL
            background = rippleRect(BG_CARD2, 0f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 1 }
            setOnClickListener { showRequestDetail(req) }
            // Long-press: instant copy URL, skips the detail dialog. Short-press unchanged.
            setOnLongClickListener { activity.copyToClipboard(req.url, "URL copied"); true }
        }
        // 3dp colored left strip — native stand-in for the design's colored row border
        row.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(typeCol)
        })
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Category icon
        content.addView(TextView(context).apply {
            text = icon; textSize = 11f; setTextColor(iconColor)
            layoutParams = LinearLayout.LayoutParams(dp(16), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        })
        // Method
        content.addView(TextView(context).apply {
            text = req.method; textSize = 9.5f; setTextColor(TEXT_SEC); maxLines = 1
            layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        // Host + path
        val hostCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4), 0, dp(4), 0)
        }
        hostCol.addView(TextView(context).apply {
            text = req.host.take(24); textSize = 10.5f; setTextColor(TEXT_PRI)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        hostCol.addView(TextView(context).apply {
            text = req.path.take(30); textSize = 9f; setTextColor(TEXT_SEC)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        content.addView(hostCol)
        // Status
        val statusColor = when {
            req.statusCode in 200..299 -> ACCENT
            req.statusCode in 300..399 -> C_M3U9
            req.statusCode >= 400      -> DANGER
            else -> TEXT_DIM
        }
        content.addView(TextView(context).apply {
            text = if (req.statusCode > 0) req.statusCode.toString() else "…"
            textSize = 9.5f; setTextColor(statusColor); maxLines = 1
            layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        })
        // Type badge
        content.addView(TextView(context).apply {
            text = req.tag; textSize = 8.5f; setTextColor(Color.BLACK); maxLines = 1
            setBackgroundColor(typeCol)
            setPadding(dp(3), dp(1), dp(3), dp(1))
            layoutParams = LinearLayout.LayoutParams(dp(38), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        })
        // Size
        val sizeStr = when {
            req.contentLength > 0                  -> formatBytes(req.contentLength)
            req.responseBodyPreview.isEmpty()       -> "—"
            req.responseBodyPreview.length < 1024   -> "${req.responseBodyPreview.length}B"
            else -> "${req.responseBodyPreview.length/1024}K"
        }
        content.addView(TextView(context).apply {
            text = sizeStr; textSize = 9f; setTextColor(TEXT_SEC); maxLines = 1
            layoutParams = LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.END
        })
        // Waterfall bar
        val rel    = ((req.timestamp - t0).toFloat() / tRange).coerceIn(0f, 0.9f)
        val barW   = (0.08f + rel * 0.02f).coerceAtMost(0.15f)
        content.addView(buildWaterfallBar(rel, barW, typeCol))
        row.addView(content)
        return row
    }

    private fun formatBytes(b: Long): String = when {
        b >= 1_000_000 -> String.format("%.1f MB", b / 1_000_000.0)
        b >= 1_000     -> String.format("%.1f KB", b / 1_000.0)
        else           -> "$b B"
    }

    private fun buildWaterfallBar(offset: Float, width: Float, color: Int): View {
        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(12))
        }
        val spacer = View(context).apply {
            layoutParams = FrameLayout.LayoutParams((dp(60) * offset).toInt(),
                FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val bar = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ((dp(60) * width).toInt()).coerceAtLeast(dp(3)), FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.NO_GRAVITY
            ).apply { leftMargin = (dp(60) * offset).toInt() }
            setBackgroundColor(Color.argb(200, Color.red(color), Color.green(color), Color.blue(color)))
        }
        frame.addView(spacer); frame.addView(bar)
        return frame
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB 1: Streams — grouped by quality (or type), codec info
    // ═══════════════════════════════════════════════════════════════════════════
    private fun buildStreamsTab(): View {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val streams = detector.streams
        // Header counts + actions
        val hdr = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG_HEADER)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        hdr.addView(TextView(context).apply {
            text = "${streams.size} Streams Detected"
            textSize = 12f; setTextColor(TEXT_PRI); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        listOf("By Quality" to false, "By Type" to true).forEach { (label, isType) ->
            hdr.addView(TextView(context).apply {
                text = label; textSize = 9f
                setTextColor(if (streamGroupByType == isType) Color.BLACK else TEXT_SEC)
                setPadding(dp(7), dp(3), dp(7), dp(3))
                background = roundRect(if (streamGroupByType == isType) ACCENT else BG_BADGE, 4f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) }
                setOnClickListener { streamGroupByType = isType; showTab(1) }
            })
        }
        hdr.addView(TextView(context).apply {
            text = "Export All"; textSize = 9f; setTextColor(TEXT_SEC)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = roundRect(BG_BADGE, 4f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) }
            setOnClickListener { exportAllStreams(streams) }
        })
        outer.addView(hdr)

        if (streams.isEmpty()) { outer.addView(emptyState("Chưa có streams. Mở trang video.")); return outer }

        val sv = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        if (streamGroupByType) {
            val groups = linkedMapOf<String, MutableList<StreamItem>>()
            streams.forEach { s -> groups.getOrPut(s.label) { mutableListOf() }.add(s) }
            groups.forEach { (label, list) ->
                inner.addView(groupHeader(label, list.size, streamTypeColor(list.first().type)))
                list.forEach { stream -> inner.addView(buildStreamCard(stream, stream.qualityLabel.ifEmpty { "Auto" })) }
            }
        } else {
            // Group by quality
            val groups = linkedMapOf(
                "1080p" to mutableListOf<StreamItem>(),
                "720p"  to mutableListOf(),
                "480p"  to mutableListOf(),
                "360p"  to mutableListOf(),
                "Auto"  to mutableListOf()
            )
            streams.forEach { s ->
                val q = when {
                    s.url.contains("1080") || s.url.contains("fhd") -> "1080p"
                    s.url.contains("720")  || s.url.contains("hd")  -> "720p"
                    s.url.contains("480")                            -> "480p"
                    s.url.contains("360")                            -> "360p"
                    else                                              -> "Auto"
                }
                groups[q]?.add(s)
            }
            val dotColors = mapOf("1080p" to ACCENT, "720p" to C_MP4, "480p" to TEXT_SEC, "360p" to TEXT_DIM, "Auto" to C_DASH)
            groups.forEach { (quality, list) ->
                if (list.isEmpty()) return@forEach
                inner.addView(groupHeader(quality, list.size, dotColors[quality] ?: TEXT_SEC))
                list.forEach { stream -> inner.addView(buildStreamCard(stream, quality)) }
            }
        }
        sv.addView(inner); outer.addView(sv)
        return outer
    }

    private fun groupHeader(title: String, count: Int, dotColor: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity     = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(6))
        addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) }
            background = roundRect(dotColor, 4f)
        })
        addView(TextView(context).apply {
            text = title; textSize = 11f; setTextColor(TEXT_PRI)
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(context).apply {
            text = "($count)"; textSize = 10f; setTextColor(TEXT_SEC)
            setPadding(dp(4), 0, 0, 0)
        })
        addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) }
            setBackgroundColor(DIVIDER)
        })
    }

    private fun buildStreamCard(stream: StreamItem, quality: String): View {
        val typeColor = streamTypeColor(stream.type)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(BG_CARD, 6f)
            elevation = dp(2).toFloat()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6); marginStart = dp(8); marginEnd = dp(8)
            }
        }
        // Type + quality + codec badges row
        val badges = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        badges.addView(typeBadge(stream.type.name, typeColor))
        badges.addView(TextView(context).apply {
            text = quality; textSize = 9f; setTextColor(ACCENT)
            setPadding(dp(5), dp(1), dp(5), dp(1))
            background = roundRect(Color.parseColor("#0D2E1A"), 3f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) }
        })
        stream.codec?.let { codec ->
            badges.addView(TextView(context).apply {
                text = codec; textSize = 9f; setTextColor(TEXT_SEC)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = roundRect(BG_BADGE, 3f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) }
            })
        }
        // Source badge
        badges.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0,1,1f) })
        badges.addView(TextView(context).apply {
            text = "via ${stream.source}".take(20); textSize = 8.5f; setTextColor(TEXT_DIM)
            setPadding(dp(4), dp(1), dp(4), dp(1))
            background = roundRect(BG_BADGE, 3f)
        })
        card.addView(badges)

        // URL
        card.addView(TextView(context).apply {
            text = stream.url; textSize = 9.5f; setTextColor(TEXT_SEC)
            isSelected = true; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.MONOSPACE
            isSingleLine = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        })

        // Age indicator
        val ageMs = detector.getStreamAge(stream.url)
        if (ageMs > 0) {
            val ageSec = ageMs / 1000
            val ageColor = when { ageSec < 60 -> ACCENT; ageSec < 300 -> C_JS; else -> DANGER }
            card.addView(TextView(context).apply {
                text = if (ageSec < 60) "⏱ ${ageSec}s ago" else if (ageSec < 3600) "⏱ ${ageSec/60}min ago" else "⚠ ${ageSec/3600}h ago — may expire"
                textSize = 8.5f; setTextColor(ageColor)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
            })
        }

        // Action buttons: Copy | Play | Plugin | Share
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.START
        }
        listOf(
            "Copy"   to ACCENT,
            "▶ Play" to C_CSS,
            "Plugin" to C_DASH,
            "Share"  to C_IMG
        ).forEach { (label, color) ->
            btnRow.addView(TextView(context).apply {
                text = label; textSize = 10f; setTextColor(Color.BLACK)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = roundRect(color, 4f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) }
                setOnClickListener {
                    when (label) {
                        "Copy"   -> { activity.copyToClipboard(stream.url, "Stream URL copied") }
                        "▶ Play" -> onPlayStream(stream)
                        "Plugin" -> activity.copyToClipboard(buildPluginSnippet(stream), "Plugin code copied")
                        "Share"  -> {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, stream.url)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share stream"))
                        }
                    }
                }
            })
        }
        card.addView(btnRow)
        return card
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB 5: Headers — request + response headers for selected request + JWT decoder
    // ═══════════════════════════════════════════════════════════════════════════
    private fun buildHeadersTab(): View {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val reqs    = detector.requests.filter { it.headers.isNotEmpty() || it.responseHeaders.isNotEmpty() }
        val sv      = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner   = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }

        // Show headers for streams first, then API calls
        val interestingReqs = (reqs.filter { it.isStream } + reqs.filter { it.tag == "XHR" }).take(3)

        if (interestingReqs.isEmpty() && reqs.isEmpty()) {
            inner.addView(emptyState("Chưa có request headers. Mở trang video."))
        } else {
            (interestingReqs.ifEmpty { reqs.take(3) }).forEach { req ->
                inner.addView(buildHeadersCard(req))
                inner.addView(vDivider())
            }
        }

        // JWT Decoder
        inner.addView(buildSectionHeader("# JWT Decoder"))
        val (jwtWrap, etJwt) = buildLabeledInput("JWT token", "eyJhbGc...")
        etJwt.apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        inner.addView(jwtWrap)
        inner.addView(TextView(context).apply {
            text = "Paste any JWT above to decode header + payload"
            textSize = 9f; setTextColor(TEXT_DIM)
            setPadding(0, dp(2), 0, dp(6))
        })
        val tvJwtResult = buildMonoTv("", TEXT_SEC, 9.5f)
        val decodeBtn = buildActionBtn("🔍 Decode", ACCENT) {
            val token = etJwt.text.toString().trim()
            val info  = JwtDecoder.decode(token)
            tvJwtResult.text = if (info != null) {
                "Header:\n${info.header}\n\nPayload:\n${info.payload}\n\nExp: ${info.expTime} ${if (info.isExpired) "⚠ EXPIRED" else "✓"}"
            } else "Invalid JWT"
        }
        etJwt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { decodeBtn.performClick(); true } else false
        }
        inner.addView(decodeBtn)
        inner.addView(tvJwtResult)
        sv.addView(inner); outer.addView(sv)
        return outer
    }

    private fun buildHeadersCard(req: NetworkRequest): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(BG_CARD, 6f)
            elevation = dp(2).toFloat()
            setPadding(dp(10), dp(8), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        card.addView(buildMonoTv(req.url.take(80), typeColor(req.tag), 9.5f).apply { setTextIsSelectable(true) })
        if (req.headers.isNotEmpty()) {
            card.addView(buildSectionHeader("REQUEST HEADERS"))
            req.headers.entries.take(15).forEach { (k, v) ->
                card.addView(buildMonoTv("$k: ${v.take(120)}", TEXT_SEC, 9f))
            }
        }
        if (req.responseHeaders.isNotEmpty()) {
            card.addView(buildSectionHeader("RESPONSE HEADERS · ${req.statusCode}"))
            req.responseHeaders.entries.take(10).forEach { (k, v) ->
                card.addView(buildMonoTv("$k: ${v.take(120)}", TEXT_SEC, 9f))
            }
        }
        card.addView(buildActionBtn("📋 Copy headers", ACCENT) {
            val full = buildString {
                appendLine(req.url)
                if (req.headers.isNotEmpty()) { appendLine("\nREQUEST HEADERS"); req.headers.forEach { (k, v) -> appendLine("$k: $v") } }
                if (req.responseHeaders.isNotEmpty()) { appendLine("\nRESPONSE HEADERS · ${req.statusCode}"); req.responseHeaders.forEach { (k, v) -> appendLine("$k: $v") } }
            }
            activity.copyToClipboard(full, "Headers copied")
        })
        return card
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Other tabs — delegate, same pattern as before
    // ═══════════════════════════════════════════════════════════════════════════
    private fun buildConsoleTab()  = buildConsoleView()
    private fun buildCryptoTab()   = buildCryptoView()
    private fun buildWsTab()       = buildWsView()
    private fun buildStorageTab()  = buildStorageView()
    private fun buildCssTab()      = buildCssView()
    private fun buildTimelineTab() = buildTimelineView()
    private fun buildProxyTab()    = buildProxyView()

    // ── Cookies tab ──────────────────────────────────────────────────────────
    private fun buildCookieTab(): View {
        val sv = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        val url = webView.url ?: ""
        val raw = CookieExporter.getRaw(url)

        inner.addView(buildSectionHeader("Cookies — ${url.take(50)}"))
        if (raw.isBlank()) {
            inner.addView(buildMonoTv("Không có cookie cho URL này.", TEXT_DIM, 10f))
        } else {
            val parsed = CookieExporter.parse(raw)
            parsed.forEach { (k, v) ->
                val isJwt = JwtDecoder.isJwt(v)
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(BG_CARD)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(3) }
                }
                // key/value shown separately with clear labeling; JWT-looking values get an inline decode
                // action right where they are, instead of the user needing to guess and hunt for a decoder.
                row.addView(buildMonoTv(k, if (isJwt) Color.parseColor("#FFD54F") else ACCENT, 10.5f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(buildMonoTv(v.take(40) + if (v.length > 40) "…" else "", TEXT_PRI, 9.5f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                    setTextIsSelectable(true)
                })
                inner.addView(row)
                if (isJwt) {
                    inner.addView(buildActionBtn("🔍 Decode JWT: $k", ACCENT) {
                        val info = JwtDecoder.decode(v)
                        if (info != null) showJwtInfoDialog(info) else toast("Không parse được JWT")
                    })
                }
            }
        }

        inner.addView(vDivider())
        inner.addView(buildSectionHeader("Export"))
        val exports = listOf(
            "Cookie raw"  to CookieExporter.toDocument(url),
            "curl -b"     to CookieExporter.toCurlFlag(url),
            "Kotlin Map"  to CookieExporter.toKotlinMap(url),
            "Header line" to CookieExporter.toHeaderLine(url)
        )
        exports.forEach { (label, value) ->
            inner.addView(buildActionBtn("📋 Copy $label", BG_BADGE) {
                if (value.isBlank()) toast("Không có dữ liệu để copy") else activity.copyToClipboard(value, "$label copied")
            })
        }
        sv.addView(inner); return sv
    }

    private fun showJwtInfoDialog(info: JwtDecoder.JwtInfo) {
        val d = android.app.AlertDialog.Builder(context)
        val sv = ScrollView(context)
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)); setBackgroundColor(BG_PANEL) }
        inner.addView(buildSectionHeader("Header"))
        inner.addView(buildMonoTv(info.header, TEXT_PRI, 9.5f).apply { setTextIsSelectable(true) })
        inner.addView(buildSectionHeader("Payload"))
        inner.addView(buildMonoTv(info.payload, TEXT_PRI, 9.5f).apply { setTextIsSelectable(true) })
        val expColor = if (info.isExpired) DANGER else ACCENT
        inner.addView(buildMonoTv("Exp: ${info.expTime}  ${if (info.isExpired) "⚠ EXPIRED" else "✓ valid"}", expColor, 9.5f))
        sv.addView(inner)
        d.setView(sv).setPositiveButton("Copy Payload") { _, _ -> activity.copyToClipboard(info.payload, "Payload copied") }
            .setNegativeButton("Đóng", null).show()
    }

    // ── Plugin Generator tab ────────────────────────────────────────────────────
    private fun buildPluginTab(): View {
        val sv = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        val url = webView.url ?: ""
        val site = runCatching { val u = java.net.URL(url); "${u.protocol}://${u.host}" }.getOrElse { url }

        inner.addView(buildSectionHeader("☁ CloudStream3 Plugin Generator"))
        inner.addView(buildMonoTv("Site: $site", TEXT_DIM, 10f))

        val (nameWrap, etName) = buildLabeledInput("Plugin name", "vd: HentaiZ")
        etName.apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(site.substringAfterLast("/").substringAfterLast(".").replaceFirstChar { it.uppercaseChar() })
        }
        inner.addView(nameWrap)

        // Each generate action writes to its OWN labeled, persistent block below — running
        // "Skeleton" after "Analyze" doesn't erase the analysis result, and it's always
        // visible which block is which and whether it has actually been run yet.
        inner.addView(buildSectionHeader("Kết quả: Phân tích URL Pattern"))
        val tvAnalysis = buildMonoTv("(chưa chạy — bấm nút bên dưới)", TEXT_DIM, 9f).apply { setTextIsSelectable(true) }
        inner.addView(tvAnalysis)
        inner.addView(buildActionBtn("🔍 Analyze URL Patterns", ACCENT) {
            tvAnalysis.text = PluginGenerator.analyzePatterns(detector.requests).ifBlank { "Không tìm thấy request nào giống API call." }
        })
        inner.addView(buildActionBtn("📋 Copy phân tích", BG_BADGE) {
            if (tvAnalysis.text.startsWith("(chưa")) toast("Chưa chạy — bấm Analyze trước") else activity.copyToClipboard(tvAnalysis.text.toString(), "Phân tích copied")
        })

        inner.addView(vDivider())
        inner.addView(buildSectionHeader("Kết quả: Extractor Code"))
        val tvExtractor = buildMonoTv("(chưa chạy — bấm nút bên dưới)", TEXT_DIM, 9f).apply { setTextIsSelectable(true) }
        inner.addView(tvExtractor)
        inner.addView(buildActionBtn("⚙ Generate Extractor Code", ACCENT) {
            val session = PluginGenerator.SessionData(
                streams = detector.streams, requests = detector.requests,
                siteUrl = site, cookies = CookieExporter.toDocument(url), referer = url
            )
            tvExtractor.text = PluginGenerator.generateExtractorCode(session)
        })
        inner.addView(buildActionBtn("📋 Copy Extractor Code", BG_BADGE) {
            if (tvExtractor.text.startsWith("(chưa")) toast("Chưa chạy — bấm Generate trước") else activity.copyToClipboard(tvExtractor.text.toString(), "Extractor code copied")
        })

        inner.addView(vDivider())
        inner.addView(buildSectionHeader("Kết quả: Plugin Skeleton"))
        val tvSkeleton = buildMonoTv("(chưa chạy — bấm nút bên dưới)", TEXT_DIM, 9f).apply { setTextIsSelectable(true) }
        inner.addView(tvSkeleton)
        inner.addView(buildActionBtn("📋 Generate Plugin Skeleton", ACCENT) {
            val name = etName.text.toString().ifBlank { "MyPlugin" }
            tvSkeleton.text = PluginGenerator.generateSkeleton(name, site)
        })
        inner.addView(buildActionBtn("📋 Copy Skeleton", BG_BADGE) {
            if (tvSkeleton.text.startsWith("(chưa")) toast("Chưa chạy — bấm Generate trước") else activity.copyToClipboard(tvSkeleton.text.toString(), "Plugin skeleton copied")
        })

        inner.addView(vDivider())
        inner.addView(buildSectionHeader("Session Summary"))
        val cookieCount = CookieExporter.parse(CookieExporter.toDocument(url)).size
        inner.addView(buildMonoTv(buildString {
            appendLine("Streams captured:    ${detector.streamCount()}")
            appendLine("Requests captured:   ${detector.requestCount()}")
            appendLine("WebSocket messages:  ${detector.wsCount()}")
            appendLine("Crypto keys:         ${detector.cryptoCount()}")
            append("Cookies:              ${if (cookieCount == 0) "none" else "$cookieCount entries"}")
        }, TEXT_SEC, 9.5f))

        sv.addView(inner); return sv
    }

    // ── TAB 2: Console — filter chips + real activity log + JS REPL ────────────
    private fun buildConsoleView(): View {
        val outer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT) }

        // ── Filter chips: All | Info | Success | Warn | Error ────────────────
        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG_HEADER)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        listOf("All" to TEXT_SEC, "Info" to TEXT_SEC, "Success" to ACCENT,
               "Warn" to C_M3U9, "Error" to DANGER).forEach { (level, col) ->
            val isActive = level == consoleLevelFilter
            chipRow.addView(TextView(context).apply {
                text = level; textSize = 10f
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = roundRect(if (isActive) col else BG_BADGE, 10f)
                setTextColor(if (isActive) Color.WHITE else TEXT_SEC)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) }
                setOnClickListener { consoleLevelFilter = level; showTab(2) }
            })
        }
        outer.addView(chipRow)

        // ── Activity log ─────────────────────────────────────────────────────
        val sv = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val logCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        val filteredLog = detector.activityLog.filter { e ->
            consoleLevelFilter == "All" || e.level.equals(consoleLevelFilter, ignoreCase = true)
        }
        if (filteredLog.isEmpty()) {
            logCol.addView(emptyState(if (consoleLevelFilter == "All")
                "Chưa có activity. Mở trang video để xem log." else "Không có mục ${consoleLevelFilter}."))
        } else {
            filteredLog.asReversed().forEach { entry -> logCol.addView(buildLogLine(entry)) }
        }
        sv.addView(logCol); outer.addView(sv)
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }

        // ── JS REPL ──────────────────────────────────────────────────────────
        outer.addView(vDivider())
        val replLog = StringBuilder(detector.consoleLog)
        val replScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(90))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val replTv = buildMonoTv(replLog.toString(), TEXT_SEC, 9f).apply { setPadding(dp(10), dp(4), dp(10), dp(4)); setTextIsSelectable(true) }
        replScroll.addView(replTv); outer.addView(replScroll)
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG_HEADER)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val et = buildEditText("JavaScript...").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            typeface = Typeface.MONOSPACE
        }
        val runBtn = buildActionBtn("▶ Run", ACCENT) {
            val code = et.text.toString()
            if (code.isBlank()) return@buildActionBtn
            consoleHistory.remove(code); consoleHistory.add(0, code); if (consoleHistory.size > 50) consoleHistory.removeLast()
            replLog.append("\n▶ $code\n")
            webView.evaluateJavascript(code) { res ->
                replLog.append(res?.removeSurrounding("\"") ?: "null")
                post { replTv.text = replLog; replScroll.post { replScroll.fullScroll(View.FOCUS_DOWN) } }
            }
            et.setText("")
        }
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { runBtn.performClick(); true } else false
        }
        inputRow.addView(et); inputRow.addView(runBtn)
        outer.addView(inputRow)
        return outer
    }

    private fun buildLogLine(entry: ActivityLogEntry): View {
        val (icon, iconColor) = when (entry.level) {
            "success" -> "✓" to ACCENT
            "warn"    -> "⚠" to C_M3U9
            "error"   -> "✕" to DANGER
            else      -> "›" to TEXT_SEC
        }
        val rowBg = when (entry.level) {
            "warn"  -> C_WARN_BG
            "error" -> Color.parseColor("#2A0D0D")
            else    -> Color.TRANSPARENT
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            if (rowBg != Color.TRANSPARENT) setBackgroundColor(rowBg)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) }
            // Tap row to copy the log message (same pattern as WS-message copy)
            setOnClickListener { activity.copyToClipboard(entry.message, "Log copied") }
            // Icon
            addView(TextView(context).apply {
                text = icon; textSize = 11f; setTextColor(iconColor)
                layoutParams = LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            // Message (flex)
            addView(buildMonoTv(entry.message, if (entry.level == "warn" || entry.level == "error") iconColor else TEXT_PRI, 9.5f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Source tag on right
            if (entry.source.isNotBlank()) {
                addView(TextView(context).apply {
                    text = entry.source.take(6); textSize = 8f; setTextColor(TEXT_DIM)
                    setPadding(dp(4), 0, 0, 0)
                })
            }
        }
    }

    // ── TAB 3: Crypto — captured keys (color-coded by algorithm) + AES decrypt helper ──
    private fun buildCryptoView(): View {
        val sv    = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        inner.addView(buildSectionHeader("Captured Keys (${detector.cryptoCount()})"))
        if (detector.cryptoKeys.isEmpty()) inner.addView(emptyState("Chưa có. Site cần dùng CryptoJS/SubtleCrypto."))
        detector.cryptoKeys.take(20).forEach { cap ->
            val isSubtle = cap.algorithm.startsWith("SubtleCrypto")
            val accent   = if (isSubtle) C_MP4 else C_M3U9
            val cardBg   = if (isSubtle) C_BLUE_BG else C_WARN_BG
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = roundRect(cardBg, 6f)
                elevation = dp(2).toFloat()
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            }
            val titleRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(TextView(context).apply {
                text = if (isSubtle) "🛡" else "🔑"; textSize = 13f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
            })
            titleRow.addView(TextView(context).apply { text = cap.algorithm; textSize = 12f; setTextColor(accent); typeface = Typeface.DEFAULT_BOLD })
            card.addView(titleRow)
            card.addView(kvLine("Key:", cap.key, accent))
            if (cap.iv.isNotBlank()) card.addView(kvLine("IV:", cap.iv, accent))
            card.addView(kvLine("Type:", "AES-128-CBC (hex32)", TEXT_SEC))
            val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            btnRow.addView(buildActionBtn("Copy Key", BG_BADGE) { activity.copyToClipboard(cap.key, "Key copied") })
            btnRow.addView(buildActionBtn("Decrypt ▶", accent) { fillDecryptInput(cap.key, cap.iv) })
            card.addView(btnRow); inner.addView(card)
        }
        inner.addView(vDivider())

        // AES Decrypt Helper
        inner.addView(buildSectionHeader("AES DECRYPT HELPER"))
        val (keyWrap, etKey) = buildLabeledInput("Key (hex)", "vd: a1b2c3...")
        etKey.apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; typeface = Typeface.MONOSPACE }; inner.addView(keyWrap)
        val (ivWrap, etIv) = buildLabeledInput("IV (hex)", "vd: f0e1d2...")
        etIv.apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; typeface = Typeface.MONOSPACE }; inner.addView(ivWrap)
        val (cipherWrap, etCipher) = buildLabeledInput("Ciphertext (base64)", "vd: U2FsdGVk...")
        etCipher.apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; typeface = Typeface.MONOSPACE }; inner.addView(cipherWrap)
        val tvResult = buildMonoTv("", TEXT_SEC, 9.5f)
        inner.addView(buildActionBtn("🔓 Decrypt", ACCENT) {
            tvResult.text = aesDecryptHexIvB64Cipher(etKey.text.toString().trim(), etIv.text.toString().trim(), etCipher.text.toString().trim())
        })
        decryptKeyInput = etKey; decryptIvInput = etIv; decryptCipherInput = etCipher
        inner.addView(tvResult)

        // AES Key finder
        inner.addView(vDivider())
        inner.addView(buildSectionHeader("Find Keys in JS (${detector.requests.count{ it.url.endsWith(".js") || it.url.contains(".js?") }} files)"))
        inner.addView(buildActionBtn("🔍 Scan JS Files", C_JS) {
            val jsUrls = detector.requests.filter { it.url.endsWith(".js") || it.url.contains(".js?") }.map { it.url }.take(15)
            if (jsUrls.isEmpty()) { toast("Chưa bắt được file .js nào"); return@buildActionBtn }
            val loading = TextView(context).apply {
                text = "⏳ Đang quét ${jsUrls.size} file JS..."
                textSize = SZ_LABEL; setTextColor(TEXT_DIM)
                setPadding(dp(2), dp(8), dp(2), dp(4))
            }
            inner.addView(loading)
            scope.launch {
                val found = scanJsForKeys(jsUrls)
                post {
                    inner.removeView(loading)
                    showFoundKeysInline(inner, found, jsUrls.size)
                }
            }
        })
        sv.addView(inner); return sv
    }

    private fun kvLine(label: String, value: String, color: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(2), 0, dp(2))
        addView(TextView(context).apply {
            text = label; textSize = 9.5f; setTextColor(TEXT_SEC); typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        addView(TextView(context).apply {
            text = value; textSize = 9.5f; setTextColor(color); typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    data class JsKeyFinding(val fileUrl: String, val kind: String, val match: String, val context: String, val confidence: Int = 0)

    /** Static scan: fetch each captured .js file concurrently (bounded 500KB/file, IO dispatcher
     *  per file) and look for quoted hex strings of AES key/IV length. Heuristic, not exact —
     *  always ships with surrounding source context so the user (who knows the target site) can
     *  judge relevance themselves rather than the tool guessing right or wrong silently. Results
     *  are ranked by whether a crypto-related keyword appears nearby — not proof, just a sort hint. */
    private suspend fun scanJsForKeys(jsUrls: List<String>): List<JsKeyFinding> = coroutineScope {
        val hexPattern     = Regex("""["']([0-9a-fA-F]{32}|[0-9a-fA-F]{48}|[0-9a-fA-F]{64})["']""")
        val keywordPattern = Regex("(?i)key|iv|secret|aes|crypto|cipher")
        jsUrls.map { fileUrl ->
            async(Dispatchers.IO) {
                val results = mutableListOf<JsKeyFinding>()
                try {
                    val req = okhttp3.Request.Builder().url(fileUrl).build()
                    val body = jsScanClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) null else resp.body?.string()?.take(500_000)
                    } ?: return@async results
                    for (m in hexPattern.findAll(body)) {
                        val hex = m.groupValues[1]
                        val ctxStart = (m.range.first - 40).coerceAtLeast(0)
                        val ctxEnd   = (m.range.last + 15).coerceAtMost(body.length)
                        val ctx = body.substring(ctxStart, ctxEnd).replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                        val kind = when (hex.length) { 32 -> "128-bit"; 48 -> "192-bit"; else -> "256-bit" }
                        val confidence = if (keywordPattern.containsMatchIn(ctx)) 1 else 0
                        results.add(JsKeyFinding(fileUrl.substringAfterLast('/').substringBefore('?'), kind, hex, ctx, confidence))
                        if (results.size >= 15) break   // per-file cap so one huge file can't crowd out the rest
                    }
                } catch (_: Exception) { /* unreachable/CORS/timeout — skip this file */ }
                results
            }
        }.awaitAll().flatten().sortedByDescending { it.confidence }.take(40)
    }

    private val jsScanClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun showFoundKeysInline(container: LinearLayout, found: Any?, scannedCount: Int) {
        @Suppress("UNCHECKED_CAST")
        val findings = found as? List<JsKeyFinding> ?: emptyList()
        if (findings.isEmpty()) {
            container.addView(emptyState("Đã quét $scannedCount file JS — không thấy chuỗi hex 32/48/64 ký tự nào trong dấu nháy."))
            return
        }
        val highConf = findings.count { it.confidence > 0 }
        container.addView(buildSectionHeader(
            if (highConf > 0) "Tìm thấy ${findings.size} chuỗi khả nghi ($highConf khả năng cao) trong $scannedCount file"
            else "Tìm thấy ${findings.size} chuỗi khả nghi trong $scannedCount file"
        ))
        var shownLowConfHeader = false
        findings.forEach { f ->
            if (f.confidence == 0 && highConf > 0 && !shownLowConfHeader) {
                shownLowConfHeader = true
                container.addView(buildSectionHeader("Khả năng thấp hơn (không có từ khoá key/iv/aes/crypto gần đó)"))
            }
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = roundRect(BG_CARD, 6f)
                elevation = dp(2).toFloat()
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
            }
            // File + bit-length label first — tells the user WHERE and WHAT SIZE before the raw value
            card.addView(TextView(context).apply {
                text = (if (f.confidence > 0) "⭐ " else "") + "${f.fileUrl}  ·  ${f.kind}"
                textSize = 9f; setTextColor(if (f.confidence > 0) C_JS else TEXT_DIM)
            })
            card.addView(buildMonoTv(f.match, C_JS, 10f).apply { setTextIsSelectable(true) })
            // Surrounding source code so the user can see the variable name / call site and judge
            // whether this is really a key, or just a coincidental hex-looking hash/id.
            card.addView(TextView(context).apply {
                text = "…${f.context}…"
                textSize = 8.5f; setTextColor(TEXT_SEC)
                setPadding(0, dp(3), 0, dp(4))
            })
            card.addView(buildActionBtn("📋 Copy + điền vào Crypto tab", ACCENT) {
                activity.copyToClipboard(f.match, "Copied: ${f.match.take(16)}...")
                fillDecryptInput(f.match, "")
            })
            container.addView(card)
        }
    }

    private var decryptCipherInput: EditText? = null
    private var decryptKeyInput: EditText?    = null
    private var decryptIvInput: EditText?     = null
    private fun fillDecryptInput(key: String, iv: String) {
        decryptKeyInput?.setText(key)
        decryptIvInput?.setText(iv)
        showTab(3)
    }

    private fun aesDecryptHexIvB64Cipher(keyHex: String, ivHex: String, cipherB64: String): String {
        if (keyHex.isBlank() || ivHex.isBlank() || cipherB64.isBlank()) return "Cần nhập đủ Key, IV, Ciphertext"
        return try {
            val keyB = if (keyHex.length in listOf(32,48,64) && keyHex.all { it.isLetterOrDigit() })
                hexToBytes(keyHex) else keyHex.toByteArray().let { when { it.size<=16->it.copyOf(16); it.size<=24->it.copyOf(24); else->it.copyOf(32) } }
            val ivB = hexToBytes(ivHex)
            val cipherBytes = android.util.Base64.decode(cipherB64, android.util.Base64.DEFAULT)
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyB,"AES"), javax.crypto.spec.IvParameterSpec(ivB))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ── TAB 4: WebSocket — connection header + SENT/RECV message list ───────────
    private fun buildWsView(): View {
        val sv    = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        inner.addView(buildSectionHeader("WebSocket Messages (${detector.wsMessages.size})"))
        if (detector.wsMessages.isEmpty()) { inner.addView(emptyState("Chưa có WS. Mở trang dùng WebSocket.")); sv.addView(inner); return sv }
        val grouped = detector.wsMessages.groupBy { it.wsUrl }
        grouped.forEach { (wsUrl, msgs) ->
            inner.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(BG_CARD); setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                addView(buildMonoTv(wsUrl, TEXT_PRI, 10.5f).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                addView(TextView(context).apply {
                    text = "CONNECTED"; textSize = 8.5f; setTextColor(ACCENT)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    background = roundRect(Color.parseColor("#0D2E1A"), 4f)
                })
            })
            msgs.sortedBy { it.timestamp }.take(50).forEach { msg ->
                val (dirColor, dirLabel) = when(msg.direction) {
                    "send"  -> C_MP4 to "SENT"
                    "recv"  -> C_M3U9 to "RECV"
                    "open"  -> ACCENT to "OPEN"
                    else    -> TEXT_DIM to "CLOSE"
                }
                val hasStreamHint = msg.data.contains("http") &&
                    (msg.data.contains(".m3u8") || msg.data.contains("stream") || msg.data.contains(".mp4"))
                inner.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
                    setPadding(dp(8), dp(7), dp(8), dp(7))
                    background = rippleRect(BG_CARD2, 0f)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 1 }
                    addView(TextView(context).apply {
                        text = dirLabel; textSize = 8.5f; setTextColor(Color.BLACK)
                        setBackgroundColor(dirColor)
                        setPadding(dp(4), dp(1), dp(4), dp(1))
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                    })
                    val msgCol = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    msgCol.addView(buildMonoTv(msg.data.take(220), TEXT_PRI, 9.5f).apply {
                        setOnClickListener { activity.copyToClipboard(msg.data, "WS data copied") }
                    })
                    msgCol.addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        if (hasStreamHint) addView(TextView(context).apply {
                            text = "▶"; textSize = 9f; setTextColor(ACCENT)
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) }
                        })
                        addView(TextView(context).apply {
                            text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(msg.timestamp))
                            textSize = 8f; setTextColor(TEXT_DIM)
                        })
                    })
                    addView(msgCol)
                })
            }
        }
        sv.addView(inner); return sv
    }

    // ── Storage, CSS, Timeline, Proxy — unchanged (no design reference provided) ──
    private fun buildStorageView(): View {
        val sv = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        inner.addView(buildSectionHeader("localStorage + sessionStorage"))
        val tvRes = buildMonoTv("Đang đọc...", TEXT_SEC, 9.5f).apply { setTextIsSelectable(true) }; inner.addView(tvRes)
        var fullStorageJson = ""
        webView.evaluateJavascript("""(function(){try{var ls={},ss={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);ls[k]=localStorage.getItem(k);}for(var i=0;i<sessionStorage.length;i++){var k=sessionStorage.key(i);ss[k]=sessionStorage.getItem(k);}return JSON.stringify({l:ls,s:ss});}catch(e){return '{"error":"'+e+'"}';}})()""") { raw ->
            val clean = raw?.removeSurrounding("\"")?.replace("\\\"","\"") ?: "{}"
            fullStorageJson = clean
            post { try {
                val j = org.json.JSONObject(clean); val sb = StringBuilder()
                sb.appendLine("=== localStorage ===")
                j.optJSONObject("l")?.keys()?.forEach { k0 -> val k = k0 as String; sb.appendLine("$k: ${j.optJSONObject("l")?.optString(k,"")?.take(100)}") }
                sb.appendLine("\n=== sessionStorage ===")
                j.optJSONObject("s")?.keys()?.forEach { k0 -> val k = k0 as String; sb.appendLine("$k: ${j.optJSONObject("s")?.optString(k,"")?.take(100)}") }
                tvRes.text = sb
            } catch(_:Exception){tvRes.text=clean} }
        }
        inner.addView(buildActionBtn("📋 Copy full JSON", ACCENT) {
            if (fullStorageJson.isNotBlank()) activity.copyToClipboard(fullStorageJson, "Storage JSON copied (${fullStorageJson.length} chars)")
            else toast("Chưa đọc xong, thử lại sau")
        })
        inner.addView(buildActionBtn("🗑 Clear localStorage", DANGER) {
            webView.evaluateJavascript("localStorage.clear();void 0", null)
            toast("localStorage cleared")
        })
        sv.addView(inner); return sv
    }

    private fun buildCssView(): View {
        val sv = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        inner.addView(buildSectionHeader("🎨 CSS Injector"))
        val et = buildEditText("/* CSS here */\nbody { background: #000 !important; }").apply {
            minLines = 5
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
        }
        inner.addView(et)
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(buildActionBtn("▶ Inject", ACCENT) {
            val css = et.text.toString().replace("`","\\`")
            webView.evaluateJavascript("""(function(){var el=document.getElementById('__sb_css');if(!el){el=document.createElement('style');el.id='__sb_css';document.head.appendChild(el);}el.textContent=`$css`;return 'ok';})()""", null)
            toast("CSS injected")
        })
        btnRow.addView(buildActionBtn("✖ Remove", DANGER) {
            webView.evaluateJavascript("var e=document.getElementById('__sb_css');if(e)e.remove();'ok'", null)
        })
        inner.addView(btnRow)
        listOf("🌑 Dark" to "* { background: #111 !important; color: #eee !important; }",
               "🙈 Hide ads" to ".ad,.ads,[id*=ad],[class*=ad] { display:none!important; }",
               "👁 Show hidden" to "[style*='display:none'],[hidden] { display:block!important; }",
               "📐 Desktop layout" to "body { min-width:1280px!important; zoom:0.7; }",
               "🔍 Highlight video" to "video { outline:3px solid #1DB954!important; }").forEach { (l,c) ->
            inner.addView(buildActionBtn(l, BG_BADGE) { et.setText(c) })
        }
        sv.addView(inner); return sv
    }

    private fun buildTimelineView(): View {
        val reqs = detector.requests.sortedBy { it.timestamp }
        if (reqs.isEmpty()) return ScrollView(context).apply { addView(emptyState("Chưa có requests.")) }
        val t0 = reqs.first().timestamp; val tRange = (reqs.last().timestamp - t0).coerceAtLeast(1L)
        val sv = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(16)) }
        inner.addView(buildSectionHeader("Timeline (${reqs.size} requests)"))
        reqs.take(200).forEach { req ->
            val rel = (req.timestamp - t0).toFloat() / tRange
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
                background = rippleRect(Color.TRANSPARENT, 0f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 1 }
                setOnClickListener { showRequestDetail(req) }
                setOnLongClickListener { activity.copyToClipboard(req.url, "URL copied"); true }
            }
            row.addView(typeBadge(req.tag, typeColor(req.tag)).apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(16)) })
            row.addView(buildMonoTv("${req.host.take(16)}${req.path.take(22)}", TEXT_SEC, 8f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(buildWaterfallBar(rel, 0.06f, typeColor(req.tag)))
            row.addView(buildMonoTv("+${req.timestamp-t0}ms", TEXT_DIM, 7.5f).apply { layoutParams = LinearLayout.LayoutParams(dp(45), LinearLayout.LayoutParams.WRAP_CONTENT); gravity = Gravity.END })
            inner.addView(row)
        }
        sv.addView(inner); return sv
    }

    private fun buildProxyView(): View {
        val sv = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        inner.addView(buildSectionHeader("🌐 HTTP Proxy"))
        val curH = System.getProperty("http.proxyHost") ?: ""; val curP = System.getProperty("http.proxyPort") ?: ""
        inner.addView(buildMonoTv(if (curH.isNotBlank()) "Proxy ON: $curH:$curP" else "Proxy: OFF", if (curH.isNotBlank()) ACCENT else TEXT_DIM, 11f).apply { setPadding(0,0,0,dp(8)) })
        val (hostWrap, etHost) = buildLabeledInput("Proxy host", "vd: 192.168.1.100")
        etHost.apply {
            setText(curH)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val (portWrap, etPort) = buildLabeledInput("Port", "vd: 8888")
        etPort.apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(curP)
        }
        inner.addView(hostWrap); inner.addView(portWrap)
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(buildActionBtn("✅ Set", ACCENT) {
            val h = etHost.text.toString().trim(); val p = etPort.text.toString().trim().toIntOrNull() ?: 0
            activity.setHttpProxy(h, p); showTab(9)
        })
        btnRow.addView(buildActionBtn("🗑 Clear", DANGER) { activity.setHttpProxy("", 0); showTab(9) })
        inner.addView(btnRow)
        inner.addView(vDivider())
        inner.addView(buildSectionHeader("Quick Setup — Charles Port 8888 · Burp Port 8080"))
        inner.addView(buildMonoTv("1. Mở Charles/Burp trên cùng WiFi\n2. Lấy IP máy tính\n3. Nhập host:port ở trên\n4. Cài SSL cert + bật SSL bypass (tab Network → SSL)", TEXT_SEC, 10f))
        sv.addView(inner); return sv
    }

    // ── Request detail dialog ──────────────────────────────────────────────────
    private fun showRequestDetail(req: NetworkRequest) {
        val d = android.app.AlertDialog.Builder(context)
        val sv = ScrollView(context)
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)); setBackgroundColor(BG_PANEL) }
        inner.addView(buildMonoTv(req.url, typeColor(req.tag), 10.5f).apply { setTextIsSelectable(true) })
        if (req.url.contains(".m3u8")) {
            inner.addView(buildActionBtn("🎞 Xem chất lượng (quality variants)", ACCENT) {
                val loading = TextView(context).apply {
                    text = "⏳ Đang tải m3u8..."
                    textSize = SZ_LABEL; setTextColor(TEXT_DIM)
                    setPadding(dp(2), dp(6), dp(2), dp(4))
                }
                inner.addView(loading)
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        M3u8QualityParser.fetchQualities(req.url, req.referer.ifBlank { req.pageUrl })
                    }
                    val qualities = result.getOrNull().orEmpty()
                    post { inner.removeView(loading) }
                    if (qualities.isEmpty()) {
                        toast(if (result.isFailure) "Lỗi tải m3u8: ${result.exceptionOrNull()?.message ?: "?"}" else "Không có variant (có thể là playlist 1 bitrate)")
                    } else {
                        val labels = qualities.map { q -> "${q.label}  ·  ${q.bandwidth / 1000}kbps  ·  ${q.codecs.ifBlank { "codec ?" }}" }.toTypedArray()
                        android.app.AlertDialog.Builder(context)
                            .setTitle("Quality variants (chạm để copy URL)")
                            .setItems(labels) { _, i -> activity.copyToClipboard(qualities[i].url, "${qualities[i].label} URL copied") }
                            .setNegativeButton("Đóng", null).show()
                    }
                }
            })
        }
        if (req.headers.isNotEmpty()) {
            inner.addView(buildSectionHeader("REQUEST HEADERS"))
            req.headers.entries.forEach { (k,v) -> inner.addView(buildMonoTv("$k: ${v.take(200)}", TEXT_SEC, 9f)) }
        }
        if (req.requestBody.isNotBlank()) {
            inner.addView(buildSectionHeader("REQUEST BODY"))
            inner.addView(buildMonoTv(req.requestBody.take(3000), TEXT_SEC, 9f).apply { setTextIsSelectable(true) })
        }
        if (req.responseHeaders.isNotEmpty()) {
            inner.addView(buildSectionHeader("RESPONSE HEADERS · ${req.statusCode}"))
            req.responseHeaders.entries.forEach { (k,v) -> inner.addView(buildMonoTv("$k: ${v.take(200)}", TEXT_SEC, 9f)) }
        }
        if (req.responseBodyPreview.isNotBlank()) {
            inner.addView(buildSectionHeader("BODY PREVIEW"))
            inner.addView(buildMonoTv(req.responseBodyPreview.take(3000), TEXT_SEC, 9f).apply { setTextIsSelectable(true) })
        }
        // Actions
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        listOf("Copy URL" to ACCENT, "cURL" to C_CSS, "OkHttp" to C_DASH, "CS3" to C_IMG).forEach { (label, color) ->
            btnRow.addView(buildActionBtn(label, color) {
                val code = when(label) {
                    "Copy URL" -> req.url
                    "cURL" -> CurlExporter.toCurl(req)
                    "OkHttp" -> buildOkHttpCode(req)
                    "CS3"    -> buildCs3Code(req)
                    else -> req.url
                }
                activity.copyToClipboard(code, "$label copied")
            })
        }
        inner.addView(btnRow)
        sv.addView(inner)
        d.setView(sv).setNegativeButton("Đóng", null).show()
    }

    private fun showDiff() {
        if (snapshotUrls.isEmpty()) { toast("Chưa snap. Bấm 📸 Snap trước."); return }
        val newReqs = detector.requests.filter { it.url !in snapshotUrls }
        val msg = if (newReqs.isEmpty()) "Không có request mới." else newReqs.take(10).joinToString("\n") { "[${it.tag}] ${it.host}${it.path.take(40)}" }
        android.app.AlertDialog.Builder(context).setTitle("Diff: ${newReqs.size} new").setMessage(msg).setPositiveButton("OK",null).show()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private fun typeColor(tag: String) = when (tag) {
        "HLS","M3U8"        -> C_HLS
        "M3U9"              -> C_M3U9
        "DASH"              -> C_DASH
        "MP4","WEBM","FLV"  -> C_MP4
        "XHR"               -> C_XHR
        "JS"                -> C_JS
        "CSS"               -> C_CSS
        "IMG"               -> C_IMG
        "DOC"               -> C_DOC
        else                -> TEXT_DIM
    }

    private fun streamTypeColor(type: StreamType) = when (type) {
        StreamType.HLS  -> C_HLS
        StreamType.DASH -> C_DASH
        StreamType.M3U9 -> C_M3U9
        else            -> C_MP4
    }

    private fun typeBadge(label: String, color: Int) = TextView(context).apply {
        text = label; textSize = 8.5f
        setTextColor(if (isColorDark(color)) Color.WHITE else Color.BLACK)
        setBackgroundColor(color)
        setPadding(dp(4), dp(1), dp(4), dp(1))
    }

    private fun isColorDark(color: Int): Boolean {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return (0.299 * r + 0.587 * g + 0.114 * b) < 128
    }

    private fun roundRect(color: Int, cornerDp: Float): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = cornerDp * dp
        }
    }

    private fun bottomBorder(color: Int): android.graphics.drawable.Drawable {
        val layer = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT),
            android.graphics.drawable.ColorDrawable(color)
        ))
        layer.setLayerInset(1, 0, dp(36), 0, 0)
        return layer
    }

    private fun buildSectionHeader(text: String) = TextView(context).apply {
        this.text = text; textSize = 10.5f; setTextColor(TEXT_DIM)
        typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(10), 0, dp(4))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun buildMonoTv(text: String, color: Int, size: Float) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color); typeface = Typeface.MONOSPACE
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun buildEditText(hint: String) = EditText(context).apply {
        this.hint = hint; textSize = 13f; setTextColor(TEXT_PRI)
        setHintTextColor(TEXT_DIM); background = roundRect(BG_CARD, 6f)
        setPadding(dp(10), dp(11), dp(10), dp(11))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
    }

    /** Fix: buildEditText's hint disappears the moment the user starts typing — for lookalike
     *  fields (Key/IV/Ciphertext) this loses context mid-entry. Wraps the same EditText with a
     *  small always-visible label above it, reusing buildEditText so visual styling is unchanged. */
    private fun buildLabeledInput(label: String, hint: String = ""): Pair<View, EditText> {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        wrap.addView(TextView(context).apply {
            text = label; textSize = 9f; setTextColor(TEXT_SEC)
            setPadding(dp(2), 0, 0, dp(3))
        })
        val et = buildEditText(hint).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        wrap.addView(et)
        return wrap to et
    }

    private fun rippleRect(baseColor: Int, cornerDp: Float): android.graphics.drawable.Drawable {
        val content = roundRect(baseColor, cornerDp)
        val rippleColor = android.content.res.ColorStateList.valueOf(
            if (isColorDark(baseColor)) Color.argb(90, 255, 255, 255) else Color.argb(70, 0, 0, 0)
        )
        val mask = roundRect(Color.WHITE, cornerDp)
        return android.graphics.drawable.RippleDrawable(rippleColor, content, mask)
    }

    private fun buildActionBtn(label: String, color: Int, action: (() -> Unit)? = null) = TextView(context).apply {
        text = label; textSize = 10.5f
        setTextColor(if (isColorDark(color)) Color.WHITE else Color.BLACK)
        background = rippleRect(color, 5f)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4); topMargin = dp(4) }
        action?.let { setOnClickListener { it() } }
    }

    private fun vDivider() = View(context).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(10); bottomMargin = dp(10) }
    }

    private fun emptyState(msg: String) = TextView(context).apply {
        text = msg; textSize = 12f; setTextColor(TEXT_DIM)
        gravity = Gravity.CENTER; setPadding(dp(16), dp(32), dp(16), dp(32))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(v: Int) = (v * dp).toInt()

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    private fun hexToBytes(h: String): ByteArray {
        val c = h.replace(":","").replace(" ","")
        return ByteArray(c.length/2) { i -> c.substring(i*2,i*2+2).toInt(16).toByte() }
    }

    private fun buildOkHttpCode(req: NetworkRequest): String {
        val dq = '"'
        return buildString {
            appendLine("val client = OkHttpClient()")
            appendLine("val request = Request.Builder()")
            appendLine("    .url($dq${req.url}$dq)")
            req.headers.forEach { (k,v) -> if (k.lowercase() !in listOf("host","connection","content-length")) appendLine("    .addHeader($dq$k$dq, $dq$v$dq)") }
            appendLine("    .get().build()")
            appendLine("val response = client.newCall(request).execute()")
        }
    }

    private fun buildCs3Code(req: NetworkRequest): String {
        val dq = '"'; val isM3u8 = req.url.contains(".m3u8",true)
        val ref = req.headers["Referer"] ?: req.headers["referer"] ?: req.pageUrl
        return buildString {
            appendLine("callback(newExtractorLink(source=name,name=name,url=$dq${req.url}$dq,type=${if(isM3u8)"ExtractorLinkType.M3U8" else "ExtractorLinkType.VIDEO"}) {")
            appendLine("    quality=Qualities.P1080.value")
            appendLine("    headers=mapOf($dq" + "Referer$dq to $dq$ref$dq)")
            appendLine("})")
        }
    }

    private fun buildPluginSnippet(stream: StreamItem): String {
        val dq = '"'
        return buildString {
            appendLine("// Captured stream: ${stream.source}")
            appendLine("callback(newExtractorLink(source=name,name=name,url=$dq${stream.url}$dq,")
            appendLine("    type=${if(stream.type==StreamType.HLS) "ExtractorLinkType.M3U8" else "ExtractorLinkType.VIDEO"}) {")
            appendLine("    quality=Qualities.P1080.value")
            appendLine("    headers=mapOf($dq" + "Referer$dq to $dq${stream.referer}$dq)")
            appendLine("})")
        }
    }

    private fun exportAllStreams(streams: List<StreamItem>) {
        val text = streams.joinToString("\n\n") { s ->
            "[${s.type}] ${s.url}\nReferer: ${s.referer}\nSource: ${s.source}"
        }
        activity.copyToClipboard(text, "${streams.size} streams exported")
    }

    // ── Show / Hide ────────────────────────────────────────────────────────────
    fun show() {
        this@DevToolsOverlay.visibility = View.VISIBLE
        val panelH = screenHeight * (1f - defaultTopFraction)
        // Ensure we start from below screen regardless of last drag position
        panelView.translationY = panelH
        ObjectAnimator.ofFloat(panelView, "translationY", panelH, 0f)
            .apply { duration = 340; interpolator = android.view.animation.OvershootInterpolator(1.02f); start() }
        refresh()
    }

    fun hide() {
        val panelH = screenHeight * (1f - defaultTopFraction)
        val startY = panelView.translationY
        ObjectAnimator.ofFloat(panelView, "translationY", startY, panelH)
            .apply {
                duration = 240
                interpolator = android.view.animation.AccelerateInterpolator()
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        this@DevToolsOverlay.visibility = View.GONE
                        panelView.translationY = 0f   // reset for next show()
                    }
                })
                start()
            }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); scope.cancel() }
}