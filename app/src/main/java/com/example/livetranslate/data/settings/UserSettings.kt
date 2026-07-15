package com.example.livetranslate.data.settings

import com.example.livetranslate.data.asr.ApiAuthStyle
import com.example.livetranslate.data.asr.AsrApiStyle
import com.example.livetranslate.data.llm.LlmThinkingMode

data class UserSettings(
    val asrBaseUrl: String = "https://api.openai.com",
    val asrApiKey: String = "",
    val asrModel: String = "whisper-1",
    /** transcriptions = OpenAI Whisper multipart; chat_audio = MIMO-style chat+base64 */
    val asrApiStyle: String = AsrApiStyle.OpenAiTranscriptions.name,
    val asrAuthStyle: String = ApiAuthStyle.ApiKeyHeader.name,
    /** When true, ASR URL is used as-is (no OpenAI path append). */
    val asrFullUrl: Boolean = false,
    val llmBaseUrl: String = "https://api.openai.com",
    val llmApiKey: String = "",
    val llmModel: String = "gpt-4o-mini",
    // DeepSeek / OpenAI chat: Bearer. ASR MIMO may still use ApiKeyHeader separately.
    val llmAuthStyle: String = ApiAuthStyle.Bearer.name,
    /** When true, LLM URL is used as-is (no OpenAI path append). */
    val llmFullUrl: Boolean = false,
    /**
     * LLM body `thinking` field:
     * Default = omit; true / false = send boolean.
     * Stored as [LlmThinkingMode] name: Default | True | False
     */
    val llmThinking: String = LlmThinkingMode.Default.name,
    /**
     * LLM system prompt template. Placeholders:
     * - `{{to}}` → output language (e.g. zh / Chinese)
     * - `{{from}}` → input language (e.g. en / English)
     * - `{{glossary}}` → formatted glossary lines (may be empty)
     */
    val llmSystemPrompt: String = DEFAULT_LLM_SYSTEM_PROMPT,
    /** Global glossary pairs for `{{glossary}}` injection. */
    val glossaryTerms: List<GlossaryEntry> = emptyList(),
    /**
     * App UI language: [com.example.livetranslate.util.AppLocale.EN] (default)
     * or [com.example.livetranslate.util.AppLocale.ZH]. Independent of ASR/LLM languages.
     */
    val uiLanguage: String = "en",
    val inputLanguage: String = "en",
    val outputLanguage: String = "zh",
    val silenceMs: Int = 500,
    val maxUtteranceMs: Int = 4_500,
    val minUtteranceMs: Int = 1_700,
    val energyThreshold: Double = 400.0,
    val contextWindowSize: Int = 2,
    // Floating subtitle overlay
    /** Max width in dp (clamped to screen). */
    val overlayMaxWidthDp: Int = 360,
    /** Max height in dp; content scrolls if taller. */
    val overlayMaxHeightDp: Int = 140,
    /** Background opacity 0–100 (0 = fully transparent, 100 = solid). */
    val overlayAlphaPercent: Int = 80,
    /** Background RGB, e.g. #000000 or #FF000000 (alpha ignored; use overlayAlphaPercent). */
    val overlayBgColor: String = DEFAULT_OVERLAY_BG,
    /** Source-line text color, e.g. #FFFFFF */
    val overlayEnTextColor: String = DEFAULT_OVERLAY_EN,
    /** Translation-line text color, e.g. #FFEB3B */
    val overlayZhTextColor: String = DEFAULT_OVERLAY_ZH,
    /**
     * Overlay stack order:
     * true  = translation (ZH) on top, original (EN) below (default)
     * false = original on top, translation below
     */
    val overlayTranslationOnTop: Boolean = true
) {
    fun normalizedAsrBaseUrl(): String = normalizeBaseUrl(asrBaseUrl)
    fun normalizedLlmBaseUrl(): String = normalizeBaseUrl(llmBaseUrl)

    fun asrApiStyleEnum(): AsrApiStyle =
        runCatching { AsrApiStyle.valueOf(asrApiStyle) }.getOrDefault(AsrApiStyle.OpenAiTranscriptions)

    fun asrAuthStyleEnum(): ApiAuthStyle =
        runCatching { ApiAuthStyle.valueOf(asrAuthStyle) }.getOrDefault(ApiAuthStyle.ApiKeyHeader)

    fun llmAuthStyleEnum(): ApiAuthStyle =
        runCatching { ApiAuthStyle.valueOf(llmAuthStyle) }.getOrDefault(ApiAuthStyle.Bearer)

    fun llmThinkingMode(): LlmThinkingMode = LlmThinkingMode.fromStorage(llmThinking)

    fun renderLlmSystemPrompt(): String =
        llmSystemPrompt
            .replace("{{to}}", outputLanguage)
            .replace("{{from}}", inputLanguage)
            .replace("{{glossary}}", GlossaryCodec.formatBlock(glossaryTerms))

    companion object {
        const val DEFAULT_LLM_SYSTEM_PROMPT: String =
            "你是一位精通 {{to}} 专业母语译者，致力于提供流畅、地道、符合表达习惯且高保真的翻译。" +
                "uh,yeah这种口水话请略过不要翻译，可以结合上下文修正语音识别的错误，修正口吃的重复表达。" +
                "术语表（须优先遵守；可为空）：\n{{glossary}}"

        const val DEFAULT_OVERLAY_BG = "#000000"
        const val DEFAULT_OVERLAY_EN = "#FFFFFF"
        const val DEFAULT_OVERLAY_ZH = "#FFEB3B"

        fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')

        /**
         * Parse #RGB / #RRGGBB / #AARRGGBB (optional #) into ARGB int.
         * On failure return [fallback]. Pure Kotlin (no android.graphics.Color).
         */
        fun parseColorHex(raw: String?, fallback: Int): Int {
            val s = raw?.trim()?.removePrefix("#")?.uppercase().orEmpty()
            if (s.isEmpty() || s.any { it !in "0123456789ABCDEF" }) return fallback
            val full = when (s.length) {
                3 -> buildString {
                    // RGB → RRGGBB, opaque
                    for (c in s) {
                        append(c)
                        append(c)
                    }
                }.let { "FF$it" }
                6 -> "FF$s"
                8 -> s
                else -> return fallback
            }
            return try {
                full.toLong(16).toInt()
            } catch (_: Exception) {
                fallback
            }
        }

        fun normalizeColorHex(raw: String, fallback: String): String {
            val s = raw.trim().removePrefix("#").uppercase()
            if (s.isEmpty() || s.any { it !in "0123456789ABCDEF" }) return fallback
            if (s.length != 3 && s.length != 6 && s.length != 8) return fallback
            val parsed = parseColorHex(raw, 0)
            val rgb = parsed and 0x00FFFFFF
            return String.format("#%06X", rgb)
        }
    }
}
