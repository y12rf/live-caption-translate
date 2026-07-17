package com.example.livetranslate.data.llm

/**
 * Thinking / reasoning request controls (OpenAI-compatible chat.completions).
 *
 * Wire format (after `messages`), only when not [LlmThinkingMode.Default]:
 * - Enabled: `"thinking":{"type":"enabled"}` + `"reasoning_effort":"low|medium|high|max"`
 * - Disabled: `"thinking":{"type":"disabled"}` (no effort)
 * - Default: omit both fields (vanilla request)
 */
enum class LlmThinkingMode {
    /** Do not send `thinking` / `reasoning_effort` (product default). */
    Default,
    /** `"thinking":{"type":"enabled"}` + `reasoning_effort` */
    Enabled,
    /** `"thinking":{"type":"disabled"}` */
    Disabled;

    val wireType: String?
        get() = when (this) {
            Default -> null
            Enabled -> "enabled"
            Disabled -> "disabled"
        }

    companion object {
        fun fromStorage(raw: String?): LlmThinkingMode {
            val s = raw?.trim().orEmpty()
            return when {
                s.isEmpty() ||
                    s.equals(Default.name, ignoreCase = true) ||
                    s.equals("none", ignoreCase = true) ||
                    s.equals("omit", ignoreCase = true) -> Default
                s.equals("false", ignoreCase = true) ||
                    s.equals(Disabled.name, ignoreCase = true) ||
                    s.equals("off", ignoreCase = true) -> Disabled
                s.equals(Enabled.name, ignoreCase = true) ||
                    s.equals("true", ignoreCase = true) ||
                    s.equals("on", ignoreCase = true) -> Enabled
                // Legacy storage that treated blank as enabled — map old Enabled-as-default users
                // who had "True" already handled; bare unknown → Default (new product default)
                else -> Default
            }
        }
    }
}

/**
 * OpenAI `reasoning_effort` when thinking is [LlmThinkingMode.Enabled].
 * Default: [Low].
 */
enum class LlmReasoningEffort {
    Low,
    Medium,
    High,
    Max;

    val wireValue: String
        get() = when (this) {
            Low -> "low"
            Medium -> "medium"
            High -> "high"
            Max -> "max"
        }

    companion object {
        fun fromStorage(raw: String?): LlmReasoningEffort {
            val s = raw?.trim()?.lowercase().orEmpty()
            return when (s) {
                "max", "xhigh", "extra_high", "extra-high" -> Max
                "high" -> High
                "medium", "med" -> Medium
                "low", "" -> Low
                else -> Low
            }
        }
    }
}

/**
 * Appends thinking + optional OpenAI `reasoning_effort` after `messages`.
 * [LlmThinkingMode.Default] appends nothing.
 */
object LlmThinkingBody {
    /**
     * @param out StringBuilder ending just after the messages array close `]`
     */
    fun appendAfterMessages(
        out: StringBuilder,
        mode: LlmThinkingMode,
        effort: LlmReasoningEffort = LlmReasoningEffort.Low
    ) {
        when (mode) {
            LlmThinkingMode.Default -> Unit
            LlmThinkingMode.Disabled ->
                out.append(",\"thinking\":{\"type\":\"disabled\"}")
            LlmThinkingMode.Enabled -> {
                out.append(",\"thinking\":{\"type\":\"enabled\"}")
                out.append(",\"reasoning_effort\":\"").append(effort.wireValue).append("\"")
            }
        }
    }
}
