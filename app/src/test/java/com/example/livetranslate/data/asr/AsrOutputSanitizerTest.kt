package com.example.livetranslate.data.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrOutputSanitizerTest {

    @Test
    fun stripsThinkBlockAndLanguageTag() {
        val raw = """
            <think>internal notes</think>
            <语言>en</语言>
            Hello world
        """.trimIndent()
        assertEquals("Hello world", AsrOutputSanitizer.clean(raw))
    }

    @Test
    fun stripsLooseThinkGreaterThan() {
        val raw = "think>scratch\nActual sentence"
        val cleaned = AsrOutputSanitizer.clean(raw)
        assertEquals("Actual sentence", cleaned)
    }

    @Test
    fun leavesCleanTextAlone() {
        assertEquals("Just speech.", AsrOutputSanitizer.clean("Just speech."))
    }
}
