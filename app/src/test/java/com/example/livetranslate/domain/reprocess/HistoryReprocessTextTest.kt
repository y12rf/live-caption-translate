package com.example.livetranslate.domain.reprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryReprocessTextTest {
    @Test
    fun joinAndSplit_basic() {
        val full = HistoryReprocessText.joinBlockTranscripts(
            listOf("Hello world.", "How are you?")
        )
        assertEquals("Hello world. How are you?", full)
        val sources = HistoryReprocessText.splitSources(full)
        assertTrue(sources.isNotEmpty())
    }

    @Test
    fun bilingualSegments_offsetAlwaysZero() {
        val segs = HistoryReprocessText.bilingualSegments(
            sources = listOf("Hi", "There"),
            translations = listOf("你好", "那里"),
            nowMs = 1L
        )
        assertEquals(2, segs.size)
        assertTrue(segs.all { it.offsetMs == 0L && !it.incomplete })
        assertEquals("你好", segs[0].translation)
    }

    @Test(expected = IllegalArgumentException::class)
    fun bilingualSegments_emptyZh_throws() {
        HistoryReprocessText.bilingualSegments(listOf("a"), listOf("  "))
    }
}
