package com.example.livetranslate.data.asr

import com.example.livetranslate.data.audio.WavEncoder
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
import com.example.livetranslate.data.network.JsonLite
import java.io.IOException

/**
 * OpenAI-compatible streaming ASR client.
 *
 * POST {baseUrl}/v1/audio/transcriptions with stream=true,
 * then parse SSE chunks and emit [AsrStreamEvent.Delta] with **merged display text**
 * (after [AsrTextMerger]) so the UI can replace partialEn each time.
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
        val url = "$base/v1/audio/transcriptions"

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

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body)
            .build()

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
            // Stream SSE: each line may be a JSON chunk with incremental or full text.
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
     * Best-effort extraction of transcript text from heterogeneous vendor JSON.
     * Prefers top-level "text", then "delta" string, then nested "content".
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
}
