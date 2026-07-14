package com.example.livetranslate.data.asr

import com.example.livetranslate.data.audio.WavEncoder
import com.example.livetranslate.data.network.JsonLite
import com.example.livetranslate.data.network.SseReader
import com.example.livetranslate.domain.AsrTextMerger
import com.example.livetranslate.domain.model.AsrStreamEvent
import com.example.livetranslate.domain.model.UtteranceAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Streaming ASR client supporting:
 * 1) OpenAI-style `/v1/audio/transcriptions` (multipart)
 * 2) Chat completions + base64 `input_audio` (Xiaomi MIMO `mimo-v2.5-asr` etc.)
 *
 * Both use `stream=true` and parse SSE lines; each chunk updates UI via [AsrStreamEvent.Delta].
 */
class AsrClient(
    private val http: OkHttpClient
) {
    fun transcribeStream(
        audio: UtteranceAudio,
        config: AsrConfig
    ): Flow<AsrStreamEvent> = callbackFlow {
        val wav = WavEncoder.pcm16MonoToWav(audio.pcm, audio.sampleRate)
        val base = config.baseUrl.trim().trimEnd('/')
        // Avoid double /v1 if user pastes .../v1 already
        val root = base.removeSuffix("/v1")

        val request = when (config.apiStyle) {
            AsrApiStyle.OpenAiTranscriptions -> buildTranscriptionsRequest(root, wav, config)
            AsrApiStyle.ChatCompletionsAudio -> buildChatAudioRequest(root, wav, config)
        }

        val call = http.newCall(request)
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val code = response.code
                val msg = response.body?.string().orEmpty()
                response.close()
                trySend(
                    AsrStreamEvent.Error(
                        IOException("ASR HTTP $code: $msg"),
                        retryable = code == 408 || code == 429 || code in 500..599
                    )
                )
                close()
                return@callbackFlow
            }
            val source = response.body?.source()
            if (source == null) {
                trySend(AsrStreamEvent.Error(IOException("Empty ASR body"), retryable = true))
                close()
                return@callbackFlow
            }
            var acc = ""
            // SSE: data: {...}\n\n  — emit merged text for typewriter UI
            for (payload in SseReader.readPayloads(source)) {
                val piece = extractText(payload) ?: continue
                acc = AsrTextMerger.merge(acc, piece)
                trySend(AsrStreamEvent.Delta(acc))
            }
            trySend(AsrStreamEvent.Completed(acc))
            response.close()
            close()
        } catch (e: Exception) {
            if (call.isCanceled()) {
                close()
                return@callbackFlow
            }
            trySend(AsrStreamEvent.Error(e, retryable = true))
            close(e)
        }

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /**
     * OpenAI Whisper-style:
     * POST {base}/v1/audio/transcriptions  multipart file + stream=true
     */
    private fun buildTranscriptionsRequest(
        root: String,
        wav: ByteArray,
        config: AsrConfig
    ): Request {
        val url = "$root/v1/audio/transcriptions"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "utterance.wav",
                wav.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", config.model)
            .addFormDataPart("language", config.language)
            .addFormDataPart("stream", "true")
            .build()
        return Request.Builder()
            .url(url)
            .applyAuth(config.authStyle, config.apiKey)
            .post(body)
            .build()
    }

    /**
     * MIMO / chat-audio style (matches official curl):
     * POST {base}/v1/chat/completions
     * messages[0].content[] = input_audio data URI base64
     * asr_options.language, stream=true
     */
    private fun buildChatAudioRequest(
        root: String,
        wav: ByteArray,
        config: AsrConfig
    ): Request {
        val url = "$root/v1/chat/completions"
        val b64 = java.util.Base64.getEncoder().encodeToString(wav)
        val dataUri = "data:audio/wav;base64,$b64"
        val lang = config.language.ifBlank { "auto" }

        val bodyJson = buildString {
            append('{')
            append("\"model\":\"").append(JsonLite.escape(config.model)).append("\",")
            append("\"stream\":true,")
            append("\"messages\":[{")
            append("\"role\":\"user\",")
            append("\"content\":[{")
            append("\"type\":\"input_audio\",")
            append("\"input_audio\":{")
            append("\"data\":\"").append(JsonLite.escape(dataUri)).append("\"")
            append("}}]}],")
            append("\"asr_options\":{")
            append("\"language\":\"").append(JsonLite.escape(lang)).append("\"")
            append("}")
            append('}')
        }

        return Request.Builder()
            .url(url)
            .applyAuth(config.authStyle, config.apiKey)
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Best-effort extraction from SSE JSON chunks (text / delta / content).
     */
    internal fun extractText(payload: String): String? {
        return try {
            JsonLite.firstStringField(payload, "text")
                ?: JsonLite.firstStringField(payload, "delta")
                ?: JsonLite.firstStringField(payload, "content")
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun Request.Builder.applyAuth(style: ApiAuthStyle, apiKey: String): Request.Builder {
            return when (style) {
                ApiAuthStyle.Bearer -> header("Authorization", "Bearer $apiKey")
                ApiAuthStyle.ApiKeyHeader -> header("api-key", apiKey)
            }
        }
    }
}
