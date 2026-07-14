package com.example.livetranslate.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.history.SessionDetail
import com.example.livetranslate.data.history.SessionSummary
import com.example.livetranslate.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val repo: HistoryRepository
) : ViewModel() {

    val sessions: StateFlow<List<SessionSummary>> = repo.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _detail = MutableStateFlow<SessionDetail?>(null)
    val detail: StateFlow<SessionDetail?> = _detail.asStateFlow()

    private val _exportText = MutableStateFlow<String?>(null)
    val exportText: StateFlow<String?> = _exportText.asStateFlow()

    fun loadDetail(id: Long) {
        viewModelScope.launch {
            _detail.value = repo.getSessionDetail(id)
            _exportText.value = null
        }
    }

    fun prepareExport(): String? {
        val d = _detail.value ?: return null
        val md = repo.formatMarkdown(d)
        _exportText.value = md
        return md
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(container.historyRepository) as T
        }
    }
}
