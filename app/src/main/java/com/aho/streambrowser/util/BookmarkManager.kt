package com.aho.streambrowser.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BookmarkEntry(
    val url: String,
    val title: String,
    val isBookmark: Boolean,   // false = history
    val timestamp: Long = System.currentTimeMillis()
)

object BookmarkManager {

    private const val PREF   = "sb_bookmarks"
    private const val KEY_BM = "bookmarks"
    private const val KEY_HI = "history"
    private const val MAX_HISTORY = 200

    // ── Bookmarks ─────────────────────────────────────────────────────────────
    fun addBookmark(ctx: Context, url: String, title: String) {
        val list = getBookmarks(ctx).toMutableList()
        if (list.none { it.url == url }) {
            list.add(0, BookmarkEntry(url, title, true))
            save(ctx, KEY_BM, list)
        }
    }

    fun removeBookmark(ctx: Context, url: String) {
        save(ctx, KEY_BM, getBookmarks(ctx).filter { it.url != url })
    }

    fun isBookmarked(ctx: Context, url: String) =
        getBookmarks(ctx).any { it.url == url }

    fun getBookmarks(ctx: Context): List<BookmarkEntry> = load(ctx, KEY_BM)

    // ── History ───────────────────────────────────────────────────────────────
    fun addHistory(ctx: Context, url: String, title: String) {
        if (url.startsWith("about:") || url.startsWith("data:")) return
        val list = getHistory(ctx).toMutableList()
        list.removeAll { it.url == url }          // bỏ entry cũ cùng URL
        list.add(0, BookmarkEntry(url, title, false))
        if (list.size > MAX_HISTORY) list.subList(MAX_HISTORY, list.size).clear()
        save(ctx, KEY_HI, list)
    }

    fun clearHistory(ctx: Context) = save(ctx, KEY_HI, emptyList())

    fun getHistory(ctx: Context): List<BookmarkEntry> = load(ctx, KEY_HI)

    // ── Internal ──────────────────────────────────────────────────────────────
    private fun save(ctx: Context, key: String, list: List<BookmarkEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("url", e.url); put("title", e.title); put("ts", e.timestamp)
            })
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(key, arr.toString()).apply()
    }

    private fun load(ctx: Context, key: String): List<BookmarkEntry> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                BookmarkEntry(
                    url       = obj.getString("url"),
                    title     = obj.optString("title", obj.getString("url")),
                    isBookmark= key == KEY_BM,
                    timestamp = obj.optLong("ts", 0L)
                )
            }
        } catch (e: Exception) { emptyList() }
    }
}
