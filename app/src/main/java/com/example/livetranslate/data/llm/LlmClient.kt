package com.example.livetranslate.data.llm

import com.example.livetranslate.data.network.SseReader
import com.example.livetranslate.domain.model.ContextTurn
import com.example.livetranslate.domain.model.LlmStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.livetranslate.data.network.JsonLite
import java.io.IOException

/**
 * OpenAI-compatible chat completions streaming client for translation.
 *
 * Emits [LlmStreamEvent.Delta] with **piece only** (append); [Completed] has full text.
 */
class LlmClient(
    private val http: OkHttpClient
) {
    fun translateStream(
        sourceText: String,
        context: List<ContextTurn>,
        config: LlmConfig
    ): Flow<LlmStreamEvent> = callbackFlow {
        val base = config.baseUrl.trim().trimEnd('/')
        val url = "$base/v1/chat/completions"

        val historyBlock = if (context.isEmpty()) {
            "(none)"
        } else {
            context.joinToString("\n") { turn ->
                "EN: ${turn.source}\nZH: ${turn.translation}"
            }
        }

        val system = buildString {
            append("You are a simultaneous interpreter for lecture notes. ")
            append("Translate the user's English into ")
            append(config.targetLanguage)
            append(". Output only the translation for the CURRENT utterance. ")
            append("Do not re-translate history. Use history only for terminology consistency.")
        }
        val user = buildString {
            append("History:\n")
            append(historyBlock)
            append("\n\nCurrent EN:\n")
            append(sourceText)
        }

        // Build JSON as string (avoids Android JSONObject unit-test stubs).
        val bodyJson = buildString {
            append('{')
            append("\"model\":\"").append(JsonLite.escape(config.model)).append("\",")
            append("\"stream\":true,")
            append("\"messages\":[")
            append("{\"role\":\"system\",\"content\":\"")
                .append(JsonLite.escape(system)).append("\"},")
            append("{\"role\":\"user\",\"content\":\"")
                .append(JsonLite.escape(user)).append("\"}")
            append("]}")
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(request)
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val code = response.code
                val msg = response.body?.string().orEmpty()
                response.close()
                trySend(
                    LlmStreamEvent.Error(
                        IOException("LLM HTTP $code: $msg"),
                        retryable = code == 408 || code == 429 || code in 500..599
                    )
                )
                close()
                return@callbackFlow
            }
            val source = response.body?.source()
            if (source == null) {
                trySend(LlmStreamEvent.Error(IOException("Empty LLM body"), retryable = true))
                close()
                return@callbackFlow
            }
            val acc = StringBuilder()
            for (payload in SseReader.readPayloads(source)) {
                val piece = extractDeltaContent(payload) ?: continue
                acc.append(piece)
                trySend(LlmStreamEvent.Delta(piece))
            }
            trySend(LlmStreamEvent.Completed(acc.toString()))
            response.close()
            close()
        } catch (e: Exception) {
            if (call.isCanceled()) {
                close()
                return@callbackFlow
            }
            trySend(LlmStreamEvent.Error(e, retryable = true))
            close(e)
        }

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    internal fun extractDeltaContent(payload: String): String? {
        return try {
            // Prefer nested delta.content; fall back to any "content" string field.
            JsonLite.firstStringField(payload, "content")
        } catch (_: Exception) {
            null
        }
    }
}
