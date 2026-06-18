package com.aho.streambrowser.model

class TabManager {
    private val _tabs    = mutableListOf(TabModel(url = "about:blank", title = "New Tab"))
    private var _current = 0

    val tabs:       List<TabModel> get() = _tabs.toList()
    val current:    TabModel       get() = _tabs[_current]
    val currentIdx: Int            get() = _current
    val count:      Int            get() = _tabs.size

    fun newTab(url: String = "about:blank"): TabModel {
        val tab = TabModel(url = url, title = "New Tab")
        _tabs.add(tab)
        _current = _tabs.lastIndex
        return tab
    }

    fun switchTo(idx: Int): TabModel {
        _current = idx.coerceIn(0, _tabs.lastIndex)
        return _tabs[_current]
    }

    fun close(idx: Int) {
        if (_tabs.size <= 1) { _tabs[0] = TabModel(); _current = 0; return }
        _tabs.removeAt(idx)
        if (_current >= _tabs.size) _current = _tabs.lastIndex
    }

    fun updateCurrent(url: String, title: String, scrollY: Int = 0) {
        _tabs[_current] = _tabs[_current].copy(url = url, title = title, scrollY = scrollY)
    }
}
