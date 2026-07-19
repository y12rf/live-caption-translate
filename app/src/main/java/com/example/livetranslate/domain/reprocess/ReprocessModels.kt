package com.example.livetranslate.domain.reprocess

/**
 * UI / engine phase for history reprocess & orphan recovery (scheme C′).
 */
enum class ReprocessPhase {
    Idle,
    Running,
    Cancelling
}

data class ReprocessUiState(
    val phase: ReprocessPhase = ReprocessPhase.Idle,
    val asrChunkIndex: Int = 0,
    val asrChunkTotal: Int = 0,
    val translateIndex: Int = 0,
    val translateTotal: Int = 0,
    val message: String = "",
    val error: String? = null,
    /** Last successfully saved session id. */
    val lastSavedSessionId: Long? = null,
    /** Audio path currently being processed (UI lock / orphan exclude). */
    val activeAudioPath: String? = null,
    val sessionTitle: String? = null
)

/**
 * Multi-sentence ASR packing for reprocess (scheme C′).
 *
 * @param sentencesPerBlock target sentences per ASR upload (default 20)
 * @param maxSentences hard cap (30)
 * @param maxBlockDurationMs duration safety valve (90s) — whoever trips first closes the block
 */
data class AsrPackPolicy(
    val sentencesPerBlock: Int = DEFAULT_SENTENCES,
    val maxSentences: Int = MAX_SENTENCES,
    val maxBlockDurationMs: Long = DEFAULT_MAX_BLOCK_DURATION_MS
) {
    fun normalized(): AsrPackPolicy {
        val maxN = maxSentences.coerceIn(1, ABSOLUTE_MAX_SENTENCES)
        val n = sentencesPerBlock.coerceIn(1, maxN)
        val dur = maxBlockDurationMs.coerceAtLeast(1_000L)
        return copy(sentencesPerBlock = n, maxSentences = maxN, maxBlockDurationMs = dur)
    }

    companion object {
        const val DEFAULT_SENTENCES = 20
        const val MAX_SENTENCES = 30
        const val ABSOLUTE_MAX_SENTENCES = 30
        const val DEFAULT_MAX_BLOCK_DURATION_MS = 90_000L

        val Default = AsrPackPolicy().normalized()
    }
}

/**
 * One historical (or VAD) sentence time window used only to cut PCM — not written back as timeline.
 */
data class TimeWindow(
    val startMs: Long,
    val endMs: Long,
    val index: Int
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Packed multi-sentence range for one ASR request.
 * PCM is the continuous file slice [startMs, endMs).
 */
data class AsrBlock(
    val startMs: Long,
    val endMs: Long,
    val windowCount: Int,
    val index: Int,
    val total: Int
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}
