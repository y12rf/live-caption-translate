package com.example.livetranslate.data.settings

/**
 * One bilingual glossary pair for injection into the LLM system prompt.
 */
data class GlossaryEntry(
    val source: String,
    val target: String
)

/**
 * Minimal JSON array codec for [GlossaryEntry] list (no org.json; unit-test friendly).
 *
 * Format: `[{"source":"...","target":"..."},...]`
 */
object GlossaryCodec {
    const val MAX_ENTRIES = 100

    fun encode(entries: List<GlossaryEntry>): String {
        val cleaned = normalize(entries)
        if (cleaned.isEmpty()) return "[]"
        return cleaned.joinToString(prefix = "[", postfix = "]", separator = ",") { e ->
            """{"source":"${escape(e.source)}","target":"${escape(e.target)}"}"""
        }
    }

    fun decode(raw: String?): List<GlossaryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val s = raw.trim()
        if (s == "[]") return emptyList()
        val out = ArrayList<GlossaryEntry>()
        // Match objects with source/target string fields (order-flexible within object).
        val obj = Regex("""\{([^{}]*)\}""")
        for (m in obj.findAll(s)) {
            val body = m.groupValues[1]
            val source = field(body, "source") ?: continue
            val target = field(body, "target") ?: ""
            val src = unescape(source).trim()
            val tgt = unescape(target).trim()
            if (src.isNotEmpty()) {
                out.add(GlossaryEntry(src, tgt))
                if (out.size >= MAX_ENTRIES) break
            }
        }
        return out
    }

    fun normalize(entries: List<GlossaryEntry>): List<GlossaryEntry> {
        val out = ArrayList<GlossaryEntry>()
        for (e in entries) {
            val src = e.source.trim()
            if (src.isEmpty()) continue
            out.add(GlossaryEntry(src, e.target.trim()))
            if (out.size >= MAX_ENTRIES) break
        }
        return out
    }

    fun formatBlock(entries: List<GlossaryEntry>): String {
        val cleaned = normalize(entries)
        if (cleaned.isEmpty()) return ""
        return cleaned.joinToString("\n") { "${it.source} → ${it.target}" }
    }

    private fun field(objectBody: String, key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        return pattern.find(objectBody)?.groupValues?.get(1)
    }

    private fun escape(s: String): String = buildString(s.length + 8) {
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
