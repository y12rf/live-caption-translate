package com.example.livetranslate.domain.reprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineWindowBuilderTest {

    @Test
    fun build_basicWindows() {
        val w = TimelineWindowBuilder.build(
            offsets = listOf(0L, 1_000L, 3_000L),
            durationMs = 5_000L
        )
        assertEquals(3, w.size)
        assertEquals(0L, w[0].startMs)
        assertEquals(1_000L, w[0].endMs)
        assertEquals(1_000L, w[1].startMs)
        assertEquals(3_000L, w[1].endMs)
        assertEquals(3_000L, w[2].startMs)
        assertEquals(5_000L, w[2].endMs)
    }

    @Test
    fun build_allZero_returnsEmpty() {
        val w = TimelineWindowBuilder.build(listOf(0L, 0L, 0L), 10_000L)
        assertTrue(w.isEmpty())
    }

    @Test
    fun build_clampsAndSorts() {
        val w = TimelineWindowBuilder.build(
            offsets = listOf(8_000L, 1_000L, 50_000L),
            durationMs = 10_000L
        )
        // 1000, 8000, 10000(clamped) — last start==duration may drop
        assertTrue(w.isNotEmpty())
        assertEquals(1_000L, w.first().startMs)
        assertTrue(w.last().endMs <= 10_000L)
    }

    @Test
    fun build_mergesUltraShort() {
        val w = TimelineWindowBuilder.build(
            offsets = listOf(0L, 50L, 2_000L),
            durationMs = 5_000L
        )
        // 0-50 merged into next → one window covering 0-2000, then 2000-5000
        assertEquals(2, w.size)
        assertEquals(0L, w[0].startMs)
        assertEquals(2_000L, w[0].endMs)
    }

    @Test
    fun hasUsableTimeline() {
        assertTrue(TimelineWindowBuilder.hasUsableTimeline(listOf(0L, 500L)))
        assertTrue(!TimelineWindowBuilder.hasUsableTimeline(listOf(0L, 0L)))
    }

    @Test
    fun build_singleNonZeroOffset_toDuration() {
        // A lone 0 offset is treated as "no axis" (legacy Re sessions); need >0 for timeline path.
        val w = TimelineWindowBuilder.build(listOf(500L), durationMs = 4_000L)
        assertEquals(1, w.size)
        assertEquals(500L, w[0].startMs)
        assertEquals(4_000L, w[0].endMs)
    }

    @Test
    fun build_startsAtZero_withLaterOffsets() {
        val w = TimelineWindowBuilder.build(listOf(0L, 2_000L), durationMs = 5_000L)
        assertEquals(2, w.size)
        assertEquals(0L, w[0].startMs)
        assertEquals(2_000L, w[0].endMs)
        assertEquals(2_000L, w[1].startMs)
        assertEquals(5_000L, w[1].endMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun build_invalidDuration_throws() {
        TimelineWindowBuilder.build(listOf(0L, 100L), durationMs = 0L)
    }
}
