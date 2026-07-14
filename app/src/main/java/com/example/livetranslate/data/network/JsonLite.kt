package com.example.livetranslate.data.network

/**
 * Minimal JSON helpers that work on JVM unit tests without Android JSONObject stubs.
 * Not a full parser — only what streaming ASR/LLM clients need.
 */
object JsonLite {
    fun escape(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    /**
     * Extract a string field value for [key] at any nesting depth (first match).
     * Handles common `"key":"value"` shapes used in OpenAI SSE chunks.
     */
    fun firstStringField(json: String, key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val m = pattern.find(json) ?: return null
        return unescape(m.groupValues[1]).takeIf { it.isNotEmpty() }
    }

    private fun unescape(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    '"' -> append('"')
                    '\\' -> append('\\')
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            append(hex.toInt(16).toChar())
                            i += 6
                            continue
                        } else append('u')
                    }
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
