package com.example.livetranslate.domain

import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.model.ContextTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTranslationTest {

    @Test
    fun parse_delimiterExactCount() {
        val raw = "你好|||世界|||再见"
        val parts = BatchTranslation.parseTranslations(raw, 3)
        assertEquals(listOf("你好", "世界", "再见"), parts)
        assertFalse(BatchTranslation.hadCountMismatch(raw, 3))
    }

    @Test
    fun parse_delimiterWithNewlines() {
        val raw = "第一句\n|||\n第二句\n|||\n第三句"
        val parts = BatchTranslation.parseTranslations(raw, 3)
        assertEquals(listOf("第一句", "第二句", "第三句"), parts)
    }

    @Test
    fun parse_lineFallbackWhenNoDelimiter() {
        val raw = "line one\nline two\nline three"
        val parts = BatchTranslation.parseTranslations(raw, 3)
        assertEquals(listOf("line one", "line two", "line three"), parts)
        assertFalse(BatchTranslation.hadCountMismatch(raw, 3))
    }

    @Test
    fun parse_padsWhenShort() {
        val raw = "only one"
        val parts = BatchTranslation.parseTranslations(raw, 3)
        assertEquals(3, parts.size)
        assertEquals("only one", parts[0])
        assertEquals("", parts[1])
        assertEquals("", parts[2])
        assertTrue(BatchTranslation.hadCountMismatch(raw, 3))
    }

    @Test
    fun parse_truncatesWhenLong() {
        val raw = "a|||b|||c|||d"
        val parts = BatchTranslation.parseTranslations(raw, 2)
        assertEquals(listOf("a", "b"), parts)
        assertTrue(BatchTranslation.hadCountMismatch(raw, 2))
    }

    @Test
    fun chunkSources_batches() {
        val src = (1..25).map { "s$it" }
        val batches = BatchTranslation.chunkSources(src, 10)
        assertEquals(3, batches.size)
        assertEquals(10, batches[0].size)
        assertEquals(10, batches[1].size)
        assertEquals(5, batches[2].size)
    }

    @Test
    fun buildMessages_containsDelimiterAndCount() {
        val (system, user) = BatchTranslation.buildMessages(
            sources = listOf("Hello", "World"),
            context = listOf(ContextTurn("Hi", "嗨")),
            settings = UserSettings(inputLanguage = "en", outputLanguage = "zh")
        )
        assertTrue(system.contains(BatchTranslation.DELIMITER))
        assertTrue(system.contains("exactly 2"))
        assertTrue(user.contains("Hello${BatchTranslation.DELIMITER}World"))
        assertTrue(user.contains("History"))
        assertTrue(user.contains("Hi"))
    }
}
