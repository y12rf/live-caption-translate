package com.example.livetranslate.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.livetranslate.data.audio.SessionAudioPlayer
import com.example.livetranslate.data.audio.SessionAudioRecorder
import com.example.livetranslate.data.history.ExportTextMode
import com.example.livetranslate.data.history.HistoryExport
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.history.SegmentEntity
import com.example.livetranslate.data.history.SessionDetail
import com.example.livetranslate.data.history.SessionSummary
import com.example.livetranslate.di.AppContainer
import com.example.livetranslate.domain.OfflineReprocessPipeline
import com.example.livetranslate.domain.ReprocessUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repo: HistoryRepository,
    private val reprocess: OfflineReprocessPipeline
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

    val reprocessState: StateFlow<ReprocessUiState> = reprocess.state

    private val player = SessionAudioPlayer()

    data class PlaybackUi(
        val hasTimeline: Boolean = false,
        val prepared: Boolean = false,
        val playing: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val activeSegmentId: Long? = null
    )

    private val _playback = MutableStateFlow(PlaybackUi())
    val playback: StateFlow<PlaybackUi> = _playback.asStateFlow()

    private var tickJob: Job? = null
    private var loadedSessionId: Long = -1L

    fun setListQuery(q: String) {
        _listQuery.value = q
    }

    fun setDetailQuery(q: String) {
        _detailQuery.value = q
    }

    fun loadDetail(id: Long) {
        viewModelScope.launch {
            if (loadedSessionId != id) {
                releasePlayer()
            }
            loadedSessionId = id
            val d = repo.getSessionDetail(id)
            _detail.value = d
            _detailQuery.value = ""
            preparePlayerIfNeeded(d)
        }
    }

    fun startReprocessCurrent() {
        val d = _detail.value ?: return
        val path = d.session.audioPath ?: return
        if (SessionAudioRecorder.fileForPath(path) == null) return
        reprocess.start(path, d.session.previewZh)
    }

    fun cancelReprocess() = reprocess.cancel()

    fun clearReprocessError() = reprocess.clearError()

    fun clearReprocessSaved() = reprocess.clearLastSaved()

    fun canReprocess(): Boolean {
        val d = _detail.value ?: return false
        val path = d.session.audioPath
        return path != null && SessionAudioRecorder.fileForPath(path) != null && !reprocess.isBusy
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

    fun togglePlayPause() {
        if (!_playback.value.prepared) return
        if (player.isPlaying) {
            player.pause()
            stopTicker()
            _playback.update { it.copy(playing = false, positionMs = player.positionMs) }
        } else {
            player.play()
            startTicker()
            _playback.update { it.copy(playing = true) }
        }
    }

    fun seekTo(ms: Int) {
        if (!_playback.value.prepared) return
        player.seekTo(ms)
        val pos = player.positionMs
        _playback.update {
            it.copy(
                positionMs = pos,
                activeSegmentId = activeSegmentIdAt(pos.toLong())
            )
        }
    }

    fun seekToSegment(seg: SegmentEntity) {
        if (!_playback.value.hasTimeline) return
        if (!_playback.value.prepared) return
        seekTo(seg.offsetMs.toInt().coerceAtLeast(0))
        if (!player.isPlaying) {
            player.play()
            startTicker()
            _playback.update { it.copy(playing = true) }
        }
    }

    fun onLeaveDetail() {
        releasePlayer()
        loadedSessionId = -1L
    }

    private fun preparePlayerIfNeeded(d: SessionDetail?) {
        if (d == null) {
            releasePlayer()
            return
        }
        val hasTimeline = d.segments.any { it.offsetMs > 0L }
        val file = repo.audioFileFor(d)
        if (!hasTimeline || file == null) {
            releasePlayer()
            _playback.value = PlaybackUi(hasTimeline = false)
            return
        }
        val ok = player.prepare(file)
        _playback.value = PlaybackUi(
            hasTimeline = true,
            prepared = ok,
            playing = false,
            positionMs = 0,
            durationMs = if (ok) player.durationMs else 0,
            activeSegmentId = null
        )
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                delay(250)
                if (!player.isPlaying) {
                    _playback.update {
                        it.copy(
                            playing = false,
                            positionMs = player.positionMs,
                            activeSegmentId = activeSegmentIdAt(player.positionMs.toLong())
                        )
                    }
                    break
                }
                val pos = player.positionMs
                _playback.update {
                    it.copy(
                        playing = true,
                        positionMs = pos,
                        durationMs = player.durationMs,
                        activeSegmentId = activeSegmentIdAt(pos.toLong())
                    )
                }
            }
        }
    }

    private fun stopTicker() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun activeSegmentIdAt(posMs: Long): Long? {
        val ordered = _detail.value?.segments.orEmpty().sortedBy { it.offsetMs }
        if (ordered.isEmpty()) return null
        var active: SegmentEntity? = ordered.first()
        for (i in ordered.indices) {
            val start = ordered[i].offsetMs
            val end = ordered.getOrNull(i + 1)?.offsetMs ?: Long.MAX_VALUE
            if (posMs >= start && posMs < end) {
                return ordered[i].id
            }
            if (posMs >= start) active = ordered[i]
        }
        return active?.id
    }

    private fun releasePlayer() {
        stopTicker()
        player.release()
        _playback.value = PlaybackUi()
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(
                repo = container.historyRepository,
                reprocess = container.reprocessPipeline
            ) as T
        }
    }
}
