package com.aho.streambrowser.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.*
import android.webkit.WebView
import android.widget.*
import androidx.core.content.ContextCompat
import com.aho.streambrowser.detector.StreamDetector
import com.aho.streambrowser.model.NetworkRequest
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.model.StreamType
import com.aho.streambrowser.util.*
import kotlinx.coroutines.*

/** DevTools Pro overlay panel — slides from right, matches InjectWebs v5 design */
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
    private val TEXT_PRI   = Color.parseColor("#F0F0F0")
    private val TEXT_SEC   = Color.parseColor("#888888")
    private val TEXT_DIM   = Color.parseColor("#444444")
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

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val dp get() = context.resources.displayMetrics.density

    private lateinit var tabStrip: LinearLayout
    private lateinit var contentArea: FrameLayout
    private lateinit var tvLive: TextView
    private var currentTab = 0
    private var networkFilter = "All"
    private var snapshotUrls: Set<String> = emptySet()
    private val consoleHistory = mutableListOf<String>()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setupOverlay()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────
    private fun setupOverlay() {
        // Backdrop (tap to close)
        val backdrop = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#88000000"))
            setOnClickListener { hide() }
        }
        addView(backdrop)

        // Panel (88% width from right, 94% height, centered vertically)
        val panelW = (screenWidth * 0.88f).toInt()
        val panel  = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_PANEL)
            layoutParams = LayoutParams(panelW, (screenHeight * 0.94f).toInt()).apply {
                gravity  = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = 0
            }
        }

        // Header
        panel.addView(buildHeader())

        // Tab strip
        tabStrip = buildTabStrip()
        panel.addView(tabStrip)

        // Divider
        panel.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(DIVIDER)
        })

        // Content
        contentArea = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(BG_PANEL)
        }
        panel.addView(contentArea)
        addView(panel)

        translationX = panelW.toFloat()
        showTab(0)
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
        val tvCounts = TextView(context).apply {
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
        listOf(logo, tvLive, spacer, tvCounts, btnClear, btnHide).forEach { row.addView(it) }
        return row
    }

    // ── Tab strip ─────────────────────────────────────────────────────────────
    private val TABS = listOf("Network","Streams","Console","Crypto","WS","Headers","Storage","CSS","Timeline","Proxy")

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
            else -> -1
        }
        val label = if (count > 0) "$name ($count)" else name
        return TextView(context).apply {
            text = label; textSize = 11f
            setTextColor(if (idx == currentTab) ACCENT else TEXT_SEC)
            setPadding(dp(14), dp(10), dp(14), dp(8))
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

    fun refresh() { refreshTabStrip(); showTab(currentTab) }

    // ── Show tab ──────────────────────────────────────────────────────────────
    private fun showTab(idx: Int) {
        currentTab = idx
        refreshTabStrip()
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
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB 0: Network — type badges + waterfall
    // ═══════════════════════════════════════════════════════════════════════════
    private fun buildNetworkTab(): View {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        // Filter strip
        val filters = listOf("All","Streams","XHR","JS","CSS","Doc","Img")
        val filterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG_HEADER)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        filters.forEach { f ->
            val btn = TextView(context).apply {
                text = f; textSize = 10f
                setPadding(dp(8), dp(3), dp(8), dp(3))
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

        // Column headers
        outer.addView(buildNetworkHeader())

        // Request list
        val reqs = detector.requests.filter { req ->
            when (networkFilter) {
                "Streams" -> req.isStream
                "XHR"     -> req.tag == "XHR"
                "JS"      -> req.tag == "JS"
                "CSS"     -> req.tag == "CSS"
                "Doc"     -> req.tag == "DOC"
                "Img"     -> req.tag == "IMG"
                else      -> true
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

    private fun buildNetworkHeader(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Color.parseColor("#0F0F0F"))
        setPadding(dp(8), dp(4), dp(8), dp(4))
        listOf(
            "Type" to 44f, "URL/Host" to 0f, "St" to 28f, "Size" to 36f, "Waterfall" to 60f
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

    private fun buildNetworkRow(req: NetworkRequest, t0: Long, tRange: Long): View {
        val typeColor = typeColor(req.tag)
        val row = LinearLayout(context).apply {
            orientation  = LinearLayout.HORIZONTAL
            gravity      = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(BG_CARD2)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 1 }
            setOnClickListener { showRequestDetail(req) }
        }
        // Type badge
        row.addView(TextView(context).apply {
            text = req.tag; textSize = 8f; setTextColor(Color.BLACK)
            setBackgroundColor(typeColor)
            setPadding(dp(3), dp(1), dp(3), dp(1))
            layoutParams = LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        })
        // Host + path
        val hostCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4), 0, dp(4), 0)
        }
        hostCol.addView(TextView(context).apply {
            text = req.host.take(28); textSize = 10f; setTextColor(TEXT_PRI)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        hostCol.addView(TextView(context).apply {
            text = req.path.take(36); textSize = 8.5f; setTextColor(TEXT_SEC)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(hostCol)
        // Status
        val statusColor = when {
            req.statusCode in 200..299 -> ACCENT
            req.statusCode in 300..399 -> C_CSS
            req.statusCode >= 400      -> Color.parseColor("#EF4444")
            else -> TEXT_DIM
        }
        row.addView(TextView(context).apply {
            text = if (req.statusCode > 0) req.statusCode.toString() else "…"
            textSize = 9f; setTextColor(statusColor)
            layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        })
        // Size
        val sizeStr = when {
            req.responseBodyPreview.isEmpty()    -> "—"
            req.responseBodyPreview.length < 1024 -> "${req.responseBodyPreview.length}B"
            else -> "${req.responseBodyPreview.length/1024}K"
        }
        row.addView(TextView(context).apply {
            text = sizeStr; textSize = 8.5f; setTextColor(TEXT_SEC)
            layoutParams = LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.END
        })
        // Waterfall bar
        val rel    = ((req.timestamp - t0).toFloat() / tRange).coerceIn(0f, 0.9f)
        val barW   = (0.08f + rel * 0.02f).coerceAtMost(0.15f)
        row.addView(buildWaterfallBar(rel, barW, typeColor))
        return row
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
    // TAB 1: Streams — grouped by quality, codec info
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
        listOf("By Quality", "Export All").forEach { label ->
            hdr.addView(TextView(context).apply {
                text = label; textSize = 9f; setTextColor(TEXT_SEC)
                setPadding(dp(7), dp(3), dp(7), dp(3))
                background = roundRect(BG_BADGE, 4f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) }
                setOnClickListener { if (label == "Export All") exportAllStreams(streams) }
            })
        }
        outer.addView(hdr)

        if (streams.isEmpty()) { outer.addView(emptyState("Chưa có streams. Mở trang video.")); return outer }

        // Group by quality
        val sv = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Quality groups
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

        groups.forEach { (quality, list) ->
            if (list.isEmpty()) return@forEach
            // Quality group header
            inner.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(6))
                addView(TextView(context).apply {
                    text = quality; textSize = 11f; setTextColor(TEXT_PRI)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(context).apply {
                    text = "(${list.size})"; textSize = 10f; setTextColor(TEXT_SEC)
                    setPadding(dp(4), 0, 0, 0)
                })
                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) }
                    setBackgroundColor(DIVIDER)
                })
            })
            list.forEach { stream -> inner.addView(buildStreamCard(stream, quality)) }
        }
        sv.addView(inner); outer.addView(sv)
        return outer
    }

    private fun buildStreamCard(stream: StreamItem, quality: String): View {
        val typeColor = streamTypeColor(stream.type)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_CARD)
            setBackgroundColor(BG_CARD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(2); marginStart = dp(8); marginEnd = dp(8)
            }
        }
        // Type + quality + detection badges row
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
        val codec = when {
            stream.url.contains("avc") || stream.url.contains("h264") -> "H.264"
            stream.url.contains("hevc") || stream.url.contains("h265") -> "H.265"
            stream.url.contains("vp9") -> "VP9"
            stream.url.contains("av1") -> "AV1"
            else -> ""
        }
        if (codec.isNotBlank()) badges.addView(TextView(context).apply {
            text = codec; textSize = 9f; setTextColor(TEXT_SEC)
            setPadding(dp(5), dp(1), dp(5), dp(1))
            background = roundRect(BG_BADGE, 3f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) }
        })
        // Source badge
        badges.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0,1,1f) })
        badges.addView(TextView(context).apply {
            text = stream.source.take(18); textSize = 8.5f; setTextColor(TEXT_DIM)
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
            val ageColor = when { ageSec < 60 -> ACCENT; ageSec < 300 -> C_JS; else -> Color.parseColor("#EF4444") }
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
    // TAB 5: Headers — request + response headers for selected request
    // ═══════════════════════════════════════════════════════════════════════════
    private fun buildHeadersTab(): View {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val streams = detector.streams
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
        inner.addView(buildSectionHeader("JWT Decoder"))
        val etJwt = buildEditText("Paste JWT token ở đây...")
        val tvJwtResult = buildMonoTv("", TEXT_SEC, 9.5f)
        inner.addView(etJwt)
        inner.addView(buildActionBtn("🔍 Decode", ACCENT) {
            val token = etJwt.text.toString().trim()
            val info  = JwtDecoder.decode(token)
            tvJwtResult.text = if (info != null) {
                "Header:\n${info.header}\n\nPayload:\n${info.payload}\n\nExp: ${info.expTime} ${if (info.isExpired) "⚠ EXPIRED" else "✓"}"
            } else "Invalid JWT"
        })
        inner.addView(tvJwtResult)
        sv.addView(inner); outer.addView(sv)
        return outer
    }

    private fun buildHeadersCard(req: NetworkRequest): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_CARD)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
        }
        card.addView(buildMonoTv(req.url.take(80), typeColor(req.tag), 9.5f))
        if (req.headers.isNotEmpty()) {
            card.addView(buildSectionHeader("REQUEST"))
            req.headers.entries.take(15).forEach { (k, v) ->
                card.addView(buildMonoTv("$k: ${v.take(120)}", TEXT_SEC, 9f))
            }
        }
        if (req.responseHeaders.isNotEmpty()) {
            card.addView(buildSectionHeader("RESPONSE · ${req.statusCode}"))
            req.responseHeaders.entries.take(10).forEach { (k, v) ->
                card.addView(buildMonoTv("$k: ${v.take(120)}", TEXT_SEC, 9f))
            }
        }
        return card
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Other tabs — delegate to same logic as DevToolsSheet but with new styling
    // ═══════════════════════════════════════════════════════════════════════════
    private fun buildConsoleTab()  = buildConsoleView()
    private fun buildCryptoTab()   = buildCryptoView()
    private fun buildWsTab()       = buildWsView()
    private fun buildStorageTab()  = buildStorageView()
    private fun buildCssTab()      = buildCssView()
    private fun buildTimelineTab() = buildTimelineView()
    private fun buildProxyTab()    = buildProxyView()

    // ── Console ────────────────────────────────────────────────────────────────
    private fun buildConsoleView(): View {
        val outer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT) }
        val log   = StringBuilder(detector.consoleLog)
        val sv    = ScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f); overScrollMode = View.OVER_SCROLL_NEVER }
        val tv    = buildMonoTv(log.toString(), TEXT_SEC, 9.5f).apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        sv.addView(tv); outer.addView(sv)
        // Input row
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG_HEADER)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val et = buildEditText("JavaScript...").apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val histBtn = buildActionBtn("⏫", BG_BADGE) {
            if (consoleHistory.isNotEmpty()) et.setText(consoleHistory[0])
        }
        val runBtn = buildActionBtn("▶ Run", ACCENT) {
            val code = et.text.toString()
            if (code.isBlank()) return@buildActionBtn
            consoleHistory.remove(code); consoleHistory.add(0, code); if (consoleHistory.size > 50) consoleHistory.removeLast()
            log.append("\n▶ $code\n")
            webView.evaluateJavascript(code) { res ->
                log.append(res?.removeSurrounding("\"") ?: "null")
                post { tv.text = log; sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
            }
        }
        inputRow.addView(et); inputRow.addView(histBtn); inputRow.addView(runBtn)
        outer.addView(inputRow)
        return outer
    }

    // ── Crypto ─────────────────────────────────────────────────────────────────
    private fun buildCryptoView(): View {
        val sv    = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        inner.addView(buildSectionHeader("Captured Keys (${detector.cryptoCount()})"))
        if (detector.cryptoKeys.isEmpty()) inner.addView(emptyState("Chưa có. Site cần dùng CryptoJS/SubtleCrypto."))
        detector.cryptoKeys.take(20).forEach { cap ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(BG_CARD)
                setBackgroundColor(BG_CARD); setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
            }
            card.addView(typeBadge(cap.algorithm, C_JS))
            card.addView(buildMonoTv("KEY: ${cap.key}", TEXT_PRI, 10f))
            if (cap.iv.isNotBlank()) card.addView(buildMonoTv("IV:  ${cap.iv}", C_CSS, 10f))
            val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            btnRow.addView(buildActionBtn("Copy Key", ACCENT) { activity.copyToClipboard(cap.key, "Key copied") })
            btnRow.addView(buildActionBtn("Decrypt ▶", C_DASH) { fillDecryptInput(cap.key, cap.iv) })
            card.addView(btnRow); inner.addView(card)
        }
        // AES Decrypt helper
        inner.addView(vDivider())
        inner.addView(buildSectionHeader("AES-CBC Decrypt"))
        val etCipher = buildEditText("IV_HEX:CIPHERTEXT_HEX"); inner.addView(etCipher)
        val etKey    = buildEditText("Key (string or hex)"); inner.addView(etKey)
        val tvResult = buildMonoTv("", TEXT_SEC, 9.5f)
        inner.addView(buildActionBtn("🔓 Decrypt", ACCENT) {
            tvResult.text = aesDecrypt(etCipher.text.toString().trim(), etKey.text.toString().trim())
        })
        decryptCipherInput = etCipher; decryptKeyInput = etKey
        inner.addView(tvResult)
        // AES Key finder
        inner.addView(vDivider())
        inner.addView(buildSectionHeader("Find Keys in JS (${detector.requests.count{ it.url.endsWith(".js") || it.url.contains(".js?") }} files)"))
        inner.addView(buildActionBtn("🔍 Scan JS Files", C_JS) {
            val jsUrls = detector.requests.filter { it.url.endsWith(".js") || it.url.contains(".js?") }.map { it.url }.take(15)
            scope.launch {
                val found = AesKeyFinder.scanJsFiles(jsUrls, webView.url ?: "")
                post {
                    inner.removeViewAt(inner.childCount - 1)
                    if (found.isEmpty()) inner.addView(emptyState("Không tìm thấy trong ${jsUrls.size} files"))
                    else found.take(10).forEach { k ->
                        inner.addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG_CARD)
                            setBackgroundColor(BG_CARD); setPadding(dp(10),dp(6),dp(10),dp(6))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(3) }
                            addView(buildMonoTv("[${k.keyType}] ${k.keyValue}", ACCENT, 10f))
                            addView(buildMonoTv("…${k.context.take(80)}…", TEXT_DIM, 8.5f))
                            addView(buildActionBtn("Copy Key", ACCENT) { activity.copyToClipboard(k.keyValue,"Copied") })
                        })
                    }
                }
            }
        })
        sv.addView(inner); return sv
    }

    private var decryptCipherInput: EditText? = null
    private var decryptKeyInput: EditText?    = null
    private fun fillDecryptInput(key: String, iv: String) {
        decryptKeyInput?.setText(key)
        if (iv.isNotBlank()) decryptCipherInput?.setText("$iv:")
        showTab(3)
    }

    // ── WebSocket ──────────────────────────────────────────────────────────────
    private fun buildWsView(): View {
        val sv    = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        inner.addView(buildSectionHeader("WebSocket Messages (${detector.wsMessages.size})"))
        if (detector.wsMessages.isEmpty()) { inner.addView(emptyState("Chưa có WS. Mở trang dùng WebSocket.")); sv.addView(inner); return sv }
        // Group by WS URL
        val grouped = detector.wsMessages.groupBy { it.wsUrl }
        grouped.forEach { (wsUrl, msgs) ->
            inner.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6)); setBackgroundColor(BG_HEADER)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) }
                addView(buildMonoTv(wsUrl.take(50), TEXT_PRI, 10f).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                addView(typeBadge("CONNECTED", ACCENT))
            })
            msgs.take(30).forEach { msg ->
                val (dirColor, dirLabel) = when(msg.direction) {
                    "send"  -> C_XHR to "SENT"
                    "recv"  -> ACCENT to "RECV"
                    "open"  -> C_CSS to "OPEN"
                    else    -> TEXT_DIM to "CLOSE"
                }
                inner.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    setBackgroundColor(BG_CARD2)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 1 }
                    addView(typeBadge(dirLabel, dirColor).apply { layoutParams = LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) } })
                    val msgTv = buildMonoTv(msg.data.take(200), TEXT_SEC, 9.5f).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setOnClickListener { activity.copyToClipboard(msg.data, "WS data copied") }
                    }
                    addView(msgTv)
                })
            }
        }
        sv.addView(inner); return sv
    }

    // ── Storage, CSS, Timeline, Proxy — stub wrappers calling WebView ─────────
    private fun buildStorageView(): View {
        val sv = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        inner.addView(buildSectionHeader("localStorage + sessionStorage"))
        val tvRes = buildMonoTv("Đang đọc...", TEXT_SEC, 9.5f); inner.addView(tvRes)
        webView.evaluateJavascript("""(function(){try{var ls={},ss={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);ls[k]=localStorage.getItem(k);}for(var i=0;i<sessionStorage.length;i++){var k=sessionStorage.key(i);ss[k]=sessionStorage.getItem(k);}return JSON.stringify({l:ls,s:ss});}catch(e){return '{"error":"'+e+'"}';}})()""") { raw ->
            val clean = raw?.removeSurrounding("\"")?.replace("\\\"","\"") ?: "{}"
            post { try {
                val j = org.json.JSONObject(clean); val sb = StringBuilder()
                sb.appendLine("=== localStorage ===")
                j.optJSONObject("l")?.keys()?.forEach { k0 -> val k = k0 as String; sb.appendLine("$k: ${j.optJSONObject("l")?.optString(k,"")?.take(100)}") }
                sb.appendLine("\n=== sessionStorage ===")
                j.optJSONObject("s")?.keys()?.forEach { k0 -> val k = k0 as String; sb.appendLine("$k: ${j.optJSONObject("s")?.optString(k,"")?.take(100)}") }
                tvRes.text = sb
            } catch(_:Exception){tvRes.text=clean} }
        }
        inner.addView(buildActionBtn("🗑 Clear localStorage", Color.parseColor("#EF4444")) {
            webView.evaluateJavascript("localStorage.clear();void 0", null)
            toast("localStorage cleared")
        })
        sv.addView(inner); return sv
    }

    private fun buildCssView(): View {
        val sv = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        inner.addView(buildSectionHeader("🎨 CSS Injector"))
        val et = buildEditText("/* CSS here */\nbody { background: #000 !important; }").apply { minLines = 5 }
        inner.addView(et)
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(buildActionBtn("▶ Inject", ACCENT) {
            val css = et.text.toString().replace("`","\\`")
            webView.evaluateJavascript("""(function(){var el=document.getElementById('__sb_css');if(!el){el=document.createElement('style');el.id='__sb_css';document.head.appendChild(el);}el.textContent=`$css`;return 'ok';})()""", null)
            toast("CSS injected")
        })
        btnRow.addView(buildActionBtn("✖ Remove", Color.parseColor("#EF4444")) {
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
                setPadding(dp(4), dp(3), dp(4), dp(3))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 1 }
                setOnClickListener { showRequestDetail(req) }
            }
            row.addView(typeBadge(req.tag, typeColor(req.tag)).apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(16)) })
            row.addView(buildWaterfallBar(rel, 0.06f, typeColor(req.tag)).apply { layoutParams = LinearLayout.LayoutParams(0, dp(12), 1f) })
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
        val etHost = buildEditText("Proxy host (vd: 192.168.1.100)").apply { setText(curH) }
        val etPort = buildEditText("Port (vd: 8888)").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(curP)
        }
        inner.addView(etHost); inner.addView(etPort)
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(buildActionBtn("✅ Set", ACCENT) {
            val h = etHost.text.toString().trim(); val p = etPort.text.toString().trim().toIntOrNull() ?: 0
            activity.setHttpProxy(h, p); showTab(9)
        })
        btnRow.addView(buildActionBtn("🗑 Clear", Color.parseColor("#EF4444")) { activity.setHttpProxy("", 0); showTab(9) })
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
        if (req.headers.isNotEmpty()) {
            inner.addView(buildSectionHeader("REQUEST HEADERS"))
            req.headers.entries.forEach { (k,v) -> inner.addView(buildMonoTv("$k: ${v.take(200)}", TEXT_SEC, 9f)) }
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
        "HLS","M3U8","M3U9" -> C_HLS
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

    private fun leftBorder(borderColor: Int, bgColor: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(bgColor)
            // Left border via stroke on left side not directly supported
            // We use a simple background and handle left stripe separately
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
        this.hint = hint; textSize = 11f; setTextColor(TEXT_PRI)
        setHintTextColor(TEXT_DIM); background = roundRect(BG_CARD, 6f)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
    }

    private fun buildActionBtn(label: String, color: Int, action: (() -> Unit)? = null) = TextView(context).apply {
        text = label; textSize = 10.5f
        setTextColor(if (isColorDark(color)) Color.WHITE else Color.BLACK)
        background = roundRect(color, 5f)
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

    private fun aesDecrypt(input: String, keyInput: String): String {
        val parts = input.trim().split(":")
        if (parts.size < 2) return "Format: IV_HEX:CIPHERTEXT_HEX"
        return try {
            val ivHex  = parts[0]; val cipherHex = parts.drop(1).joinToString("")
            val keyB   = if (keyInput.length in listOf(32,48,64) && keyInput.all { it.isLetterOrDigit() })
                hexToBytes(keyInput) else keyInput.toByteArray().let { when { it.size<=16->it.copyOf(16); it.size<=24->it.copyOf(24); else->it.copyOf(32) } }
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyB,"AES"), javax.crypto.spec.IvParameterSpec(hexToBytes(ivHex)))
            String(cipher.doFinal(hexToBytes(cipherHex)), Charsets.UTF_8)
        } catch (e: Exception) { "Error: ${e.message}" }
    }

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
        visibility = View.VISIBLE
        ObjectAnimator.ofFloat(getChildAt(1), "translationX", getChildAt(1).width.toFloat(), 0f)
            .apply { duration = 280; interpolator = android.view.animation.DecelerateInterpolator(); start() }
        refresh()
    }

    fun hide() {
        val panel = getChildAt(1) ?: return
        ObjectAnimator.ofFloat(panel, "translationX", 0f, panel.width.toFloat())
            .apply {
                duration = 220; interpolator = android.view.animation.AccelerateInterpolator()
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        visibility = View.GONE
                    }
                }); start()
            }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); scope.cancel() }
}
