package com.example.livetranslate.data.llm

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val targetLanguage: String
)
