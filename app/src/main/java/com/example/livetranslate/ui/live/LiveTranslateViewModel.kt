package com.example.livetranslate.ui.live

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.livetranslate.data.OrphanRecordingDetector
import com.example.livetranslate.data.history.ExportTextMode
import com.example.livetranslate.di.AppContainer
import com.example.livetranslate.domain.LiveSessionUiState
import com.example.livetranslate.domain.OfflineReprocessPipeline
import com.example.livetranslate.domain.ReprocessTitle
import com.example.livetranslate.domain.ReprocessUiState
import com.example.livetranslate.domain.SessionController
import com.example.livetranslate.domain.model.AudioSourceType
import com.example.livetranslate.domain.model.SessionPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LiveTranslateViewModel(
    app: Application,
    private val controller: SessionController,
    private val reprocess: OfflineReprocessPipeline
) : AndroidViewModel(app) {

    val state: StateFlow<LiveSessionUiState> = controller.state
    val reprocessState: StateFlow<ReprocessUiState> = reprocess.state

    data class OrphanPrompt(
        val path: String,
        val label: String
    )

    private val _orphanPrompt = MutableStateFlow<OrphanPrompt?>(null)
    val orphanPrompt: StateFlow<OrphanPrompt?> = _orphanPrompt.asStateFlow()

    /** Paths dismissed with "later" for this process lifetime. */
    private val laterSkip = linkedSetOf<String>()
    private var scannedOnce = false

    fun setAudioSource(type: AudioSourceType) = controller.setAudioSource(type)
    fun setOverlayEnabled(enabled: Boolean) = controller.setOverlayEnabled(enabled)
    fun start() = controller.start()
    fun startFromFile(uri: Uri) = controller.startFromFile(uri)
    fun pause() = controller.pause()
    fun stop(drain: Boolean = true) = controller.stop(drain)
    fun retry() = controller.retry()
    fun retryAllFailed() = controller.retryAllFailed()
    fun dismissFailures() = controller.dismissFailures()
    fun retrySave() = controller.retrySave()
    fun exportMarkdown(): String? = controller.exportMarkdown()
    fun exportSrt(mode: ExportTextMode = ExportTextMode.Both): String? =
        controller.exportSrt(mode)

    fun exportPlain(mode: ExportTextMode): String? = controller.exportPlain(mode)

    /**
     * Call on Live screen resume / first composition.
     * Skips prompts while a live session is running, and excludes the in-progress
     * session WAV (+ reprocess target) so continuous recordings are not mistaken
     * for interrupted orphan tasks.
     */
    fun checkOrphans() {
        viewModelScope.launch {
            // Do not interrupt an active live/file session with recovery UI.
            if (controller.isSessionBusy() ||
                controller.state.value.phase != SessionPhase.Idle
            ) {
                _orphanPrompt.value = null
                return@launch
            }
            if (reprocess.isBusy) {
                _orphanPrompt.value = null
                return@launch
            }
            val exclude = buildSet {
                controller.activeSessionAudioPath()?.let { add(it) }
                reprocess.state.value.activeAudioPath?.let { add(it) }
            }
            val orphans = OrphanRecordingDetector.findOrphans(
                context = getApplication(),
                excludePaths = exclude
            )
            val next = orphans.firstOrNull { it.path !in laterSkip && it.path !in exclude }
                ?: run {
                    _orphanPrompt.value = null
                    return@launch
                }
            _orphanPrompt.value = OrphanPrompt(
                path = next.path,
                label = next.file.name
            )
            scannedOnce = true
        }
    }

    fun orphanLater() {
        val p = _orphanPrompt.value?.path
        if (p != null) laterSkip.add(p)
        _orphanPrompt.value = null
    }

    fun orphanDiscard() {
        val p = _orphanPrompt.value?.path ?: return
        OrphanRecordingDetector.delete(p)
        laterSkip.remove(p)
        _orphanPrompt.value = null
        // If more orphans remain, surface next
        checkOrphans()
    }

    fun orphanReprocess() {
        val p = _orphanPrompt.value?.path ?: return
        _orphanPrompt.value = null
        if (!OrphanRecordingDetector.isPlayable(java.io.File(p))) {
            // Leave error on reprocess state via start validation
        }
        reprocess.start(p, ReprocessTitle.ORPHAN_BASE)
    }

    fun clearReprocessError() = reprocess.clearError()
    fun clearReprocessSaved() = reprocess.clearLastSaved()

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            val app = container.appContext as? Application
                ?: error("AppContainer requires Application context")
            return LiveTranslateViewModel(
                app = app,
                controller = container.sessionController,
                reprocess = container.reprocessPipeline
            ) as T
        }
    }
}
