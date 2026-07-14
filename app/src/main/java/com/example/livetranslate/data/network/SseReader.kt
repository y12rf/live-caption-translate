package com.example.livetranslate.data.network

import okio.BufferedSource
import java.io.BufferedReader

/**
 * Parses Server-Sent Events (SSE) lines from OpenAI-compatible streaming APIs.
 *
 * Each non-empty `data:` line becomes one payload string (JSON).
 * Empty data lines and `data: [DONE]` are ignored.
 */
object SseReader {
    fun parsePayloads(reader: BufferedReader): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val line = reader.readLine() ?: break
            parseLine(line)?.let { out.add(it) }
        }
        return out
    }

    fun readPayloads(source: BufferedSource): Sequence<String> = sequence {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            parseLine(line)?.let { yield(it) }
        }
    }

    private fun parseLine(line: String): String? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        return payload
    }
}
