package com.example.livetranslate.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.livetranslate.data.audio.AudioCapture
import com.example.livetranslate.di.AppContainer
import com.example.livetranslate.domain.LiveSessionUiState
import com.example.livetranslate.domain.SessionOrchestrator
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LiveTranslateViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val audio = AudioCapture(
        scope = viewModelScope,
        settings = {
            // Blocking read avoided: use last known or defaults via runBlocking is bad.
            // AudioCapture reads settings at start() time via this lambda on IO thread.
            // We use a cached settings holder updated from Flow.
            cachedSettings
        }
    )

    @Volatile
    private var cachedSettings = com.example.livetranslate.data.settings.UserSettings()

    private val orchestrator = SessionOrchestrator(
        scope = viewModelScope,
        audio = audio,
        asr = container.asrClient,
        llm = container.llmClient,
        settingsRepo = container.settingsRepository,
        history = container.historyRepository
    )

    val state: StateFlow<LiveSessionUiState> = orchestrator.state

    init {
        viewModelScope.launch {
            container.settingsRepository.settings.collect { cachedSettings = it }
        }
    }

    fun start() = orchestrator.start()
    fun pause() = orchestrator.pause()
    fun stop() = orchestrator.stop()
    fun retry() = orchestrator.retryLastFailed()

    suspend fun ensureSettingsLoaded() {
        cachedSettings = container.settingsRepository.settings.first()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LiveTranslateViewModel(container) as T
        }
    }
}
