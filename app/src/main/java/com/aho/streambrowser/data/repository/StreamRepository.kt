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
        // Keep the newest 500 exactly. The old implementation only inspected 100 rows and
        // could leave unbounded history behind after a long session.
        if (dao.count() > HISTORY_LIMIT) dao.pruneToLimit(HISTORY_LIMIT)
    }

    suspend fun getRecent(): List<StreamHistoryEntity> = dao.getRecent()

    private companion object { const val HISTORY_LIMIT = 500 }
}
