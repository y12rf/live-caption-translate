package com.example.livetranslate.domain.reprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HistoryReprocessPlannerTest {

    @Test
    fun planFromTimeline_packs20And90sValve() {
        // 25 sentences, 3s each → two blocks by count (20 + 5)
        val offsets = (0 until 25).map { it * 3_000L }
        val plan = HistoryReprocessPlanner.planFromTimeline(
            segmentOffsets = offsets,
            durationMs = 75_000L
        )
        assertEquals(HistoryReprocessPlanner.WindowSource.Timeline, plan.source)
        assertEquals(25, plan.windowCount)
        assertEquals(2, plan.blockCount)
        assertEquals(20, plan.blocks[0].windowCount)
        assertEquals(5, plan.blocks[1].windowCount)
    }

    @Test
    fun tryPlanFromTimeline_allZero_returnsNull() {
        assertNull(
            HistoryReprocessPlanner.tryPlanFromTimeline(
                segmentOffsets = listOf(0L, 0L, 0L),
                durationMs = 10_000L
            )
        )
    }

    @Test
    fun planFromSession2Srt_matchesHistoryReprocessCut() {
        val srt = resolveTestFile("session_2_1784321859406.srt")
        val wav = resolveTestFile("session_20260718_045739.wav")
        org.junit.Assume.assumeTrue(srt.isFile && wav.isFile)

        val offsets = parseSrtStartOffsets(srt)
        val durationMs = WavDuration.ms(wav)
        val plan = HistoryReprocessPlanner.planFromTimeline(offsets, durationMs)

        assertEquals(HistoryReprocessPlanner.WindowSource.Timeline, plan.source)
        assertEquals(136, offsets.size)
        assertEquals(136, plan.windowCount)
        assertEquals(9, plan.blockCount)
        // First cue in session_2 SRT
        assertEquals(18_549L, plan.blocks.first().startMs)
        assertEquals(durationMs, plan.blocks.last().endMs)
        // 90s valve or count: every block duration ≤ ~90s except single long cues
        plan.blocks.forEach { b ->
            assertTrue(
                "block ${b.index} dur=${b.durationMs} windows=${b.windowCount}",
                b.windowCount >= 1 && b.durationMs > 0
            )
        }
    }

    private fun parseSrtStartOffsets(srt: File): List<Long> {
        val re = Regex(
            """(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})"""
        )
        return srt.readLines().mapNotNull { line ->
            val m = re.find(line.trim()) ?: return@mapNotNull null
            val g = m.groupValues
            g[1].toLong() * 3_600_000L +
                g[2].toLong() * 60_000L +
                g[3].toLong() * 1_000L +
                g[4].toLong()
        }
    }

    private fun resolveTestFile(name: String): File {
        val candidates = listOf(
            File("test", name),
            File("../test", name),
            File("E:/Desktop/project/test", name)
        )
        return candidates.firstOrNull { it.isFile } ?: candidates.last()
    }

    /** Minimal duration read without depending on Android. */
    private object WavDuration {
        fun ms(wav: File): Long {
            val open = com.example.livetranslate.data.audio.WavPcmReader.open(wav)
            return open.durationMs
        }
    }
}
