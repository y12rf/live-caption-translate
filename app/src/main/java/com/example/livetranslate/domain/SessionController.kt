package com.example.livetranslate.domain

import android.content.Context
import android.media.projection.MediaProjection
import com.example.livetranslate.data.asr.AsrClient
import com.example.livetranslate.data.audio.AudioCapture
import com.example.livetranslate.data.history.HistoryRepository
import com.example.livetranslate.data.llm.LlmClient
import com.example.livetranslate.data.settings.SettingsRepository
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.model.AudioSourceType
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
    history: HistoryRepository
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
        history = history
    )

    val state: StateFlow<LiveSessionUiState> = orchestrator.state

    fun updateCachedSettings(s: UserSettings) {
        latestSettings = s
    }

    fun setMediaProjection(projection: MediaProjection?) {
        audio.mediaProjection = projection
    }

    fun setAudioSource(type: AudioSourceType) = orchestrator.setAudioSource(type)
    fun setOverlayEnabled(enabled: Boolean) = orchestrator.setOverlayEnabled(enabled)
    fun start() = orchestrator.start()
    fun pause() = orchestrator.pause()
    fun stop() = orchestrator.stop()
    fun retry() = orchestrator.retryLastFailed()
    fun exportMarkdown(): String? = orchestrator.exportMarkdown()
}
