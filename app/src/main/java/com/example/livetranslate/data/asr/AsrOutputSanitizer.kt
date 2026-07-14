package com.example.livetranslate.data.asr

/**
 * Strips model meta tags that some ASR / multimodal models leak into the transcript.
 *
 * Removes:
 * - think blocks: `<think>...</think>`, bare `think>` lines, etc.
 * - language marker tags like `<chinese>`, `<english>`, `</zh>`, `<en-US>`
 *   (not the Chinese word 语言)
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
     * Language name / ISO code tags, e.g. `<chinese>`, `</English>`, `<en>`, `<zh-CN>`.
     * Deliberately limited to known language tokens so we do not strip arbitrary XML.
     */
    private val languageNames = listOf(
        "chinese", "english", "japanese", "korean", "french", "german", "spanish",
        "russian", "arabic", "portuguese", "italian", "hindi", "vietnamese", "thai",
        "indonesian", "malay", "dutch", "turkish", "polish", "ukrainian", "swedish",
        "norwegian", "danish", "finnish", "czech", "romanian", "hungarian", "greek",
        "hebrew", "persian", "bengali", "tamil", "telugu", "marathi", "urdu",
        "cantonese", "mandarin"
    ).joinToString("|")

    private val languageTag = Regex(
        """(?i)</?\s*(?:$languageNames|[a-z]{2}(?:[-_][a-z]{2,8})?)\s*>"""
    )

    fun clean(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = raw
        s = thinkBlock.replace(s, "")
        s = thinkOpenToEnd.replace(s, "")
        s = thinkLine.replace(s, "")
        s = thinkLoose.replace(s, "")
        s = languageTag.replace(s, "")
        // Collapse leftover blank lines / spaces from removed tags
        s = s.replace(Regex("[\\t ]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }
}
