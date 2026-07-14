package com.example.livetranslate.data.asr

/**
 * Strips model meta tags that some ASR / multimodal models leak into the transcript.
 *
 * Removes:
 * - think blocks: `<think>...</think>`, bare `think>` lines, etc.
 * - Any angle-bracket tag whose name is English letters (optionally + digits/_/-),
 *   e.g. `<chinese>`, `<english>`, `<en>`, `<zh-CN>`, `</English>`
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

    /**
     * Tags whose name is English letters only (ASCII), optional digits / _ / - after first letter.
     * Examples: `<chinese>`, `</en>`, `<zh-CN>`, `<English>`
     * Does not match Chinese names or attributes (`<a href=...>` is not a simple tag name).
     */
    private val englishNameTag = Regex(
        """(?i)</?[a-z][a-z0-9_-]*>"""
    )

    fun clean(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = raw
        s = thinkBlock.replace(s, "")
        s = thinkOpenToEnd.replace(s, "")
        s = thinkLine.replace(s, "")
        s = thinkLoose.replace(s, "")
        s = englishNameTag.replace(s, "")
        // Collapse leftover blank lines / spaces from removed tags
        s = s.replace(Regex("[\\t ]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }
}
