package com.example.livetranslate.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.livetranslate.data.history.ExportTextMode
import com.example.livetranslate.data.history.HistoryExport
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.history.SegmentEntity
import com.example.livetranslate.data.history.SessionDetail
import com.example.livetranslate.data.history.SessionSummary
import com.example.livetranslate.di.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repo: HistoryRepository
) : ViewModel() {

    private val _listQuery = MutableStateFlow("")
    val listQuery: StateFlow<String> = _listQuery.asStateFlow()

    val sessions: StateFlow<List<SessionSummary>> = _listQuery
        .debounce(200)
        .flatMapLatest { q -> repo.observeSessionsSearch(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _detail = MutableStateFlow<SessionDetail?>(null)
    val detail: StateFlow<SessionDetail?> = _detail.asStateFlow()

    private val _detailQuery = MutableStateFlow("")
    val detailQuery: StateFlow<String> = _detailQuery.asStateFlow()

    /** Segments filtered by detail search box. */
    val filteredSegments: StateFlow<List<SegmentEntity>> =
        combine(_detail, _detailQuery) { d, q ->
            if (d == null) emptyList()
            else repo.filterSegments(d, q)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setListQuery(q: String) {
        _listQuery.value = q
    }

    fun setDetailQuery(q: String) {
        _detailQuery.value = q
    }

    fun loadDetail(id: Long) {
        viewModelScope.launch {
            _detail.value = repo.getSessionDetail(id)
            _detailQuery.value = ""
        }
    }

    fun prepareSrt(mode: ExportTextMode = ExportTextMode.Both): String? {
        val d = _detail.value ?: return null
        if (d.segments.isEmpty()) return null
        return HistoryExport.formatSrt(d, mode).ifBlank { null }
    }

    fun preparePlain(mode: ExportTextMode): String? {
        val d = _detail.value ?: return null
        if (d.segments.isEmpty()) return null
        return HistoryExport.formatPlainText(d, mode).ifBlank { null }
    }

    fun exportBaseName(suffix: String = ""): String {
        val d = _detail.value
        val id = d?.session?.id ?: 0L
        val t = d?.session?.createdAt ?: System.currentTimeMillis()
        return if (suffix.isEmpty()) "session_${id}_$t" else "session_${id}_${t}_$suffix"
    }

    fun audioFile(): java.io.File? {
        val d = _detail.value ?: return null
        return repo.audioFileFor(d)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(container.historyRepository) as T
        }
    }
}
