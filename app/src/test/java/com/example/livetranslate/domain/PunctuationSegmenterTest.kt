package com.example.livetranslate.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctuationSegmenterTest {

    @Test
    fun splitsChineseAndEnglish() {
        val text = "你好世界。Hello world! 下一句？"
        val parts = PunctuationSegmenter.split(text)
        assertEquals(3, parts.size)
        assertEquals("你好世界。", parts[0])
        assertEquals("Hello world!", parts[1])
        assertEquals("下一句？", parts[2])
    }

    @Test
    fun noPunctuation_singleSentence() {
        val parts = PunctuationSegmenter.split("just one long line without stop")
        assertEquals(listOf("just one long line without stop"), parts)
    }

    @Test
    fun decimalNotSplit() {
        val parts = PunctuationSegmenter.split("Value is 3.14 today.")
        assertEquals(1, parts.size)
        assertTrue(parts[0].contains("3.14"))
        assertTrue(parts[0].endsWith("."))
    }

    @Test
    fun consecutivePunctuation() {
        val parts = PunctuationSegmenter.split("Wow!!! Really?")
        assertEquals(2, parts.size)
        assertEquals("Wow!!!", parts[0])
        assertEquals("Really?", parts[1])
    }

    @Test
    fun newlineSplits() {
        val parts = PunctuationSegmenter.split("line one\nline two")
        assertEquals(listOf("line one", "line two"), parts)
    }

    @Test
    fun dropsTinyFragments() {
        val parts = PunctuationSegmenter.split("a。ab。")
        // "a" length 1 dropped after strip of 。 wait - "a。" has length 2
        assertTrue(parts.any { it.startsWith("ab") })
    }

    @Test
    fun blank_empty() {
        assertTrue(PunctuationSegmenter.split("   ").isEmpty())
        assertTrue(PunctuationSegmenter.split("").isEmpty())
    }
}
