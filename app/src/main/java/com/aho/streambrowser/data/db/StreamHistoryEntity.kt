package com.aho.streambrowser.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stream_history")
data class StreamHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url:       String,
    val referer:   String,
    val streamType:String,
    val source:    String,
    val timestamp: Long = System.currentTimeMillis()
)
