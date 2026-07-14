package com.aho.streambrowser.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal local crash logger.
 *
 * No Crashlytics/Firebase/Sentry — those need an external account + project setup that can't be
 * done from here. This instead catches uncaught exceptions, writes a timestamped stack trace to
 * app-internal storage (no permission needed), then hands off to whatever the previous default
 * handler was — the crash still surfaces and the app still closes exactly as it would have
 * before. This only ADDS a saved log; it never swallows or hides a crash.
 *
 * Fits the existing workflow: [listLogs]/[latestLogText] make it easy to grab the most recent
 * crash and paste it back for debugging, instead of having to manually reproduce and describe it.
 */
object CrashHandler {
    private const val DIR_NAME = "crash_logs"
    private const val MAX_LOGS = 20

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeLog(appContext, thread, throwable)
            } catch (_: Exception) {
                // Never let the logger itself block the real crash from surfacing.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun writeLog(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "crash_$stamp.txt")
        @Suppress("DEPRECATION")
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
        file.writeText(buildString {
            appendLine("Time: ${Date()}")
            appendLine("Thread: ${thread.name}")
            appendLine("Version: $versionName")
            appendLine()
            appendLine(android.util.Log.getStackTraceString(throwable))
        })
        // Keep only the most recent MAX_LOGS files — this can't grow unbounded over a long-lived install.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_LOGS)?.forEach { it.delete() }
    }

    /** Saved crash logs, most recent first. */
    fun listLogs(context: Context): List<File> =
        File(context.filesDir, DIR_NAME).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Text of the most recent crash log, or null if there are none yet. */
    fun latestLogText(context: Context): String? =
        listLogs(context).firstOrNull()?.let { runCatching { it.readText() }.getOrNull() }
}
