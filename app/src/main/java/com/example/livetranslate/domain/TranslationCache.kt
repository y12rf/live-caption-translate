package com.example.livetranslate.domain

/**
 * In-session translation cache: keeps the latest [maxSize] hits, then drops oldest.
 *
 * Keys include source/target language (and model) so a later session or language
 * switch cannot reuse a wrong translation. Not thread-safe — call only from the
 * serial LLM worker, or external synchronization.
 *
 * Lifecycle: clear when a session starts / ends ("用完就丢").
 */
class TranslationCache(
    private val maxSize: Int = DEFAULT_MAX_SIZE
) {
    init {
        require(maxSize > 0) { "maxSize must be > 0" }
    }

    /**
     * Cache identity for one source sentence under a specific translation setup.
     * Language fields prevent cross-language pollution.
     */
    data class Key(
        val sourceText: String,
        val sourceLang: String,
        val targetLang: String,
        val model: String
    ) {
        companion object {
            fun of(
                sourceText: String,
                sourceLang: String,
                targetLang: String,
                model: String
            ): Key? {
                val text = normalizeSource(sourceText)
                if (text.isEmpty()) return null
                return Key(
                    sourceText = text,
                    sourceLang = normalizeLang(sourceLang),
                    targetLang = normalizeLang(targetLang),
                    model = model.trim().lowercase()
                )
            }

            fun normalizeSource(raw: String): String =
                raw.trim().replace(WHITESPACE, " ")

            fun normalizeLang(raw: String): String =
                raw.trim().lowercase()

            private val WHITESPACE = Regex("\\s+")
        }
    }

    /** insertion-order map; eldest removed when over capacity */
    private val map = object : LinkedHashMap<Key, String>(maxSize + 2, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>?): Boolean =
            size > maxSize
    }

    val size: Int get() = map.size

    fun get(key: Key): String? = map[key]

    fun put(key: Key, translation: String) {
        val zh = translation.trim()
        if (zh.isEmpty()) return
        // Re-insert so this key counts as "newest" among the 50
        map.remove(key)
        map[key] = zh
    }

    fun clear() {
        map.clear()
    }

    fun contains(key: Key): Boolean = map.containsKey(key)

    companion object {
        const val DEFAULT_MAX_SIZE = 50
    }
}
