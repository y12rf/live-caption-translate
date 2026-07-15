package com.example.livetranslate.data.llm

import com.example.livetranslate.data.asr.ApiAuthStyle

/**
 * How to send the request body `thinking` field.
 *
 * - [Default]: omit the field entirely
 * - [True]: `"thinking": true`
 * - [False]: `"thinking": false`
 */
enum class LlmThinkingMode {
    Default,
    True,
    False;

    companion object {
        fun fromStorage(raw: String?): LlmThinkingMode {
            val s = raw?.trim().orEmpty()
            return when {
                s.equals("true", ignoreCase = true) || s.equals(True.name, ignoreCase = true) -> True
                s.equals("false", ignoreCase = true) || s.equals(False.name, ignoreCase = true) -> False
                else -> Default
            }
        }
    }
}

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val targetLanguage: String,
    val sourceLanguage: String = "en",
    /**
     * Already-rendered system prompt (placeholders like {{to}} resolved by caller),
     * or a template that [LlmClient] will render if it still contains placeholders.
     */
    val systemPrompt: String,
    val authStyle: ApiAuthStyle = ApiAuthStyle.Bearer,
    /** When true, use [baseUrl] as-is (no path append). */
    val fullUrl: Boolean = false,
    val thinking: LlmThinkingMode = LlmThinkingMode.Default
)
