package com.example.livetranslate.data.asr

data class AsrConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val language: String
)
