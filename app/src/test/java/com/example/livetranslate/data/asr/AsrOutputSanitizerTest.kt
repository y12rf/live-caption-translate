package com.example.livetranslate.data.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrOutputSanitizerTest {

    @Test
    fun stripsThinkBlockAndLanguageNameTags() {
        val raw = """
            <think>internal notes</think>
            <chinese>
            你好世界
            <english>
            Hello world
        """.trimIndent()
        val cleaned = AsrOutputSanitizer.clean(raw)
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("你好世界", "Hello world"), lines)
    }

    @Test
    fun stripsAnyEnglishLetterTags() {
        val raw = "<foo>Hi</bar> there <zh-CN>好 <CustomTag>"
        assertEquals("Hi there 好", AsrOutputSanitizer.clean(raw))
    }

    @Test
    fun stripsLooseThinkGreaterThan() {
        val raw = "think>scratch\nActual sentence"
        assertEquals("Actual sentence", AsrOutputSanitizer.clean(raw))
    }

    @Test
    fun leavesCleanTextAlone() {
        assertEquals("Just speech.", AsrOutputSanitizer.clean("Just speech."))
    }

    @Test
    fun doesNotStripNonAsciiTagNames() {
        // Tag name is not pure English letters pattern start — leave as-is
        assertEquals("<中文> bar", AsrOutputSanitizer.clean("<中文> bar"))
    }
}
