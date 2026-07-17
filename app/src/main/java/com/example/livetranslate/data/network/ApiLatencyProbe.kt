package com.example.livetranslate.data.network

import com.example.livetranslate.data.asr.ApiAuthStyle
import com.example.livetranslate.data.asr.AsrApiStyle
import com.example.livetranslate.data.asr.AsrClient.Companion.applyAuth
import com.example.livetranslate.data.audio.WavEncoder
import com.example.livetranslate.data.llm.LlmThinkingBody
import com.example.livetranslate.data.settings.UserSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * One-shot API latency probe for Settings.
 * Uses a short timeout client (independent of the streaming session client).
 */
class ApiLatencyProbe(
    private val http: OkHttpClient = defaultClient()
) {
    data class Result(
        val ok: Boolean,
        val latencyMs: Long,
        val httpCode: Int?,
        val url: String,
        val detail: String
    ) {
        fun summary(label: String): String {
            return if (ok) {
                "$label: ${latencyMs} ms (HTTP $httpCode)"
            } else {
                "$label: FAIL ${latencyMs} ms — $detail"
            }
        }
    }

    fun probeAsr(settings: UserSettings): Result {
        val defaultPath = when (settings.asrApiStyleEnum()) {
            AsrApiStyle.OpenAiTranscriptions -> "/v1/audio/transcriptions"
            AsrApiStyle.ChatCompletionsAudio -> "/v1/chat/completions"
        }
        val url = try {
            ApiUrlResolver.resolve(
                settings.normalizedAsrBaseUrl(),
                defaultPath,
                fullUrl = settings.asrFullUrl
            )
        } catch (e: Exception) {
            return Result(false, 0, null, settings.asrBaseUrl, e.message ?: "bad URL")
        }
        if (settings.asrApiKey.isBlank()) {
            return Result(false, 0, null, url, "ASR API Key is empty")
        }

        // ~100 ms silence @ 16 kHz mono 16-bit
        val pcm = ByteArray(16000 / 10 * 2)
        val wav = WavEncoder.pcm16MonoToWav(pcm, sampleRate = 16_000)

        val request = when (settings.asrApiStyleEnum()) {
            AsrApiStyle.OpenAiTranscriptions -> {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        "ping.wav",
                        wav.toRequestBody("audio/wav".toMediaType())
                    )
                    .addFormDataPart("model", settings.asrModel.ifBlank { "whisper-1" })
                    .addFormDataPart("language", settings.inputLanguage.ifBlank { "en" })
                    .build()
                Request.Builder()
                    .url(url)
                    .applyAuth(settings.asrAuthStyleEnum(), settings.asrApiKey)
                    .post(body)
                    .build()
            }
            AsrApiStyle.ChatCompletionsAudio -> {
                val b64 = java.util.Base64.getEncoder().encodeToString(wav)
                val dataUri = "data:audio/wav;base64,$b64"
                val lang = settings.inputLanguage.ifBlank { "auto" }
                val bodyJson = buildString {
                    append('{')
                    append("\"model\":\"").append(JsonLite.escape(settings.asrModel)).append("\",")
                    append("\"stream\":false,")
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
                Request.Builder()
                    .url(url)
                    .applyAuth(settings.asrAuthStyleEnum(), settings.asrApiKey)
                    .header("Content-Type", "application/json")
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()
            }
        }
        return execute(request, url)
    }

    fun probeLlm(settings: UserSettings): Result {
        val url = try {
            ApiUrlResolver.resolve(
                settings.normalizedLlmBaseUrl(),
                "/v1/chat/completions",
                fullUrl = settings.llmFullUrl
            )
        } catch (e: Exception) {
            return Result(false, 0, null, settings.llmBaseUrl, e.message ?: "bad URL")
        }
        if (settings.llmApiKey.isBlank()) {
            return Result(false, 0, null, url, "LLM API Key is empty")
        }

        val bodyJson = buildString {
            append('{')
            append("\"model\":\"").append(JsonLite.escape(settings.llmModel.ifBlank { "gpt-4o-mini" })).append("\",")
            append("\"stream\":false,")
            append("\"max_tokens\":1,")
            append("\"messages\":[")
            append("{\"role\":\"user\",\"content\":\"ping\"}")
            append(']')
            LlmThinkingBody.appendAfterMessages(
                this,
                mode = settings.llmThinkingMode(),
                effort = settings.llmReasoningEffortEnum()
            )
            append('}')
        }
        val request = Request.Builder()
            .url(url)
            .applyAuth(settings.llmAuthStyleEnum(), settings.llmApiKey)
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return execute(request, url)
    }

    private fun execute(request: Request, url: String): Result {
        var code: Int? = null
        var detail = ""
        var ok: Boolean
        val ms = measureTimeMillis {
            try {
                http.newCall(request).execute().use { resp ->
                    code = resp.code
                    val bodyPreview = resp.body?.string().orEmpty().take(180)
                    ok = resp.isSuccessful
                    detail = if (ok) {
                        "OK"
                    } else {
                        "HTTP $code ${bodyPreview.ifBlank { resp.message }}"
                    }
                }
            } catch (e: Exception) {
                ok = false
                detail = e.message ?: e.javaClass.simpleName
            }
        }
        return Result(ok = ok, latencyMs = ms, httpCode = code, url = url, detail = detail)
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
