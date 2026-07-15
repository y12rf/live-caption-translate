package com.example.livetranslate.data.llm

import com.example.livetranslate.data.asr.AsrClient.Companion.applyAuth
import com.example.livetranslate.data.network.ApiUrlResolver
import com.example.livetranslate.data.network.JsonLite
import com.example.livetranslate.data.network.SseReader
import com.example.livetranslate.domain.model.ContextTurn
import com.example.livetranslate.domain.model.LlmStreamEvent
import com.example.livetranslate.domain.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        // fullUrl=true → as-is; else full path after /v1/ as-is, otherwise append /v1/chat/completions
        val url = ApiUrlResolver.resolve(
            config.baseUrl,
            "/v1/chat/completions",
            fullUrl = config.fullUrl
        )

        val historyBlock = if (context.isEmpty()) {
            "(none)"
        } else {
            context.joinToString("\n") { turn ->
                "EN: ${turn.source}\nZH: ${turn.translation}"
            }
        }

        // Customizable system prompt from settings; support {{to}} / {{from}}.
        val system = config.systemPrompt
            .replace("{{to}}", config.targetLanguage)
            .replace("{{from}}", config.sourceLanguage)
            .ifBlank {
                "你是一位精通 ${config.targetLanguage} 专业母语译者，致力于提供流畅、地道、符合表达习惯且高保真的翻译。"
            }

        val user = buildString {
            append("请将当前句从 ")
            append(config.sourceLanguage)
            append(" 译为 ")
            append(config.targetLanguage)
            append("。只输出当前句译文，不要重译历史；历史仅供术语与指代一致。\n\n")
            append("History:\n")
            append(historyBlock)
            append("\n\nCurrent (")
            append(config.sourceLanguage)
            append("):\n")
            append(sourceText)
        }

        // Build JSON as string (avoids Android JSONObject unit-test stubs).
        val bodyJson = buildString {
            append('{')
            append("\"model\":\"").append(JsonLite.escape(config.model)).append("\",")
            append("\"stream\":true,")
            when (config.thinking) {
                LlmThinkingMode.Default -> { /* omit thinking field */ }
                LlmThinkingMode.True -> append("\"thinking\":true,")
                LlmThinkingMode.False -> append("\"thinking\":false,")
            }
            append("\"messages\":[")
            append("{\"role\":\"system\",\"content\":\"")
                .append(JsonLite.escape(system)).append("\"},")
            append("{\"role\":\"user\",\"content\":\"")
                .append(JsonLite.escape(user)).append("\"}")
            append("]}")
        }

        val keyPreview = config.apiKey.trim().let { k ->
            when {
                k.isEmpty() -> "(empty)"
                k.length <= 8 -> "***"
                else -> "${k.take(4)}…${k.takeLast(4)} (len=${k.length})"
            }
        }

        val request = Request.Builder()
            .url(url)
            .applyAuth(config.authStyle, config.apiKey)
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = http.newCall(request)
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val code = response.code
                val msg = response.body?.string().orEmpty().take(500)
                response.close()
                val hint = when (code) {
                    401, 403 ->
                        " 鉴权失败：检查 LLM API Key、LLM auth（Bearer/ApiKeyHeader）、" +
                            "以及 API URL 是否指向正确服务。当前 auth=${config.authStyle} url=$url key=$keyPreview"
                    else -> " url=$url auth=${config.authStyle}"
                }
                trySend(
                    LlmStreamEvent.Error(
                        IOException("LLM HTTP $code:$hint | body=$msg"),
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

    /**
     * Non-streaming chat completion (single response). Used for session title, etc.
     */
    suspend fun completeOnce(
        systemPrompt: String,
        userPrompt: String,
        config: LlmConfig
    ): String = withContext(Dispatchers.IO) {
        val url = ApiUrlResolver.resolve(
            config.baseUrl,
            "/v1/chat/completions",
            fullUrl = config.fullUrl
        )
        val bodyJson = buildString {
            append('{')
            append("\"model\":\"").append(JsonLite.escape(config.model)).append("\",")
            append("\"stream\":false,")
            when (config.thinking) {
                LlmThinkingMode.Default -> { }
                LlmThinkingMode.True -> append("\"thinking\":true,")
                LlmThinkingMode.False -> append("\"thinking\":false,")
            }
            append("\"messages\":[")
            append("{\"role\":\"system\",\"content\":\"")
                .append(JsonLite.escape(systemPrompt)).append("\"},")
            append("{\"role\":\"user\",\"content\":\"")
                .append(JsonLite.escape(userPrompt)).append("\"}")
            append("]}")
        }
        val request = Request.Builder()
            .url(url)
            .applyAuth(config.authStyle, config.apiKey)
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val call = http.newCall(request)
        call.execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("LLM HTTP ${response.code}: ${body.take(300)}")
            }
            // Prefer message.content; fall back to any content string
            JsonLite.firstStringField(body, "content")?.trim().orEmpty()
        }
    }

    /**
     * Summarize the first turns into a short history title (Chinese, ≤20 chars preferred).
     */
    suspend fun summarizeSessionTitle(
        segments: List<TranscriptSegment>,
        config: LlmConfig
    ): String {
        val sample = segments.take(TITLE_TURN_SAMPLE)
        val dialogue = sample.mapIndexed { i, seg ->
            "${i + 1}. [${seg.source}] → [${seg.translation}]"
        }.joinToString("\n")
        val system = "你是会议/课堂笔记助手，擅长用一句短语概括对话主题。"
        val user = buildString {
            append("根据下列同传对话（原文→译文），生成一个简短中文标题。\n")
            append("要求：不超过20个字；不要引号、编号、标点装饰；只输出标题本身。\n\n")
            append(dialogue)
        }
        val raw = completeOnce(system, user, config)
        return sanitizeTitle(raw)
    }

    internal fun extractDeltaContent(payload: String): String? {
        return try {
            // Prefer nested delta.content; fall back to any "content" string field.
            JsonLite.firstStringField(payload, "content")
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val TITLE_TURN_THRESHOLD = 10
        private const val TITLE_TURN_SAMPLE = 10
        private const val TITLE_MAX_CHARS = 40

        fun sanitizeTitle(raw: String): String {
            var t = raw.trim()
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
                .trim()
            // Strip common wrappers
            t = t.removePrefix("标题：").removePrefix("标题:").trim()
            t = t.trim('"', '“', '”', '\'', '「', '」', '《', '》')
            return t.take(TITLE_MAX_CHARS)
        }
    }
}
