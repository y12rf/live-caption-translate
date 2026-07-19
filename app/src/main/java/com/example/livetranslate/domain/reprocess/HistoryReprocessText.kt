package com.example.livetranslate.domain.reprocess

import com.example.livetranslate.domain.PunctuationSegmenter
import com.example.livetranslate.domain.model.CutReason
import com.example.livetranslate.domain.model.TranscriptSegment

/**
 * Pure post-ASR text stages of history reprocess (scheme C′):
 * join block transcripts → punctuation split → segment rows (`offsetMs = 0`).
 *
 * Translation itself stays in [ReprocessEngine] (needs LLM client / network).
 */
object HistoryReprocessText {

    /**
     * Join per-block ASR strings the same way the engine does before split.
     */
    fun joinBlockTranscripts(blockTexts: List<String>): String =
        blockTexts.joinToString(" ").trim()

    /**
     * Punctuation segmentation of the full ASR transcript.
     * Falls back to the whole string if the segmenter returns nothing.
     *
     * @throws IllegalArgumentException when result would be empty
     */
    fun splitSources(fullAsrText: String): List<String> {
        val trimmed = fullAsrText.trim()
        require(trimmed.isNotEmpty()) { "empty ASR transcript" }
        var sources = PunctuationSegmenter.split(trimmed)
        if (sources.isEmpty()) {
            sources = listOf(trimmed)
        }
        sources = sources.map { it.trim() }.filter { it.isNotEmpty() }
        require(sources.isNotEmpty()) { "punctuation split empty" }
        return sources
    }

    /**
     * ASR-only reprocess rows (no translation). New Re sessions always use [offsetMs] = 0.
     */
    fun asrOnlySegments(sources: List<String>, nowMs: Long = System.currentTimeMillis()): List<TranscriptSegment> =
        sources.map { src ->
            TranscriptSegment(
                source = src,
                translation = "",
                cutReason = CutReason.Silence,
                incomplete = false,
                timestampMs = nowMs,
                offsetMs = 0L
            )
        }

    /**
     * Build bilingual rows after a successful fail-closed translate pass.
     * [translations] must align 1:1 with [sources] and every slot non-blank.
     */
    fun bilingualSegments(
        sources: List<String>,
        translations: List<String>,
        nowMs: Long = System.currentTimeMillis()
    ): List<TranscriptSegment> {
        require(sources.size == translations.size) {
            "source/translation size mismatch ${sources.size} vs ${translations.size}"
        }
        return sources.mapIndexed { i, src ->
            val zh = translations[i].trim()
            require(zh.isNotEmpty()) { "empty translation at #${i + 1}" }
            TranscriptSegment(
                source = src,
                translation = zh,
                cutReason = CutReason.Silence,
                incomplete = false,
                timestampMs = nowMs,
                offsetMs = 0L
            )
        }
    }
}
