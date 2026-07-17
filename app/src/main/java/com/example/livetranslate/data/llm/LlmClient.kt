package com.example.livetranslate.data.llm

import com.example.livetranslate.data.asr.AsrClient.Companion.applyAuth
import com.example.livetranslate.data.network.ApiUrlResolver
import com.example.livetranslate.data.network.JsonLite
import com.example.livetranslate.data.network.NetworkErrors
import com.example.livetranslate.data.network.SseReader
import com.example.livetranslate.data.settings.UserSettings
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
    ): Flow<LlmStreamEvent> {
        val from = config.sourceLanguage.trim().ifBlank { "source" }
        val to = config.targetLanguage.trim().ifBlank { "target" }
        val historyBlock = if (context.isEmpty()) {
            "(none)"
        } else {
            context.joinToString("\n") { turn ->
                // Language-agnostic labels (not hardcoded EN/ZH).
                "[$from] ${turn.source}\n[$to] ${turn.translation}"
            }
        }

        // System / user templates from settings (editable); fill remaining placeholders.
        val system = config.systemPrompt
            .ifBlank { UserSettings.DEFAULT_LLM_SYSTEM_PROMPT }
            .replace("{{from}}", from)
            .replace("{{to}}", to)
            .replace("{{glossary}}", "")

        val user = config.userPromptTemplate
            .ifBlank { UserSettings.DEFAULT_LLM_USER_PROMPT }
            .replace("{{from}}", from)
            .replace("{{to}}", to)
            .replace("{{history}}", historyBlock)
            .replace("{{text}}", sourceText)

        return chatStream(system, user, config)
    }

    /**
     * Streaming chat completion with explicit system/user content
     * (used by offline batch translate and [translateStream]).
     */
    fun chatStream(
        system: String,
        user: String,
        config: LlmConfig
    ): Flow<LlmStreamEvent> = callbackFlow {
        // fullUrl=true → as-is; else full path after /v1/ as-is, otherwise append /v1/chat/completions
        val url = ApiUrlResolver.resolve(
            config.baseUrl,
            "/v1/chat/completions",
            fullUrl = config.fullUrl
        )

        // Build JSON as string (avoids Android JSONObject unit-test stubs).
        // thinking / reasoning_effort (or output_config) after messages.
        val bodyJson = buildString {
            append('{')
            append("\"model\":\"").append(JsonLite.escape(config.model)).append("\",")
            append("\"stream\":true,")
            append("\"messages\":[")
            append("{\"role\":\"system\",\"content\":\"")
                .append(JsonLite.escape(system)).append("\"},")
            append("{\"role\":\"user\",\"content\":\"")
                .append(JsonLite.escape(user)).append("\"}")
            append(']')
            LlmThinkingBody.appendAfterMessages(
                this,
                mode = config.thinking,
                effort = config.reasoningEffort,
                style = config.reasoningEffortStyle
            )
            append('}')
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
                        retryable = NetworkErrors.isRetryableHttp(code)
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
            // Strip residual thinking wrappers that may span multiple deltas.
            trySend(LlmStreamEvent.Completed(stripThinkingArtifacts(acc.toString())))
            response.close()
            close()
        } catch (e: Exception) {
            if (call.isCanceled()) {
                close()
                return@callbackFlow
            }
            trySend(
                LlmStreamEvent.Error(
                    e,
                    retryable = NetworkErrors.isRetryableThrowable(e)
                )
            )
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
            append("\"messages\":[")
            append("{\"role\":\"system\",\"content\":\"")
                .append(JsonLite.escape(systemPrompt)).append("\"},")
            append("{\"role\":\"user\",\"content\":\"")
                .append(JsonLite.escape(userPrompt)).append("\"}")
            append(']')
            LlmThinkingBody.appendAfterMessages(
                this,
                mode = config.thinking,
                effort = config.reasoningEffort,
                style = config.reasoningEffortStyle
            )
            append('}')
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
            // Prefer message.content (not nested thinking.content)
            val raw = extractMessageContent(body) ?: ""
            stripThinkingArtifacts(raw).trim()
        }
    }

    /**
     * Summarize the first turns into a short history title.
     * Prompts come from [LlmConfig.titleSystemPrompt] / [LlmConfig.titleUserPromptTemplate]
     * (Settings-editable; English defaults).
     */
    suspend fun summarizeSessionTitle(
        segments: List<TranscriptSegment>,
        config: LlmConfig
    ): String {
        val sample = segments.take(TITLE_TURN_SAMPLE)
        val dialogue = sample.mapIndexed { i, seg ->
            "${i + 1}. [${seg.source}] → [${seg.translation}]"
        }.joinToString("\n")
        val system = config.titleSystemPrompt
            .ifBlank { UserSettings.DEFAULT_LLM_TITLE_SYSTEM_PROMPT }
        val user = config.titleUserPromptTemplate
            .ifBlank { UserSettings.DEFAULT_LLM_TITLE_USER_PROMPT }
            .replace("{{dialogue}}", dialogue)
        val raw = completeOnce(system, user, config)
        return sanitizeTitle(raw)
    }

    /**
     * Extract assistant text from an SSE chunk.
     *
     * Prefers `choices[].delta.content` and **ignores** thinking / reasoning fields
     * (`reasoning_content`, `thinking`, nested `thinking.content`) so chain-of-thought
     * is never concatenated into the translation stream.
     */
    internal fun extractDeltaContent(payload: String): String? {
        return try {
            val deltaBody = extractObjectAfterKey(payload, "delta") ?: return null
            // Explicit null content (common while only reasoning streams)
            if (Regex("\"content\"\\s*:\\s*null").containsMatchIn(deltaBody)) {
                return null
            }
            val content = extractTopLevelStringField(deltaBody, "content") ?: return null
            // Do not strip mid-stream (tags may span chunks); only emit raw content piece.
            // Final [Completed] runs stripThinkingArtifacts on the full buffer.
            content.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Non-stream response: prefer `message.content` over any nested thinking content.
     */
    internal fun extractMessageContent(body: String): String? {
        val messageBody = extractObjectAfterKey(body, "message")
        if (messageBody != null) {
            extractTopLevelStringField(messageBody, "content")?.let { return it }
        }
        // Fallback: first top-level-ish content (still better than nested thinking)
        return extractTopLevelStringField(body, "content")
            ?: JsonLite.firstStringField(body, "content")
    }

    companion object {
        const val TITLE_TURN_THRESHOLD = 10
        private const val TITLE_TURN_SAMPLE = 10
        private const val TITLE_MAX_CHARS = 40

        /**
         * First balanced `{...}` value after `"key":`.
         * Used to scope field lookups to `delta` / `message` objects.
         */
        internal fun extractObjectAfterKey(json: String, key: String): String? {
            val keyPat = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\\{")
            val m = keyPat.find(json) ?: return null
            val start = m.range.last // index of '{'
            var depth = 0
            var i = start
            var inString = false
            var escape = false
            while (i < json.length) {
                val c = json[i]
                if (inString) {
                    when {
                        escape -> escape = false
                        c == '\\' -> escape = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                // exclude surrounding braces
                                return json.substring(start + 1, i)
                            }
                        }
                    }
                }
                i++
            }
            return null
        }

        /**
         * String field at the **current object depth only** (does not enter nested `{}`).
         * Avoids matching `"content"` inside `"thinking":{"content":"..."}`.
         */
        internal fun extractTopLevelStringField(objectBody: String, key: String): String? {
            var depth = 0
            var i = 0
            var inString = false
            var escape = false
            val keyNeedle = "\"$key\""
            while (i < objectBody.length) {
                val c = objectBody[i]
                if (inString) {
                    when {
                        escape -> escape = false
                        c == '\\' -> escape = true
                        c == '"' -> inString = false
                    }
                    i++
                    continue
                }
                when (c) {
                    '"' -> {
                        // candidate key at depth 0
                        if (depth == 0 && objectBody.startsWith(keyNeedle, i)) {
                            val afterKey = i + keyNeedle.length
                            val rest = objectBody.substring(afterKey)
                            val colon = Regex("^\\s*:\\s*\"").find(rest)
                            if (colon != null) {
                                val valueStart = afterKey + colon.range.last + 1
                                val value = readJsonString(objectBody, valueStart) ?: return null
                                return value
                            }
                        }
                        inString = true
                        i++
                    }
                    '{' -> {
                        depth++
                        i++
                    }
                    '}' -> {
                        depth--
                        i++
                    }
                    else -> i++
                }
            }
            return null
        }

        /** Read a JSON string starting at [start] (first char after opening quote). */
        private fun readJsonString(s: String, start: Int): String? {
            val out = StringBuilder()
            var i = start
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        'u' -> {
                            if (i + 5 < s.length) {
                                out.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                                i += 6
                                continue
                            }
                            out.append('u')
                        }
                        else -> out.append(s[i + 1])
                    }
                    i += 2
                    continue
                }
                if (c == '"') return out.toString()
                out.append(c)
                i++
            }
            return null
        }

        /**
         * Remove chain-of-thought wrappers that some models still put in `content`.
         * Also drops leading `reasoning` / `thinking` fence blocks.
         */
        fun stripThinkingArtifacts(raw: String): String {
            var t = raw
            // <think>...</think> and <thinking>...</thinking> (greedy multi-block)
            t = t.replace(Regex("(?is)<think\\b[^>]*>.*?</think>"), "")
            t = t.replace(Regex("(?is)<thinking\\b[^>]*>.*?</thinking>"), "")
            // Unclosed think tag at start → drop until end tag or whole prefix
            t = t.replace(Regex("(?is)^\\s*<think\\b[^>]*>.*"), "")
            t = t.replace(Regex("(?is)^\\s*<thinking\\b[^>]*>.*"), "")
            // Markdown-style fences some gateways emit
            t = t.replace(Regex("(?is)```(?:thinking|reasoning)\\s*.*?```"), "")
            return t.trim()
        }

        fun sanitizeTitle(raw: String): String {
            var t = stripThinkingArtifacts(raw).trim()
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
