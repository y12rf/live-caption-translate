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

    /**
     * Regression: wrong ||| count must be a mismatch even if line count == expected.
     * Old logic returned false and accepted a shifted parse (first block "gone").
     */
    @Test
    fun hadCountMismatch_delimiterWrong_evenIfLineCountMatches() {
        val raw = """
            T1
            T2|||T3
            T4
            T5
        """.trimIndent()
        // 5 non-empty lines, but only 2 delimiter parts
        assertTrue(BatchTranslation.hadCountMismatch(raw, 5))
        val parts = BatchTranslation.parseTranslations(raw, 5)
        assertEquals(5, parts.size)
        // pad path — first two hold merged text, rest blank
        assertTrue(parts[0].contains("T1"))
        assertTrue(parts[2].isEmpty())
    }

    @Test
    fun hasSuspiciousDuplicates_detectsLoop() {
        val parts = listOf("同一句", "同一句", "同一句", "另一句", "同一句")
        assertTrue(BatchTranslation.hasSuspiciousDuplicates(parts))
        assertFalse(
            BatchTranslation.hasSuspiciousDuplicates(listOf("a", "b", "c", "d"))
        )
        assertFalse(
            BatchTranslation.hasSuspiciousDuplicates(listOf("x", "x")) // too few
        )
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

    @Test
    fun buildMessages_reprocessFewShotContainsDelimiterExamples() {
        val settings = UserSettings(inputLanguage = "en", outputLanguage = "zh")
        val (system, _) = BatchTranslation.buildMessages(
            sources = listOf("Hello", "World"),
            context = emptyList(),
            settings = settings,
            requireNonEmptySlots = true
        )
        assertTrue(system.contains("Few-shot"))
        assertTrue(system.contains(BatchTranslation.DELIMITER))
        assertTrue(system.contains("Never leave a translation segment empty"))
        assertTrue(system.contains("Example 1"))
    }

    @Test
    fun isBatchFullySuccessful_requiresAllNonBlank() {
        assertTrue(BatchTranslation.isBatchFullySuccessful(listOf("a", "b"), 2))
        assertFalse(BatchTranslation.isBatchFullySuccessful(listOf("a", ""), 2))
        assertFalse(BatchTranslation.isBatchFullySuccessful(listOf("a", "b", "c"), 2))
        assertFalse(
            BatchTranslation.isBatchFullySuccessful(listOf("x", "x", "x", "y"), 4)
        )
    }

    @Test
    fun isRawBatchFullySuccessful() {
        assertTrue(BatchTranslation.isRawBatchFullySuccessful("你好|||世界", 2))
        assertFalse(BatchTranslation.isRawBatchFullySuccessful("only one", 2))
    }
}
