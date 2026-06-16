package com.aho.streambrowser.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val url:        String,
    val title:      String,
    val timestamp:  Long    = System.currentTimeMillis(),
    val isBookmark: Boolean = true   // false = history
)
