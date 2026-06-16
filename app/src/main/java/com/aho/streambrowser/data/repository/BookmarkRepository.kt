package com.aho.streambrowser.data.repository

import com.aho.streambrowser.data.db.BookmarkDao
import com.aho.streambrowser.data.db.BookmarkEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepository @Inject constructor(
    private val dao: BookmarkDao
) {
    val bookmarks: Flow<List<BookmarkEntity>> = dao.observeBookmarks()
    val history:   Flow<List<BookmarkEntity>> = dao.observeHistory()

    suspend fun addBookmark(url: String, title: String) =
        dao.upsert(BookmarkEntity(url = url, title = title, isBookmark = true))

    suspend fun removeBookmark(url: String) = dao.delete(url)

    suspend fun addHistory(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        dao.upsert(BookmarkEntity(url = url, title = title, isBookmark = false))
    }

    suspend fun clearHistory()          = dao.clearHistory()
    suspend fun isBookmarked(url: String) = dao.isBookmarked(url) > 0
    suspend fun getAll()                = dao.getBookmarks() + dao.getHistory()
}
