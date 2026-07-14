package com.example.livetranslate.data.llm

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
    val systemPrompt: String
)
