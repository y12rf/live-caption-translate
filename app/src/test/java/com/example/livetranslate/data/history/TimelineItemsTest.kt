package com.example.livetranslate.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineItemsTest {

    @Test
    fun silenceGap_atLeastOneSecond_insertsBlankRow() {
        val segs = listOf(
            seg(1, 0L, "a", "A"),
            seg(2, 2_500L, "b", "B")
        )
        val items = HistoryExport.buildTimelineItems(segs)
        assertEquals(3, items.size)
        assertTrue(items[0] is TimelineItem.Segment)
        val silence = items[1] as TimelineItem.Silence
        assertEquals(0L, silence.startMs)
        assertEquals(2_500L, silence.durationMs)
        assertTrue(items[2] is TimelineItem.Segment)
    }

    @Test
    fun silenceGap_underOneSecond_noBlankRow() {
        val segs = listOf(
            seg(1, 0L, "a", "A"),
            seg(2, 800L, "b", "B")
        )
        val items = HistoryExport.buildTimelineItems(segs)
        assertEquals(2, items.size)
        assertTrue(items.all { it is TimelineItem.Segment })
    }

    @Test
    fun emptySegments_emptyTimeline() {
        assertTrue(HistoryExport.buildTimelineItems(emptyList()).isEmpty())
    }

    private fun seg(id: Long, offset: Long, en: String, zh: String) = SegmentEntity(
        id = id,
        sessionId = 1L,
        source = en,
        translation = zh,
        cutReason = null,
        incomplete = false,
        createdAt = 0L,
        offsetMs = offset
    )
}
