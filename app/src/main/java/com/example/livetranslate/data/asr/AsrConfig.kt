package com.example.livetranslate.data.asr

/**
 * How to call the ASR endpoint.
 *
 * - [OpenAiTranscriptions]: POST `/v1/audio/transcriptions` multipart file + stream=true
 * - [ChatCompletionsAudio]: POST `/v1/chat/completions` with base64 input_audio (e.g. Xiaomi MIMO)
 */
enum class AsrApiStyle {
    OpenAiTranscriptions,
    ChatCompletionsAudio
}

/**
 * Auth header style for OpenAI-compatible gateways.
 *
 * Note: "having an API Key" ≠ [ApiKeyHeader].
 * - DeepSeek / OpenAI / most chat APIs: use [Bearer] with your key as the token.
 * - Some gateways (e.g. MIMO curl): use HTTP header literally named `api-key` → [ApiKeyHeader].
 */
enum class ApiAuthStyle {
    /** Authorization: Bearer <key>  — DeepSeek, OpenAI, most LLM APIs */
    Bearer,
    /** Header `api-key: <key>` (+ also sends Bearer for compatibility) */
    ApiKeyHeader
}

data class AsrConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val language: String,
    val apiStyle: AsrApiStyle = AsrApiStyle.OpenAiTranscriptions,
    val authStyle: ApiAuthStyle = ApiAuthStyle.Bearer
)
