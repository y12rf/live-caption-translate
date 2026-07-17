package com.example.livetranslate.data.llm

/**
 * Thinking / reasoning request controls for OpenAI-compatible (and Anthropic-style) gateways.
 *
 * Wire format (after `messages`):
 * - Switch: `"thinking":{"type":"enabled"|"disabled"}` (default enabled)
 * - Effort when enabled:
 *   - OpenAI: `"reasoning_effort":"high"|"max"`
 *   - Anthropic: `"output_config":{"effort":"high"|"max"}`
 *
 * Compatibility maps for effort input: low/medium → high, xhigh → max.
 */
enum class LlmThinkingMode {
    /** `"thinking":{"type":"enabled"}` + effort fields */
    Enabled,
    /** `"thinking":{"type":"disabled"}` (no effort fields) */
    Disabled;

    val wireType: String
        get() = when (this) {
            Enabled -> "enabled"
            Disabled -> "disabled"
        }

    companion object {
        fun fromStorage(raw: String?): LlmThinkingMode {
            val s = raw?.trim().orEmpty()
            return when {
                s.equals("false", ignoreCase = true) ||
                    s.equals(Disabled.name, ignoreCase = true) ||
                    s.equals("off", ignoreCase = true) -> Disabled
                // Legacy True / Default and new Enabled → enabled (product default)
                else -> Enabled
            }
        }
    }
}

/**
 * Reasoning effort when thinking is [LlmThinkingMode.Enabled].
 * Default for ordinary translate requests: [High].
 */
enum class LlmReasoningEffort {
    High,
    Max;

    val wireValue: String
        get() = when (this) {
            High -> "high"
            Max -> "max"
        }

    companion object {
        fun fromStorage(raw: String?): LlmReasoningEffort {
            val s = raw?.trim()?.lowercase().orEmpty()
            return when (s) {
                "max", "xhigh", "extra_high", "extra-high" -> Max
                // low / medium / high / empty → high (compat + default)
                else -> High
            }
        }
    }
}

/**
 * Where to put effort when thinking is enabled.
 */
enum class LlmReasoningEffortStyle {
    /** `"reasoning_effort":"high|max"` (OpenAI / DeepSeek chat.completions style) */
    OpenAi,
    /** `"output_config":{"effort":"high|max"}` (Anthropic-style) */
    Anthropic;

    companion object {
        fun fromStorage(raw: String?): LlmReasoningEffortStyle {
            val s = raw?.trim().orEmpty()
            return when {
                s.equals(Anthropic.name, ignoreCase = true) ||
                    s.equals("claude", ignoreCase = true) -> Anthropic
                else -> OpenAi
            }
        }
    }
}

/**
 * Appends thinking + optional effort fields to a JSON object builder that already has
 * `"messages":[...]` (fields go after messages, matching common OpenAI-compat layouts).
 */
object LlmThinkingBody {
    /**
     * @param out StringBuilder ending just after the messages array close `]`
     */
    fun appendAfterMessages(
        out: StringBuilder,
        mode: LlmThinkingMode,
        effort: LlmReasoningEffort = LlmReasoningEffort.High,
        style: LlmReasoningEffortStyle = LlmReasoningEffortStyle.OpenAi
    ) {
        out.append(",\"thinking\":{\"type\":\"").append(mode.wireType).append("\"}")
        if (mode == LlmThinkingMode.Enabled) {
            val e = effort.wireValue
            when (style) {
                LlmReasoningEffortStyle.OpenAi ->
                    out.append(",\"reasoning_effort\":\"").append(e).append("\"")
                LlmReasoningEffortStyle.Anthropic ->
                    out.append(",\"output_config\":{\"effort\":\"").append(e).append("\"}")
            }
        }
    }
}
