package com.example.livetranslate.domain

/**
 * Merges ASR streaming chunks from heterogeneous OpenAI-compatible providers.
 *
 * - Snapshot providers send full text so far → replace when new text starts with previous.
 * - Append providers send only the new fragment → concatenate.
 */
object AsrTextMerger {
    fun merge(previous: String, incoming: String): String {
        if (incoming.isEmpty()) return previous
        if (previous.isEmpty()) return incoming
        return if (incoming.startsWith(previous)) incoming else previous + incoming
    }
}
