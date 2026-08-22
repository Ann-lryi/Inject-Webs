package com.aho.streambrowser.util

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object PerfMonitor {
    private const val TAG = "PerfMonitor"
    private val timings = ConcurrentHashMap<String, RunningStat>()
    private val totalFrames = AtomicLong(0)
    private val jankyFrames = AtomicLong(0)
    private val frameCountAtLastReport = AtomicLong(0)
    private val jankAtLastReport = AtomicLong(0)

    private val enabled: Boolean get() = try {
        Class.forName("com.aho.streambrowser.BuildConfig")
            .getField("DEBUG").getBoolean(null)
    } catch (_: Throwable) { false }

    private data class RunningStat(var count: Long = 0, var totalMs: Long = 0, var maxMs: Long = 0)

    inline fun <T> time(label: String, slowThresholdMs: Long = 16L, block: () -> T): T {
        if (!enabled) return block()
        val start = System.nanoTime()
        try { return block() } finally {
            val ms = (System.nanoTime() - start) / 1_000_000L
            record(label, ms, slowThresholdMs)
        }
    }

    fun record(label: String, ms: Long, slowThresholdMs: Long = 16L) {
        if (!enabled) return
        val stat = timings.getOrPut(label) { RunningStat() }
        synchronized(stat) { stat.count++; stat.totalMs += ms; if (ms > stat.maxMs) stat.maxMs = ms }
        if (ms >= slowThresholdMs) Log.w(TAG, "slow: $label took ${ms}ms")
    }

    fun recordFrame(vsyncTimeNanos: Long, doFrameStartNanos: Long) {
        if (!enabled) return
        totalFrames.incrementAndGet()
        if ((doFrameStartNanos - vsyncTimeNanos) / 1_000_000L > 16) jankyFrames.incrementAndGet()
    }

    fun dump(): String {
        if (!enabled) return "PerfMonitor disabled (release build)"
        val sb = StringBuilder("=== PerfMonitor ===\n")
        synchronized(timings) {
            timings.entries.sortedByDescending { it.value.totalMs }.forEach { (k, v) ->
                val avg = if (v.count > 0) v.totalMs / v.count else 0
                sb.append(String.format("%-28s cnt=%-6d avg=%-4d max=%-4d total=%dms\n",
                    k, v.count, avg, v.maxMs, v.totalMs))
            }
        }
        val total = totalFrames.get(); val jank = jankyFrames.get()
        if (total > 0) {
            val pct = jank * 100 / total
            val dTotal = total - frameCountAtLastReport.get()
            val dJank = jank - jankAtLastReport.get()
            val dPct = if (dTotal > 0) dJank * 100 / dTotal else 0
            sb.append(String.format("frames: %d total, %d janky (%d%%) | last window: %d frames, %d janky (%d%%)\n",
                total, jank, pct, dTotal, dJank, dPct))
        }
        return sb.toString()
    }

    fun markWindow() { frameCountAtLastReport.set(totalFrames.get()); jankAtLastReport.set(jankyFrames.get()) }
    fun clear() { timings.clear(); totalFrames.set(0); jankyFrames.set(0); markWindow() }
}
