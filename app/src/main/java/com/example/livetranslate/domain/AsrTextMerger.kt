package com.example.livetranslate.domain

/**
 * Merges ASR streaming chunks from heterogeneous OpenAI-compatible providers.
 *
 * - Snapshot providers send full text so far → replace when new text starts with previous.
 * - Length-regression snapshots (Whisper-style correction shrinks text) → replace **only**
 *   when [incoming] looks like a real full-document rewrite, not a token delta.
 * - Near-prefix snapshots (small rewrite of the tail) → replace when share is large.
 * - Overlapping resend (previous suffix == incoming prefix) → append only the new tail
 *   (avoids "chunk repeated twice" when providers re-emit the last phrase).
 * - Append providers send only the new fragment → concatenate.
 *
 * **Critical:** MIMO / chat-completions ASR often streams pure deltas of a few characters.
 * Treating those as "snapshot got shorter" (previous.startsWith(incoming)) **wipes** the
 * accumulator and leaves only the tail of the utterance — which looked like "lost first
 * ASR block" in history reprocess.
 */
object AsrTextMerger {
    fun merge(previous: String, incoming: String): String {
        if (incoming.isEmpty()) return previous
        if (previous.isEmpty()) return incoming
        // Growing / full snapshot
        if (incoming.startsWith(previous)) return incoming
        // Whisper-style correction: full text got shorter from a shared prefix.
        // Guard: short pure-deltas that happen to be a prefix of [previous] must APPEND
        // (or overlap-merge), never replace the whole buffer.
        if (previous.startsWith(incoming) && isPlausibleSnapshotRegression(previous, incoming)) {
            return incoming
        }
        val common = commonPrefixLength(previous, incoming)
        val minLen = minOf(previous.length, incoming.length)
        if (
            minLen >= MIN_NEAR_PREFIX_CHARS &&
            common >= minLen - 2 &&
            common >= (previous.length + 1) / 2
        ) {
            return incoming
        }
        // Streaming resend of the tail: "...hello wor" + "world today" → "...hello world today"
        val overlap = longestSuffixPrefixOverlap(previous, incoming)
        val minOverlap = minOverlapChars(previous, incoming)
        if (overlap >= minOverlap) {
            return previous + incoming.substring(overlap)
        }
        return previous + incoming
    }

    /**
     * True when [incoming] is large enough to be a corrected full snapshot, not a token delta.
     * Requires ≥ [MIN_REGRESSION_CHARS] and at least ~half of [previous] length.
     */
    internal fun isPlausibleSnapshotRegression(previous: String, incoming: String): Boolean {
        if (incoming.length < MIN_REGRESSION_CHARS) return false
        // incoming shorter than half of previous → almost certainly not a full rewrite
        if (incoming.length * 2 < previous.length) return false
        return true
    }

    private fun minOverlapChars(previous: String, incoming: String): Int {
        // Require a meaningful overlap so short accidental matches (e.g. "的") do not merge wrong.
        val bound = minOf(previous.length, incoming.length)
        return when {
            bound < 4 -> bound // very short strings: only full overlap is accepted above
            else -> minOf(8, bound / 3).coerceAtLeast(4)
        }
    }

    /**
     * Longest n such that previous.endsWith(incoming[0, n)).
     * Capped scan for performance on long transcripts.
     */
    internal fun longestSuffixPrefixOverlap(previous: String, incoming: String): Int {
        val max = minOf(previous.length, incoming.length, 256)
        for (n in max downTo 1) {
            if (previous.regionMatches(previous.length - n, incoming, 0, n)) {
                return n
            }
        }
        return 0
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private const val MIN_REGRESSION_CHARS = 16
    private const val MIN_NEAR_PREFIX_CHARS = 12
}
