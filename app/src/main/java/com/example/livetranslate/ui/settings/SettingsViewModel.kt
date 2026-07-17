package com.example.livetranslate.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.livetranslate.R
import com.example.livetranslate.data.CacheCleaner
import com.example.livetranslate.data.network.ApiLatencyProbe
import com.example.livetranslate.data.settings.SettingsRepository
import com.example.livetranslate.data.settings.SettingsValidation
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.di.AppContainer
import com.example.livetranslate.domain.SessionController
import com.example.livetranslate.util.AppLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val draft: UserSettings = UserSettings(),
    val warnings: List<String> = emptyList(),
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
                val v = SettingsValidation.validate(s)
                _ui.update {
                    it.copy(draft = s, warnings = v.warnings)
                }
            }
        }
    }

    fun updateDraft(transform: (UserSettings) -> UserSettings) {
        _ui.update {
            val next = transform(it.draft)
            val v = SettingsValidation.validate(next)
            it.copy(
                draft = next,
                warnings = v.warnings,
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
            val validated = SettingsValidation.validate(draft)
            val previousLang = AppLocale.normalize(
                runCatching { repo.settings.first().uiLanguage }.getOrDefault(AppLocale.EN)
            )
            val nextLang = AppLocale.normalize(validated.sanitized.uiLanguage)
            repo.update { validated.sanitized }
            // Apply UI locale after DataStore write so string resources refresh.
            AppLocale.apply(nextLang)
            val langChanged = previousLang != nextLang
            _ui.update {
                it.copy(
                    draft = validated.sanitized,
                    warnings = validated.warnings,
                    savedMessage = buildString {
                        append(if (langChanged) "Saved · language applied" else "Saved")
                        if (validated.warnings.isNotEmpty()) {
                            append(" · ${validated.warnings.size} warning(s)")
                        }
                    }
                )
            }
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
        if (sessionController.isSessionBusy() || sessionController.isSessionRecordingActive()) {
            _ui.update {
                it.copy(
                    cacheMessage = getApplication<Application>()
                        .getString(R.string.cache_blocked_session_active)
                )
            }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(cacheBusy = true, cacheMessage = null) }
            sessionController.clearTranslationCache()
            val exclude = protectedSessionPaths()
            val r = withContext(Dispatchers.IO) {
                CacheCleaner.clearOrphanRecordings(getApplication(), excludePaths = exclude)
            }.copy(translationCacheCleared = true)
            _ui.update {
                it.copy(cacheBusy = false, cacheMessage = r.summary(getApplication()))
            }
        }
    }

    /** Wipe all history sessions and recordings (destructive). */
    fun clearAllHistory() {
        if (_ui.value.cacheBusy) return
        if (sessionController.isSessionBusy() || sessionController.isSessionRecordingActive()) {
            _ui.update {
                it.copy(
                    cacheMessage = getApplication<Application>()
                        .getString(R.string.cache_blocked_session_active)
                )
            }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(cacheBusy = true, cacheMessage = null) }
            sessionController.clearTranslationCache()
            val exclude = protectedSessionPaths()
            val r = withContext(Dispatchers.IO) {
                CacheCleaner.clearAllHistoryAndRecordings(getApplication(), excludePaths = exclude)
            }.copy(translationCacheCleared = true)
            _ui.update {
                it.copy(cacheBusy = false, cacheMessage = r.summary(getApplication()))
            }
        }
    }

    /** Active session WAV must never be deleted mid-recording / mid-import. */
    private fun protectedSessionPaths(): Set<String> =
        buildSet {
            sessionController.activeSessionAudioPath()?.let { add(it) }
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
