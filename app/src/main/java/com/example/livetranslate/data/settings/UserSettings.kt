package com.example.livetranslate.data.settings

data class UserSettings(
    val asrBaseUrl: String = "https://api.openai.com",
    val asrApiKey: String = "",
    val asrModel: String = "whisper-1",
    val llmBaseUrl: String = "https://api.openai.com",
    val llmApiKey: String = "",
    val llmModel: String = "gpt-4o-mini",
    val inputLanguage: String = "en",
    val outputLanguage: String = "zh",
    val silenceMs: Int = 700,
    val maxUtteranceMs: Int = 10_000,
    val minUtteranceMs: Int = 300,
    val energyThreshold: Double = 500.0,
    val contextWindowSize: Int = 4
) {
    fun normalizedAsrBaseUrl(): String = normalizeBaseUrl(asrBaseUrl)
    fun normalizedLlmBaseUrl(): String = normalizeBaseUrl(llmBaseUrl)

    companion object {
        fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')
    }
}
