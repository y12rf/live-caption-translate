package com.example.livetranslate.data.history

import com.example.livetranslate.domain.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExportSrtTest {

    @Test
    fun formatSrtTimestamp_basic() {
        assertEquals("00:00:00,000", HistoryExport.formatSrtTimestamp(0))
        assertEquals("00:00:01,500", HistoryExport.formatSrtTimestamp(1_500))
        assertEquals("00:01:02,003", HistoryExport.formatSrtTimestamp(62_003))
        assertEquals("01:00:00,000", HistoryExport.formatSrtTimestamp(3_600_000))
    }

    @Test
    fun formatSrt_bilingual_and_single() {
        val segs = listOf(
            TranscriptSegment(
                source = "Hello world",
                translation = "你好世界",
                cutReason = null,
                offsetMs = 0L
            ),
            TranscriptSegment(
                source = "Next line",
                translation = "下一句",
                cutReason = null,
                offsetMs = 5_000L
            )
        )
        val both = HistoryExport.formatSrtFromLive(segs, ExportTextMode.Both, sessionDurationMs = 10_000)
        assertTrue(both.contains("00:00:00,000 --> 00:00:05,000"))
        assertTrue(both.contains("你好世界"))
        assertTrue(both.contains("Hello world"))
        assertTrue(both.startsWith("1\n"))

        val zh = HistoryExport.formatSrtFromLive(segs, ExportTextMode.TranslationOnly, 10_000)
        assertTrue(zh.contains("你好世界"))
        assertTrue(!zh.contains("Hello world"))

        val en = HistoryExport.formatSrtFromLive(segs, ExportTextMode.SourceOnly, 10_000)
        assertTrue(en.contains("Hello world"))
        assertTrue(!en.contains("你好世界"))
    }

    @Test
    fun formatSrt_emptySegments() {
        assertEquals("", HistoryExport.formatSrtFromLive(emptyList()))
    }
}
