package com.example.livetranslate.domain

import android.content.Context
import android.media.projection.MediaProjection
import android.net.Uri
import com.example.livetranslate.data.asr.AsrClient
import com.example.livetranslate.data.audio.AudioCapture
import com.example.livetranslate.data.history.ExportTextMode
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.llm.LlmClient
import com.example.livetranslate.data.network.NetworkMonitor
import com.example.livetranslate.data.settings.SettingsRepository
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.model.AudioSourceType
import com.example.livetranslate.domain.model.SessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * App-scoped session owner so recording survives Activity and is driven by
 * UI, foreground notification, and overlay together.
 */
class SessionController(
    appContext: Context,
    private val appScope: CoroutineScope,
    asr: AsrClient,
    llm: LlmClient,
    settingsRepo: SettingsRepository,
    history: HistoryRepository,
    network: NetworkMonitor
) {
    @Volatile
    private var latestSettings = UserSettings()

    val audio = AudioCapture(
        scope = appScope,
        settings = { latestSettings },
        appContext = appContext
    )

    private val orchestrator = SessionOrchestrator(
        scope = appScope,
        audio = audio,
        asr = asr,
        llm = llm,
        settingsRepo = settingsRepo,
        history = history,
        network = network,
        appContext = appContext
    )

    val state: StateFlow<LiveSessionUiState> = orchestrator.state

    /** True while a live/file session is active (not Idle). */
    fun isSessionBusy(): Boolean = state.value.phase != SessionPhase.Idle

    /**
     * Path of the session WAV currently being written or held by file import.
     * Used to exclude in-progress recordings from orphan recovery prompts.
     */
    fun activeSessionAudioPath(): String? = audio.activeSessionAudioPath()

    fun updateCachedSettings(s: UserSettings) {
        latestSettings = s
    }

    fun setMediaProjection(projection: MediaProjection?) {
        audio.mediaProjection = projection
    }

    fun setAudioSource(type: AudioSourceType) = orchestrator.setAudioSource(type)
    fun setOverlayEnabled(enabled: Boolean) = orchestrator.setOverlayEnabled(enabled)
    fun start() = orchestrator.start()
    fun startFromFile(uri: Uri) = orchestrator.startFromFile(uri)
    fun pause() = orchestrator.pause()
    fun stop(drain: Boolean = true) = orchestrator.stop(drain)
    fun retry() = orchestrator.retryLastFailed()
    fun retryAllFailed() = orchestrator.retryAllFailed()
    fun dismissFailures() = orchestrator.dismissFailures()
    fun retrySave() = orchestrator.retrySave()
    fun exportMarkdown(): String? = orchestrator.exportMarkdown()
    fun exportSrt(mode: ExportTextMode = ExportTextMode.Both): String? =
        orchestrator.exportSrt(mode)

    fun exportPlain(mode: ExportTextMode): String? = orchestrator.exportPlain(mode)

    fun clearTranslationCache() = orchestrator.clearTranslationCache()

    fun translationCacheSize(): Int = orchestrator.translationCacheSize()

    fun reportCaptureError(message: String) = orchestrator.reportCaptureError(message)

    /** Observe capture-thread failures (e.g. internal audio init on OEM). */
    fun observeCaptureErrors() {
        appScope.launch {
            audio.captureError.collect { msg ->
                if (msg != null) {
                    orchestrator.reportCaptureError(msg)
                }
            }
        }
    }
}
