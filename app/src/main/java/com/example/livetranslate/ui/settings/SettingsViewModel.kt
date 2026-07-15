package com.example.livetranslate.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.livetranslate.data.CacheCleaner
import com.example.livetranslate.data.network.ApiLatencyProbe
import com.example.livetranslate.data.settings.SettingsRepository
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.di.AppContainer
import com.example.livetranslate.domain.SessionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val draft: UserSettings = UserSettings(),
    val savedMessage: String? = null,
    val asrLatencyTesting: Boolean = false,
    val llmLatencyTesting: Boolean = false,
    val asrLatencyResult: String? = null,
    val llmLatencyResult: String? = null,
    val cacheBusy: Boolean = false,
    val cacheMessage: String? = null
)

class SettingsViewModel(
    application: Application,
    private val repo: SettingsRepository,
    private val sessionController: SessionController,
    private val latencyProbe: ApiLatencyProbe = ApiLatencyProbe()
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repo.settings.collect { s ->
                _ui.update { it.copy(draft = s) }
            }
        }
    }

    fun updateDraft(transform: (UserSettings) -> UserSettings) {
        _ui.update {
            it.copy(
                draft = transform(it.draft),
                savedMessage = null,
                asrLatencyResult = null,
                llmLatencyResult = null,
                cacheMessage = null
            )
        }
    }

    fun save() {
        viewModelScope.launch {
            val draft = _ui.value.draft
            repo.update { draft }
            _ui.update { it.copy(savedMessage = "Saved") }
        }
    }

    fun testAsrLatency() {
        if (_ui.value.asrLatencyTesting) return
        viewModelScope.launch {
            _ui.update {
                it.copy(asrLatencyTesting = true, asrLatencyResult = "Testing ASR…")
            }
            val draft = _ui.value.draft
            val result = withContext(Dispatchers.IO) {
                latencyProbe.probeAsr(draft)
            }
            _ui.update {
                it.copy(
                    asrLatencyTesting = false,
                    asrLatencyResult = result.summary("ASR") +
                        if (!result.ok) "\n${result.url}" else "\n${result.url}"
                )
            }
        }
    }

    fun testLlmLatency() {
        if (_ui.value.llmLatencyTesting) return
        viewModelScope.launch {
            _ui.update {
                it.copy(llmLatencyTesting = true, llmLatencyResult = "Testing LLM…")
            }
            val draft = _ui.value.draft
            val result = withContext(Dispatchers.IO) {
                latencyProbe.probeLlm(draft)
            }
            _ui.update {
                it.copy(
                    llmLatencyTesting = false,
                    llmLatencyResult = result.summary("LLM") + "\n${result.url}"
                )
            }
        }
    }

    /** Clear in-session translation cache + orphan WAV files (history kept). */
    fun clearCache() {
        if (_ui.value.cacheBusy) return
        viewModelScope.launch {
            _ui.update { it.copy(cacheBusy = true, cacheMessage = null) }
            sessionController.clearTranslationCache()
            val r = withContext(Dispatchers.IO) {
                CacheCleaner.clearOrphanRecordings(getApplication())
            }.copy(translationCacheCleared = true)
            _ui.update {
                it.copy(cacheBusy = false, cacheMessage = r.summaryZh())
            }
        }
    }

    /** Wipe all history sessions and recordings (destructive). */
    fun clearAllHistory() {
        if (_ui.value.cacheBusy) return
        viewModelScope.launch {
            _ui.update { it.copy(cacheBusy = true, cacheMessage = null) }
            sessionController.clearTranslationCache()
            val r = withContext(Dispatchers.IO) {
                CacheCleaner.clearAllHistoryAndRecordings(getApplication())
            }.copy(translationCacheCleared = true)
            _ui.update {
                it.copy(cacheBusy = false, cacheMessage = r.summaryZh())
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = container.appContext as Application
            return SettingsViewModel(
                application = app,
                repo = container.settingsRepository,
                sessionController = container.sessionController
            ) as T
        }
    }
}
