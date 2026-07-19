package com.example.livetranslate.domain

import com.example.livetranslate.data.settings.GlossaryCodec
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.model.ContextTurn

/**
 * Offline reprocess: pack many source lines into one LLM call and split the reply
 * on a fixed delimiter so sentence count matches without N round-trips.
 */
object BatchTranslation {
    /**
     * Delimiter between source segments (user message) and between translations (model reply).
     * Rare in prose; model is instructed to use the exact same token.
     */
    const val DELIMITER = "|||"

    /** Default number of source sentences per LLM request. */
    const val DEFAULT_BATCH_SIZE = 20

    /**
     * Build system + user messages for a batch translate.
     * [sources] must be non-empty, already trimmed non-blank lines.
     *
     * @param requireNonEmptySlots when true (reprocess C′), forbid empty translation slots
     *        and include few-shot examples that demonstrate [DELIMITER] alignment.
     */
    fun buildMessages(
        sources: List<String>,
        context: List<ContextTurn>,
        settings: UserSettings,
        requireNonEmptySlots: Boolean = false
    ): Pair<String, String> {
        require(sources.isNotEmpty()) { "sources empty" }
        val from = settings.inputLanguage.trim().ifBlank { "source" }
        val to = settings.outputLanguage.trim().ifBlank { "target" }
        val glossary = GlossaryCodec.formatBlock(settings.glossaryTerms)
        val historyBlock = if (context.isEmpty()) {
            "(none)"
        } else {
            context.joinToString("\n") { turn ->
                "[$from] ${turn.source}\n[$to] ${turn.translation}"
            }
        }

        val emptyRule = if (requireNonEmptySlots) {
            "6. Never leave a translation segment empty — every slot must contain a real $to translation."
        } else {
            "6. Skip pure fillers / false starts when they are the whole segment — leave that translation empty but still keep the delimiter so the count stays ${sources.size}."
        }

        val fewShot = if (requireNonEmptySlots) {
            """

            Few-shot examples (follow the same delimiter format exactly):

            Example 1 — 2 segments:
            Source: Hello everyone.${DELIMITER}Welcome to the lecture.
            Output: 大家好。${DELIMITER}欢迎来到本次讲座。

            Example 2 — 3 segments:
            Source: We will start with an overview.${DELIMITER}Please hold your questions.${DELIMITER}Thank you.
            Output: 我们先从概述开始。${DELIMITER}请先保留问题。${DELIMITER}谢谢。
            """.trimIndent()
        } else {
            ""
        }

        val system = """
            You are a professional simultaneous interpreter. Translate from $from into $to.

            You will receive MULTIPLE source utterances in one message, separated by the exact delimiter: $DELIMITER
            There are exactly ${sources.size} source segment(s).

            Output rules:
            1. Output ONLY the translations — no quotes, labels, numbering, notes, or explanations.
            2. Produce exactly ${sources.size} translation segment(s), in the same order as the sources.
            3. Separate consecutive translations with the exact delimiter $DELIMITER (no spaces required around it; newlines around it are OK).
            4. Do not translate or copy the delimiter itself; never invent extra segments.
            5. Prefer natural, idiomatic $to; stay faithful to meaning; keep names and technical terms accurate.
            $emptyRule
            7. You may lightly repair obvious ASR errors using context; never invent content.
            8. When the glossary is non-empty, follow it strictly for listed terms.
            $fewShot

            Glossary (must follow when non-empty; may be empty):
            $glossary
        """.trimIndent()

        val packed = sources.joinToString(DELIMITER)
        val user = """
            Translate each source segment from $from into $to.
            Output only the translations separated by $DELIMITER (exactly ${sources.size} segments).

            History (for terminology consistency only; do not retranslate):
            $historyBlock

            Source segments ($from), separated by $DELIMITER:
            $packed
        """.trimIndent()

        return system to user
    }

    /**
     * Fail-closed success for reprocess batch translate:
     * exact count, every slot non-blank, no suspicious duplicate loop.
     */
    fun isBatchFullySuccessful(parts: List<String>, expected: Int): Boolean {
        if (expected <= 0 || parts.size != expected) return false
        if (parts.any { it.trim().isEmpty() }) return false
        if (hasSuspiciousDuplicates(parts)) return false
        return true
    }

    /** Whether [raw] parses to a fully successful batch of [expected] slots. */
    fun isRawBatchFullySuccessful(raw: String, expected: Int): Boolean {
        if (hadCountMismatch(raw, expected)) return false
        return isBatchFullySuccessful(parseTranslations(raw, expected), expected)
    }

    /**
     * Split model output into [expected] translations.
     * Tolerates optional whitespace / newlines around [DELIMITER].
     * On count mismatch: truncate extras, pad missing with blank.
     *
     * Parsing rules must stay in lockstep with [hadCountMismatch] — the latter
     * must not claim "OK" when this path would pad/truncate.
     */
    fun parseTranslations(raw: String, expected: Int): List<String> {
        require(expected > 0)
        val parts = splitRaw(raw, expected) ?: return List(expected) { "" }
        return when {
            parts.size == expected -> parts
            parts.size > expected -> parts.take(expected)
            else -> parts + List(expected - parts.size) { "" }
        }
    }

    fun chunkSources(sources: List<String>, batchSize: Int = DEFAULT_BATCH_SIZE): List<List<String>> {
        val n = batchSize.coerceAtLeast(1)
        if (sources.isEmpty()) return emptyList()
        return sources.chunked(n)
    }

    /**
     * True when [parseTranslations] would need pad/truncate (segment count ≠ expected
     * after the same delimiter / line-fallback rules).
     *
     * Important: line-count coincidence must **not** override a wrong delimiter split —
     * that used to mark mismatch=false while parse still used the bad `|||` parts,
     * shifting later sentences and making the first translation look "gone".
     */
    fun hadCountMismatch(raw: String, expected: Int): Boolean {
        if (expected <= 0) return false
        val parts = splitRaw(raw, expected) ?: return expected > 0
        return parts.size != expected
    }

    /**
     * Detect batch replies where count matches but content is shifted/looped
     * (same translation repeated many times). Forces single-sentence fallback.
     */
    fun hasSuspiciousDuplicates(parts: List<String>): Boolean {
        val nonBlank = parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (nonBlank.size < 3) return false
        val freq = nonBlank.groupingBy { it }.eachCount()
        val maxFreq = freq.values.maxOrNull() ?: 0
        // ≥3 identical slots, and at least half the batch is that same string
        return maxFreq >= 3 && maxFreq * 2 >= nonBlank.size
    }

    /**
     * Shared split used by parse + mismatch detection.
     * @return null when [raw] is blank after trim
     */
    private fun splitRaw(raw: String, expected: Int): List<String>? {
        val cleaned = raw.replace("\r\n", "\n").trim()
        if (cleaned.isEmpty()) return null

        var parts = cleaned
            .split(Regex("""\s*${Regex.escape(DELIMITER)}\s*"""))
            .map { it.trim() }

        // Line fallback only when the model ignored the delimiter entirely.
        if (parts.size == 1 && expected > 1) {
            val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size == expected) {
                parts = lines
            }
        }

        // Strip accidental leading/trailing empty from delimiter edges
        // (e.g. output started/ended with |||). Only while oversized.
        while (parts.size > expected && parts.first().isEmpty()) {
            parts = parts.drop(1)
        }
        while (parts.size > expected && parts.last().isEmpty()) {
            parts = parts.dropLast(1)
        }
        return parts
    }
}
