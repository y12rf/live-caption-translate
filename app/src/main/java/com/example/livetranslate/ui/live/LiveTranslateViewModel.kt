package com.example.livetranslate.ui.live

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.livetranslate.data.OrphanRecordingDetector
import com.example.livetranslate.data.history.ExportTextMode
import com.example.livetranslate.data.settings.SettingsRepository
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.di.AppContainer
import com.example.livetranslate.domain.LiveSessionUiState
import com.example.livetranslate.domain.ReprocessEngine
import com.example.livetranslate.domain.ReprocessTitle
import com.example.livetranslate.domain.ReprocessUiState
import com.example.livetranslate.domain.SessionController
import com.example.livetranslate.domain.model.AudioSourceType
import com.example.livetranslate.domain.model.SessionPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LiveTranslateViewModel(
    app: Application,
    private val controller: SessionController,
    private val reprocess: ReprocessEngine,
    settingsRepo: SettingsRepository
) : AndroidViewModel(app) {

    val state: StateFlow<LiveSessionUiState> = controller.state
    val reprocessState: StateFlow<ReprocessUiState> = reprocess.state
    val settings: StateFlow<UserSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

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

    fun start() {
        // Dismiss recovery UI so a new session is not confused with an orphan prompt.
        _orphanPrompt.value = null
        controller.start()
    }

    /**
     * File source: live recording pipeline with timeline offsets
     * (FFmpeg → energy VAD → same ASR/LLM queues as mic/internal).
     * Orphan recovery still uses [ReprocessEngine] (scheme C′).
     */
    fun startFromFile(uri: Uri) {
        _orphanPrompt.value = null
        controller.setAudioSource(AudioSourceType.File)
        controller.startFromFile(uri)
    }

    fun pause() = controller.pause()

    fun stop(drain: Boolean = true) {
        // File import / offline reprocess: cancel pipeline instead of live stop.
        if (reprocess.isBusy) {
            reprocess.cancel()
            return
        }
        controller.stop(drain)
    }

    fun cancelReprocess() = reprocess.cancel()
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
            // Never prompt while live/file session is running or WAV is still open.
            if (controller.isSessionBusy() ||
                controller.state.value.phase != SessionPhase.Idle ||
                controller.isSessionRecordingActive() ||
                reprocess.isBusy
            ) {
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
            val next = orphans.firstOrNull { o ->
                o.path !in laterSkip &&
                    o.path !in exclude &&
                    // Ignore brand-new empty-ish headers that may race with Start
                    o.lengthBytes > 44L
            } ?: run {
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
                reprocess = container.reprocessPipeline,
                settingsRepo = container.settingsRepository
            ) as T
        }
    }
}
