package com.aho.streambrowser.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aho.streambrowser.data.db.BookmarkEntity
import com.aho.streambrowser.data.db.StreamHistoryEntity
import com.aho.streambrowser.data.repository.BookmarkRepository
import com.aho.streambrowser.data.repository.StreamRepository
import com.aho.streambrowser.model.StreamItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val bookmarkRepo: BookmarkRepository,
    private val streamRepo:   StreamRepository
) : ViewModel() {

    // H2: StateFlow — reactive bookmark/history/stream state
    val bookmarks: StateFlow<List<BookmarkEntity>> = bookmarkRepo.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<BookmarkEntity>> = bookmarkRepo.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val streamHistory: StateFlow<List<StreamHistoryEntity>> = streamRepo.streamHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Current page state
    private var _currentUrl   = ""
    private var _currentTitle = ""
    val currentUrl:   String get() = _currentUrl
    val currentTitle: String get() = _currentTitle

    fun updatePage(url: String, title: String) {
        _currentUrl   = url
        _currentTitle = title
        if (url.isNotBlank() && url != "about:blank") {
            viewModelScope.launch { bookmarkRepo.addHistory(url, title) }
        }
    }

    // ── Bookmark actions ──────────────────────────────────────────────────────
    fun addBookmark(url: String, title: String) =
        viewModelScope.launch { bookmarkRepo.addBookmark(url, title) }

    fun removeBookmark(url: String) =
        viewModelScope.launch { bookmarkRepo.removeBookmark(url) }

    fun clearHistory() =
        viewModelScope.launch { bookmarkRepo.clearHistory() }

    suspend fun isBookmarked(url: String) = bookmarkRepo.isBookmarked(url)

    // ── Stream actions ────────────────────────────────────────────────────────
    fun saveStream(item: StreamItem) =
        viewModelScope.launch { streamRepo.saveStream(item) }

    suspend fun getRecentStreams(): List<StreamHistoryEntity> = streamRepo.getRecent()
}
