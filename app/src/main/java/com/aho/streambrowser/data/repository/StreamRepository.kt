package com.aho.streambrowser.data.repository

import com.aho.streambrowser.data.db.StreamHistoryDao
import com.aho.streambrowser.data.db.StreamHistoryEntity
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.model.StreamType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamRepository @Inject constructor(
    private val dao: StreamHistoryDao
) {
    val streamHistory: Flow<List<StreamHistoryEntity>> = dao.observeAll()

    suspend fun saveStream(item: StreamItem) {
        dao.insert(StreamHistoryEntity(
            url        = item.url,
            referer    = item.referer,
            streamType = item.type.name,
            source     = item.source
        ))
        // Auto-cleanup: keep last 500
        val count = dao.count()
        if (count > 500) {
            val old = dao.getRecent().lastOrNull()?.timestamp ?: return
            dao.deleteOlderThan(old)
        }
    }

    suspend fun getRecent(): List<StreamHistoryEntity> = dao.getRecent()
}
