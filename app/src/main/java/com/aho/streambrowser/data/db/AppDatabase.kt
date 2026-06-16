package com.aho.streambrowser.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BookmarkEntity::class, StreamHistoryEntity::class],
    version  = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao():     BookmarkDao
    abstract fun streamHistoryDao(): StreamHistoryDao
}
