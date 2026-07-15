package com.example.livetranslate.domain

/**
 * Split ASR text into sentences using Chinese / English terminal punctuation.
 * Used by offline full-WAV reprocess (no timeline offsets).
 */
object PunctuationSegmenter {
    /** Drop fragments shorter than this after trim. */
    const val MIN_CHARS = 2

    /**
     * Terminal / split characters. Kept at end of the preceding sentence.
     * Newlines also split.
     */
    private val terminal = charArrayOf(
        '。', '！', '？', '；', '…',
        '.', '!', '?', ';'
    )

    fun split(text: String): List<String> {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n')
        if (raw.isBlank()) return emptyList()

        val out = ArrayList<String>()
        val buf = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\n' -> {
                    flush(buf, out)
                }
                c == '.' && isDecimalDot(raw, i) -> {
                    buf.append(c)
                }
                c in terminal || isEllipsisStart(raw, i) -> {
                    if (isEllipsisStart(raw, i)) {
                        // Consume full "…" or "..."
                        if (raw[i] == '…') {
                            buf.append('…')
                            i++
                        } else {
                            while (i < raw.length && raw[i] == '.') {
                                buf.append('.')
                                i++
                            }
                        }
                        // Trailing same-class terminals
                        while (i < raw.length && raw[i] in terminal && raw[i] != '.') {
                            buf.append(raw[i])
                            i++
                        }
                        flush(buf, out)
                        continue
                    } else {
                        buf.append(c)
                        i++
                        while (i < raw.length && raw[i] in terminal) {
                            buf.append(raw[i])
                            i++
                        }
                        flush(buf, out)
                        continue
                    }
                }
                else -> buf.append(c)
            }
            i++
        }
        flush(buf, out)
        return out
    }

    private fun flush(buf: StringBuilder, out: MutableList<String>) {
        val t = buf.toString().trim()
        buf.clear()
        if (t.length >= MIN_CHARS) out.add(t)
    }

    /** True when '.' is between digits (e.g. 3.14). */
    private fun isDecimalDot(s: String, index: Int): Boolean {
        if (index <= 0 || index + 1 >= s.length) return false
        return s[index - 1].isDigit() && s[index + 1].isDigit()
    }

    private fun isEllipsisStart(s: String, index: Int): Boolean {
        if (s[index] == '…') return true
        if (s[index] != '.') return false
        // "..." starting here
        var n = 0
        var j = index
        while (j < s.length && s[j] == '.') {
            n++
            j++
        }
        return n >= 3
    }
}
