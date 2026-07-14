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

/** Auth header style for OpenAI-compatible gateways. */
enum class ApiAuthStyle {
    /** Authorization: Bearer <key> */
    Bearer,
    /** api-key: <key>  (Xiaomi MIMO etc.) */
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
