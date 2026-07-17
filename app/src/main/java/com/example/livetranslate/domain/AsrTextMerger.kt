package com.example.livetranslate.domain

/**
 * Merges ASR streaming chunks from heterogeneous OpenAI-compatible providers.
 *
 * - Snapshot providers send full text so far → replace when new text starts with previous.
 * - Length-regression snapshots (correction shrinks text) → replace.
 * - Near-prefix snapshots (small rewrite of the tail) → replace.
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
        return previous + incoming
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}
