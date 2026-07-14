package com.example.livetranslate.data.settings

import com.example.livetranslate.data.asr.ApiAuthStyle
import com.example.livetranslate.data.asr.AsrApiStyle

data class UserSettings(
    val asrBaseUrl: String = "https://api.openai.com",
    val asrApiKey: String = "",
    val asrModel: String = "whisper-1",
    /** transcriptions = OpenAI Whisper multipart; chat_audio = MIMO-style chat+base64 */
    val asrApiStyle: String = AsrApiStyle.OpenAiTranscriptions.name,
    val asrAuthStyle: String = ApiAuthStyle.ApiKeyHeader.name,
    val llmBaseUrl: String = "https://api.openai.com",
    val llmApiKey: String = "",
    val llmModel: String = "gpt-4o-mini",
    // DeepSeek / OpenAI chat: Bearer. ASR MIMO may still use ApiKeyHeader separately.
    val llmAuthStyle: String = ApiAuthStyle.Bearer.name,
    /**
     * LLM system prompt template. Placeholders:
     * - `{{to}}` → output language (e.g. zh / Chinese)
     * - `{{from}}` → input language (e.g. en / English)
     */
    val llmSystemPrompt: String = DEFAULT_LLM_SYSTEM_PROMPT,
    val inputLanguage: String = "en",
    val outputLanguage: String = "zh",
    // More aggressive segmentation defaults (shorter silence / max span → cut sooner)
    val silenceMs: Int = 300,
    val maxUtteranceMs: Int = 6_000,
    val minUtteranceMs: Int = 200,
    val energyThreshold: Double = 400.0,
    val contextWindowSize: Int = 4
) {
    fun normalizedAsrBaseUrl(): String = normalizeBaseUrl(asrBaseUrl)
    fun normalizedLlmBaseUrl(): String = normalizeBaseUrl(llmBaseUrl)

    fun asrApiStyleEnum(): AsrApiStyle =
        runCatching { AsrApiStyle.valueOf(asrApiStyle) }.getOrDefault(AsrApiStyle.OpenAiTranscriptions)

    fun asrAuthStyleEnum(): ApiAuthStyle =
        runCatching { ApiAuthStyle.valueOf(asrAuthStyle) }.getOrDefault(ApiAuthStyle.ApiKeyHeader)

    fun llmAuthStyleEnum(): ApiAuthStyle =
        runCatching { ApiAuthStyle.valueOf(llmAuthStyle) }.getOrDefault(ApiAuthStyle.Bearer)

    fun renderLlmSystemPrompt(): String =
        llmSystemPrompt
            .replace("{{to}}", outputLanguage)
            .replace("{{from}}", inputLanguage)

    companion object {
        const val DEFAULT_LLM_SYSTEM_PROMPT: String =
            "你是一位精通 {{to}} 专业母语译者，致力于提供流畅、地道、符合表达习惯且高保真的翻译。"

        fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')
    }
}
