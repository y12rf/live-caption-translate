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
     */
    fun buildMessages(
        sources: List<String>,
        context: List<ContextTurn>,
        settings: UserSettings
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
            6. Skip pure fillers / false starts when they are the whole segment — leave that translation empty but still keep the delimiter so the count stays ${sources.size}.
            7. You may lightly repair obvious ASR errors using context; never invent content.
            8. When the glossary is non-empty, follow it strictly for listed terms.

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
     * Split model output into [expected] translations.
     * Tolerates optional whitespace / newlines around [DELIMITER].
     * On count mismatch: truncate extras, pad missing with blank.
     */
    fun parseTranslations(raw: String, expected: Int): List<String> {
        require(expected > 0)
        val cleaned = raw
            .replace("\r\n", "\n")
            .trim()
        if (cleaned.isEmpty()) {
            return List(expected) { "" }
        }

        // Primary: delimiter split (allow whitespace around |||)
        var parts = cleaned
            .split(Regex("""\s*${Regex.escape(DELIMITER)}\s*"""))
            .map { it.trim() }

        // If model ignored delimiter and used one-per-line with exact count, accept that.
        if (parts.size == 1 && expected > 1) {
            val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size == expected) {
                parts = lines
            }
        }

        // Strip accidental leading/trailing empty from delimiter edges
        while (parts.size > expected && parts.first().isEmpty()) {
            parts = parts.drop(1)
        }
        while (parts.size > expected && parts.last().isEmpty()) {
            parts = parts.dropLast(1)
        }

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
     * True when neither delimiter-split nor line-split produced exactly [expected] non-empty parts
     * before pad/truncate (model may still be usable after [parseTranslations]).
     */
    fun hadCountMismatch(raw: String, expected: Int): Boolean {
        if (expected <= 0) return false
        val cleaned = raw.replace("\r\n", "\n").trim()
        if (cleaned.isEmpty()) return expected > 0
        var delim = cleaned
            .split(Regex("""\s*${Regex.escape(DELIMITER)}\s*"""))
            .map { it.trim() }
        while (delim.size > 1 && delim.first().isEmpty()) delim = delim.drop(1)
        while (delim.size > 1 && delim.last().isEmpty()) delim = delim.dropLast(1)
        if (delim.size == expected) return false
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.size != expected
    }
}
