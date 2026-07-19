package com.example.livetranslate.domain

/**
 * Merges ASR streaming chunks from heterogeneous OpenAI-compatible providers.
 *
 * - Snapshot providers send full text so far → replace when new text starts with previous.
 * - Length-regression snapshots (correction shrinks text) → replace.
 * - Near-prefix snapshots (small rewrite of the tail) → replace.
 * - Overlapping resend (previous suffix == incoming prefix) → append only the new tail
 *   (avoids "chunk repeated twice" when providers re-emit the last phrase).
 * - Append providers send only the new fragment → concatenate.
 */
object AsrTextMerger {
    fun merge(previous: String, incoming: String): String {
        if (incoming.isEmpty()) return previous
        if (previous.isEmpty()) return incoming
        if (incoming.startsWith(previous)) return incoming
        // Whisper-style correction: full text got shorter or rewritten from a shared prefix.
        if (previous.startsWith(incoming)) return incoming
        val common = commonPrefixLength(previous, incoming)
        val minLen = minOf(previous.length, incoming.length)
        if (minLen > 0 && common >= minLen - 2 && common >= (previous.length + 1) / 2) {
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
}
