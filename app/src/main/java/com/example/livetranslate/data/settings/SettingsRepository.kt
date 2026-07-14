package com.example.livetranslate.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val asrBaseUrl = stringPreferencesKey("asr_base_url")
        val asrApiKey = stringPreferencesKey("asr_api_key")
        val asrModel = stringPreferencesKey("asr_model")
        val llmBaseUrl = stringPreferencesKey("llm_base_url")
        val llmApiKey = stringPreferencesKey("llm_api_key")
        val llmModel = stringPreferencesKey("llm_model")
        val inputLanguage = stringPreferencesKey("input_language")
        val outputLanguage = stringPreferencesKey("output_language")
        val silenceMs = intPreferencesKey("silence_ms")
        val maxUtteranceMs = intPreferencesKey("max_utterance_ms")
        val minUtteranceMs = intPreferencesKey("min_utterance_ms")
        val energyThreshold = doublePreferencesKey("energy_threshold")
        val contextWindowSize = intPreferencesKey("context_window_size")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        val defaults = UserSettings()
        UserSettings(
            asrBaseUrl = p[Keys.asrBaseUrl] ?: defaults.asrBaseUrl,
            asrApiKey = p[Keys.asrApiKey] ?: defaults.asrApiKey,
            asrModel = p[Keys.asrModel] ?: defaults.asrModel,
            llmBaseUrl = p[Keys.llmBaseUrl] ?: defaults.llmBaseUrl,
            llmApiKey = p[Keys.llmApiKey] ?: defaults.llmApiKey,
            llmModel = p[Keys.llmModel] ?: defaults.llmModel,
            inputLanguage = p[Keys.inputLanguage] ?: defaults.inputLanguage,
            outputLanguage = p[Keys.outputLanguage] ?: defaults.outputLanguage,
            silenceMs = p[Keys.silenceMs] ?: defaults.silenceMs,
            maxUtteranceMs = p[Keys.maxUtteranceMs] ?: defaults.maxUtteranceMs,
            minUtteranceMs = p[Keys.minUtteranceMs] ?: defaults.minUtteranceMs,
            energyThreshold = p[Keys.energyThreshold] ?: defaults.energyThreshold,
            contextWindowSize = p[Keys.contextWindowSize] ?: defaults.contextWindowSize
        )
    }

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.dataStore.edit { prefs ->
            // Read current from prefs snapshot via defaults merge
            val current = UserSettings(
                asrBaseUrl = prefs[Keys.asrBaseUrl] ?: UserSettings().asrBaseUrl,
                asrApiKey = prefs[Keys.asrApiKey] ?: "",
                asrModel = prefs[Keys.asrModel] ?: UserSettings().asrModel,
                llmBaseUrl = prefs[Keys.llmBaseUrl] ?: UserSettings().llmBaseUrl,
                llmApiKey = prefs[Keys.llmApiKey] ?: "",
                llmModel = prefs[Keys.llmModel] ?: UserSettings().llmModel,
                inputLanguage = prefs[Keys.inputLanguage] ?: UserSettings().inputLanguage,
                outputLanguage = prefs[Keys.outputLanguage] ?: UserSettings().outputLanguage,
                silenceMs = prefs[Keys.silenceMs] ?: UserSettings().silenceMs,
                maxUtteranceMs = prefs[Keys.maxUtteranceMs] ?: UserSettings().maxUtteranceMs,
                minUtteranceMs = prefs[Keys.minUtteranceMs] ?: UserSettings().minUtteranceMs,
                energyThreshold = prefs[Keys.energyThreshold] ?: UserSettings().energyThreshold,
                contextWindowSize = prefs[Keys.contextWindowSize] ?: UserSettings().contextWindowSize
            )
            val next = transform(current)
            prefs[Keys.asrBaseUrl] = UserSettings.normalizeBaseUrl(next.asrBaseUrl)
            prefs[Keys.asrApiKey] = next.asrApiKey
            prefs[Keys.asrModel] = next.asrModel
            prefs[Keys.llmBaseUrl] = UserSettings.normalizeBaseUrl(next.llmBaseUrl)
            prefs[Keys.llmApiKey] = next.llmApiKey
            prefs[Keys.llmModel] = next.llmModel
            prefs[Keys.inputLanguage] = next.inputLanguage
            prefs[Keys.outputLanguage] = next.outputLanguage
            prefs[Keys.silenceMs] = next.silenceMs
            prefs[Keys.maxUtteranceMs] = next.maxUtteranceMs
            prefs[Keys.minUtteranceMs] = next.minUtteranceMs
            prefs[Keys.energyThreshold] = next.energyThreshold
            prefs[Keys.contextWindowSize] = next.contextWindowSize
        }
    }
}
