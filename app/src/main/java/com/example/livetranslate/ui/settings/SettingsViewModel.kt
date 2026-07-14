package com.example.livetranslate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.livetranslate.data.settings.SettingsRepository
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val draft: UserSettings = UserSettings(),
    val savedMessage: String? = null
)

class SettingsViewModel(
    private val repo: SettingsRepository
) : ViewModel() {

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
        _ui.update { it.copy(draft = transform(it.draft), savedMessage = null) }
    }

    fun save() {
        viewModelScope.launch {
            val draft = _ui.value.draft
            repo.update { draft }
            _ui.update { it.copy(savedMessage = "Saved") }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(container.settingsRepository) as T
        }
    }
}
