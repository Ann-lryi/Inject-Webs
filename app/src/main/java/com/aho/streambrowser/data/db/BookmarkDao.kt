package com.aho.streambrowser.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE isBookmark = 1 ORDER BY timestamp DESC")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE isBookmark = 0 ORDER BY timestamp DESC LIMIT 200")
    fun observeHistory(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE isBookmark = 1 ORDER BY timestamp DESC")
    suspend fun getBookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE isBookmark = 0 ORDER BY timestamp DESC LIMIT 200")
    suspend fun getHistory(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun delete(url: String)

    @Query("DELETE FROM bookmarks WHERE isBookmark = 0")
    suspend fun clearHistory()

    @Query("SELECT COUNT(*) FROM bookmarks WHERE url = :url AND isBookmark = 1")
    suspend fun isBookmarked(url: String): Int
}
