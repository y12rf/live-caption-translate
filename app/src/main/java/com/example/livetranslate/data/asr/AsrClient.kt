package com.example.livetranslate.data.asr

import com.example.livetranslate.data.audio.WavEncoder
import com.example.livetranslate.data.network.ApiUrlResolver
import com.example.livetranslate.data.network.JsonLite
import com.example.livetranslate.data.network.NetworkErrors
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
 * Streaming ASR client.
 *
 * URL: [ApiUrlResolver] — full path after `/v1/` used as-is; otherwise OpenAI-style default path.
 * Body style still selected by [AsrApiStyle] (multipart Whisper vs chat+base64 audio).
 */
class AsrClient(
    private val http: OkHttpClient
) {
    fun transcribeStream(
        audio: UtteranceAudio,
        config: AsrConfig
    ): Flow<AsrStreamEvent> = callbackFlow {
        val wav = WavEncoder.pcm16MonoToWav(audio.pcm, audio.sampleRate)
        val defaultPath = when (config.apiStyle) {
            AsrApiStyle.OpenAiTranscriptions -> "/v1/audio/transcriptions"
            AsrApiStyle.ChatCompletionsAudio -> "/v1/chat/completions"
        }
        val url = ApiUrlResolver.resolve(config.baseUrl, defaultPath, fullUrl = config.fullUrl)

        val request = when (config.apiStyle) {
            AsrApiStyle.OpenAiTranscriptions -> buildTranscriptionsRequest(url, wav, config)
            AsrApiStyle.ChatCompletionsAudio -> buildChatAudioRequest(url, wav, config)
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
                        retryable = NetworkErrors.isRetryableHttp(code)
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
            trySend(
                AsrStreamEvent.Error(
                    e,
                    retryable = NetworkErrors.isRetryableThrowable(e)
                )
            )
            close(e)
        }

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun buildTranscriptionsRequest(
        url: String,
        wav: ByteArray,
        config: AsrConfig
    ): Request {
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

    private fun buildChatAudioRequest(
        url: String,
        wav: ByteArray,
        config: AsrConfig
    ): Request {
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
        /**
         * Normalize key and apply auth headers.
         *
         * MIMO's OpenAI Python SDK uses `Authorization: Bearer <key>`.
         * Their curl examples often use `api-key: <key>`.
         * For [ApiAuthStyle.ApiKeyHeader] we send **both** so either style of gateway works.
         */
        fun Request.Builder.applyAuth(style: ApiAuthStyle, apiKey: String): Request.Builder {
            val key = normalizeApiKey(apiKey)
            return when (style) {
                ApiAuthStyle.Bearer ->
                    header("Authorization", "Bearer $key")
                ApiAuthStyle.ApiKeyHeader -> {
                    header("api-key", key)
                    // OpenAI-compatible clients (incl. MIMO SDK) expect Bearer
                    header("Authorization", "Bearer $key")
                }
            }
        }

        fun normalizeApiKey(apiKey: String): String {
            var k = apiKey.trim()
            // User may paste "Bearer sk-xxx" into the key field
            if (k.startsWith("Bearer ", ignoreCase = true)) {
                k = k.substring(7).trim()
            }
            return k
        }
    }
}
