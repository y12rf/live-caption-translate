package com.example.livetranslate.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.livetranslate.data.llm.LlmReasoningEffort
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
        val llmReasoningEffort = stringPreferencesKey("llm_reasoning_effort")
        val llmSystemPrompt = stringPreferencesKey("llm_system_prompt")
        val llmUserPrompt = stringPreferencesKey("llm_user_prompt")
        val llmTitleSystemPrompt = stringPreferencesKey("llm_title_system_prompt")
        val llmTitleUserPrompt = stringPreferencesKey("llm_title_user_prompt")
        val glossaryTerms = stringPreferencesKey("glossary_terms")
        val uiLanguage = stringPreferencesKey("ui_language")
        val inputLanguage = stringPreferencesKey("input_language")
        val outputLanguage = stringPreferencesKey("output_language")
        val silenceMs = intPreferencesKey("silence_ms")
        val maxUtteranceMs = intPreferencesKey("max_utterance_ms")
        val minUtteranceMs = intPreferencesKey("min_utterance_ms")
        val sileroVadMode = stringPreferencesKey("silero_vad_mode")
        val contextWindowSize = intPreferencesKey("context_window_size")
        val offlineVadBatchSize = intPreferencesKey("offline_vad_batch_size")
        val titleTurnThreshold = intPreferencesKey("title_turn_threshold")
        val maxNetworkAttempts = intPreferencesKey("max_network_attempts")
        val translationCacheMax = intPreferencesKey("translation_cache_max")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val immersiveMode = booleanPreferencesKey("immersive_mode")
        val asrOnlyMode = booleanPreferencesKey("asr_only_mode")
        val overlayMaxWidthDp = intPreferencesKey("overlay_max_width_dp")
        val overlayMaxHeightDp = intPreferencesKey("overlay_max_height_dp")
        val overlayAlphaPercent = intPreferencesKey("overlay_alpha_percent")
        val overlayBgColor = stringPreferencesKey("overlay_bg_color")
        val overlayEnTextColor = stringPreferencesKey("overlay_en_text_color")
        val overlayZhTextColor = stringPreferencesKey("overlay_zh_text_color")
        val overlayTranslationOnTop = booleanPreferencesKey("overlay_translation_on_top")
        val overlayTextMode = stringPreferencesKey("overlay_text_mode")
        val overlayLayoutMode = stringPreferencesKey("overlay_layout_mode")
        val overlayFontSizeSp = intPreferencesKey("overlay_font_size_sp")
        val overlayPadHDp = intPreferencesKey("overlay_pad_h_dp")
        val overlayPadVDp = intPreferencesKey("overlay_pad_v_dp")
        val overlayMarqueeSpeed = intPreferencesKey("overlay_marquee_speed")
        val overlayMarqueeFinishBeforeNext =
            booleanPreferencesKey("overlay_marquee_finish_before_next")
        val overlayShowBorder = booleanPreferencesKey("overlay_show_border")
        val overlayShowDivider = booleanPreferencesKey("overlay_show_divider")
        val liveFontSizeSp = intPreferencesKey("live_font_size_sp")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        // Always expose clamped/normalized values (not only after save).
        SettingsValidation.validate(read(p)).sanitized
    }

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.dataStore.edit { prefs ->
            val next = SettingsValidation.validate(transform(read(prefs))).sanitized
            write(prefs, next)
        }
    }

    /**
     * Ensure [Keys.uiLanguage] is persisted. Missing key used to display as English in
     * Settings while resources still followed the system language — seed the product default.
     * @return normalized language tag to apply to AppCompat
     */
    suspend fun ensureUiLanguageAndGet(): String {
        var resolved = AppLocale.EN
        context.dataStore.edit { prefs ->
            val raw = prefs[Keys.uiLanguage]
            if (raw == null) {
                prefs[Keys.uiLanguage] = AppLocale.EN
                resolved = AppLocale.EN
            } else {
                resolved = AppLocale.normalize(raw)
                // Re-write normalized form if user stored a synonym (e.g. zh-CN).
                prefs[Keys.uiLanguage] = resolved
            }
        }
        return resolved
    }

    private fun read(p: Preferences): UserSettings {
        val d = UserSettings()
        return UserSettings(
            asrBaseUrl = p[Keys.asrBaseUrl] ?: d.asrBaseUrl,
            asrApiKey = p[Keys.asrApiKey] ?: d.asrApiKey,
            asrModel = p[Keys.asrModel] ?: d.asrModel,
            asrApiStyle = p[Keys.asrApiStyle] ?: d.asrApiStyle,
            asrAuthStyle = p[Keys.asrAuthStyle] ?: d.asrAuthStyle,
            asrFullUrl = p[Keys.asrFullUrl] ?: d.asrFullUrl,
            llmBaseUrl = p[Keys.llmBaseUrl] ?: d.llmBaseUrl,
            llmApiKey = p[Keys.llmApiKey] ?: d.llmApiKey,
            llmModel = p[Keys.llmModel] ?: d.llmModel,
            llmAuthStyle = p[Keys.llmAuthStyle] ?: d.llmAuthStyle,
            llmFullUrl = p[Keys.llmFullUrl] ?: d.llmFullUrl,
            llmThinking = p[Keys.llmThinking] ?: d.llmThinking,
            llmReasoningEffort = p[Keys.llmReasoningEffort] ?: d.llmReasoningEffort,
            llmSystemPrompt = p[Keys.llmSystemPrompt] ?: d.llmSystemPrompt,
            llmUserPrompt = p[Keys.llmUserPrompt] ?: d.llmUserPrompt,
            llmTitleSystemPrompt = p[Keys.llmTitleSystemPrompt] ?: d.llmTitleSystemPrompt,
            llmTitleUserPrompt = p[Keys.llmTitleUserPrompt] ?: d.llmTitleUserPrompt,
            glossaryTerms = GlossaryCodec.decode(p[Keys.glossaryTerms]),
            uiLanguage = AppLocale.normalize(p[Keys.uiLanguage] ?: d.uiLanguage),
            inputLanguage = p[Keys.inputLanguage] ?: d.inputLanguage,
            outputLanguage = p[Keys.outputLanguage] ?: d.outputLanguage,
            silenceMs = p[Keys.silenceMs] ?: d.silenceMs,
            maxUtteranceMs = p[Keys.maxUtteranceMs] ?: d.maxUtteranceMs,
            minUtteranceMs = p[Keys.minUtteranceMs] ?: d.minUtteranceMs,
            sileroVadMode = SileroVadMode.fromStorage(p[Keys.sileroVadMode] ?: d.sileroVadMode).name,
            contextWindowSize = p[Keys.contextWindowSize] ?: d.contextWindowSize,
            offlineVadBatchSize = p[Keys.offlineVadBatchSize] ?: d.offlineVadBatchSize,
            titleTurnThreshold = p[Keys.titleTurnThreshold] ?: d.titleTurnThreshold,
            maxNetworkAttempts = p[Keys.maxNetworkAttempts] ?: d.maxNetworkAttempts,
            translationCacheMax = p[Keys.translationCacheMax] ?: d.translationCacheMax,
            keepScreenOn = p[Keys.keepScreenOn] ?: d.keepScreenOn,
            immersiveMode = p[Keys.immersiveMode] ?: d.immersiveMode,
            asrOnlyMode = p[Keys.asrOnlyMode] ?: d.asrOnlyMode,
            overlayMaxWidthDp = p[Keys.overlayMaxWidthDp] ?: d.overlayMaxWidthDp,
            overlayMaxHeightDp = p[Keys.overlayMaxHeightDp] ?: d.overlayMaxHeightDp,
            overlayAlphaPercent = p[Keys.overlayAlphaPercent] ?: d.overlayAlphaPercent,
            overlayBgColor = p[Keys.overlayBgColor] ?: d.overlayBgColor,
            overlayEnTextColor = p[Keys.overlayEnTextColor] ?: d.overlayEnTextColor,
            overlayZhTextColor = p[Keys.overlayZhTextColor] ?: d.overlayZhTextColor,
            overlayTranslationOnTop = p[Keys.overlayTranslationOnTop]
                ?: d.overlayTranslationOnTop,
            overlayTextMode = p[Keys.overlayTextMode] ?: d.overlayTextMode,
            overlayLayoutMode = p[Keys.overlayLayoutMode] ?: d.overlayLayoutMode,
            overlayFontSizeSp = p[Keys.overlayFontSizeSp] ?: d.overlayFontSizeSp,
            overlayPadHDp = p[Keys.overlayPadHDp] ?: d.overlayPadHDp,
            overlayPadVDp = p[Keys.overlayPadVDp] ?: d.overlayPadVDp,
            overlayMarqueeSpeed = p[Keys.overlayMarqueeSpeed] ?: d.overlayMarqueeSpeed,
            overlayMarqueeFinishBeforeNext = p[Keys.overlayMarqueeFinishBeforeNext]
                ?: d.overlayMarqueeFinishBeforeNext,
            overlayShowBorder = p[Keys.overlayShowBorder] ?: d.overlayShowBorder,
            overlayShowDivider = p[Keys.overlayShowDivider] ?: d.overlayShowDivider,
            liveFontSizeSp = p[Keys.liveFontSizeSp] ?: d.liveFontSizeSp
        )
    }

    private fun write(prefs: MutablePreferences, next: UserSettings) {
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
        prefs[Keys.llmReasoningEffort] =
            LlmReasoningEffort.fromStorage(next.llmReasoningEffort).name
        prefs[Keys.llmSystemPrompt] = next.llmSystemPrompt.ifBlank {
            UserSettings.DEFAULT_LLM_SYSTEM_PROMPT
        }
        prefs[Keys.llmUserPrompt] = next.llmUserPrompt.ifBlank {
            UserSettings.DEFAULT_LLM_USER_PROMPT
        }
        prefs[Keys.llmTitleSystemPrompt] = next.llmTitleSystemPrompt.ifBlank {
            UserSettings.DEFAULT_LLM_TITLE_SYSTEM_PROMPT
        }
        prefs[Keys.llmTitleUserPrompt] = next.llmTitleUserPrompt.ifBlank {
            UserSettings.DEFAULT_LLM_TITLE_USER_PROMPT
        }
        prefs[Keys.glossaryTerms] = GlossaryCodec.encode(next.glossaryTerms)
        prefs[Keys.uiLanguage] = AppLocale.normalize(next.uiLanguage)
        prefs[Keys.inputLanguage] = next.inputLanguage
        prefs[Keys.outputLanguage] = next.outputLanguage
        prefs[Keys.silenceMs] = next.silenceMs
        prefs[Keys.maxUtteranceMs] = next.maxUtteranceMs
        prefs[Keys.minUtteranceMs] = next.minUtteranceMs
        prefs[Keys.sileroVadMode] = SileroVadMode.fromStorage(next.sileroVadMode).name
        prefs[Keys.contextWindowSize] = next.contextWindowSize
        prefs[Keys.offlineVadBatchSize] = next.offlineVadBatchSize
        prefs[Keys.titleTurnThreshold] = next.titleTurnThreshold
        prefs[Keys.maxNetworkAttempts] = next.maxNetworkAttempts
        prefs[Keys.translationCacheMax] = next.translationCacheMax
        prefs[Keys.keepScreenOn] = next.keepScreenOn
        prefs[Keys.immersiveMode] = next.immersiveMode
        prefs[Keys.asrOnlyMode] = next.asrOnlyMode
        prefs[Keys.overlayMaxWidthDp] = next.overlayMaxWidthDp
        prefs[Keys.overlayMaxHeightDp] = next.overlayMaxHeightDp
        prefs[Keys.overlayAlphaPercent] = next.overlayAlphaPercent
        prefs[Keys.overlayBgColor] = next.overlayBgColor
        prefs[Keys.overlayEnTextColor] = next.overlayEnTextColor
        prefs[Keys.overlayZhTextColor] = next.overlayZhTextColor
        prefs[Keys.overlayTranslationOnTop] = next.overlayTranslationOnTop
        prefs[Keys.overlayTextMode] = OverlayTextMode.fromStorage(next.overlayTextMode).name
        prefs[Keys.overlayLayoutMode] = OverlayLayoutMode.fromStorage(next.overlayLayoutMode).name
        prefs[Keys.overlayFontSizeSp] = next.overlayFontSizeSp
        prefs[Keys.overlayPadHDp] = next.overlayPadHDp
        prefs[Keys.overlayPadVDp] = next.overlayPadVDp
        prefs[Keys.overlayMarqueeSpeed] = next.overlayMarqueeSpeed
        prefs[Keys.overlayMarqueeFinishBeforeNext] = next.overlayMarqueeFinishBeforeNext
        prefs[Keys.overlayShowBorder] = next.overlayShowBorder
        prefs[Keys.overlayShowDivider] = next.overlayShowDivider
        prefs[Keys.liveFontSizeSp] = next.liveFontSizeSp
    }
}
