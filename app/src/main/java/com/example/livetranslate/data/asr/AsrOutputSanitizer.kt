package com.example.livetranslate.data.asr

/**
 * Strips model meta tags that some ASR / multimodal models leak into the transcript.
 *
 * Removes:
 * - think blocks: `<think>...</think>`, bare `think>`, `</think>`, etc.
 * - language tags: `<语言>...</语言>`, bare `<语言>` / `</语言>`
 */
object AsrOutputSanitizer {

    private val thinkBlock = Regex(
        """(?is)<\s*think\s*>.*?<\s*/\s*think\s*>"""
    )
    private val thinkOpenToEnd = Regex(
        """(?is)<\s*think\s*>.*\z"""
    )
    /** Bare `think>…` (often whole line of model scratch). */
    private val thinkLine = Regex(
        """(?im)^[ \t]*think\s*>.*$"""
    )
    private val thinkLoose = Regex(
        """(?i)</?\s*think\s*>"""
    )

    private val langBlock = Regex(
        """(?is)<\s*语言\s*>.*?<\s*/\s*语言\s*>"""
    )
    private val langLoose = Regex(
        """</?\s*语言\s*>"""
    )

    fun clean(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = raw
        s = thinkBlock.replace(s, "")
        s = thinkOpenToEnd.replace(s, "")
        s = thinkLine.replace(s, "")
        s = thinkLoose.replace(s, "")
        s = langBlock.replace(s, "")
        s = langLoose.replace(s, "")
        // Collapse leftover blank lines / spaces from removed tags
        s = s.replace(Regex("[\\t ]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }
}
