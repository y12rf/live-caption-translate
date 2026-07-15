package com.example.livetranslate.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.livetranslate.data.llm.LlmThinkingMode
import com.example.livetranslate.util.AppLocale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val asrBaseUrl = stringPreferencesKey("asr_base_url")
        val asrApiKey = stringPreferencesKey("asr_api_key")
        val asrModel = stringPreferencesKey("asr_model")
        val asrApiStyle = stringPreferencesKey("asr_api_style")
        val asrAuthStyle = stringPreferencesKey("asr_auth_style")
        val asrFullUrl = booleanPreferencesKey("asr_full_url")
        val llmBaseUrl = stringPreferencesKey("llm_base_url")
        val llmApiKey = stringPreferencesKey("llm_api_key")
        val llmModel = stringPreferencesKey("llm_model")
        val llmAuthStyle = stringPreferencesKey("llm_auth_style")
        val llmFullUrl = booleanPreferencesKey("llm_full_url")
        val llmThinking = stringPreferencesKey("llm_thinking")
        val llmSystemPrompt = stringPreferencesKey("llm_system_prompt")
        val glossaryTerms = stringPreferencesKey("glossary_terms")
        val uiLanguage = stringPreferencesKey("ui_language")
        val inputLanguage = stringPreferencesKey("input_language")
        val outputLanguage = stringPreferencesKey("output_language")
        val silenceMs = intPreferencesKey("silence_ms")
        val maxUtteranceMs = intPreferencesKey("max_utterance_ms")
        val minUtteranceMs = intPreferencesKey("min_utterance_ms")
        val energyThreshold = doublePreferencesKey("energy_threshold")
        val contextWindowSize = intPreferencesKey("context_window_size")
        val overlayMaxWidthDp = intPreferencesKey("overlay_max_width_dp")
        val overlayMaxHeightDp = intPreferencesKey("overlay_max_height_dp")
        val overlayAlphaPercent = intPreferencesKey("overlay_alpha_percent")
        val overlayBgColor = stringPreferencesKey("overlay_bg_color")
        val overlayEnTextColor = stringPreferencesKey("overlay_en_text_color")
        val overlayZhTextColor = stringPreferencesKey("overlay_zh_text_color")
        val overlayTranslationOnTop = booleanPreferencesKey("overlay_translation_on_top")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        val defaults = UserSettings()
        UserSettings(
            asrBaseUrl = p[Keys.asrBaseUrl] ?: defaults.asrBaseUrl,
            asrApiKey = p[Keys.asrApiKey] ?: defaults.asrApiKey,
            asrModel = p[Keys.asrModel] ?: defaults.asrModel,
            asrApiStyle = p[Keys.asrApiStyle] ?: defaults.asrApiStyle,
            asrAuthStyle = p[Keys.asrAuthStyle] ?: defaults.asrAuthStyle,
            asrFullUrl = p[Keys.asrFullUrl] ?: defaults.asrFullUrl,
            llmBaseUrl = p[Keys.llmBaseUrl] ?: defaults.llmBaseUrl,
            llmApiKey = p[Keys.llmApiKey] ?: defaults.llmApiKey,
            llmModel = p[Keys.llmModel] ?: defaults.llmModel,
            llmAuthStyle = p[Keys.llmAuthStyle] ?: defaults.llmAuthStyle,
            llmFullUrl = p[Keys.llmFullUrl] ?: defaults.llmFullUrl,
            llmThinking = p[Keys.llmThinking] ?: defaults.llmThinking,
            llmSystemPrompt = p[Keys.llmSystemPrompt] ?: defaults.llmSystemPrompt,
            glossaryTerms = GlossaryCodec.decode(p[Keys.glossaryTerms]),
            uiLanguage = AppLocale.normalize(p[Keys.uiLanguage] ?: defaults.uiLanguage),
            inputLanguage = p[Keys.inputLanguage] ?: defaults.inputLanguage,
            outputLanguage = p[Keys.outputLanguage] ?: defaults.outputLanguage,
            silenceMs = p[Keys.silenceMs] ?: defaults.silenceMs,
            maxUtteranceMs = p[Keys.maxUtteranceMs] ?: defaults.maxUtteranceMs,
            minUtteranceMs = p[Keys.minUtteranceMs] ?: defaults.minUtteranceMs,
            energyThreshold = p[Keys.energyThreshold] ?: defaults.energyThreshold,
            contextWindowSize = p[Keys.contextWindowSize] ?: defaults.contextWindowSize,
            overlayMaxWidthDp = p[Keys.overlayMaxWidthDp] ?: defaults.overlayMaxWidthDp,
            overlayMaxHeightDp = p[Keys.overlayMaxHeightDp] ?: defaults.overlayMaxHeightDp,
            overlayAlphaPercent = p[Keys.overlayAlphaPercent] ?: defaults.overlayAlphaPercent,
            overlayBgColor = p[Keys.overlayBgColor] ?: defaults.overlayBgColor,
            overlayEnTextColor = p[Keys.overlayEnTextColor] ?: defaults.overlayEnTextColor,
            overlayZhTextColor = p[Keys.overlayZhTextColor] ?: defaults.overlayZhTextColor,
            overlayTranslationOnTop = p[Keys.overlayTranslationOnTop]
                ?: defaults.overlayTranslationOnTop
        )
    }

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.dataStore.edit { prefs ->
            val d = UserSettings()
            val current = UserSettings(
                asrBaseUrl = prefs[Keys.asrBaseUrl] ?: d.asrBaseUrl,
                asrApiKey = prefs[Keys.asrApiKey] ?: "",
                asrModel = prefs[Keys.asrModel] ?: d.asrModel,
                asrApiStyle = prefs[Keys.asrApiStyle] ?: d.asrApiStyle,
                asrAuthStyle = prefs[Keys.asrAuthStyle] ?: d.asrAuthStyle,
                asrFullUrl = prefs[Keys.asrFullUrl] ?: d.asrFullUrl,
                llmBaseUrl = prefs[Keys.llmBaseUrl] ?: d.llmBaseUrl,
                llmApiKey = prefs[Keys.llmApiKey] ?: "",
                llmModel = prefs[Keys.llmModel] ?: d.llmModel,
                llmAuthStyle = prefs[Keys.llmAuthStyle] ?: d.llmAuthStyle,
                llmFullUrl = prefs[Keys.llmFullUrl] ?: d.llmFullUrl,
                llmThinking = prefs[Keys.llmThinking] ?: d.llmThinking,
                llmSystemPrompt = prefs[Keys.llmSystemPrompt] ?: d.llmSystemPrompt,
                glossaryTerms = GlossaryCodec.decode(prefs[Keys.glossaryTerms]),
                uiLanguage = AppLocale.normalize(prefs[Keys.uiLanguage] ?: d.uiLanguage),
                inputLanguage = prefs[Keys.inputLanguage] ?: d.inputLanguage,
                outputLanguage = prefs[Keys.outputLanguage] ?: d.outputLanguage,
                silenceMs = prefs[Keys.silenceMs] ?: d.silenceMs,
                maxUtteranceMs = prefs[Keys.maxUtteranceMs] ?: d.maxUtteranceMs,
                minUtteranceMs = prefs[Keys.minUtteranceMs] ?: d.minUtteranceMs,
                energyThreshold = prefs[Keys.energyThreshold] ?: d.energyThreshold,
                contextWindowSize = prefs[Keys.contextWindowSize] ?: d.contextWindowSize,
                overlayMaxWidthDp = prefs[Keys.overlayMaxWidthDp] ?: d.overlayMaxWidthDp,
                overlayMaxHeightDp = prefs[Keys.overlayMaxHeightDp] ?: d.overlayMaxHeightDp,
                overlayAlphaPercent = prefs[Keys.overlayAlphaPercent] ?: d.overlayAlphaPercent,
                overlayBgColor = prefs[Keys.overlayBgColor] ?: d.overlayBgColor,
                overlayEnTextColor = prefs[Keys.overlayEnTextColor] ?: d.overlayEnTextColor,
                overlayZhTextColor = prefs[Keys.overlayZhTextColor] ?: d.overlayZhTextColor,
                overlayTranslationOnTop = prefs[Keys.overlayTranslationOnTop]
                    ?: d.overlayTranslationOnTop
            )
            val next = transform(current)
            prefs[Keys.asrBaseUrl] = UserSettings.normalizeBaseUrl(next.asrBaseUrl)
            prefs[Keys.asrApiKey] = next.asrApiKey.trim()
            prefs[Keys.asrModel] = next.asrModel.trim()
            prefs[Keys.asrApiStyle] = next.asrApiStyle.trim()
            prefs[Keys.asrAuthStyle] = next.asrAuthStyle.trim()
            prefs[Keys.asrFullUrl] = next.asrFullUrl
            prefs[Keys.llmBaseUrl] = UserSettings.normalizeBaseUrl(next.llmBaseUrl)
            prefs[Keys.llmApiKey] = next.llmApiKey.trim()
            prefs[Keys.llmModel] = next.llmModel.trim()
            prefs[Keys.llmAuthStyle] = next.llmAuthStyle.trim()
            prefs[Keys.llmFullUrl] = next.llmFullUrl
            prefs[Keys.llmThinking] = LlmThinkingMode.fromStorage(next.llmThinking).name
            prefs[Keys.llmSystemPrompt] = next.llmSystemPrompt.ifBlank {
                UserSettings.DEFAULT_LLM_SYSTEM_PROMPT
            }
            prefs[Keys.glossaryTerms] = GlossaryCodec.encode(next.glossaryTerms)
            prefs[Keys.uiLanguage] = AppLocale.normalize(next.uiLanguage)
            prefs[Keys.inputLanguage] = next.inputLanguage
            prefs[Keys.outputLanguage] = next.outputLanguage
            prefs[Keys.silenceMs] = next.silenceMs
            prefs[Keys.maxUtteranceMs] = next.maxUtteranceMs
            prefs[Keys.minUtteranceMs] = next.minUtteranceMs
            prefs[Keys.energyThreshold] = next.energyThreshold
            prefs[Keys.contextWindowSize] = next.contextWindowSize
            prefs[Keys.overlayMaxWidthDp] = next.overlayMaxWidthDp.coerceIn(120, 2000)
            prefs[Keys.overlayMaxHeightDp] = next.overlayMaxHeightDp.coerceIn(60, 800)
            prefs[Keys.overlayAlphaPercent] = next.overlayAlphaPercent.coerceIn(0, 100)
            prefs[Keys.overlayBgColor] = UserSettings.normalizeColorHex(
                next.overlayBgColor, UserSettings.DEFAULT_OVERLAY_BG
            )
            prefs[Keys.overlayEnTextColor] = UserSettings.normalizeColorHex(
                next.overlayEnTextColor, UserSettings.DEFAULT_OVERLAY_EN
            )
            prefs[Keys.overlayZhTextColor] = UserSettings.normalizeColorHex(
                next.overlayZhTextColor, UserSettings.DEFAULT_OVERLAY_ZH
            )
            prefs[Keys.overlayTranslationOnTop] = next.overlayTranslationOnTop
        }
    }
}
