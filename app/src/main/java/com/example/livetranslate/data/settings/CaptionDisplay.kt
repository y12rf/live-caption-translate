package com.example.livetranslate.data.settings

/**
 * Which lines the floating overlay / immersive captions show.
 */
enum class OverlayTextMode {
    /** Original + translation */
    Both,
    /** Source / ASR only */
    SourceOnly,
    /** Translation only */
    TranslationOnly;

    companion object {
        fun fromStorage(raw: String?): OverlayTextMode {
            val s = raw?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: Both
        }
    }
}

/**
 * How a caption line is laid out.
 *
 * - [FullSentence]: wrap full sentence, center-aligned
 * - [ScrollLine]: single-line horizontal marquee
 */
enum class OverlayLayoutMode {
    FullSentence,
    ScrollLine;

    companion object {
        fun fromStorage(raw: String?): OverlayLayoutMode {
            val s = raw?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: FullSentence
        }
    }
}
