package com.example.livetranslate.data.llm

import com.example.livetranslate.data.asr.ApiAuthStyle

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val targetLanguage: String,
    val sourceLanguage: String = "en",
    /**
     * Translation system prompt (placeholders like {{to}} may still be present;
     * [LlmClient] fills leftovers).
     */
    val systemPrompt: String,
    /**
     * Translation user-message template.
     * Placeholders: {{from}} {{to}} {{history}} {{text}}
     * Blank → client default.
     */
    val userPromptTemplate: String = "",
    /**
     * Session-title system prompt. Blank → client default.
     */
    val titleSystemPrompt: String = "",
    /**
     * Session-title user template with {{dialogue}}. Blank → client default.
     */
    val titleUserPromptTemplate: String = "",
    val authStyle: ApiAuthStyle = ApiAuthStyle.Bearer,
    /** When true, use [baseUrl] as-is (no path append). */
    val fullUrl: Boolean = false,
    /** `"thinking":{"type":"enabled"|"disabled"}` — default enabled. */
    val thinking: LlmThinkingMode = LlmThinkingMode.Enabled,
    /** Effort when thinking is enabled (`high` / `max`). */
    val reasoningEffort: LlmReasoningEffort = LlmReasoningEffort.High,
    /** OpenAI `reasoning_effort` vs Anthropic `output_config.effort`. */
    val reasoningEffortStyle: LlmReasoningEffortStyle = LlmReasoningEffortStyle.OpenAi
)
