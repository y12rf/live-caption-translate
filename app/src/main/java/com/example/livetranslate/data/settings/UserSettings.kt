package com.example.livetranslate.data.settings

import com.example.livetranslate.data.asr.ApiAuthStyle
import com.example.livetranslate.data.asr.AsrApiStyle
import com.example.livetranslate.data.llm.LlmConfig
import com.example.livetranslate.data.llm.LlmReasoningEffort
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
     * Thinking switch: [LlmThinkingMode.Default] (omit field) /
     * [LlmThinkingMode.Enabled] / [LlmThinkingMode.Disabled].
     */
    val llmThinking: String = LlmThinkingMode.Default.name,
    /**
     * OpenAI `reasoning_effort` when thinking is Enabled: low | medium | high | max.
     */
    val llmReasoningEffort: String = LlmReasoningEffort.Low.name,
    /**
     * Translation system-prompt template. Placeholders:
     * `{{from}}` `{{to}}` `{{glossary}}`
     */
    val llmSystemPrompt: String = DEFAULT_LLM_SYSTEM_PROMPT,
    /**
     * Translation user-message template. Placeholders:
     * `{{from}}` `{{to}}` `{{history}}` `{{text}}`
     */
    val llmUserPrompt: String = DEFAULT_LLM_USER_PROMPT,
    /**
     * Session-title system prompt (no required placeholders).
     */
    val llmTitleSystemPrompt: String = DEFAULT_LLM_TITLE_SYSTEM_PROMPT,
    /**
     * Session-title user-message template. Placeholder: `{{dialogue}}`
     */
    val llmTitleUserPrompt: String = DEFAULT_LLM_TITLE_USER_PROMPT,
    /** Global glossary pairs for `{{glossary}}` injection. */
    val glossaryTerms: List<GlossaryEntry> = emptyList(),
    /**
     * App UI language: [com.example.livetranslate.util.AppLocale.EN] (default)
     * or [com.example.livetranslate.util.AppLocale.ZH]. Independent of ASR/LLM languages.
     */
    val uiLanguage: String = "en",
    val inputLanguage: String = "en",
    val outputLanguage: String = "zh",
    /**
     * Silero library [silenceDurationMs]: hangover after last speech before isSpeech goes false.
     * Default 260ms.
     */
    val silenceMs: Int = 260,
    val maxUtteranceMs: Int = 4_500,
    /**
     * Minimum voiced duration for a silence cut to emit.
     * Shorter cuts **hold PCM and merge into the next speech run** (not discarded).
     * Stop / max-duration always flush. 0 = no minimum.
     */
    val minUtteranceMs: Int = 1_500,
    /**
     * Silero VAD confidence mode: [SileroVadMode] name
     * (NORMAL / AGGRESSIVE / VERY_AGGRESSIVE).
     */
    val sileroVadMode: String = SileroVadMode.NORMAL.name,
    val contextWindowSize: Int = 2,
    /**
     * Offline file / reprocess: max VAD sentences packed into one ASR request
     * (also hard-capped by ~30s PCM duration in [com.example.livetranslate.data.audio.WavChunker]).
     * Default 15 — large packs (old default 70) often drop the opening and loop phrases.
     */
    val offlineVadBatchSize: Int = 15,
    /** Generate LLM session title after this many completed turns. */
    val titleTurnThreshold: Int = 10,
    /** ASR/LLM automatic retry attempts (1–10). */
    val maxNetworkAttempts: Int = 3,
    /** In-session translation cache capacity (0 = disabled growth still allows put until clear). */
    val translationCacheMax: Int = 50,
    /** Keep screen awake on Live screen. */
    val keepScreenOn: Boolean = false,
    /**
     * When true (default entry from Live), hide system bars.
     * Immersive content view is entered via Live top-bar fullscreen button; this still controls bars.
     */
    val immersiveMode: Boolean = false,
    /**
     * ASR-only session: recognize speech but skip LLM translation.
     * Segments keep source text; translation stays empty.
     */
    val asrOnlyMode: Boolean = false,
    // Floating subtitle overlay
    /** Max width in dp (clamped to screen). */
    val overlayMaxWidthDp: Int = 360,
    /** Max height in dp; content scrolls if taller. */
    val overlayMaxHeightDp: Int = DEFAULT_OVERLAY_HEIGHT_DP,
    /** Background opacity 0–100 (0 = fully transparent, 100 = solid). */
    val overlayAlphaPercent: Int = DEFAULT_OVERLAY_ALPHA,
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
    val overlayTranslationOnTop: Boolean = true,
    /**
     * Subtitle lines: both / source only / translation only.
     * Stored as [OverlayTextMode] name.
     */
    val overlayTextMode: String = OverlayTextMode.Both.name,
    /**
     * Caption layout: full sentence (center) or single-line marquee.
     * Stored as [OverlayLayoutMode] name.
     */
    val overlayLayoutMode: String = OverlayLayoutMode.FullSentence.name,
    /** Overlay caption text size in sp (10–48). */
    val overlayFontSizeSp: Int = DEFAULT_OVERLAY_FONT_SP,
    /**
     * Horizontal padding (dp) between caption text and overlay border / sides.
     * Smaller = tighter to edges. Typical 2–12.
     */
    val overlayPadHDp: Int = DEFAULT_OVERLAY_PAD_H_DP,
    /**
     * Vertical padding (dp) between caption text and border / divider.
     * Smaller = tighter rows. Typical 0–10.
     */
    val overlayPadVDp: Int = DEFAULT_OVERLAY_PAD_V_DP,
    /**
     * ScrollLine marquee speed in px/s (20–160). Only used when layout is ScrollLine.
     */
    val overlayMarqueeSpeed: Int = DEFAULT_OVERLAY_MARQUEE_SPEED,
    /**
     * When true (default), ScrollLine waits for the current caption to finish scrolling
     * before showing the next committed sentence (no mid-scroll jump).
     */
    val overlayMarqueeFinishBeforeNext: Boolean = true,
    /** Draw stroke around the floating panel (lock green / lock gray). */
    val overlayShowBorder: Boolean = DEFAULT_OVERLAY_SHOW_BORDER,
    /** Draw the line between source and translation when both are shown. */
    val overlayShowDivider: Boolean = true,
    /** Live home bilingual panel text size in sp (10–48). */
    val liveFontSizeSp: Int = DEFAULT_LIVE_FONT_SP
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

    fun llmReasoningEffortEnum(): LlmReasoningEffort =
        LlmReasoningEffort.fromStorage(llmReasoningEffort)

    fun overlayTextModeEnum(): OverlayTextMode = OverlayTextMode.fromStorage(overlayTextMode)

    fun overlayLayoutModeEnum(): OverlayLayoutMode = OverlayLayoutMode.fromStorage(overlayLayoutMode)

    fun renderLlmSystemPrompt(): String =
        llmSystemPrompt.ifBlank { DEFAULT_LLM_SYSTEM_PROMPT }
            .replace("{{from}}", inputLanguage.trim())
            .replace("{{to}}", outputLanguage.trim())
            .replace("{{glossary}}", GlossaryCodec.formatBlock(glossaryTerms))

    /** Effective translation user template (never blank). */
    fun effectiveLlmUserPrompt(): String =
        llmUserPrompt.ifBlank { DEFAULT_LLM_USER_PROMPT }

    fun effectiveLlmTitleSystemPrompt(): String =
        llmTitleSystemPrompt.ifBlank { DEFAULT_LLM_TITLE_SYSTEM_PROMPT }

    fun effectiveLlmTitleUserPrompt(): String =
        llmTitleUserPrompt.ifBlank { DEFAULT_LLM_TITLE_USER_PROMPT }

    /** Build [LlmConfig] for chat-completions (translate + title). */
    fun toLlmConfig(): LlmConfig = LlmConfig(
        baseUrl = normalizedLlmBaseUrl(),
        apiKey = llmApiKey.trim(),
        model = llmModel.trim(),
        targetLanguage = outputLanguage.trim(),
        sourceLanguage = inputLanguage.trim(),
        systemPrompt = renderLlmSystemPrompt(),
        userPromptTemplate = effectiveLlmUserPrompt(),
        titleSystemPrompt = effectiveLlmTitleSystemPrompt(),
        titleUserPromptTemplate = effectiveLlmTitleUserPrompt(),
        authStyle = llmAuthStyleEnum(),
        fullUrl = llmFullUrl,
        thinking = llmThinkingMode(),
        reasoningEffort = llmReasoningEffortEnum()
    )

    companion object {
        /**
         * Default simultaneous-interpretation system prompt (English).
         * Placeholders: {{from}} {{to}} {{glossary}}
         */
        const val DEFAULT_LLM_SYSTEM_PROMPT: String =
            "You are a professional simultaneous interpreter. Translate from {{from}} into {{to}}.\n" +
                "\n" +
                "Output rules:\n" +
                "1. Output ONLY the translation of the current utterance — no quotes, labels, prefixes, notes, or explanations.\n" +
                "2. Prefer natural, idiomatic {{to}}; stay faithful to meaning; keep names and technical terms accurate.\n" +
                "3. Skip pure fillers / false starts (e.g. uh, um, yeah, hmm) — do not translate them.\n" +
                "4. You may lightly repair obvious ASR errors and stuttered repetitions using context; never invent content.\n" +
                "5. When the glossary is non-empty, follow it strictly for listed terms.\n" +
                "\n" +
                "Glossary (must follow when non-empty; may be empty):\n" +
                "{{glossary}}"

        /**
         * Default translation user-message template.
         * Placeholders: {{from}} {{to}} {{history}} {{text}}
         */
        const val DEFAULT_LLM_USER_PROMPT: String =
            "Translate the Current utterance from {{from}} into {{to}}.\n" +
                "Output only the Current translation. Do not retranslate History; " +
                "History is for terminology and reference consistency only.\n" +
                "\n" +
                "History:\n" +
                "{{history}}\n" +
                "\n" +
                "Current ({{from}}):\n" +
                "{{text}}"

        /** Default session-title system prompt. */
        const val DEFAULT_LLM_TITLE_SYSTEM_PROMPT: String =
            "You are a note-taking assistant. Write a short title that captures the topic " +
                "of a live-translated conversation."

        /**
         * Default session-title user template.
         * Placeholder: {{dialogue}}
         */
        const val DEFAULT_LLM_TITLE_USER_PROMPT: String =
            "Based on the following bilingual turns (source → translation), write a short title.\n" +
                "Rules: preferably ≤20 characters or a few words; no quotes, numbering, or decoration; " +
                "output the title only.\n" +
                "\n" +
                "{{dialogue}}"

        const val DEFAULT_OVERLAY_BG = "#000000"
        const val DEFAULT_OVERLAY_EN = "#FFFFFF"
        const val DEFAULT_OVERLAY_ZH = "#FFEB3B"
        const val DEFAULT_OVERLAY_FONT_SP = 16
        const val DEFAULT_LIVE_FONT_SP = 16
        const val DEFAULT_OVERLAY_WIDTH_DP = 360
        const val DEFAULT_OVERLAY_HEIGHT_DP = 80
        const val DEFAULT_OVERLAY_ALPHA = 0
        const val DEFAULT_OVERLAY_SHOW_BORDER = false
        /** Tighter than the old hard-coded 14dp. */
        const val DEFAULT_OVERLAY_PAD_H_DP = 6
        /** Tighter than the old hard-coded 10dp. */
        const val DEFAULT_OVERLAY_PAD_V_DP = 4
        const val DEFAULT_OVERLAY_MARQUEE_SPEED = 60

        /** Reset caption-related fields (overlay + live font) to defaults. */
        fun captionDefaults(): UserSettings = UserSettings()

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
